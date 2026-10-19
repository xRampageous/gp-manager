package com.gpmanager;


import static com.gpmanager.ChargeCheck.ChargedItem.*;

import static com.gpmanager.GameData.*;

import static com.gpmanager.ChargeCheck.*;
import static com.gpmanager.Inbox.*;

import com.gpmanager.ChargeCheck.ChargedItem;


import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

import java.util.*;

import javax.inject.*;
import static com.gpmanager.PriceSource.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static java.util.Locale.*;
import java.util.regex.*;
import lombok.*;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.nonNeg;
import com.gpmanager.SavedState.PendingClaim;
import java.util.function.IntPredicate;
import net.runelite.api.gameval.*;
import static com.gpmanager.SafeMath.now;
import static com.gpmanager.SafeMath.safeMultiply;

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

    int lastAttackTick = -1, quiverBlessing = -1;

    /** Learnable eye cast triggers: local graphics or animations proven by same-tick magic XP. */
    final Set<Integer> eyeGraphics = new HashSet<>();

    final Set<Integer> eyeAnimations = new HashSet<>();
    int eyeCandidateGraphic, eyeCandidateAnimation, eyeCandidateTick = -1, eyeCandidateWeaponId;
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
        if (variant == null || !variant.implemented || blank(id) || ticks <= 0)
            return false;
        var e = engine.inbox.add(CHECK_LINK, -1, 1, ticks, null);
        e.variant = variant;
        e.id = id;
        e.at = tick + ticks;
        return true;
    }

    String resolveCheck(ChargeCheck read, int tick) {
        var e = checkIntent();
        String id = e != null && tick <= e.at && read != null && read.bookable && read.variant == e.variant ? e.id
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
        quiverBlessing = -1;
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
        var read = parse(message);
        if (read == null) return false;
        if (read.variant.isCommittedLoad()) {
            // A committed load books when its shards arrive; its Check has nothing to compare
            // (owner 2026-10-12).
            engine.resetChecks(read.variant);
            return true;
        }
        if (read.variant == BLOWPIPE) blowpipeDart = read.dartItemId;
        var clicked = checkIntent() == null ? null : checkIntent().variant;
        // A revenant Check says only "bow", "sceptre" or "chainmace": the clicked weapon names the
        // row.
        if (clicked != null && clicked.check != null && clicked != read.variant && clicked.check == read.variant.check)
        {
            // The line names only the family ("your tome"); the clicked item names the row, and
            // the read's stock is rebuilt from that row's own recipe (the tomes use different
            // pages; owner 2026-10-12).
            read.componentCounts = restock(clicked, read.variant, read.componentCounts);
            read.variant = clicked;
        }
        String target = resolveCheck(read, client.getTickCount());
        if (target == null && read.variant == BLOOD_FURY) {
            // The amulet's count arrives on its own, without a Check click: bind it to the
            // worn amulet's neck slot (owner 2026-09-29).
            target = wornBloodFury();
        }
        if (read.variant == EYE_OF_AYAK) {
            // No Check click: the line can only mean the worn staff or the pack copy.
            if (target == null) target = eyeIdentity();
            // The line names the mode ("demon tears" or "runes"), stored for that staff (review
            // 2026-10-09).
            eyeRunes.put(target, read.componentCounts.containsKey(ItemID.DEATHRUNE));
        }
        if (target == null) {
            // A value without its fresh Check target cannot distinguish another
            // same-variant weapon; discard any older comparison baseline of that kind.
            engine.resetChecks(read.variant);
            return true;
        }
        if (message.toLowerCase(ROOT).contains("charged with")) {
            // Any charge wording is a load: the components moved into the weapon, never spend.
            // Armed even on the first read, where no delta exists yet (owner 2026-09-30).
            transfer("charge-load");
        }
        var delta = engine.observeCheck(read, target, now);
        // Every exact read names its result; quantities still come only from measured decreases.
        if (delta.result == ChargeDelta.CheckResult.SAME) {
            engine.closeChargeWindow(target);
            return true;
        }
        if (delta.result == ChargeDelta.CheckResult.LOAD) transfer("charge-load");
        var lost = delta.result == ChargeDelta.CheckResult.SPEND ? measuredLosses(read, delta, now) : null;
        if (lost != null)
            engine.bookChargeSpend(delta, read.variant.displayName, lost, now, target);
        // The measured read reconciles only this weapon's estimates; another of the same kind keeps
        // its own until its own Check (Codex research 1.2). Any other read is a fresh start:
        // earlier estimates stay as booked, and the next Check, measured from here, never
        // reconciles them.
        engine.clearPendingEstimates(target, lost == null ? emptySet() : delta.unchanged);
        return true;
    }

    /** The measured decrease priced as losses; null when it cannot be booked as spend. */
    List<Flow> measuredLosses(ChargeCheck read, ChargeDelta delta, long now) {
        if (lookup == null) return null;
        if (read.variant == EYE_OF_AYAK) {
            boolean resource = read.componentCounts.containsKey(ItemID.DEATHRUNE)
                            || read.componentCounts.containsKey(ItemID.CHAOSRUNE);
            if (delta.componentDeltas.stream()
                    .anyMatch(component -> (component.getItemId() == ItemID.DEMON_TEAR) == resource)) {
                // A mode switch's vanished component is a load or unload, never spend.
                return null;
            }
        }
        var lost = new ArrayList<Flow>();
        for (var component : delta.componentDeltas) {
            Flow loss = chargeFlow(read.variant, component.itemId, component.quantityDelta, now, false);
            if (loss == null) return null;
            lost.add(loss);
        }
        return lost;
    }

    /** The tick through which recent combat XP proves a melee stance; -1 when it does not. */
    int meleeStanceUntil = -1;

    int stanceXpTick = -1;
    boolean meleeXpThisTick, otherXpThisTick;

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
                bookAttackEstimate(EYE_OF_AYAK, eyeCandidateWeaponId, eyeCandidateIdentity, ActionKind.CAST,
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
        engine.spent(BRYOPHYTA.displayName,
                List.of(valuation.valueComponent(ItemID.NATURERUNE, -charges, now)), now);
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
    ItemContainer container(int container) {
        return client == null ? null : client.getItemContainer(container);
    }

    /** Marks the next item move as a neutral charge transfer with this message. */
    void transfer(String messageKey) {
        engine.markContext(Context.TRANSFER, max(4, config.stabilizationTicks() + 2), msg(messageKey));
    }

    /** That staff's charging mode; unknown means tears (the default charge type). */
    boolean eyeRune(String identity) {
        return Boolean.TRUE.equals(eyeRunes.get(identity));
    }

    /**
     * The eye's identity for lines that arrive with no Check click: the worn staff, else the pack.
     */
    String eyeIdentity() {
        return heldIdentity(EYE_OF_AYAK, EquipmentInventorySlot.WEAPON);
    }

    /**
     * A teleport click that carries no item id (a Rub-interface destination button, or a worn
     * pendant's own minimenu destination) resolves the pendant itself: the worn amulet first,
     * then the pack (owner 1.3.4).
     */
    void bookAmulet(ChargedItem variant, long now) {
        String identity = heldIdentity(variant, EquipmentInventorySlot.AMULET);
        if (identity != null)
            bookAttackEstimate(variant, identityItemId(identity), identity, ActionKind.SUPPLIES, now);
    }

    /**
     * The identity of a held item of this family: the one worn in that equipment slot first, then
     * the first one in the pack; null when neither holds one.
     */
    String heldIdentity(ChargedItem variant, EquipmentInventorySlot wornSlot) {
        Item item = wornItem(wornSlot);
        if (item != null && supportedForId(item.getId()) == variant)
            return weaponIdentity(InterfaceID.Wornitems.UNIVERSE, wornSlot.getSlotIdx(), item.getId(), variant);
        var inventory = container(InventoryID.INV);
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
        Item amulet = wornItem(EquipmentInventorySlot.AMULET);
        return amulet != null && amulet.getId() == ItemID.BLOOD_AMULET ? amulet : null;
    }

    /** The worn amulet's identity for its click-free count messages; null when not worn. */
    String wornBloodFury() {
        Item amulet = furyAmulet();
        return amulet == null ? null : weaponIdentity(InterfaceID.Wornitems.UNIVERSE,
                        EquipmentInventorySlot.AMULET.getSlotIdx(), amulet.getId(), BLOOD_FURY);
    }

    /** One blood fury charge for a melee hit. */
    void bookFury(long now) {
        bookAttackEstimate(BLOOD_FURY, ItemID.BLOOD_AMULET, wornBloodFury(),
                kindFor(BLOOD_FURY), now);
    }

    /** One use of the worn weapon at its pinned recipe. */
    void bookWorn(ChargedItem variant, Item weapon, long now) {
        bookAttackEstimate(variant, weapon.getId(), wornIdentity(variant, weapon.getId()), kindFor(variant), now);
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
            engine.markChargeLoad(MEMOIRS, ItemID.LAWRUNE, "law rune", null, LOAD_TICKS);
        if (isAtesDestination(event)) {
            // A Rub-interface destination button carries no item id: the pendant itself, worn or
            // in the pack, names the teleport (owner 1.3.4).
            bookAmulet(ATES, now());
            return;
        }
        if (isAmuletLocation(option) && isAmuletTeleport(event, target, option)) {
            // A sailors' amulet teleport: the destination names the teleport whether the click
            // sits on the worn amulet's own menu, its teleport dialog or the teleport interface
            // (owner captures and live 2026-10-14). One charge leaves the amulet; the amulet
            // itself names the row.
            bookAmulet(SAILORS_AMULET, now());
            return;
        }
        if (!isAtesLocation(option)) return;
        int id = weaponItemId(event);
        var variant = supportedForId(id);
        if (variant == null && target.contains("pendant of ates")) {
            // A worn pendant's own minimenu destination can carry no item id either (owner 1.3.4
            // live capture: "Nemus Retreat" arrived with param1 on the worn amulet and itemId
            // -1); resolve the pendant itself rather than drop the click.
            bookAmulet(ATES, now());
            return;
        }
        if (variant != ATES && variant != MEMOIRS) return;
        String identity = clickedIdentity(event, variant, id);
        if (identity == null) identity = wornCheckIdentity(variant, id);
        if (identity != null)
            bookAttackEstimate(variant, id, identity, ActionKind.SUPPLIES, now());
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
    static final List<String> LOCATIONS = names("ates_locations");

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

    static final List<String> AMULET_LOCATIONS = names("amulet_locations");

    /**
     * A teleport click that names the amulet itself: an interface or worn-item widget carrying
     * the amulet's item id, the worn amulet's own minimenu target, or the amulet's teleport
     * dialog ("Where would you like to teleport to?", each option a sentence). A sentence-shaped
     * option (a menu option never ends in a period) counts too. A destination-named option that
     * names no amulet (a port travel menu) never books (owner 2026-10-14).
     */
    static boolean isAmuletTeleport(MenuOptionClicked event, String target, String option) {
        if (supportedForId(weaponItemId(event)) == SAILORS_AMULET) return true;
        if (target != null && target.contains("sailors' amulet")) return true;
        int dialogGroup = InterfaceID.Chatmenu.UNIVERSE >>> 16;
        if (event != null && (event.getParam1() >>> 16) == dialogGroup) return true;
        var widget = event == null ? null : event.getWidget();
        if (widget != null && (widget.getId() >>> 16) == dialogGroup) return true;
        return option != null && option.trim().endsWith(".");
    }

    /** The Rub interface's six destination buttons; a click there is a teleport (owner 1.3.4). */
    static final Set<Integer> ATES_DESTINATIONS = byId("ates_destinations").keySet();

    /** A Rub-interface destination click; the event carries the packed component id. */
    static boolean isAtesDestination(MenuOptionClicked event) {
        if (event == null) return false;
        if (ATES_DESTINATIONS.contains(event.getParam1())) return true;
        var widget = event.getWidget();
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
        for (var anim : anims) {
            if (anim == null) continue;
            long key = (long) anim.getId() << 32 | anim.getStartCycle() & 0xFFFFFFFFL;
            if (playing.add(key) && !playingAnims.contains(key)) onGraphic(anim.getId(), now);
        }
        playingAnims = playing;
    }

    void onGraphic(int graphic, long now) {
        Item weapon = wornWeapon();
        var variant = weapon == null ? null : supportedForId(weapon.getId());
        if (variant == null || graphic <= 0) return;
        boolean eye = variant == EYE_OF_AYAK;
        if (!isCastGraphic(variant, graphic) && !(eye && eyeGraphics.contains(graphic))) {
            if (eye) {
                // The eye's cast trigger is pinned in no table: learn it from the magic XP every
                // cast drops (owner 2026-09-29), then each cast books one tear like a spell.
                eyeCandidateGraphic = graphic;
                eyeCandidateTick = client.getTickCount();
                eyeCandidateWeaponId = weapon.getId();
                eyeCandidateIdentity = wornIdentity(EYE_OF_AYAK, weapon.getId());
            }
            return;
        }
        int tick = client.getTickCount();
        if (tick == lastCastTick && graphic == lastCastGraphic || eye && tick == lastAttackTick)
            return;
        lastCastTick = tick;
        lastCastGraphic = graphic;
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
        var variant = weapon == null ? null : supportedForId(weapon.getId());
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
        boolean scythe = variant == SCYTHE && damage > 0;
        Actor local = client.getLocalPlayer();
        boolean shot = variant == BLOWPIPE && local != null
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
        var variant = weapon == null ? null : supportedForId(weapon.getId());
        if (variant == null) return;
        if (variant == EYE_OF_AYAK) {
            if (!eyeAnimations.contains(animationId)) {
                // Candidate cast animation; the same tick's magic XP proves it, like the graphic.
                eyeCandidateAnimation = animationId;
                eyeCandidateTick = client.getTickCount();
                eyeCandidateWeaponId = weapon.getId();
                eyeCandidateIdentity = wornIdentity(EYE_OF_AYAK, weapon.getId());
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

    /** Progress counts actual splinters consumed, not an average cost for every ranged shot. */
    void quiverProgress(int progress, long now) {
        int before = quiverBlessing;
        quiverBlessing = progress;
        int used = progress - before;
        Item cape = wornItem(EquipmentInventorySlot.CAPE);
        var load = engine.inbox.peek(LOAD_TARGET);
        if (before < 0 || used <= 0 || used > 2 || cape == null
                || supportedForId(cape.getId()) != DIZANAS
                || load != null && load.variant == DIZANAS) return;
        String identity = heldIdentity(DIZANAS, EquipmentInventorySlot.CAPE);
        for (int i = 0; i < used; i++)
            bookAttackEstimate(DIZANAS, cape.getId(), identity, ActionKind.FIRE, now);
    }

    Item wornWeapon() {
        return wornItem(EquipmentInventorySlot.WEAPON);
    }

    Item wornItem(EquipmentInventorySlot slot) {
        var gear = container(InventoryID.WORN);
        return gear == null ? null : gear.getItem(slot.getSlotIdx());
    }

    void bookAttackEstimate(ChargedItem variant, int weaponId, String identity, ActionKind kind, long now) {
        // A hit is gameplay: resume the pauses gameplay ends (logout/idle/recovery) and start
        // tracking when automatic startup is on, before booking — so the very first attack after
        // logging in counts instead of arriving while the session is still catching up.
        engine.resume(now, PauseReason.LIFECYCLE, PauseReason.IDLE, PauseReason.RECOVERY);
        if (!engine.live()) engine.autoStart(now);
        var recipe = estimateRecipe(variant, identity, now);
        if (empty(recipe)) return;
        var receipt = engine.bookEstimatedCharge(identity, variant.displayName, kind, recipe, now);
        if (receipt != null) {
            receipt.castItemId = weaponId;
            if (variant == ATES) {
                lastAtesIdentity = identity;
                lastAtesTransactionId = receipt.getId();
                lastAtesAt = now;
            }
        }
    }

    /** How long after a pendant click its refusal message still cancels the estimate. */
    static final long REFUSAL_WINDOW_MILLIS = 3_000;

    /** The newest pendant click's estimate, so a refusal can remove exactly that row (1.3.4). */
    String lastAtesIdentity, lastAtesTransactionId;
    long lastAtesAt;

    /**
     * The game refused a pendant teleport ("you appear to already be at that location"), so the
     * click consumed nothing: the estimate that click booked is removed through the announced
     * automatic-evidence path (owner 1.3.4). Earlier pendant estimates are untouched.
     */
    void refusedTeleport(long now) {
        String identity = lastAtesIdentity, id = lastAtesTransactionId;
        lastAtesIdentity = null;
        lastAtesTransactionId = null;
        if (identity == null || id == null || now - lastAtesAt > REFUSAL_WINDOW_MILLIS) {
            return;
        }
        engine.forfeitEstimate(identity, id);
    }

    /** A family may cast with more than one local graphic (Sanguinesti's Justiciar variant). */
    static boolean isCastGraphic(ChargedItem variant, int graphic) {
        return graphic > 0 && Arrays.stream(variant.graphics).anyMatch(id -> id == graphic);
    }

    /** The attack animation pinned for per-shot estimates; 0 means none. */
    static int attackAnimation(ChargedItem variant) {
        return variant == BLOWPIPE ? 5061 : 0;
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
    static String wornIdentity(ChargedItem variant, int weapon) {
        return weaponIdentity(InterfaceID.Wornitems.UNIVERSE, EquipmentInventorySlot.WEAPON.getSlotIdx(), weapon,
                variant);
    }

    /**
     * The worn-container identity of a clicked item, by the slot that actually holds it. A Check
     * from the Worn Equipment tab arrives with param0 -1 (owner capture 2026-10-12), so the
     * clicked location is unknown; the item's own worn slot is the identity the estimates use.
     */
    String wornCheckIdentity(ChargedItem variant, int weapon) {
        if (client == null || variant == null) return null;
        var gear = client.getItemContainer(InventoryID.WORN);
        if (gear == null) return null;
        for (var slot : EquipmentInventorySlot.values()) {
            Item item = gear.getItem(slot.getSlotIdx());
            if (item == null || supportedForId(item.getId()) != variant) continue;
            if (weapon > 0 && item.getId() != weapon) continue;
            return weaponIdentity(InterfaceID.Wornitems.UNIVERSE, slot.getSlotIdx(), item.getId(), variant);
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
        String cape = lower(capeName);
        if (cape.contains("assembler") || cape.contains("dizana")) return new long[] {1L, 5L};
        if (cape.contains("accumulator")) return new long[] {7L, 25L};
        if (cape.contains("attractor")) return new long[] {2L, 5L};
        return new long[] {1L, 1L};
    }

    String wornCapeName() {
        Item cape = wornItem(EquipmentInventorySlot.CAPE);
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
    long carriedValue(ChargedItem variant, int id, long units, int price, int item) {
        if (units <= 0 || price <= 0 || item <= 1)
            return nonNeg(units * max(0, price));
        // Only the new units are priced, at today's quote; earlier use keeps the price it was
        // booked
        // at (Codex review 1.2). The sub-gp remainder carries, and so does any 1 gp floor overpaid.
        long[] carry = valueCarry.computeIfAbsent(variant.name() + ":" + id, ignored -> new long[1]);
        long total = carry[0] + units * price, booked = max(1, total / item);
        carry[0] = total - booked * item;
        return booked;
    }

    /** Accrues one fractional component into the recipe (owner 1.4.0 size pass). */
    void accrueInto(ChargedItem variant, int id, long numerator, long denominator, Map<Integer, Long> recipe) {
        String key = variant.name() + ":" + id;
        long[] accrued = accrue(chargeCarry.getOrDefault(key, 0L), numerator, denominator);
        chargeCarry.put(key, accrued[1]);
        if (accrued[0] > 0) recipe.put(id, accrued[0]);
    }

    /** One cast's priced recipe, or null when a component lacks a supported valuation. */
    List<Flow> estimateRecipe(ChargedItem variant, String identity, long now) {
        var recipe = recipeOf(variant);
        if (variant == EYE_OF_AYAK) recipe = eyeRecipe(eyeRune(identity));
        long[] fraction = fractionOf(variant);
        if (fraction != null) {
            accrueInto(variant, (int) fraction[0], fraction[1], fraction[2], recipe);
        }
        if (variant == BLOWPIPE && blowpipeDart > 0) {
            // Owner 1.1: each shot spends its dart unless an Ava's device saves it; the expected
            // share
            // accrues like the scales, and a Check reconciles it.
            long[] loss = dartLoss(wornCapeName());
            accrueInto(variant, blowpipeDart, loss[0], loss[1], recipe);
        }
        var flows = new ArrayList<Flow>();
        for (var entry : recipe.entrySet()) {
            Flow flow = chargeFlow(variant, entry.getKey(), -entry.getValue(), now, true);
            if (flow == null) return null;
            flows.add(flow);
        }
        return flows;
    }

    /** One price/carry path; measured rows preserve their component id and unpriced evidence. */
    Flow chargeFlow(ChargedItem variant, int identifier, long units, long now, boolean estimate) {
        int id = lookup == null ? -1 : lookup.canon(identifier);
        var item = identifier <= 0 || id <= 0 ? null : lookup.composition(id);
        if (item == null || blank(item.getName())) return null;
        Flow priced = valuation == null ? null : valuation.valueComponent(id, units, now);
        int price = priced == null ? 0 : priced.unitPrice;
        if (estimate && price <= 0) return null;
        int perItem = unitsPerItem(variant, identifier);
        long value = safeMultiply(units, price);
        if (perItem > 1 && price > 0) {
            value = -carriedValue(variant, identifier, Math.abs(units), price, perItem);
            price = max(1, price / perItem);
        }
        return new Flow(estimate ? id : identifier, item.getName(), units, price, value,
                priced == null ? UNPRICED : priced.getPriceSource(), now);
    }

    /**
     * Link one numeric Check response to the item slot that was actually checked. The click
     * contains identity only; any other menu action supersedes the pending link.
     */
    void armCheck(MenuOptionClicked event, String option, String target) {
        engine.inbox.drop(CHECK_LINK);
        int raw = weaponItemId(event);
        var variant = supportedForId(raw);
        if (variant == null) variant = supportedForName(target);
        if ("configure-charges".equals(option)) {
            // The bank's Configure-Charges loads or removes charges with bank items the plugin
            // never sees, so no Check of that kind can be compared across it (OSRS Wiki, 5 March
            // 2025).
            engine.resetChecks(variant);
            return;
        }
        if ("charge".equals(option) && variant != null && !variant.recipe.isEmpty()) {
            // A right-click Charge prompt applies the weapon's own component from the pack
            // (Tonalztics of Ralos: "You apply N charges…", OSRS Wiki transcript 2026-10-12).
            int part = variant.recipe.keySet().iterator().next();
            engine.markChargeLoad(
                    variant, part, itemName(part), clickedIdentity(event, variant, raw), LOAD_TICKS);
            return;
        }
        if (("uncharge".equals(option) || "unload".equals(option) || "empty".equals(option)) && variant != null) {
            // These actions can return loaded components. A later lower Check value
            // cannot be treated as consumed charges across that unmeasured return.
            engine.resetChecks(variant);
            // The return is the player's own charge investment coming back: an
            // ownership-neutral transfer, never income (owner 2026-09-29).
            markReturnTransfer();
            // Most weapons ask to confirm first, so the components can land long after this short
            // context: keep watching for them (watchReturn) instead of booking them as a gain.
            if (client != null) {
                var e = engine.inbox.put(UNCHARGE, -1, 1, RETURN_TICKS, null);
                e.variant = variant;
                e.at = client.getTickCount() + RETURN_TICKS;
                e.data = inventoryCounts();
            }
            return;
        }
        if (!"check".equals(option) || variant == null || !variant.implemented
                || variant.isCommittedLoad()) return;
        String identity = clickedIdentity(event, variant, raw);
        if (identity == null) {
            // A Check from the Worn Equipment tab carries no slot (param0 -1, owner capture
            // 2026-10-12): the item's own worn slot names the baseline the estimates use.
            identity = wornCheckIdentity(variant, raw);
        }
        if (identity != null)
            startCheck(variant, identity, client.getTickCount(), 2);
    }

    /**
     * Each tick before booking: an Uncharge's own components landing make that change a transfer.
     */
    void watchReturn() {
        var e = engine.inbox.peek(UNCHARGE);
        if (e == null || client == null) return;
        if (client.getTickCount() > e.at) {
            engine.inbox.drop(UNCHARGE);
            return;
        }
        if (!returnLanded(e.variant, (Map<Integer, Long>) e.data, inventoryCounts(), this::itemName)) return;
        markReturnTransfer();
        engine.inbox.drop(UNCHARGE);
    }

    /** The returned charge is the player's own investment coming back: ownership-neutral. */
    void markReturnTransfer() {
        transfer("charge-unload");
    }

    /** A click that answers a chat dialog (the "Really uncharge?" Yes/No), not a new action. */
    static boolean isDialogAnswer(MenuOptionClicked event, String option) {
        return event.getMenuAction() == MenuAction.WIDGET_CONTINUE || "yes".equals(option) || "continue".equals(option);
    }

    /** True when an item that loads this weapon (its scales, darts or runes) increased. */
    static boolean returnLanded(ChargedItem variant, Map<Integer, Long> before, Map<Integer, Long> after,
            java.util.function.IntFunction<String> names) {
        for (var item : after.entrySet()) {
            if (item.getValue() > before.getOrDefault(item.getKey(), 0L)
                    && isLoadComponent(variant, item.getKey(), names.apply(item.getKey())))
                return true;
        }
        return false;
    }

    String itemName(int id) {
        var composition = lookup == null ? null : lookup.composition(id);
        return composition == null ? null : composition.getName();
    }

    /** Canonical inventory quantities, so noted or placeholder ids compare as the item itself. */
    Map<Integer, Long> inventoryCounts() {
        var inventory = container(InventoryID.INV);
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
        var clicked = event.getWidget();
        var selected = client == null ? null : client.getSelectedWidget();
        var used = selected == null ? null : supportedForId(selected.getItemId());
        if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT && used != null) {
            // A weapon used on an object (the scythe on the Vyre well) loads its recipe from the
            // pack.
            int part = used.recipe.isEmpty() ? (int) used.fraction[0] : used.recipe.keySet().iterator().next();
            engine.markChargeLoad(used, part, String.valueOf(part), weaponIdentity(
                            selected.getId(), selected.getIndex(), selected.getItemId(), used), LOAD_TICKS);
            return;
        }
        if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_WIDGET || clicked == null || selected == null) {
            engine.clearChargeLoad();
            return;
        }
        boolean weaponClicked = supportedForId(clicked.getItemId()) != null;
        var weapon = weaponClicked ? clicked : selected;
        var variant = supportedForId(weapon.getItemId());
        int source = canonical((weaponClicked ? selected : clicked).getItemId());
        String name = source <= 0 ? null : itemName(source);
        if (variant == null || name == null || !isLoadComponent(variant, source, name)) {
            engine.clearChargeLoad();
            return;
        }
        if (variant == BLOWPIPE && DARTS.containsValue(source))
            blowpipeDart = source;
        String target = weaponIdentity(weapon.getId(), weapon.getIndex(), weapon.getItemId(), variant);
        engine.markChargeLoad(variant, source, name, target, LOAD_TICKS);
    }

    int canonical(int id) {
        return lookup == null || id <= 0 ? id : lookup.canon(id);
    }

    /** Resolve the weapon targeted by the menu action, distinct from an item-on-item source. */
    static int weaponItemId(MenuOptionClicked event) {
        if (event == null) return -1;
        int id = event.getId();
        if (supportedForId(id) != null) return id;
        int identifier = event.getItemId();
        if (supportedForId(identifier) != null) return identifier;
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
    static String clickedIdentity(MenuOptionClicked event, ChargedItem variant, int weapon) {
        if (event == null) return null;
        int widget = event.getParam1();
        if (widget <= 0 && event.getWidget() != null) widget = event.getWidget().getId();
        return weaponIdentity(widget, event.getParam0(), weapon, variant);
    }

    /**
     * Container group, slot, item and variant uniquely identify one weapon: the same item in
     * inventory and equipment (or two slots in either container) cannot share a baseline, and a
     * worn estimate builds the identical identity from the equipment slot.
     */
    static String weaponIdentity(int widget, int slot, int weapon, ChargedItem variant) {
        int widgetGroup = widget >>> 16;
        if (variant == null || weapon <= 0 || slot < 0 || widget <= 0 || widgetGroup <= 0)
            return null;
        return widgetGroup + ":" + slot + ":" + weapon + ":" + variant.name();
    }

    /** The item id inside a {@link #weaponIdentity} ("group:slot:item:variant"). */
    static int identityItemId(String identity) {
        return Integer.parseInt(identity.split(":")[2]);
    }
}

/**
 * One explicit weapon Check response, expressed as its measured component stock. It does not infer
 * depletion from combat events, menu intent, or probabilities.
 */
@AllArgsConstructor
class ChargeCheck {
    static final int SCALES = ItemID.SNAKEBOSS_SCALE;
    static final Pattern BLOWPIPE_CHECK = pattern("blowpipe-check"), TYPED_DARTS = pattern("dart-count");
    static final Pattern EYE_OF_AYAK_CHECK = pattern("eye-tears-check"), EYE_RUNE_CHECK = pattern("eye-runes-check");
    static final Map<String, Integer> DARTS = new LinkedHashMap<>();

    static {
        for (String[] row : rows("darts")) DARTS.put(row[0], Integer.parseInt(row[1]));
    }

    /**
     * Charged-item families; each one's data (names, ids, triggers, recipe, Check shape) is a d20
     * row.
     */
    @Getter
    enum ChargedItem {
        TRIDENT_SEAS, TRIDENT_SWAMP, TRIDENT_SWAMP_ENHANCED, TRIDENT_SEAS_ENHANCED, BLOWPIPE, SHADOW, SANGUINESTI,
        SANGUINESTI_HOLY, WARPED, VENATOR, VENATOR_ECHO, EYE_OF_AYAK, SCYTHE, ATES, SAILORS_AMULET, TOME_OF_FIRE,
        CRYSTAL_BOW, CRYSTAL_HALBERD, BOWFA, SAELDOR, BLOOD_FURY, CRAW, WEBWEAVER, VIGGORA, URSINE, THAMMARON, ACCURSED,
        MEMOIRS, BRYOPHYTA, TOXIC_STAFF, SERPENTINE_HELM, CRYSTAL_HELM, CRYSTAL_BODY, CRYSTAL_LEGS, CRYSTAL_SHIELD,
        SUFFERING, TONALZTICS, TOME_OF_WATER, TOME_OF_EARTH, ETHEREUM, BONE_STAFF, CRYSTAL_AXE, CRYSTAL_PICKAXE,
        CRYSTAL_HARPOON, ECHO_BOOTS, SLAYER_STAFF_E, INFERNAL_AXE, INFERNAL_PICKAXE, INFERNAL_HARPOON, DIZANAS;
        String displayName;
        boolean implemented;
        String family;
        ActionKind action;
        int units;

        /** A load's components can never be refunded: the load books as spend, not a transfer. */
        boolean committedLoad;

        /** The Check line's pattern with its count group and its single-charge group (0: none). */
        Pattern check;

        int countGroup, oneGroup;
        int[] graphics, animations, itemIds;

        /** Components one use spends; a Check's count of uses times this is its measured stock. */
        Map<Integer, Long> recipe;

        /** A fractional per-use part, [itemId, numerator, denominator], or null. */
        long[] fraction;

        List<String> aliases;

        static {
            for (String[] row : rows("charged_items")) {
                ChargedItem item = valueOf(row[0]);
                item.displayName = row[1];
                item.implemented = Boolean.parseBoolean(row[2]);
                item.family = row[3];
                item.action = ActionKind.valueOf(row[4]);
                item.units = Integer.parseInt(row[5]);
                if (!"-".equals(row[6])) {
                    String[] check = row[6].split(" ");
                    item.check = pattern(check[0]);
                    item.countGroup = Integer.parseInt(check[1]);
                    item.oneGroup = Integer.parseInt(check[2]);
                }
                item.graphics = ids(row[7]);
                item.animations = ids(row[8]);
                var recipe = new LinkedHashMap<Integer, Long>();
                for (String part : parts(row[9], " "))
                    recipe.put(id(part), Long.parseLong(part.substring(0, part.indexOf('*'))));
                item.recipe = unmodifiableMap(recipe);
                if (!"-".equals(row[10])) {
                    String[] fraction = row[10].split("[/*]");
                    item.fraction = new long[] {
                                id(row[10]), Long.parseLong(fraction[0]), Long.parseLong(fraction[1])
                            };
                }
                item.aliases = parts(row[11], ";");
                item.itemIds = ids(row[12]);
            }
            for (String[] row : rows("committed_loads")) {
                valueOf(row[0]).committedLoad = true;
            }
        }

        static List<String> parts(String cell, String separator) {
            return "-".equals(cell) ? emptyList() : List.of(cell.split(separator));
        }

        /** The id in "qty*id=NAME" or "id=NAME". */
        static int id(String part) {
            return Integer.parseInt(part.substring(part.indexOf('*') + 1, part.indexOf('=')));
        }

        static int[] ids(String cell) {
            return parts(cell, ",").stream().mapToInt(ChargedItem::id).toArray();
        }
    }

    ChargedItem variant;
    Map<Integer, Long> componentCounts;

    /** Returns -1 when a Check explicitly reported no darts or had an unknown type. */
    int dartItemId;

    boolean bookable;

    /**
     * Parse a pinned Check-message shape. Returns {@code null} for unrelated chat. Recognized but
     * unsupported/unknown variants return a non-bookable read so a tracker can clear an older
     * baseline safely.
     */
    static ChargeCheck parse(String rawMessage) {
        String message = stripTags(rawMessage);
        var surface = message == null ? null : surface(message.toLowerCase(ROOT));
        if (surface == null) return null;
        // A recognizably malformed Check surface resets the caller's old baseline
        // rather than carrying stale evidence forward.
        var components = new LinkedHashMap<Integer, Long>();
        int dartId = -1;
        try {
            var blowpipe = BLOWPIPE_CHECK.matcher(message);
            if (blowpipe.matches()) {
                components.put(SCALES, parseCount(blowpipe.group(2)));
                String dartPart = blowpipe.group(1).trim();
                var darts = TYPED_DARTS.matcher(dartPart);
                if (!"none".equalsIgnoreCase(dartPart)) {
                    dartId = darts.matches() ? DARTS.getOrDefault(normalizeItemName(darts.group(1)), -1) : -1;
                    if (dartId < 0) return unsupported(surface);
                    components.put(dartId, parseCount(darts.group(2)));
                }
            } else if (surface == EYE_OF_AYAK) {
                var eye = EYE_OF_AYAK_CHECK.matcher(message);
                var runes = EYE_RUNE_CHECK.matcher(message);
                if (eye.matches()) {
                    components.put(ItemID.DEMON_TEAR, parseCount(eye.group(1)));
                } else if (runes.matches()) {
                    long charges = parseCount(runes.group(1));
                    components.put(ItemID.DEATHRUNE, multiplyExact(charges, 2L));
                    components.put(ItemID.CHAOSRUNE, charges);
                }
            } else if (surface.check != null) {
                // Every other family's Check counts uses, so its stock is that count times one
                // use's recipe.
                var read = surface.check.matcher(message);
                if (read.matches()) {
                    String included = read.group(surface.countGroup);
                    long count = included != null ? parseCount(included)
                                    : surface.oneGroup > 0 && read.group(surface.oneGroup) != null ? 1L : 0L;
                    for (var part : surface.recipe.entrySet())
                        components.put(part.getKey(), multiplyExact(count, part.getValue()));
                }
            }
            if (!components.isEmpty())
                return new ChargeCheck(surface, unmodifiableMap(components), dartId, true);
        } catch (NumberFormatException | ArithmeticException malformed) {
            // The count is syntax-checked; this is a value outside the supported range.
        }
        return unsupported(surface);
    }

    /** The Check surface a lowercase chat line belongs to, whether or not it parses. */
    static ChargedItem surface(String lower) {
        if (lower.startsWith("darts:") && lower.contains("scales:")) return BLOWPIPE;
        if (lower.startsWith("the pendant has ")) return ATES;
        if (lower.startsWith("the amulet has ") || lower.startsWith("your amulet has ")) {
            // The sailors' amulet's own Check wording ("...has 106 charges." or "...charges
            // left."); the blood fury's hit-count line names itself below (owner 2026-10-14).
            return SAILORS_AMULET;
        }
        if (lower.startsWith("your tome has been charged with "))
        {
            // The page names the tome; a bare "your tome" line stays fire and the clicked row
            // redirects it (owner 2026-10-12).
            if (lower.contains("soaked")) return TOME_OF_WATER;
            if (lower.contains("soiled")) return TOME_OF_EARTH;
            return TOME_OF_FIRE;
        }
        if (lower.startsWith(msg("amulet-blood"))) return BLOOD_FURY;
        if (lower.startsWith(msg("eye-ayak-been")) || lower.startsWith(msg("eye-ayak-been-charged"))) {
            return EYE_OF_AYAK;
        }
        for (var variant : ChargedItem.values()) {
            if (variant == BLOWPIPE) continue;
            String name = variant.displayName.toLowerCase(ROOT);
            if (lower.startsWith("your " + name + " has ") || lower.startsWith("your " + name + " only has ")
                    || lower.startsWith(name + " has ") || lower.startsWith(name + " only has ")) {
                return variant;
            }
        }
        // A Check that names no weapon ("Your bow has…"): the first row sharing that wording.
        for (var variant : ChargedItem.values()) {
            if (variant.check != null && variant.check.matcher(lower).matches()) return variant;
        }
        return null;
    }

    /** Resolve only exact supported menu-target names; menu evidence is identity only. */
    static ChargedItem supportedForName(String title) {
        String name = normalizeItemName(title);
        if (name == null) return null;
        for (var variant : ChargedItem.values()) {
            if (variant.implemented && (variant.displayName.toLowerCase(ROOT).equals(name)
                            || variant.aliases.contains(name))) {
                return variant;
            }
        }
        return null;
    }

    /**
     * Any catalogue variant by exact display name for presentation; estimate-only variants
     * included.
     */
    static ChargedItem variantForName(String title) {
        String name = normalizeItemName(title);
        for (var variant : ChargedItem.values()) {
            if (variant.displayName.toLowerCase(ROOT).equals(name)) return variant;
        }
        return null;
    }

    /** Resolve charged and uncharged item ids so a Check receipt can be tied to its menu target. */
    static ChargedItem supportedForId(int identifier) {
        for (var variant : ChargedItem.values()) {
            for (int id : variant.itemIds) {
                if (id == identifier) return variant;
            }
        }
        return null;
    }

    /**
     * Validate a candidate item-on-weapon load component. Both the canonical id and displayed name
     * must agree, so unknown dart types fail closed.
     */
    static boolean isLoadComponent(ChargedItem variant, int id, String name) {
        String expectedName = variant == null || !variant.implemented ? null : expectedLoadName(variant, id);
        String actualName = normalizeItemName(name);
        // Owner 1.2: what an item's own charge is made of is loaded, never spent, for every item.
        if (actualName != null && variant != null && (variant.recipe.containsKey(id)
                        || variant.fraction != null && variant.fraction[0] == id)) return true;
        return expectedName != null && actualName != null && (expectedName.equals(actualName)
                        || actualName.equals(expectedName + "s") && !expectedName.endsWith("s"));
    }

    boolean hasDarts() {
        return dartItemId >= 0;
    }

    /** Component units per priced item: a page feeds 20 charges, a shard 100. */
    static int unitsPerItem(ChargedItem variant, int componentItemId) {
        return variant.units;
    }

    static ChargeCheck unsupported(ChargedItem variant) {
        return new ChargeCheck(variant, emptyMap(), -1, false);
    }

    /** The same measured count expressed in another same-pattern variant's own recipe. */
    static Map<Integer, Long> restock(ChargedItem target, ChargedItem source, Map<Integer, Long> counts) {
        if (counts.isEmpty() || source.recipe.isEmpty()) return counts;
        long unit = source.recipe.values().iterator().next(), count = counts.values().iterator().next() / unit;
        var stock = new LinkedHashMap<Integer, Long>();
        for (var part : target.recipe.entrySet()) stock.put(part.getKey(), count * part.getValue());
        return stock;
    }

    /**
     * A load component not in the item's own recipe: the blowpipe's darts, and the Eye of Ayak's
     * runes in rune mode (owner 2026-09-30). Everything else loads through its recipe.
     */
    static String expectedLoadName(ChargedItem variant, int id) {
        if (variant == BLOWPIPE) {
            for (var dart : DARTS.entrySet()) {
                if (dart.getValue() == id) return dart.getKey();
            }
        }
        if (variant != EYE_OF_AYAK) return null;
        return id == ItemID.DEATHRUNE ? "death rune" : id == ItemID.CHAOSRUNE ? "chaos rune" : null;
    }

    static long parseCount(String raw) {
        return Long.parseLong(raw.replace(",", ""));
    }

    static String stripTags(String value) {
        return value == null ? null : value.replaceAll("<[^>]*>", "").trim();
    }

    static String normalizeItemName(String value) {
        String cleaned = stripTags(value);
        return empty(cleaned) ? null : cleaned.toLowerCase(ROOT).replaceAll("\\s+", " ");
    }
}

/** A load target's candidate losses; only a measured Check can reconcile their quantities. */
@AllArgsConstructor
class LoadTarget {
    ChargedItem variant;
    String targetIdentity;
    List<Flow> remaining, ambiguousCandidates;

    /** When the load was clicked; 0 when there was no load. */
    long clickedAt;
}

/**
 * Transient ownership of automatic charge estimates. The canonical receipts remain in the owning
 * {@link Session}; this index links one weapon (session and target identity) to those receipt ids
 * until a compatible measured Check closes the window. Never persisted.
 */
class ChargeEstimateJournal {
    static final int MAX_RECEIPTS = 512;

    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Receipt {
        final String key, transactionId;
        final int itemId;
        long remainingQuantity;

    }

    /** Open receipts per weapon, oldest weapon first. */
    final LinkedHashMap<String, List<Receipt>> entries = new LinkedHashMap<>();

    /** Quantities evicted by the bound while their weapon's window is still open, per item. */
    final Map<String, Map<Integer, Long>> evicted = new HashMap<>();

    int receiptCount;

    static String key(String id, String target) {
        return lower(id) + "\n" + lower(target);
    }

    synchronized void clear() {
        entries.clear();
        evicted.clear();
        receiptCount = 0;
    }

    synchronized void record(String id, String target, String identifier, int itemId, long quantity) {
        if (blank(id) || blank(target) || blank(identifier) || itemId <= 0
                || quantity <= 0) {
            return;
        }
        String key = key(id, target);
        entries.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Receipt(key, identifier, itemId, quantity));
        receiptCount++;
        while (receiptCount > MAX_RECEIPTS) {
            var first = entries.entrySet().iterator().next();
            var gone = first.getValue().remove(0);
            receiptCount--;
            // The window is still open: remember the evicted quantity so the next measured
            // delta is not booked on top of an estimate that is no longer tracked here.
            evicted.computeIfAbsent(gone.key, ignored -> new HashMap<>())
                    .merge(gone.itemId, gone.remainingQuantity, Long::sum);
            if (first.getValue().isEmpty()) entries.remove(first.getKey());
        }
    }

    /** Evicted quantity still inside this weapon's open window for one component. */
    synchronized long evictedOverlap(String id, String target, int identifier) {
        return evicted.getOrDefault(key(id, target), Map.of()).getOrDefault(identifier, 0L);
    }

    /** Pending receipts for one component of one weapon. */
    synchronized List<Receipt> receipts(String id, String target, int identifier) {
        return matching(receiptsFor(id, target), receipt -> receipt.itemId == identifier);
    }

    /** Every pending receipt for one weapon, whichever component it belongs to. */
    synchronized List<Receipt> receiptsFor(String id, String target) {
        return new ArrayList<>(entries.getOrDefault(key(id, target), List.of()));
    }

    synchronized void consume(Receipt receipt, long quantity) {
        var values = receipt == null ? null : entries.get(receipt.key);
        if (values == null || quantity <= 0 || !values.contains(receipt)) return;
        receipt.remainingQuantity = nonNeg(receipt.remainingQuantity - quantity);
        if (receipt.remainingQuantity == 0) {
            values.remove(receipt);
            receiptCount--;
        }
        if (values.isEmpty()) entries.remove(receipt.key);
    }

    /** Forget every receipt, and all evicted overlap, for one weapon (its window closed). */
    synchronized void clearTarget(String id, String target) {
        String key = key(id, target);
        var removed = entries.remove(key);
        if (removed != null) receiptCount -= removed.size();
        evicted.remove(key);
    }
}

/** Measured negative component-count differences; valuation is applied by the caller. */
@Getter
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class ChargeDelta {
    ChargedItem variant;
    List<ComponentDelta> componentDeltas;

    /** Components the Check read at the same count as before: their estimates were overcounts. */
    final Set<Integer> unchanged = new HashSet<>();

    /** Explicit Check outcome; this enum is transient accounting evidence, never save data. */
    enum CheckResult {
        SEED, SAME, SPEND, LOAD, MISMATCH
    }

    CheckResult result;


    /** A caller supplying measured losses explicitly constructs SPEND, as before. */
    /** An outcome without a measured decrease carries no bookable component quantity. */
    ChargeDelta(ChargedItem variant, CheckResult result) {
        this(variant, emptyList(), result);
    }

    /** One component's measured decrease (always negative). */
    @Getter
    @AllArgsConstructor
    static class ComponentDelta {
        int itemId;
        long quantityDelta;
    }

    /**
     * The GP cost of these priced component losses when they exactly match every measured decrease;
     * 0 otherwise. Missing, partial, extra, unsupported-priced or overflowing flows fail closed as
     * a whole. Generic inventory losses can never authorize charge spend.
     */
    long exactCost(List<Flow> pricedLosses) {
        if (result != CheckResult.SPEND || variant == null || !variant.implemented || componentDeltas.isEmpty()
                || pricedLosses == null || pricedLosses.size() != componentDeltas.size()) {
            return 0;
        }
        var expected = new HashMap<Integer, Long>();
        var actual = new HashMap<Integer, Long>();
        long cost = 0;
        try {
            for (var component : componentDeltas) {
                if (component.itemId <= 0) return 0;
                expected.merge(component.itemId, component.quantityDelta, Math::addExact);
            }
            for (Flow flow : pricedLosses) {
                boolean validPriceSource = (flow.itemId == ItemID.COINS
                                        ? flow.unitPrice == 1 && flow.getPriceSource() == FACE_VALUE
                                        : flow.getPriceSource() == GRAND_EXCHANGE
                                                || flow.getPriceSource() == MANUAL_OVERRIDE);
                if (!validPriceSource || flow.quantityDelta >= 0 || flow.itemId <= 0 || flow.unitPrice <= 0
                        || flow.valueDelta >= 0 || multiplyExact(flow.quantityDelta, (long) flow.unitPrice)
                                != flow.valueDelta) {
                    return 0;
                }
                actual.merge(flow.itemId, flow.quantityDelta, Math::addExact);
                cost = addExact(cost, negateExact(flow.valueDelta));
            }
        } catch (ArithmeticException ex) {
            return 0;
        }
        return expected.equals(actual) ? cost : 0;
    }
}

/**
 * Compares explicit charge reads. One read establishes a baseline; only measured component
 * decreases can produce a booking-ready delta.
 */
class MeasuredChargeReadTracker {
    /**
     * Bounded number of same-variant weapons tracked side by side (two blowpipes, a spare trident).
     */
    static final int MAX_TARGETS = 8;

    /**
     * One baseline per stable target identity, most recently read last: each weapon's last read,
     * which its next Check is measured against.
     */
    LinkedHashMap<String, Baseline> baselines = new LinkedHashMap<>();

    /** One weapon's last exact read and its local observation time. */
    @Getter
    @AllArgsConstructor
    static class Baseline {
        ChargeCheck read;
        long atEpochMillis;

        /**
         * Component units loaded into the weapon since this read, which its next Check adds back.
         */
        final Map<Integer, Long> loaded = new HashMap<>();
    }

    /**
     * A load into one weapon, clicked at {@code clickedAt}: its next Check counts it as loaded, not
     * as unused. A load clicked before the last read is already in that read. A load whose weapon
     * is unknown goes to the only weapon of that kind with a read, or else makes that kind's reads
     * untrustworthy.
     */
    synchronized void noteLoad(ChargedItem variant, String target, long clickedAt, int id,
            long units) {
        var last = target == null ? only(variant) : baselines.get(target.trim());
        if (target == null && last == null) {
            resetVariant(variant);
            return;
        }
        if (last == null || clickedAt < last.atEpochMillis || units <= 0) return;
        last.loaded.merge(id, units, SafeMath::safeAdd);
    }

    /** Observe one exact Check read and retain its local observation time. */
    synchronized ChargeDelta observe(ChargeCheck read, String target, long now) {
        if (read == null) return new ChargeDelta(null, ChargeDelta.CheckResult.MISMATCH);
        if (!read.bookable || blank(target)) {
            reset();
            return new ChargeDelta(read.variant, ChargeDelta.CheckResult.MISMATCH);
        }
        String safeIdentity = target.trim();
        // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
        // decrease on this one nor a reason to forget what this one last read.
        var last = baselines.remove(safeIdentity);
        baselines.put(safeIdentity, new Baseline(read, nonNeg(now)));
        while (baselines.size() > MAX_TARGETS)
            baselines.remove(baselines.keySet().iterator().next());
        var previous = last == null ? null : last.read;
        if (previous == null) return new ChargeDelta(read.variant, ChargeDelta.CheckResult.SEED);
        if (previous.variant != read.variant)
            return new ChargeDelta(read.variant, ChargeDelta.CheckResult.MISMATCH);
        boolean blowpipe = read.variant == BLOWPIPE;
        // Dart type is an independent component identity. A type switch can return old darts
        // and load new ones, so do not infer dart spend from the change; scale measurements
        // remain valid across that switch.
        boolean dartTypeSwitch = blowpipe && read.hasDarts()
                        && (!previous.hasDarts() || previous.dartItemId != read.dartItemId);
        var previousCounts = previous.componentCounts;
        var loaded = last.loaded;
        var nextCounts = new LinkedHashMap<>(read.componentCounts);
        if (blowpipe && previous.hasDarts() && !read.hasDarts())
            nextCounts.put(previous.dartItemId, 0L);
        var itemIds = new LinkedHashSet<>(previousCounts.keySet());
        itemIds.addAll(nextCounts.keySet());
        var decreases = new ArrayList<ChargeDelta.ComponentDelta>();
        var unchanged = new ArrayList<Integer>();
        boolean same = true;
        for (int id : itemIds) {
            long before = previousCounts.getOrDefault(id, 0L);
            // What was loaded since is not use: count it as if it had been there all along.
            long after = nextCounts.getOrDefault(id, 0L) - loaded.getOrDefault(id, 0L);
            same &= after == before;
            if (after == before) unchanged.add(id);
            if (after < before && (!dartTypeSwitch || id == ItemID.SNAKEBOSS_SCALE)) {
                decreases.add(new ChargeDelta.ComponentDelta(id, after - before));
            }
        }
        // Independent decreases still prove spend when a different component was loaded.
        var delta = new ChargeDelta(read.variant, decreases, !decreases.isEmpty() ? ChargeDelta.CheckResult.SPEND : same
                                        ? ChargeDelta.CheckResult.SAME : ChargeDelta.CheckResult.LOAD);
        delta.unchanged.addAll(unchanged);
        return delta;
    }

    synchronized void reset() {
        baselines.clear();
    }

    /** The one baseline of this kind, or null when there are none or several. */
    Baseline only(ChargedItem variant) {
        Baseline found = null;
        for (var last : baselines.values()) {
            if (last.read.variant != variant) continue;
            if (found != null) return null;
            found = last;
        }
        return found;
    }

    /**
     * Forget one variant's baselines after a custody change: a new physical instance of the weapon
     * entered the inventory, so a stored baseline can no longer be trusted to belong to the item
     * now occupying the slot. The next Check seeds a fresh baseline.
     */
    synchronized void resetVariant(ChargedItem variant) {
        if (variant == null) return;
        baselines.values().removeIf(last -> last.read.variant == variant);
    }
}

/**
 * Key and chest claims. The account's unresolved claims are the only durable part: a claim opens
 * when a key audit row is booked (the first key type keeps the row's id, more types derive {@code
 * id#index}), shrinks as its chest contents settle or its keys are lost on death, and is removed at
 * zero. Only a claim that reached zero writes its id onto the settling transaction as {@code
 * sourceClaimId}, so a reload that sees both drops the stale claim without booking again, while a
 * partly settled claim keeps its persisted remainder. Claims never expire on their own.
 *
 * <p>Everything else is short-lived evidence: an ordinary chest's object click (and a key loss seen
 * while it is armed), the Wilderness loot-key chest container removals, and the local-death key
 * snapshot. Chest contents never become durable provenance. Invoked only under the engine monitor.
 */
class KeyClaims {
    static final int OPEN_WINDOW_TICKS = 4, DEATH_WINDOW_TICKS = 8, CLAIM_EVIDENCE_TICKS = 4;
    static final int[] KEY_ITEM_IDS = intColumn("loot_key_slots", 0);
    static final int[] KEY_CONTAINER_IDS = intColumn("loot_key_slots", 1);
    static final String LOOT_KEY_NOTE = msg("loot-key");
    List<PendingClaim> claims = new ArrayList<>();

    /** The chest object click, armed for a few ticks. */
    ChestOpen open;

    /** A key loss seen while armed whose contents have not settled yet. */
    ChestOpen used;

    Map<Integer, Long> localDeathKeys;
    int localDeathTicks;
    boolean lootChestVisible;
    Map<Integer, Map<Integer, Long>> observedKeyContainerContents = new HashMap<>();

    /**
     * Contents removed from each loot key's chest container, kept for a few ticks as claim
     * evidence.
     */
    Map<Integer, Removed> removedByKey = new HashMap<>();

    /** A chest opened with one key type: the key, how many were used and the chest's name. */
    @AllArgsConstructor
    static class ChestOpen {
        int keyItemId;
        long quantity;
        String chestName;

        @Getter(AccessLevel.NONE)
        int ticks;
    }

    @AllArgsConstructor
    static class Removed {
        Map<Integer, Long> contents;
        int ticks;
    }

    // -- Durable claims --
    void clear() {
        claims.clear();
    }

    /** Restores the persisted claims and drops any already settled by a retained transaction. */
    void restore(SavedState state, List<Session> sessions) {
        claims.clear();
        var settled = new HashSet<String>();
        for (var session : sessions) {
            for (var transaction : session == null ? Collections.<Transaction>emptyList()
                            : session.getTransactions()) {
                if (transaction != null && !transaction.sourceClaimId().isEmpty()) {
                    settled.add(transaction.sourceClaimId());
                }
            }
        }
        var seen = new HashSet<String>();
        for (var claim : state.getPendingClaims()) {
            if (claim != null && claim.isValid() && seen.add(claim.getClaimId())
                    && !settled.contains(claim.getClaimId())) {
                claims.add(claim);
            }
        }
        sort();
    }

    void adoptFrom(KeyClaims draft) {
        claims.clear();
        claims.addAll(draft.claims);
    }

    void writeTo(SavedState state) {
        state.setPendingClaims(new ArrayList<>(claims));
    }

    List<PendingClaim> all() {
        return unmodifiableList(claims);
    }

    /** Opens a claim keyed by the audit row's id; a second open with the same id is ignored. */
    void open(String id, int key, long quantity, long now) {
        if (empty(id) || key <= 0 || quantity <= 0 || indexOf(id) >= 0) return;
        claims.add(new PendingClaim(id, key, quantity, now));
        sort();
    }

    /**
     * Applies a settlement or death loss of {@code quantity} keys to the oldest claims for the key.
     * Returns the id of the last claim it touched when that claim closed; null otherwise.
     */
    String consume(int key, long quantity) {
        String closed = null;
        long remaining = nonNeg(quantity);
        for (int index = 0; index < claims.size() && remaining > 0; index++) {
            var claim = claims.get(index);
            if (claim.itemOrKeyId != key) continue;
            long applied = min(remaining, claim.getQuantity());
            remaining -= applied;
            closed = reduce(index, applied) ? claim.getClaimId() : null;
            index -= closed == null ? 0 : 1;
        }
        return closed;
    }

    /** Settles one specific claim by {@code quantity}; returns its id when it closed. */
    String settle(String id, long quantity) {
        int index = indexOf(id);
        boolean closed = index >= 0 && reduce(index, nonNeg(quantity));
        sort();
        return closed ? id : null;
    }

    /** Shrinks the claim at {@code index}; true when it reached zero and was removed. */
    boolean reduce(int index, long quantity) {
        var claim = claims.get(index);
        long left = claim.getQuantity() - quantity;
        if (left > 0) {
            claims.set(index, new PendingClaim(claim.getClaimId(), claim.itemOrKeyId, left,
                            claim.getCreatedAtEpochMillis()));
            return false;
        }
        claims.remove(index);
        return true;
    }

    int indexOf(String id) {
        for (int index = 0; index < claims.size(); index++) {
            if (claims.get(index).getClaimId().equals(id)) return index;
        }
        return -1;
    }

    /** Created-at then claim id: equal timestamps never reorder across save and reload. */
    void sort() {
        claims.sort(Comparator.comparingLong(PendingClaim::getCreatedAtEpochMillis)
                        .thenComparing(PendingClaim::getClaimId));
    }

    // -- Short-lived evidence --
    /** All transient evidence goes; the durable claims stay. */
    void clearEvidence() {
        cancelChest();
        localDeathKeys = null;
        localDeathTicks = 0;
        lootChestVisible = false;
        observedKeyContainerContents.clear();
        removedByKey.clear();
    }

    void tick() {
        open = open == null || --open.ticks <= 0 ? null : open;
        used = used == null || --used.ticks <= 0 ? null : used;
        if (localDeathTicks > 0 && --localDeathTicks == 0) localDeathKeys = null;
        removedByKey.values().removeIf(removed -> --removed.ticks <= 0);
    }

    /** Arms (or disarms) ordinary key-chest provenance from an actual chest object click. */
    boolean chestClick(String option, String target) {
        var entry = KeyChestCatalogue.entryForMenu(option, target);
        used = null;
        open = entry == null ? null : new ChestOpen(entry.keyItemId, 0L, entry.chestName, OPEN_WINDOW_TICKS);
        return open != null;
    }

    void cancelChest() {
        open = null;
        used = null;
    }

    /**
     * Correlate a settled key loss with settled positive inventory contents. A click alone is never
     * a claim, and ambiguous keys with multiple chest types require the observed object name.
     */
    ChestOpen matchChest(Map<Integer, Long> keyLosses, Map<Integer, Long> positiveContents,
            String sourceActivity) {
        boolean sourced = !blank(sourceActivity);
        var lost = positiveEntries(keyLosses);
        if (empty(positiveContents)) {
            if (sourced) {
                cancelChest();
            } else if (open != null && lost.size() == 1 && lost.containsKey(open.keyItemId)) {
                // The key left before its contents: remember the use for a few ticks.
                used = new ChestOpen(open.keyItemId, lost.get(open.keyItemId), open.chestName, OPEN_WINDOW_TICKS);
                open = null;
            }
            return null;
        }
        int key = -1;
        long quantity = 0;
        String name = null;
        if (!lost.isEmpty()) {
            var armed = open != null ? open : used;
            var loss = lost.entrySet().iterator().next();
            if (lost.size() != 1 || armed != null && armed.keyItemId != loss.getKey()) {
                cancelChest();
                return null;
            }
            key = loss.getKey();
            quantity = loss.getValue();
            var entries = KeyChestCatalogue.entriesForKey(key);
            name = open != null && open.keyItemId == key ? open.chestName
                            : entries.size() == 1 ? entries.get(0).chestName : null;
            used = null;
        } else if (used != null) {
            key = used.keyItemId;
            quantity = used.quantity;
            name = used.chestName;
            used = null;
        }
        if (key < 0 || quantity <= 0 || name == null) {
            // Do not let an uncorroborated gain leave an object click armed to
            // relabel a later, unrelated key loss and contents change.
            open = null;
            return null;
        }
        var sourceChest = sourced ? KeyChestCatalogue.entryForChest(sourceActivity) : null;
        if (sourced && (sourceChest == null || !name.equalsIgnoreCase(sourceChest.chestName))) {
            cancelChest();
            return null;
        }
        if (open != null && open.keyItemId == key && open.chestName != null
                && !open.chestName.equalsIgnoreCase(name)) {
            return null;
        }
        cancelChest();
        return new ChestOpen(key, quantity, name, 0);
    }

    void beginLocalDeath(Map<Integer, Long> keyInventory) {
        var keys = keyQuantities(keyInventory);
        localDeathKeys = keys.isEmpty() ? null : keys;
        localDeathTicks = localDeathKeys == null ? 0 : DEATH_WINDOW_TICKS;
    }

    boolean awaitingDeathSettle() {
        return localDeathKeys != null && localDeathTicks > 0;
    }

    void setLootChestVisible(boolean visible) {
        if (lootChestVisible && !visible) {
            // Reopening a key UI starts a fresh container-delta baseline. A
            // short, already-observed claim delta may still settle afterward.
            observedKeyContainerContents.clear();
        }
        lootChestVisible = visible;
    }

    /**
     * Observe one loot key's server-owned contents while its interface is visible. Only removals
     * from this container can correlate settled INV gains to a claim.
     */
    void observeKeyContents(int keyItemId, Map<Integer, Long> contents) {
        int index = keyIndex(keyItemId);
        if (!lootChestVisible || index < 0 || contents == null) return;
        var next = positiveEntries(contents);
        var removed = minus(observedKeyContainerContents.put(index, next), next);
        if (!removed.isEmpty()) {
            Removed evidence = removedByKey.computeIfAbsent(index, key -> new Removed(new HashMap<>(), 0));
            removed.forEach((id, quantity) -> evidence.contents.merge(id, quantity, SafeMath::safeAdd));
            evidence.ticks = CLAIM_EVIDENCE_TICKS;
        }
    }

    /** The last contents observed in one loot key's chest, or an empty map (read-only). */
    Map<Integer, Long> observedContents(int key) {
        int index = keyIndex(key);
        var contents = index < 0 ? null : observedKeyContainerContents.get(index);
        return contents == null ? emptyMap() : contents;
    }

    /**
     * Settles the one single-key loot-key claim whose chest removal (seen within the evidence
     * window) covers these settled gains. One matched manifest accounts for one physical key at
     * most, so a stacked claim shrinks one unit at a time; exhausting the evidence supplies that
     * unit even when the key-loss callback arrived separately. Returns the claim id when it closed.
     * Descriptive: never alters the settling transaction's type, valuation or flows.
     */
    String settleLootKey(Map<Integer, Long> positiveFlows, Map<Integer, Long> keyLosses) {
        if (empty(positiveFlows)) return null;
        PendingClaim match = null;
        int index = -1;
        for (var evidence : removedByKey.entrySet()) {
            PendingClaim claim = covers(evidence.getValue().contents, positiveFlows)
                            ? uniquePending(keyItemId(evidence.getKey())) : null;
            if (claim != null && claim.getQuantity() > 0) {
                if (match != null) return null;
                match = claim;
                index = evidence.getKey();
            }
        }
        if (match == null) return null;
        long consumedKeys = keyLosses == null ? 0L : keyLosses.getOrDefault(keyItemId(index), 0L);
        var evidence = removedByKey.get(index);
        evidence.contents = minus(evidence.contents, positiveFlows);
        evidence.ticks = CLAIM_EVIDENCE_TICKS;
        if (evidence.contents.isEmpty()) removedByKey.remove(index);
        return settle(match.getClaimId(), evidence.contents.isEmpty() && consumedKeys <= 0 ? 1 : consumedKeys);
    }

    /** The one unresolved claim for this key item when exactly one exists; null otherwise. */
    PendingClaim uniquePending(int key) {
        PendingClaim match = null;
        for (var claim : claims) {
            if (claim.itemOrKeyId != key) continue;
            if (match != null) return null;
            match = claim;
        }
        return match;
    }

    /**
     * Closes pending loot-key claims only for key units that disappeared from INV in a local-death
     * settlement, measured against the death snapshot.
     */
    void recordDeathLosses(List<Flow> settledFlows, Map<Integer, Long> currentKeyInventory) {
        if (!awaitingDeathSettle()) return;
        var flowLosses = lossQuantities(settledFlows, KeyClaims::isLootKey);
        var gone = minus(localDeathKeys, keyQuantities(currentKeyInventory));
        for (int key : KEY_ITEM_IDS) {
            long pendingKeys = 0;
            for (var claim : claims)
                pendingKeys += claim.itemOrKeyId == key ? claim.getQuantity() : 0;
            long matchedLoss = min(pendingKeys, min(gone.getOrDefault(key, 0L),
                                    flowLosses.getOrDefault(key, 0L)));
            if (matchedLoss > 0) {
                consume(key, matchedLoss);
                // Inventory/equipment changes can stabilize in separate batches after a death.
                // Keep the bounded snapshot until each confirmed key loss is consumed or the
                // game-tick window expires; an unrelated first batch must not make a later
                // key-loss flow look like an ordinary cost.
                take(localDeathKeys, key, matchedLoss);
            }
        }
        if (localDeathKeys.isEmpty()) {
            localDeathKeys = null;
            localDeathTicks = 0;
        }
    }

    // -- Key identity --
    static boolean isLootKey(int id) {
        return keyIndex(id) >= 0;
    }

    static int keyIndex(int id) {
        for (int index = 0; index < KEY_ITEM_IDS.length; index++) {
            if (KEY_ITEM_IDS[index] == id) return index;
        }
        return -1;
    }

    static int keyItemId(int index) {
        return index < 0 || index >= KEY_ITEM_IDS.length ? -1 : KEY_ITEM_IDS[index];
    }

    static int keyContainerId(int index) {
        return index < 0 || index >= KEY_CONTAINER_IDS.length ? -1 : KEY_CONTAINER_IDS[index];
    }

    /**
     * Positive item quantities in a container, skipping items {@code skip} rejects (nested crates).
     */
    static Map<Integer, Long> manifestFromContainer(ItemContainer container, IntPredicate skip) {
        // Refuse an incomplete evidence policy rather than accidentally valuing a
        // nested crate as if its unopened contents were already manifest items.
        var manifest = new LinkedHashMap<Integer, Long>();
        if (container == null || container.getItems() == null || skip == null) return emptyMap();
        for (Item item : container.getItems()) {
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0 && !skip.test(item.getId())) {
                manifest.merge(item.getId(), (long) item.getQuantity(), SafeMath::safeAdd);
            }
        }
        return unmodifiableMap(manifest);
    }

    static Map<Integer, Long> keyQuantities(ItemContainer inventory) {
        return manifestFromContainer(inventory, id -> !isLootKey(id));
    }

    static Map<Integer, Long> keyQuantities(Map<Integer, Long> counts) {
        var keys = positiveEntries(counts);
        keys.keySet().removeIf(id -> !isLootKey(id));
        return keys;
    }
}

/**
 * While a clue/casket/key path is active, digs / teleports / key spends attach as costs against
 * that clue's Pending Rewards → Claimed batch. Unmatched supply spends stay normal Used.
 */
class ClueCostPairing {
    String activeClueEncounterId, activeClueLabel;

    synchronized void beginClue(String encounter, String label) {
        activeClueEncounterId = emptyToNull(encounter);
        activeClueLabel = emptyToNull(label);
    }

    synchronized void endClue() {
        activeClueEncounterId = null;
        activeClueLabel = null;
    }

    synchronized String activeEncounter() {
        return activeClueEncounterId;
    }

    /**
     * Classify a spend note while a clue path may be open. Dig / teleport / clue key → CLUE_COST;
     * food / potions → UNRELATED_USED.
     */
    synchronized boolean isClueCost(String noteOrOption) {
        String lower = norm(noteOrOption);
        return activeClueEncounterId != null && lower != null && looksLikeClueCost(lower);
    }

    static boolean looksLikeClueCost(String note) {
        if (empty(note)) return false;
        return note.contains("dig") || note.contains("teleport") || note.contains("tele ")
                || note.contains("clue") || note.contains("casket") || note.contains("spade")
                || note.contains("key") && (has(note, "clue", "chest"));
    }

    static boolean looksLikeClueActivity(String name) {
        String lower = norm(name);
        return lower != null && (has(lower, "clue", "casket", "treasure trail"));
    }

    synchronized String costNote() {
        String label = activeClueLabel == null ? "clue" : activeClueLabel;
        return "Clue cost paired to " + label;
    }

    synchronized void clear() {
        endClue();
    }

    static String emptyToNull(String value) {
        if (blank(value)) return null;
        return value.trim();
    }
}

/**
 * Wiki-sourced key/chest pairs used for deferred-claim valuation and provenance. Chest names and
 * locations follow the OSRS Wiki pages for the <a
 * href="https://oldschool.runescape.wiki/w/Crystal_chest">Crystal chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Elf_crystal_chest">Elven Crystal Chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_small_chest">Larran's small chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_big_chest">Larran's big chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Ogre_Coffin">Ogre coffin</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Sinister_chest">Sinister chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Grubby_chest">Grubby chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Brimstone_chest">Brimstone chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Moons_rewards">Moon chest</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Category%3AShades_of_Mort%27ton_%28minigame%29">Shades
 * of Mort'ton chest/key variants</a> (for example, <a
 * href="https://oldschool.runescape.wiki/w/Steel_Chest_%28brown%29">Steel Chest (brown)</a>). Key
 * names/tradeability follow the item pages for <a
 * href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Enhanced_crystal_key">Enhanced crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_key">Larran's key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Ogre_coffin_key">Ogre coffin key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_key">Muddy key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Sinister_key">Sinister key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Grubby_key">Grubby key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Brimstone_key">Brimstone key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Moon_key">Moon key</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Shade_key">Shade key variants</a> and the <a
 * href="https://oldschool.runescape.wiki/w/Bronze_key_crimson">Bronze key crimson</a>, <a
 * href="https://oldschool.runescape.wiki/w/Steel_key_brown">Steel key brown</a>, <a
 * href="https://oldschool.runescape.wiki/w/Black_key_black">Black key black</a>, <a
 * href="https://oldschool.runescape.wiki/w/Silver_key_red">Silver key red</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Gold_key_purple">Gold key purple</a> pages. Tradeable
 * keys keep their market opportunity cost. Untradeable keys represent an unclaimed reward and are
 * deliberately held at zero until contents enter inventory. The Wiki explicitly documents
 * consumption for Crystal, Muddy, Sinister and Grubby keys (<a
 * href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_sinister_chests">Sinister
 * chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_grubby_chests">Grubby
 * chest</a>). The Enhanced crystal, Larran, Ogre coffin, Brimstone, Moon and Shade pages describe
 * using their keys but do not explicitly state that chest use consumes them. Every claim match
 * therefore still requires a measured inventory key loss; a click only disambiguates a chest, and
 * live acceptance must confirm consumption for those families.
 */
class KeyChestCatalogue {
    static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        for (String[] row : rows("key_chests")) {
            ENTRIES.add(new Entry(Integer.parseInt(row[0]), row[1], Boolean.parseBoolean(row[2])));
        }
    }

    /** Catalogue entries for a key item; Larran's key has both chest variants. */
    static List<Entry> entriesForKey(int key) {
        return matching(ENTRIES, entry -> entry.keyItemId == key);
    }

    /** First known chest association for an item, or {@code null} when not catalogued. */
    static Entry entryForKey(int key) {
        var entries = entriesForKey(key);
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** Match an observed object target that contains one exact catalogue chest label. */
    static Entry entryForChest(String target) {
        if (blank(target)) return null;
        String normalizedTarget = normalize(target);
        Entry best = null;
        for (Entry entry : ENTRIES) {
            String name = normalize(entry.chestName);
            if (normalizedTarget.contains(name)
                    && (best == null || name.length() > normalize(best.chestName).length())) {
                best = entry;
            }
        }
        return best;
    }

    /** Arm a short-lived chest label for measured key-loss disambiguation only. */
    static Entry entryForMenu(String option, String target) {
        String action = option == null ? "" : normalize(option);
        if (!(action.equals("open") || action.startsWith("open ") || action.equals("unlock")
                || action.startsWith("unlock ") || action.equals("search") || action.startsWith("search "))) {
            return null;
        }
        return entryForChest(target);
    }

    static boolean isKey(int key) {
        return entryForKey(key) != null;
    }

    /** Deferred claims have no held value and are excluded from high-alchemy fallback. */
    static boolean isDeferredKey(int key) {
        Entry entry = entryForKey(key);
        return entry != null && !entry.tradeable;
    }

    static List<Entry> entries() {
        return unmodifiableList(ENTRIES);
    }

    static String normalize(String value) {
        return value.trim().replace('\u2019', '\'').toLowerCase(ROOT);
    }

    /** Immutable key-to-chest information for accounting and presentation. */
    @AllArgsConstructor
    static class Entry {
        @Getter int keyItemId;
        @Getter String chestName;
        @Getter boolean tradeable;
    }
}

/** Untradeable unopened claims held at zero: their contents become income only on receipt. */
class DeferredClaims {
    /** A key/chest claim or a pouch. */
    static boolean of(int id) {
        return KeyChestCatalogue.isDeferredKey(id) || pouch(id);
    }

    /** Coin pouches and the Guardians of the Rift intricate pouch: contents book when opened. */
    static boolean pouch(int id) {
        return id >= ItemID.PICKPOCKET_COIN_POUCH_CITIZEN && id <= ItemID.PICKPOCKET_COIN_POUCH_ELF
                || id == ItemID.PICKPOCKET_COIN_POUCH_VYRE
                || id == ItemID.PICKPOCKET_COIN_POUCH_VARLAMORE_WEALTHY
                || id == ItemID.PICKPOCKET_COIN_POUCH_PIRATE || id == ItemID.GOTR_INTRICATE_POUCH;
    }
}

/**
 * Reward-interface / chest adapters → Pending Rewards → Claimed once. Name matching for Loot
 * Tracker EVENT + live reward UIs.
 */
class RewardChestCatalogue {
    /** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */

    /** Reward fragments share the same once-compiled matcher as other evidence tables. */
    static final Map<String, Clause[][]> RULES = rules("reward_chests");

    static boolean isRewardChest(String raw) {
        if (blank(raw)) return false;
        if (KeyChestCatalogue.entryForChest(raw) != null) return true;
        String name = lower(raw);
        return RULES.values().stream().anyMatch(c -> has(name, c));
    }
}

/** Polls the real loot-key chest interface; menu and chat wording is never key source evidence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootKeyManifestReader {
    final Client client;
    final ItemLookup lookup;
    final Engine engine;

    void poll() {
        if (client == null || engine == null) return;
        boolean visible = Widgets.visible(client, InterfaceID.WildyLootChest.UNIVERSE);
        engine.setChestVisible(visible);
        if (!visible) return;
        for (int index = 0; index < 5; index++) {
            int key = KeyClaims.keyItemId(index);
            var keyContents = client.getItemContainer(KeyClaims.keyContainerId(index));
            if (keyContents == null) continue;
            var rawManifest = KeyClaims.manifestFromContainer(keyContents, this::isNestedContainerItem);
            var manifest = new HashMap<Integer, Long>();
            for (var item : rawManifest.entrySet()) {
                int canonical = lookup == null ? item.getKey() : lookup.canon(item.getKey());
                long quantity = item.getValue();
                if (canonical <= 0 || quantity <= 0) continue;
                manifest.merge(canonical, quantity, LootKeyManifestReader::safeAddPositive);
            }
            // An open chest is evidence only; settled inventory gains carry the financial truth.
            engine.observeKeyContainer(key, manifest);
        }
    }

    boolean isNestedContainerItem(int id) {
        String name = itemName(id);
        return name == null || has(name, "crate", "casket");
    }

    String itemName(int id) {
        if (lookup == null) return null;
        try {
            var composition = lookup.composition(id);
            return composition == null || composition.getName() == null || composition.getName().trim().isEmpty() ? null
                    : composition.getName().toLowerCase(ROOT);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static long safeAddPositive(long left, long right) {
        try {
            return addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
