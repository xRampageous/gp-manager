package com.gpmanager;

import static com.gpmanager.ChargeCheck.*;
import static com.gpmanager.Inbox.*;

import com.gpmanager.ChargeCheck.ChargedItem;

import lombok.RequiredArgsConstructor;

import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;

import java.util.*;

import javax.inject.*;

/**
 * Client-thread intake for the exact Check-to-Check charge contract: a Check click binds a
 * short-lived identity, the numeric chat read is the only source of counts, and a Use-on-item click
 * arms one narrow load. Local graphics, hitsplats and location clicks book LIKELY estimates.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ChargeIntake {
    final Client client;
    final ItemLookup lookup;
    final GpManagerConfig config;
    final Engine engine;

    /** The single GP Manager valuation authority; null only in narrow synthetic tests. */
    final Valuation valuation;

    /** Fractional component carry per variant+item so whole units book when they accrue. */
    final Map<String, Long> chargeCarry = new HashMap<>();

    /** Exact cumulative value per fractional component: units consumed and value already booked. */
    final Map<String, long[]> valueCarry = new HashMap<>();

    int lastCastTick = -1;

    /** The blowpipe's loaded dart from its last Check or load; -1 until one says. */
    int blowpipeDart = -1;

    int lastCastGraphic;

    /** The player's spot anims at the last change, by id and start cycle. */
    Set<Long> playingAnims = new HashSet<>();

    int lastAttackTick = -1;

    /** Learnable eye cast triggers: local graphics or animations proven by same-tick magic XP. */
    final Set<Integer> eyeGraphics = new HashSet<>();

    final Set<Integer> eyeAnimations = new HashSet<>();
    int eyeCandidateGraphic;
    int eyeCandidateAnimation;
    int eyeCandidateTick = -1;
    int eyeCandidateWeaponId;
    String eyeCandidateIdentity;

    /** A melee hit awaiting its same-tick XP confirmation, so a login's first hit counts. */
    int pendingFuryHitTick = -1;

    /** True when the staff's own line said runes: each cast then spends 2 death and 1 chaos. */
    /** Eye charging mode per target identity: true when that staff's own line said runes. */
    final Map<String, Boolean> eyeRunes = new HashMap<>();

    /**
     * An Uncharge/Unload waiting for its components to land (a confirm prompt can take a while).
     */
    /** How long an Uncharge click waits for its returned components: 50 ticks, 30 seconds. */
    static final int RETURN_TICKS = 50;

    /** A Check command has one physical target and a short inclusive client-tick window. */
    Observation checkIntent() {
        return engine.inbox.peek(CHECK_LINK);
    }

    boolean startCheck(ChargedItem variant, String id, int tick, int ticks) {
        engine.inbox.drop(CHECK_LINK);
        if (variant == null || !variant.implemented || Phrases.blank(id) || ticks <= 0)
            return false;
        Observation e = engine.inbox.add(CHECK_LINK, -1, 1L, ticks, null);
        e.variant = variant;
        e.id = id;
        e.at = tick + ticks;
        return true;
    }

    String resolveCheck(ChargeCheck read, int tick) {
        Observation e = checkIntent();
        String id =
                e != null
                                && tick <= e.at
                                && read != null
                                && read.bookable
                                && read.variant == e.variant
                        ? e.id
                        : null;
        engine.inbox.drop(CHECK_LINK);
        return id;
    }

    /** Any unrelated chat/menu between click and numeric response drops the target link. */
    void clearCheck() {
        engine.inbox.drop(CHECK_LINK);
    }

    /**
     * Login/hop: transient baselines and load intents never survive; the fractional carries do,
     * so a vial of blood (or a tome page, or a blowpipe scale) still accrues toward its next
     * whole unit across a relog (owner live 2026-10-13: the wipe made short sessions never book
     * the vial).
     */
    void resetTransient() {
        engine.inbox.drop(CHECK_LINK);
        eyeGraphics.clear();
        eyeAnimations.clear();
        eyeCandidateGraphic = 0;
        eyeCandidateAnimation = 0;
        eyeCandidateTick = -1;
        eyeCandidateWeaponId = 0;
        eyeCandidateIdentity = null;
        pendingFuryHitTick = -1;
        eyeRunes.clear();
        blowpipeDart = -1;
        engine.inbox.drop(UNCHARGE);
        lastAttackTick = -1;
        engine.resetChecks(null);
        engine.clearChargeLoad();
    }

    /** Profile change: nothing of the previous owner survives, the fractional carries included. */
    void resetOwner() {
        resetTransient();
        chargeCarry.clear();
        valueCarry.clear();
    }

    /**
     * Feed only exact numeric Check messages into the measured charge baseline. Chat is
     * deliberately not activity/title evidence. Booking rejects the complete delta on a missing
     * supported component valuation or any arithmetic overflow.
     *
     * @return true when the message was a charge Check read (consumed, even if unbooked)
     */
    boolean observeCheck(String message, long now) {
        ChargeCheck read = parse(message);
        if (read == null) return false;
        if (read.variant.isCommittedLoad()) {
            // A committed load books when its shards arrive; its Check has nothing to compare
            // (owner 2026-10-12).
            engine.resetChecks(read.variant);
            return true;
        }
        if (read.variant == ChargedItem.BLOWPIPE) blowpipeDart = read.dartItemId;
        ChargedItem clicked = checkIntent() == null ? null : checkIntent().variant;
        // A revenant Check says only "bow", "sceptre" or "chainmace": the clicked weapon names the
        // row.
        if (clicked != null
                && clicked.check != null
                && clicked != read.variant
                && clicked.check == read.variant.check)
        {
            // The line names only the family ("your tome"); the clicked item names the row, and
            // the read's stock is rebuilt from that row's own recipe (the tomes use different
            // pages; owner 2026-10-12).
            read.componentCounts = restock(clicked, read.variant, read.componentCounts);
            read.variant = clicked;
        }
        String targetIdentity = resolveCheck(read, client.getTickCount());
        if (targetIdentity == null && read.variant == ChargedItem.BLOOD_FURY) {
            // The amulet's count arrives on its own, without a Check click: bind it to the
            // worn amulet's neck slot (owner 2026-09-29).
            targetIdentity = wornBloodFury();
        }
        if (read.variant == ChargedItem.EYE_OF_AYAK) {
            // No Check click: the line can only mean the worn staff or the pack copy.
            if (targetIdentity == null) targetIdentity = eyeIdentity();
            // The line names the mode ("demon tears" or "runes"), stored for that staff (review
            // 2026-10-09).
            eyeRunes.put(targetIdentity, read.componentCounts.containsKey(ItemID.DEATHRUNE));
        }
        if (targetIdentity == null) {
            // A value without its fresh Check target cannot distinguish another
            // same-variant weapon; discard any older comparison baseline of that kind.
            engine.resetChecks(read.variant);
            return true;
        }
        if (message.toLowerCase(Locale.ROOT).contains("charged with")) {
            // Any charge wording is a load: the components moved into the weapon, never spend.
            // Armed even on the first read, where no delta exists yet (owner 2026-09-30).
            transfer("charge-load");
        }
        ChargeDelta delta = engine.observeCheck(read, targetIdentity, now);
        // Every exact read names its result; quantities still come only from measured decreases.
        if (delta.result == ChargeDelta.CheckResult.SAME) {
            engine.closeChargeWindow(targetIdentity);
            return true;
        }
        if (delta.result == ChargeDelta.CheckResult.LOAD) transfer("charge-load");
        List<Flow> losses =
                delta.result == ChargeDelta.CheckResult.SPEND
                        ? measuredLosses(read, delta, now)
                        : null;
        if (losses != null)
            engine.bookChargeSpend(delta, read.variant.displayName, losses, now, targetIdentity);
        // The measured read reconciles only this weapon's estimates; another of the same kind keeps
        // its own until its own Check (Codex research 1.2). Any other read is a fresh start:
        // earlier estimates stay as booked, and the next Check, measured from here, never
        // reconciles them.
        engine.clearPendingEstimates(
                targetIdentity, losses == null ? Collections.emptySet() : delta.unchanged);
        return true;
    }

    /** The measured decrease priced as losses; null when it cannot be booked as spend. */
    List<Flow> measuredLosses(ChargeCheck read, ChargeDelta delta, long now) {
        if (lookup == null) return null;
        if (read.variant == ChargedItem.EYE_OF_AYAK) {
            boolean rune =
                    read.componentCounts.containsKey(ItemID.DEATHRUNE)
                            || read.componentCounts.containsKey(ItemID.CHAOSRUNE);
            if (delta.componentDeltas.stream()
                    .anyMatch(component -> (component.getItemId() == ItemID.DEMON_TEAR) == rune)) {
                // A mode switch's vanished component is a load or unload, never spend.
                return null;
            }
        }
        var losses = new ArrayList<Flow>();
        for (ChargeDelta.ComponentDelta component : delta.componentDeltas) {
            int itemId = component.itemId;
            int canonicalId = lookup.canon(itemId);
            ItemComposition composition =
                    itemId <= 0 || canonicalId <= 0 ? null : lookup.composition(canonicalId);
            if (composition == null || Phrases.blank(composition.getName())) return null;
            // Every component's base unit valuation comes from the one shared GP Manager
            // policy: face value, explicit manual override, the RuneLite mapping guard,
            // the world/economy gate and the explicit active market route. Booking rejects
            // the whole measured delta when any component lacks a supported valuation.
            Flow valued =
                    valuation == null
                            ? null
                            : valuation.valueComponent(canonicalId, component.quantityDelta, now);
            int unitPrice = valued == null ? 0 : valued.unitPrice;
            // A component measured in fractions of the priced item (a page feeds 20 charges).
            int perItem = unitsPerItem(read.variant, itemId);
            long units = component.quantityDelta;
            long value = SafeMath.safeMultiply(units, unitPrice);
            if (perItem > 1 && unitPrice > 0) {
                value = -carriedValue(read.variant, itemId, Math.abs(units), unitPrice, perItem);
                unitPrice = Math.max(1, unitPrice / perItem);
            }
            losses.add(
                    new Flow(
                            itemId,
                            composition.getName(),
                            units,
                            unitPrice,
                            value,
                            valued == null ? PriceSource.UNPRICED : valued.getPriceSource(),
                            now));
        }
        return losses;
    }

    /** The tick through which recent combat XP proves a melee stance; -1 when it does not. */
    int meleeStanceUntil = -1;

    int stanceXpTick = -1;
    boolean meleeXpThisTick;
    boolean otherXpThisTick;

    /**
     * Combat XP reveals the attack style with no per-weapon animation table: Attack/Strength/
     * Defence confirm melee, Ranged/Magic deny it, so every melee weapon qualifies (owner
     * 2026-09-29).
     */
    void learnEyeCast(Skill skill, int tick, long now) {
        if (skill == Skill.MAGIC && eyeCandidateTick >= 0 && tick - eyeCandidateTick <= 1) {
            if (eyeCandidateAnimation > 0) eyeAnimations.add(eyeCandidateAnimation);
            if (eyeCandidateGraphic > 0) eyeGraphics.add(eyeCandidateGraphic);
            if (eyeCandidateIdentity != null && tick != lastAttackTick) {
                // The cast that proved the trigger books once, through the same tick dedupe.
                lastAttackTick = tick;
                bookAttackEstimate(
                        ChargedItem.EYE_OF_AYAK,
                        eyeCandidateWeaponId,
                        eyeCandidateIdentity,
                        ActionKind.CAST,
                        now);
            }
            eyeCandidateAnimation = 0;
            eyeCandidateGraphic = 0;
            eyeCandidateTick = -1;
            eyeCandidateWeaponId = 0;
            eyeCandidateIdentity = null;
        }
        boolean melee = skill == Skill.ATTACK || skill == Skill.STRENGTH || skill == Skill.DEFENCE;
        boolean other = skill == Skill.RANGED || skill == Skill.MAGIC;
        if (!melee && !other) return;
        if (tick != stanceXpTick) {
            stanceXpTick = tick;
            meleeXpThisTick = false;
            otherXpThisTick = false;
        }
        meleeXpThisTick |= melee;
        otherXpThisTick |= other;
        meleeStanceUntil = otherXpThisTick ? -1 : meleeXpThisTick ? tick + 8 : meleeStanceUntil;
        if (melee && pendingFuryHitTick == tick) {
            // The hit that opened this fight lands before its XP in the same tick.
            pendingFuryHitTick = -1;
            if (furyAmulet() != null) bookFury(now);
        }
    }

    /** Whether the recent XP pattern proves the player is meleeing right now. */
    boolean furyMelee(int tick) {
        return tick <= meleeStanceUntil;
    }

    /**
     * Uncharging Bryophyta's staff destroys the nature runes it holds (OSRS Wiki; "You remove 1000
     * charges from your staff, leaving it empty."): those charges are spent, one rune each.
     */
    void emptied(long charges, long now) {
        engine.spent(
                ChargedItem.BRYOPHYTA.displayName,
                List.of(valuation.valueComponent(ItemID.NATURERUNE, -charges, now)),
                now);
    }

    /** The doses a chug drank this tick, booked as one row on the next game tick. */
    final List<Flow> chugged = new ArrayList<>();

    /**
     * A chug drinks one dose of every potion in the chugging barrel, never touching the inventory.
     * The game names each one, as its one-dose potion: "You have 3 doses of Saradomin brew(1) left
     * in your barrel." or "You have finished all doses of ..." (OSRS Wiki; Dude Where's My Stuff).
     */
    void chug(String potion, long now) {
        for (var match : lookup.find(potion)) {
            if (match.getName().equalsIgnoreCase(potion)) {
                chugged.add(valuation.valueComponent(match.getId(), -1L, now));
                return;
            }
        }
    }

    /** Each tick, the doses drunk from the barrel are one Drank row (owner 2026-10-09). */
    void drank(long now) {
        if (chugged.isEmpty()) return;
        engine.chug(new ArrayList<>(chugged), now);
        chugged.clear();
    }

    /** A client item container, or null without a client. */
    ItemContainer container(int containerId) {
        return client == null ? null : client.getItemContainer(containerId);
    }

    /** Marks the next item move as a neutral charge transfer with this message. */
    void transfer(String messageKey) {
        engine.markContext(
                Context.TRANSFER,
                Math.max(4, config.stabilizationTicks() + 2),
                GameData.msg(messageKey));
    }

    /** That staff's charging mode; unknown means tears (the default charge type). */
    boolean eyeRune(String identity) {
        return Boolean.TRUE.equals(eyeRunes.get(identity));
    }

    /**
     * The eye's identity for lines that arrive with no Check click: the worn staff, else the pack.
     */
    String eyeIdentity() {
        return heldIdentity(ChargedItem.EYE_OF_AYAK, EquipmentInventorySlot.WEAPON);
    }

    /**
     * A teleport click that carries no item id (a Rub-interface destination button, or a worn
     * pendant's own minimenu destination) resolves the pendant itself: the worn amulet first,
     * then the pack (owner 1.3.4).
     */
    void bookAtesPendant(long now) {
        String identity = heldIdentity(ChargedItem.ATES, EquipmentInventorySlot.AMULET);
        if (identity != null)
            bookAttackEstimate(
                    ChargedItem.ATES, identityItemId(identity), identity, ActionKind.SUPPLIES, now);
    }

    /**
     * A sailors' amulet teleport click spends one charge: the amulet itself, worn or in the pack,
     * names the teleport (owner captures).
     */
    void bookSailorsAmulet(long now) {
        String identity =
                heldIdentity(ChargedItem.SAILORS_AMULET, EquipmentInventorySlot.AMULET);
        if (identity != null)
            bookAttackEstimate(
                    ChargedItem.SAILORS_AMULET,
                    identityItemId(identity),
                    identity,
                    ActionKind.SUPPLIES,
                    now);
    }

    /**
     * The identity of a held item of this family: the one worn in that equipment slot first, then
     * the first one in the pack; null when neither holds one.
     */
    String heldIdentity(ChargedItem variant, EquipmentInventorySlot wornSlot) {
        ItemContainer worn = container(InventoryID.WORN);
        Item item = worn == null ? null : worn.getItem(wornSlot.getSlotIdx());
        if (item != null && supportedForId(item.getId()) == variant)
            return weaponIdentity(
                    InterfaceID.Wornitems.UNIVERSE, wornSlot.getSlotIdx(), item.getId(), variant);
        ItemContainer inventory = container(InventoryID.INV);
        if (inventory == null) return null;
        for (int slot = 0; slot < inventory.size(); slot++) {
            item = inventory.getItem(slot);
            if (item != null && supportedForId(item.getId()) == variant)
                return weaponIdentity(InterfaceID.Inventory.ITEMS, slot, item.getId(), variant);
        }
        return null;
    }

    /** The worn amulet of blood fury, or null when another amulet (or none) is worn. */
    Item furyAmulet() {
        ItemContainer worn = container(InventoryID.WORN);
        Item amulet =
                worn == null ? null : worn.getItem(EquipmentInventorySlot.AMULET.getSlotIdx());
        return amulet != null && amulet.getId() == ItemID.BLOOD_AMULET ? amulet : null;
    }

    /** The worn amulet's identity for its click-free count messages; null when not worn. */
    String wornBloodFury() {
        Item amulet = furyAmulet();
        return amulet == null
                ? null
                : weaponIdentity(
                        InterfaceID.Wornitems.UNIVERSE,
                        EquipmentInventorySlot.AMULET.getSlotIdx(),
                        amulet.getId(),
                        ChargedItem.BLOOD_FURY);
    }

    /** One blood fury charge for a melee hit. */
    void bookFury(long now) {
        bookAttackEstimate(
                ChargedItem.BLOOD_FURY,
                ItemID.BLOOD_AMULET,
                wornBloodFury(),
                kindFor(ChargedItem.BLOOD_FURY),
                now);
    }

    /** One use of the worn weapon at its pinned recipe. */
    void bookWorn(ChargedItem variant, Item weapon, long now) {
        bookAttackEstimate(
                variant,
                weapon.getId(),
                wornIdentity(variant, weapon.getId()),
                kindFor(variant),
                now);
    }

    /** Every menu click: supersede a pending Check link, then arm Check / unload / load intents. */
    void onMenuOptionClicked(MenuOptionClicked event, String option, String target) {
        // Answering the Uncharge prompt keeps the return watch; any other action ends it, so a
        // declined prompt never turns later loot of the same runes or scales into a transfer.
        if (!isDialogAnswer(event, option)) engine.inbox.drop(UNCHARGE);
        armCheck(event, option, target);
        armLoad(event, option);
        // The memoirs are charged at the Old Memorial: the runes move into the book (owner 1.2).
        if ("inspect".equals(option) && target.contains("old memorial"))
            engine.markChargeLoad(
                    ChargedItem.MEMOIRS, ItemID.LAWRUNE, "law rune", null, LOAD_TICKS);
        if (isAtesDestination(event)) {
            // A Rub-interface destination button carries no item id: the pendant itself, worn or
            // in the pack, names the teleport (owner 1.3.4).
            bookAtesPendant(SafeMath.now());
            return;
        }
        if (isAmuletLocation(option) && isAmuletTeleport(event, target, option)) {
            // A sailors' amulet teleport: the destination names the teleport whether the click
            // sits on the worn amulet's own menu, its teleport dialog or the teleport interface
            // (owner captures and live 2026-10-14). One charge leaves the amulet; the amulet
            // itself names the row.
            bookSailorsAmulet(SafeMath.now());
            return;
        }
        if (!isAtesLocation(option)) return;
        int itemId = weaponItemId(event);
        ChargedItem variant = supportedForId(itemId);
        if (variant == null && target.contains("pendant of ates")) {
            // A worn pendant's own minimenu destination can carry no item id either (owner 1.3.4
            // live capture: "Nemus Retreat" arrived with param1 on the worn amulet and itemId
            // -1); resolve the pendant itself rather than drop the click.
            bookAtesPendant(SafeMath.now());
            return;
        }
        if (variant != ChargedItem.ATES && variant != ChargedItem.MEMOIRS) return;
        String identity = clickedIdentity(event, variant, itemId);
        if (identity == null) identity = wornCheckIdentity(variant, itemId);
        if (identity != null)
            bookAttackEstimate(variant, itemId, identity, ActionKind.SUPPLIES, SafeMath.now());
    }

    /**
     * A pendant or memoirs teleport click spends one charge; bank, inventory and interface options
     * are plumbing.
     */
    static boolean isAtesLocation(String option) {
        String name = option == null ? "" : option.replace('\u2019', '\'');
        if (name.startsWith("the ")) name = name.substring(4);
        return LOCATIONS.contains(name);
    }

    /** The pendant's six teleport locations, normalized like menu options. */
    static final List<String> LOCATIONS = GameData.names("ates_locations");

    /**
     * The sailors' amulet's four destinations (a leading "the " strips like the pendant's own
     * options); a click there is a teleport (OSRS Wiki, owner recording 2026-10-14).
     */
    static boolean isAmuletLocation(String option) {
        String name = option == null ? "" : option.replace('\u2019', '\'');
        if (name.startsWith("the ")) name = name.substring(4);
        // The amulet's own teleport dialog lists each destination as a sentence.
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).trim();
        return AMULET_LOCATIONS.contains(name);
    }

    static final List<String> AMULET_LOCATIONS = GameData.names("amulet_locations");

    /**
     * A teleport click that names the amulet itself: an interface or worn-item widget carrying
     * the amulet's item id, the worn amulet's own minimenu target, or the amulet's teleport
     * dialog ("Where would you like to teleport to?", each option a sentence). A sentence-shaped
     * option (a menu option never ends in a period) counts too. A destination-named option that
     * names no amulet (a port travel menu) never books (owner 2026-10-14).
     */
    static boolean isAmuletTeleport(MenuOptionClicked event, String target, String option) {
        if (supportedForId(weaponItemId(event)) == ChargedItem.SAILORS_AMULET) return true;
        if (target != null && target.contains("sailors' amulet")) return true;
        int dialogGroup = InterfaceID.Chatmenu.UNIVERSE >>> 16;
        if (event != null && (event.getParam1() >>> 16) == dialogGroup) return true;
        Widget widget = event == null ? null : event.getWidget();
        if (widget != null && (widget.getId() >>> 16) == dialogGroup) return true;
        return option != null && option.trim().endsWith(".");
    }

    /** The Rub interface's six destination buttons; a click there is a teleport (owner 1.3.4). */
    static final Set<Integer> ATES_DESTINATIONS = GameData.byId("ates_destinations").keySet();

    /** A Rub-interface destination click; the event carries the packed component id. */
    static boolean isAtesDestination(MenuOptionClicked event) {
        if (event == null) return false;
        if (ATES_DESTINATIONS.contains(event.getParam1())) return true;
        Widget widget = event.getWidget();
        return widget != null && ATES_DESTINATIONS.contains(widget.getId());
    }

    /**
     * A local cast or shot graphic books this use's pinned recipe as a LIKELY estimate; the next
     * measured Check reconciles it at the captured prices. Any unpriced component fails the whole
     * estimate closed. A repeated graphic in one tick is one use.
     */
    /**
     * Spot anims play on for several ticks and the player can hold several, so only one that was
     * not playing at the last change is a new cast.
     */
    void spotAnims(Iterable<ActorSpotAnim> anims, long now) {
        var playing = new HashSet<Long>();
        for (ActorSpotAnim anim : anims) {
            if (anim == null) continue;
            long key = (long) anim.getId() << 32 | anim.getStartCycle() & 0xFFFFFFFFL;
            if (playing.add(key) && !playingAnims.contains(key)) onGraphic(anim.getId(), now);
        }
        playingAnims = playing;
    }

    void onGraphic(int graphicId, long now) {
        Item weapon = wornWeapon();
        ChargedItem variant = weapon == null ? null : supportedForId(weapon.getId());
        if (variant == null || graphicId <= 0) return;
        boolean eye = variant == ChargedItem.EYE_OF_AYAK;
        if (!isCastGraphic(variant, graphicId) && !(eye && eyeGraphics.contains(graphicId))) {
            if (eye) {
                // The eye's cast trigger is pinned in no table: learn it from the magic XP every
                // cast drops (owner 2026-09-29), then each cast books one tear like a spell.
                eyeCandidateGraphic = graphicId;
                eyeCandidateTick = client.getTickCount();
                eyeCandidateWeaponId = weapon.getId();
                eyeCandidateIdentity = wornIdentity(ChargedItem.EYE_OF_AYAK, weapon.getId());
            }
            return;
        }
        int tick = client.getTickCount();
        if (tick == lastCastTick && graphicId == lastCastGraphic || eye && tick == lastAttackTick)
            return;
        lastCastTick = tick;
        lastCastGraphic = graphicId;
        if (eye) lastAttackTick = tick;
        bookWorn(variant, weapon, now);
    }

    /**
     * A landed hit on the target. The scythe books one charge per damaging hit; the blowpipe books
     * any shot while its attack animation is live, because a 2-tick attack restarts the same
     * animation and never fires a second AnimationChanged. At most once per tick.
     */
    void onHit(int damage, long now) {
        Item weapon = wornWeapon();
        ChargedItem variant = weapon == null ? null : supportedForId(weapon.getId());
        // Blood fury: one charge per melee hit. Combat XP proves melee for every weapon
        // (owner 2026-09-29); a hit whose XP has not landed yet this tick stays pending
        // until that same tick's XP confirms the stance (owner 2026-10-01).
        if (damage > 0) {
            int tick = client.getTickCount();
            if (furyAmulet() != null) {
                if (furyMelee(tick)) bookFury(now);
                else pendingFuryHitTick = tick;
            }
        }
        if (variant == null) return;
        // Each landed splat books: a multi-target swing is several hits, not one attack.
        boolean scythe = variant == ChargedItem.SCYTHE && damage > 0;
        Actor local = client.getLocalPlayer();
        boolean shot =
                variant == ChargedItem.BLOWPIPE
                        && local != null
                        && local.getAnimation() == attackAnimation(variant);
        if (!scythe && !shot) return;
        if (shot) {
            int tick = client.getTickCount();
            if (tick == lastAttackTick) return;
            lastAttackTick = tick;
        }
        bookWorn(variant, weapon, now);
    }

    /** A local attack animation books the pinned per-attack charge for crystal weapons. */
    void onAnimation(int animationId, long now) {
        Item weapon = wornWeapon();
        ChargedItem variant = weapon == null ? null : supportedForId(weapon.getId());
        if (variant == null) return;
        if (variant == ChargedItem.EYE_OF_AYAK) {
            if (!eyeAnimations.contains(animationId)) {
                // Candidate cast animation; the same tick's magic XP proves it, like the graphic.
                eyeCandidateAnimation = animationId;
                eyeCandidateTick = client.getTickCount();
                eyeCandidateWeaponId = weapon.getId();
                eyeCandidateIdentity = wornIdentity(ChargedItem.EYE_OF_AYAK, weapon.getId());
                return;
            }
        } else if (!isPinnedAttack(variant, animationId)) {
            return;
        }
        int tick = client.getTickCount();
        if (tick == lastAttackTick) return;
        lastAttackTick = tick;
        bookWorn(variant, weapon, now);
    }

    Item wornWeapon() {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        return worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
    }

    void bookAttackEstimate(
            ChargedItem variant, int weaponId, String identity, ActionKind kind, long now) {
        // A hit is gameplay: resume the pauses gameplay ends (logout/idle/recovery) and start
        // tracking when automatic startup is on, before booking — so the very first attack after
        // logging in counts instead of arriving while the session is still catching up.
        engine.resume(now, PauseReason.LIFECYCLE, PauseReason.IDLE, PauseReason.RECOVERY);
        if (!engine.live()) engine.autoStart(now);
        List<Flow> recipe = estimateRecipe(variant, identity, now);
        if (recipe == null || recipe.isEmpty()) return;
        Transaction receipt =
                engine.bookEstimatedCharge(identity, variant.displayName, kind, recipe, now);
        if (receipt != null) {
            receipt.castItemId = weaponId;
            if (variant == ChargedItem.ATES) {
                lastAtesIdentity = identity;
                lastAtesTransactionId = receipt.getId();
                lastAtesAt = now;
            }
        }
    }

    /** How long after a pendant click its refusal message still cancels the estimate. */
    static final long REFUSAL_WINDOW_MILLIS = 3_000L;

    /** The newest pendant click's estimate, so a refusal can remove exactly that row (1.3.4). */
    String lastAtesIdentity;
    String lastAtesTransactionId;
    long lastAtesAt;

    /**
     * The game refused a pendant teleport ("you appear to already be at that location"), so the
     * click consumed nothing: the estimate that click booked is removed through the announced
     * automatic-evidence path (owner 1.3.4). Earlier pendant estimates are untouched.
     */
    void refusedTeleport(long now) {
        String identity = lastAtesIdentity;
        String transactionId = lastAtesTransactionId;
        lastAtesIdentity = null;
        lastAtesTransactionId = null;
        if (identity == null
                || transactionId == null
                || now - lastAtesAt > REFUSAL_WINDOW_MILLIS) {
            return;
        }
        engine.forfeitEstimate(identity, transactionId);
    }

    /** A family may cast with more than one local graphic (Sanguinesti's Justiciar variant). */
    static boolean isCastGraphic(ChargedItem variant, int graphicId) {
        return graphicId > 0 && Arrays.stream(variant.graphics).anyMatch(id -> id == graphicId);
    }

    /** The attack animation pinned for per-shot estimates; 0 means none. */
    static int attackAnimation(ChargedItem variant) {
        return variant == ChargedItem.BLOWPIPE ? 5061 : 0;
    }

    /** The attack animations pinned for per-attack estimates (d20); false means none. */
    static boolean isPinnedAttack(ChargedItem variant, int animationId) {
        return Arrays.stream(variant.animations).anyMatch(id -> id == animationId);
    }

    /** The action a variant's estimate represents: a built-in cast, a bow shot or a melee use. */
    static ActionKind kindFor(ChargedItem variant) {
        return variant.action;
    }

    /**
     * The equipment-side identity a worn Check uses: container group, weapon slot, item, variant.
     */
    static String wornIdentity(ChargedItem variant, int weaponItemId) {
        return weaponIdentity(
                InterfaceID.Wornitems.UNIVERSE,
                EquipmentInventorySlot.WEAPON.getSlotIdx(),
                weaponItemId,
                variant);
    }

    /**
     * The worn-container identity of a clicked item, by the slot that actually holds it. A Check
     * from the Worn Equipment tab arrives with param0 -1 (owner capture 2026-10-12), so the
     * clicked location is unknown; the item's own worn slot is the identity the estimates use.
     */
    String wornCheckIdentity(ChargedItem variant, int weaponItemId) {
        if (client == null || variant == null) return null;
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn == null) return null;
        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values()) {
            Item item = worn.getItem(slot.getSlotIdx());
            if (item == null || supportedForId(item.getId()) != variant) continue;
            if (weaponItemId > 0 && item.getId() != weaponItemId) continue;
            return weaponIdentity(
                    InterfaceID.Wornitems.UNIVERSE, slot.getSlotIdx(), item.getId(), variant);
        }
        return null;
    }

    /**
     * The eye's mode-aware per-cast recipe, keyed off its own charge line's wording (owner
     * 2026-09-30).
     */
    static Map<Integer, Long> eyeRecipe(boolean runeCharged) {
        var recipe = new LinkedHashMap<Integer, Long>();
        if (runeCharged) {
            recipe.put(ItemID.DEATHRUNE, 2L);
            recipe.put(ItemID.CHAOSRUNE, 1L);
        } else {
            recipe.put(ItemID.DEMON_TEAR, 1L);
        }
        return recipe;
    }

    /** One cast's pinned component quantities per family (d20), as a copy the caller may extend. */
    static Map<Integer, Long> recipeOf(ChargedItem variant) {
        return new LinkedHashMap<>(variant.recipe);
    }

    /** Fractional per-use components: [itemId, numerator, denominator] accruing toward one item. */
    static long[] fractionOf(ChargedItem variant) {
        return variant.fraction;
    }

    /**
     * Darts a blowpipe shot spends, as {numerator, denominator}, by the cape slot (OSRS wiki): an
     * assembler or Dizana's quiver saves 80%, the accumulator 72%, the attractor 60%; none saves
     * none.
     */
    static long[] dartLoss(String capeName) {
        String cape = Phrases.lower(capeName);
        if (cape.contains("assembler") || cape.contains("dizana")) return new long[] {1L, 5L};
        if (cape.contains("accumulator")) return new long[] {7L, 25L};
        if (cape.contains("attractor")) return new long[] {2L, 5L};
        return new long[] {1L, 1L};
    }

    String wornCapeName() {
        ItemContainer worn = container(InventoryID.WORN);
        Item cape = worn == null ? null : worn.getItem(EquipmentInventorySlot.CAPE.getSlotIdx());
        return cape == null || cape.getId() <= 0 ? "" : itemName(cape.getId());
    }

    /** Whole units to book now and the remainder to keep, for a fractional component. */
    static long[] accrue(long carry, long numerator, long denominator) {
        long total = carry + numerator;
        return new long[] {total / denominator, total % denominator};
    }

    /**
     * Exact cumulative value for a fractional priced item: charges are booked in units of {@code
     * 1/perItem} of the item, so each whole item's price is divided without losing its remainder to
     * truncation. Returns the value this booking adds.
     */
    long carriedValue(ChargedItem variant, int itemId, long units, int price, int perItem) {
        if (units <= 0L || price <= 0 || perItem <= 1)
            return Math.max(0L, units * Math.max(0, price));
        // Only the new units are priced, at today's quote; earlier use keeps the price it was
        // booked
        // at (Codex review 1.2). The sub-gp remainder carries, and so does any 1 gp floor overpaid.
        long[] carry =
                valueCarry.computeIfAbsent(variant.name() + ":" + itemId, ignored -> new long[1]);
        long total = carry[0] + units * price;
        long booked = Math.max(1L, total / perItem);
        carry[0] = total - booked * perItem;
        return booked;
    }

    /** Accrues one fractional component into the recipe (owner 1.4.0 size pass). */
    void accrueInto(
            ChargedItem variant,
            int itemId,
            long numerator,
            long denominator,
            Map<Integer, Long> recipe) {
        String key = variant.name() + ":" + itemId;
        long[] accrued = accrue(chargeCarry.getOrDefault(key, 0L), numerator, denominator);
        chargeCarry.put(key, accrued[1]);
        if (accrued[0] > 0L) recipe.put(itemId, accrued[0]);
    }

    /** One cast's priced recipe, or null when a component lacks a supported valuation. */
    List<Flow> estimateRecipe(ChargedItem variant, String identity, long now) {
        var recipe = recipeOf(variant);
        if (variant == ChargedItem.EYE_OF_AYAK) recipe = eyeRecipe(eyeRune(identity));
        long[] fraction = fractionOf(variant);
        if (fraction != null) {
            accrueInto(variant, (int) fraction[0], fraction[1], fraction[2], recipe);
        }
        if (variant == ChargedItem.BLOWPIPE && blowpipeDart > 0) {
            // Owner 1.1: each shot spends its dart unless an Ava's device saves it; the expected
            // share
            // accrues like the scales, and a Check reconciles it.
            long[] loss = dartLoss(wornCapeName());
            accrueInto(variant, blowpipeDart, loss[0], loss[1], recipe);
        }
        var flows = new ArrayList<Flow>();
        for (Map.Entry<Integer, Long> entry : recipe.entrySet()) {
            int canonicalId = lookup == null ? -1 : lookup.canon(entry.getKey());
            ItemComposition composition = canonicalId <= 0 ? null : lookup.composition(canonicalId);
            if (composition == null || Phrases.blank(composition.getName())) return null;
            Flow valued =
                    valuation == null
                            ? null
                            : valuation.valueComponent(canonicalId, -entry.getValue(), now);
            int unitPrice = valued == null ? 0 : valued.unitPrice;
            if (unitPrice <= 0) return null;
            // Charge-fed components book in charge units; the priced item covers several, and the
            // value carry keeps every whole item's price exact across those fractional bookings.
            int perItem = unitsPerItem(variant, entry.getKey());
            long units = entry.getValue();
            long value = SafeMath.safeMultiply(-units, unitPrice);
            if (perItem > 1) {
                value = -carriedValue(variant, entry.getKey(), units, unitPrice, perItem);
                unitPrice = Math.max(1, unitPrice / perItem);
            }
            flows.add(
                    new Flow(
                            canonicalId,
                            composition.getName(),
                            -units,
                            unitPrice,
                            value,
                            valued.getPriceSource(),
                            now));
        }
        return flows;
    }

    /**
     * Link one numeric Check response to the item slot that was actually checked. The click
     * contains identity only; any other menu action supersedes the pending link.
     */
    void armCheck(MenuOptionClicked event, String option, String target) {
        engine.inbox.drop(CHECK_LINK);
        int rawItemId = weaponItemId(event);
        ChargedItem itemVariant = supportedForId(rawItemId);
        if (itemVariant == null) itemVariant = supportedForName(target);
        if ("configure-charges".equals(option)) {
            // The bank's Configure-Charges loads or removes charges with bank items the plugin
            // never sees, so no Check of that kind can be compared across it (OSRS Wiki, 5 March
            // 2025).
            engine.resetChecks(itemVariant);
            return;
        }
        if ("charge".equals(option) && itemVariant != null && !itemVariant.recipe.isEmpty()) {
            // A right-click Charge prompt applies the weapon's own component from the pack
            // (Tonalztics of Ralos: "You apply N charges…", OSRS Wiki transcript 2026-10-12).
            int part = itemVariant.recipe.keySet().iterator().next();
            engine.markChargeLoad(
                    itemVariant, part, itemName(part), clickedIdentity(event, itemVariant, rawItemId), LOAD_TICKS);
            return;
        }
        if (("uncharge".equals(option) || "unload".equals(option) || "empty".equals(option))
                && itemVariant != null) {
            // These actions can return loaded components. A later lower Check value
            // cannot be treated as consumed charges across that unmeasured return.
            engine.resetChecks(itemVariant);
            // The return is the player's own charge investment coming back: an
            // ownership-neutral transfer, never income (owner 2026-09-29).
            markReturnTransfer();
            // Most weapons ask to confirm first, so the components can land long after this short
            // context: keep watching for them (watchReturn) instead of booking them as a gain.
            if (client != null) {
                Observation e = engine.inbox.put(UNCHARGE, -1, 1L, RETURN_TICKS, null);
                e.variant = itemVariant;
                e.at = client.getTickCount() + RETURN_TICKS;
                e.data = inventoryCounts();
            }
            return;
        }
        if (!"check".equals(option) || itemVariant == null || !itemVariant.implemented
                || itemVariant.isCommittedLoad()) return;
        String targetIdentity = clickedIdentity(event, itemVariant, rawItemId);
        if (targetIdentity == null) {
            // A Check from the Worn Equipment tab carries no slot (param0 -1, owner capture
            // 2026-10-12): the item's own worn slot names the baseline the estimates use.
            targetIdentity = wornCheckIdentity(itemVariant, rawItemId);
        }
        if (targetIdentity != null)
            startCheck(itemVariant, targetIdentity, client.getTickCount(), 2);
    }

    /**
     * Each tick before booking: an Uncharge's own components landing make that change a transfer.
     */
    void watchReturn() {
        Observation e = engine.inbox.peek(UNCHARGE);
        if (e == null || client == null) return;
        if (client.getTickCount() > e.at) {
            engine.inbox.drop(UNCHARGE);
            return;
        }
        if (!returnLanded(
                e.variant, (Map<Integer, Long>) e.data, inventoryCounts(), this::itemName)) return;
        markReturnTransfer();
        engine.inbox.drop(UNCHARGE);
    }

    /** The returned charge is the player's own investment coming back: ownership-neutral. */
    void markReturnTransfer() {
        transfer("charge-unload");
    }

    /** A click that answers a chat dialog (the "Really uncharge?" Yes/No), not a new action. */
    static boolean isDialogAnswer(MenuOptionClicked event, String option) {
        return event.getMenuAction() == MenuAction.WIDGET_CONTINUE
                || "yes".equals(option)
                || "continue".equals(option);
    }

    /** True when an item that loads this weapon (its scales, darts or runes) increased. */
    static boolean returnLanded(
            ChargedItem variant,
            Map<Integer, Long> before,
            Map<Integer, Long> after,
            java.util.function.IntFunction<String> names) {
        for (Map.Entry<Integer, Long> item : after.entrySet()) {
            if (item.getValue() > before.getOrDefault(item.getKey(), 0L)
                    && isLoadComponent(variant, item.getKey(), names.apply(item.getKey())))
                return true;
        }
        return false;
    }

    String itemName(int itemId) {
        ItemComposition composition = lookup == null ? null : lookup.composition(itemId);
        return composition == null ? null : composition.getName();
    }

    /** Canonical inventory quantities, so noted or placeholder ids compare as the item itself. */
    Map<Integer, Long> inventoryCounts() {
        ItemContainer inventory = container(InventoryID.INV);
        var counts = new HashMap<Integer, Long>();
        if (inventory == null) return counts;
        for (Item item : inventory.getItems()) {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) continue;
            int id = lookup == null ? item.getId() : lookup.canon(item.getId());
            counts.merge(id, (long) item.getQuantity(), Long::sum);
        }
        return counts;
    }

    /** Room to type a charge amount; any other click still ends the load. */
    static final int LOAD_TICKS = 25;

    /**
     * Arm one narrowly-scoped, ownership-neutral load only for a real Use-on-item action against an
     * exact supported weapon and a source component whose canonical id and item name both match
     * that weapon's recipe.
     */
    void armLoad(MenuOptionClicked event, String option) {
        // Answering the "How many charges?" prompt is part of the same load (owner 1.2).
        if (isDialogAnswer(event, option)) return;
        // A right-click Charge prompt arms its load in armCheck (the Tonalztics of Ralos).
        if ("charge".equals(option)) return;
        // On a widget-target click the clicked widget is the target; the item being used is the
        // client's selected widget. Either order loads: scales on the blowpipe, or the blowpipe on
        // them.
        Widget clicked = event.getWidget();
        Widget selected = client == null ? null : client.getSelectedWidget();
        ChargedItem used = selected == null ? null : supportedForId(selected.getItemId());
        if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT && used != null) {
            // A weapon used on an object (the scythe on the Vyre well) loads its recipe from the
            // pack.
            int part =
                    used.recipe.isEmpty()
                            ? (int) used.fraction[0]
                            : used.recipe.keySet().iterator().next();
            engine.markChargeLoad(
                    used,
                    part,
                    String.valueOf(part),
                    weaponIdentity(
                            selected.getId(), selected.getIndex(), selected.getItemId(), used),
                    LOAD_TICKS);
            return;
        }
        if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_WIDGET
                || clicked == null
                || selected == null) {
            engine.clearChargeLoad();
            return;
        }
        boolean weaponClicked = supportedForId(clicked.getItemId()) != null;
        Widget weapon = weaponClicked ? clicked : selected;
        ChargedItem variant = supportedForId(weapon.getItemId());
        int sourceItemId = canonical((weaponClicked ? selected : clicked).getItemId());
        String name = sourceItemId <= 0 ? null : itemName(sourceItemId);
        if (variant == null || name == null || !isLoadComponent(variant, sourceItemId, name)) {
            engine.clearChargeLoad();
            return;
        }
        if (variant == ChargedItem.BLOWPIPE && ChargeCheck.DARTS.containsValue(sourceItemId))
            blowpipeDart = sourceItemId;
        String targetIdentity =
                weaponIdentity(weapon.getId(), weapon.getIndex(), weapon.getItemId(), variant);
        engine.markChargeLoad(variant, sourceItemId, name, targetIdentity, LOAD_TICKS);
    }

    int canonical(int itemId) {
        return lookup == null || itemId <= 0 ? itemId : lookup.canon(itemId);
    }

    /** Resolve the weapon targeted by the menu action, distinct from an item-on-item source. */
    static int weaponItemId(MenuOptionClicked event) {
        if (event == null) return -1;
        int id = event.getId();
        if (supportedForId(id) != null) return id;
        int itemId = event.getItemId();
        if (supportedForId(itemId) != null) return itemId;
        if (event.getWidget() != null) {
            int widgetItemId = event.getWidget().getItemId();
            if (supportedForId(widgetItemId) != null) return widgetItemId;
        }
        return -1;
    }

    /**
     * Same item/container/slot identity for the clicked weapon across Use-on-item and Check.
     * Without all location fields, a charge load remains Review only.
     */
    static String clickedIdentity(MenuOptionClicked event, ChargedItem variant, int weaponItemId) {
        if (event == null) return null;
        int widgetId = event.getParam1();
        if (widgetId <= 0 && event.getWidget() != null) widgetId = event.getWidget().getId();
        return weaponIdentity(widgetId, event.getParam0(), weaponItemId, variant);
    }

    /**
     * Container group, slot, item and variant uniquely identify one weapon: the same item in
     * inventory and equipment (or two slots in either container) cannot share a baseline, and a
     * worn estimate builds the identical identity from the equipment slot.
     */
    static String weaponIdentity(int widgetId, int slot, int weaponItemId, ChargedItem variant) {
        int widgetGroup = widgetId >>> 16;
        if (variant == null || weaponItemId <= 0 || slot < 0 || widgetId <= 0 || widgetGroup <= 0)
            return null;
        return widgetGroup + ":" + slot + ":" + weaponItemId + ":" + variant.name();
    }

    /** The item id inside a {@link #weaponIdentity} ("group:slot:item:variant"). */
    static int identityItemId(String identity) {
        return Integer.parseInt(identity.split(":")[2]);
    }
}
