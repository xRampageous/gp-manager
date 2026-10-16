package com.gpmanager;

import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.*;
import static java.lang.Math.*;

import java.util.*;
import java.util.function.*;
import javax.inject.*;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.Client;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * Client-thread activity naming: a two-hit candidate that holds until something else is seen,
 * reseeded from the active session on owner change. Accounting never reads the title; the engine
 * only receives {@link Engine#setDetectedActivity} (session activity hint / auto-start) and the
 * PRODUCTION context window that lets a transform skill's XP confirm a processing receipt.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ActivityDetector {
    static final long MILLIS_PER_GAME_TICK = 600L;
    final GpManagerConfig config;
    final Engine engine;

    /** The activity with nothing more specific detected. */
    static final String GENERAL = "General";

    String activityCandidate = GENERAL;
    int activityEvidence;
    String detectedActivity = GENERAL;
    String activitySessionId;
    long productionWindowExpiresAtEpochMillis;

    /**
     * The skill a gather click asked for ("Chop down": Woodcutting). A click proves nothing (no
     * axe, still walking), so the skill's first XP names it at once instead (owner 1.1).
     */
    String gatherSkill = "";

    /** How long a Make-X run stays open for its skill's XP after the last production evidence. */
    long productionRunUntilEpochMillis;

    void observe(String activity, boolean immediate) {
        if (!config.autoActivityDetection() || blank(activity)) return;
        String next = activity.trim();
        if ("NPC loot".equalsIgnoreCase(next)) {
            // A generic fallback is not an activity; the previous real label stands.
            return;
        }
        if (next.equalsIgnoreCase(activityCandidate)) {
            activityEvidence++;
        } else {
            activityCandidate = next;
            activityEvidence = 1;
        }
        if (immediate || activityEvidence >= 2) {
            detectedActivity = next;
            engine.setDetectedActivity(next, SafeMath.now());
        }
    }

    /** Settled transaction types that name the activity (PK, named loot, trade). */
    void observeTransaction(Transaction transaction) {
        if (transaction == null) return;
        switch (transaction.getType()) {
            case PK_LOOT:
            case PK_SUPPLY_COST:
            case PK_DEATH_LOSS:
                observe("PKing", true);
                break;
            case LOOT:
                String lootActivity = transaction.getActivityName();
                if (!blank(lootActivity)
                        && !GENERAL.equalsIgnoreCase(lootActivity.trim())) {
                    observe(lootActivity.trim(), true);
                }
                break;
            case TRADE:
                observe("Trading", true);
                break;
            default:
                break;
        }
    }

    /** Active-owner change reseeds the candidate from the session hint. */
    void syncSession() {
        Session active = engine.getActiveSession();
        String nextSessionId = active == null ? null : active.getId();
        if (nextSessionId == null
                ? activitySessionId == null
                : nextSessionId.equals(activitySessionId)) return;
        activitySessionId = nextSessionId;
        String initial = active == null ? GENERAL : active.getActivityHint();
        detectedActivity = nonBlank(initial, GENERAL);
        activityCandidate = detectedActivity;
        activityEvidence = 0;
    }

    /** Local death drops the detected activity so the killer cannot title the next session. */
    void clearOnDeath() {
        resetToGeneral(true);
    }

    void resetToGeneral(boolean resetHold) {
        if (!GENERAL.equalsIgnoreCase(detectedActivity) || resetHold) {
            detectedActivity = GENERAL;
            activityCandidate = GENERAL;
            activityEvidence = 0;
            if (config.autoActivityDetection()) engine.setDetectedActivity(GENERAL, SafeMath.now());
        }
    }

    /**
     * Positive XP for {@code skill}: names the activity (Agility prefers the course) and, inside an
     * open PRODUCTION window, re-arms the context so the processing receipt can settle.
     */
    void observeXp(String skillName, String agilityCourse, boolean fighting, long now) {
        if ("Agility".equalsIgnoreCase(skillName) && agilityCourse != null)
            observe(agilityCourse, true);
        // Combat XP, and Magic in a fight, names nothing: the NPC's loot does. Alching is Magic.
        else if (!"Prayer".equalsIgnoreCase(skillName)
                && !ActivityLabel.COMBAT.contains(skillName)
                && !(fighting && "Magic".equals(skillName)))
            observe(skillName.trim(), skillName.equalsIgnoreCase(gatherSkill));
        // A Make-X run has actions with no XP (a failed iron smelt), so a production skill's XP
        // re-arms the context for 30 s after the last one, not only inside the six-tick window.
        if (now <= productionWindowExpiresAtEpochMillis
                || ActionSignals.TRANSFORM.containsValue(skillName)
                || "Smithing".equals(skillName)) {
            continueProduction(skillName, now);
        }
    }

    /**
     * Evidence the Make-X run goes on (its XP, a failed smelt): re-arms it within 30 s of the last.
     */
    void continueProduction(String note, long now) {
        if (now <= productionRunUntilEpochMillis) armProduction(note, now);
    }

    /** Enabling during Make-X needs fresh production XP, without a click made while disabled. */
    void clearProduction(long now) {
        productionWindowExpiresAtEpochMillis = 0L;
        productionRunUntilEpochMillis = now + 30_000L;
        gatherSkill = "";
    }

    /** Station/menu hint: arm PRODUCTION context for the correlation window. */
    void armProduction(String skillNote) {
        armProduction(skillNote, SafeMath.now());
    }

    void armProduction(String skillNote, long now) {
        int ticks = 6;
        String note = nonBlank(skillNote, "Production");
        engine.markContext(Context.PRODUCTION, ticks, note);
        productionWindowExpiresAtEpochMillis = now + ticks * MILLIS_PER_GAME_TICK;
        productionRunUntilEpochMillis = now + 30_000L;
    }
}

/**
 * Activity naming facts the engine still reads: combat XP never names an activity (the NPC's loot
 * does), and the rules table stays the single source for that skill list. The activity display was
 * retired (owner live 2026-10-13): the Live hero shows the run's own name and nothing else, so the
 * presentation ladder that used to live here is gone. Presentation only: it never books or values.
 */
class ActivityLabel {
    /** XP in these is fighting, not an activity of its own. */
    static final List<String> COMBAT = list("combat");

    /** The compiled activity rules; the table remains the only source for these rows. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("activity_rules");

    /** Original list fields retain their exact values; the table is their only source. */
    static List<String> list(String name) {
        for (String[] row : GameData.cachedRows("activity_rules"))
            if (row[0].equals(name))
                return Arrays.stream(row[2].split("\\|"))
                        .map(GameData::text)
                        .collect(java.util.stream.Collectors.toList());
        throw new IllegalArgumentException(name);
    }
}

/**
 * Agility courses by map region, so Agility XP earns a named activity ("Wilderness Agility Course")
 * instead of the bare skill. Presentation / activity naming only — accounting never reads this.
 * Region ids follow the wiki course maps (and RuneLite's agility plugin).
 */
class AgilityCourses {
    static final Map<Integer, String> BY_REGION = GameData.byId("agility_courses");

    /** Course name for a region, or null when the region is not a known course. */
    static String courseName(int regionId) {
        return BY_REGION.get(regionId);
    }

    /** The Wilderness course's reward dispenser and the Brimhaven ticket dispenser. */

    /** Name-only dispenser evidence, independent of the region, as before. */
    static final java.util.regex.Pattern DISPENSER = GameData.pattern("dispenser");

    static boolean isDispenser(String name) {
        return name != null && DISPENSER.matcher(Phrases.lower(name)).matches();
    }
}

/**
 * Client-thread interaction target for the active owner: the NPC being fought or interacted with,
 * with a short release grace so a swing gap does not blank it. Scenery and opponent names never
 * publish — the activity header titles NPCs only. The backend uses it for one fact — whether a
 * death happened in positive PvM context — and publishes the name to presentation through a
 * presentation callback.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class InteractionContextTracker {
    static final long RELEASE_GRACE_MILLIS = 4_000L;
    final Client client;
    final Engine engine;
    final GpManagerConfig config;
    String owner = "";
    String name = "";
    boolean combatNpc;
    long releasedAt;
    long lastTargetAt;
    BooleanSupplier ingestion = () -> false;
    BiConsumer<String, Boolean> presentation = (name, combat) -> {};

    void bindPresentation(BooleanSupplier ready, BiConsumer<String, Boolean> changed) {
        ingestion = ready == null ? () -> false : ready;
        presentation = changed == null ? (name, combat) -> {} : changed;
    }

    void clear() {
        owner = "";
        lastTargetAt = 0L;
        set("", false);
    }

    /** True while the active owner's target positively identifies PvM (a combat NPC). */
    boolean hasPvmContext() {
        return ready()
                && combatNpc
                && (releasedAt == 0L || SafeMath.now() - releasedAt < RELEASE_GRACE_MILLIS);
    }

    boolean ready() {
        Session session = engine.getActiveSession();
        String nextOwner = session == null ? "" : session.getId();
        if (!nextOwner.equals(owner)) {
            clear();
            owner = nextOwner;
        }
        if (nextOwner.isEmpty() || !config.autoActivityDetection() || !ingestion.getAsBoolean()) {
            set("", false);
            return false;
        }
        return true;
    }

    void onInteractingChanged(InteractingChanged event) {
        if (!ready() || event == null || event.getSource() != client.getLocalPlayer()) return;
        Player local = client.getLocalPlayer();
        if (local == null || local.isDead()) {
            set("", false);
            return;
        }
        Actor target = event.getTarget();
        if (target instanceof NPC) {
            NPC npc = (NPC) target;
            set(npc.getName(), npc.getCombatLevel() > 0);
        } else if (target instanceof Player) {
            // Opponent names are never display evidence; PvP reads as its activity.
            set("", false);
        } else {
            releasedAt = SafeMath.now();
        }
    }

    /** Scenery is never an activity; any other intentional action cancels a non-combat target. */
    void onMenuOptionClicked(MenuOptionClicked event) {
        if (!ready() || event == null || event.isConsumed() || event.getMenuAction() == null)
            return;
        String type = event.getMenuAction().name();
        MenuEntry menuEntry = event.getMenuEntry();
        String clicked =
                menuEntry == null || menuEntry.getTarget() == null
                        ? ""
                        : menuEntry.getTarget().trim();
        if (combatNpc && !clicked.isEmpty() && clicked.equalsIgnoreCase(name)) {
            // Still attacking the same target: keep the activity label alive.
            lastTargetAt = SafeMath.now();
        }
        if (!(type.startsWith("GAME_OBJECT_") && type.endsWith("_OPTION")) && !combatNpc)
            set("", false);
    }

    /** Release grace expiry for non-combat targets; a combat target lives out the label timeout. */
    void onGameTick() {
        long now = SafeMath.now();
        if (releasedAt != 0L && now - releasedAt >= RELEASE_GRACE_MILLIS && !combatNpc) {
            set("", false);
            return;
        }
        if (combatNpc
                && name != null
                && !name.isEmpty()
                && lastTargetAt != 0L
                && now - lastTargetAt >= Math.max(1_000L, config.activityLabelSeconds() * 1_000L)) {
            set("", false);
        }
    }

    void set(String next, boolean combat) {
        String n = next == null ? "" : next.trim();
        releasedAt = 0L;
        if (!n.isEmpty()) lastTargetAt = SafeMath.now();
        if (n.equals(name) && combat == combatNpc) return;
        name = n;
        combatNpc = combat;
        presentation.accept(n, combat);
    }
}

/**
 * Presentation-only character idle (Idle Notifier–shaped). Never pauses accounting or changes GP/hr
 * — session AFK pause stays separate.
 *
 * <p>Busy when animating, interacting, or recent skilling XP (Make-X / RC gaps). Idle badge after
 * hard-busy clears for {@link #delayMillis} (fixed at 5 s, SIDEBAR_SPEC.md §6b), or {@link
 * #delayMillis} after the last skilling XP when XP was the only soft-busy (no double-wait).
 */
@Singleton
class CharacterIdleModel {
    long delayMillis = 5_000L;
    long idleCandidateSinceEpochMillis;
    long lastSkillingXpEpochMillis;
    boolean characterIdle;

    /** Soft-busy for Make-X / process gaps — presentation only. */
    synchronized void noteSkillingXp(long now) {
        lastSkillingXpEpochMillis = SafeMath.nonNeg(now);
        characterIdle = false;
        idleCandidateSinceEpochMillis = 0L;
    }

    /**
     * @param animating true when local animation != -1
     * @param interacting true when local player has an interact target
     * @return true once when character idle newly becomes true
     */
    synchronized boolean tick(boolean animating, boolean interacting, long now) {
        boolean recentXp =
                lastSkillingXpEpochMillis > 0L && now - lastSkillingXpEpochMillis < delayMillis;
        boolean hardBusy = animating || interacting;
        if (hardBusy || recentXp) {
            if (hardBusy) {
                // Animation / interact: full Idle delay after clear.
                idleCandidateSinceEpochMillis = 0L;
            } else {
                // XP soft-busy alone: Idle fires delayMillis after the XP drop —
                // do not stack a second full delay when the soft window ends.
                idleCandidateSinceEpochMillis = lastSkillingXpEpochMillis;
            }
            characterIdle = false;
            return false;
        }
        if (idleCandidateSinceEpochMillis <= 0L) idleCandidateSinceEpochMillis = now;
        if (now - idleCandidateSinceEpochMillis < delayMillis) {
            characterIdle = false;
            return false;
        }
        if (!characterIdle) {
            characterIdle = true;
            return true;
        }
        return false;
    }

    /** Logout / hop — reset like Idle Notifier timers. */
    synchronized void clear() {
        idleCandidateSinceEpochMillis = 0L;
        lastSkillingXpEpochMillis = 0L;
        characterIdle = false;
    }
}

/**
 * Client-thread adapter for character idle (no animation, no target for the idle delay).
 * Presentation-only: it never pauses the engine or touches GP/hr; session idle pause is the
 * engine's own idle timeout.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CharacterIdleTracker {
    final Client client;
    final CharacterIdleModel model;

    void clear() {
        model.clear();
    }

    /** Soft-busy across Make-X / process XP gaps. */
    void noteSkillingXp() {
        model.noteSkillingXp(SafeMath.now());
    }

    /**
     * @return true once when the character newly becomes idle
     */
    boolean onGameTick() {
        Player local = client.getLocalPlayer();
        boolean animating = local != null && local.getAnimation() != -1;
        boolean interacting = local != null && local.getInteracting() != null;
        return model.tick(animating, interacting, SafeMath.now());
    }
}

/**
 * Samples the local player's PvP context (NONE / SAFE / DANGEROUS) on the game tick and on demand
 * for death/loot disposition. Context only; it never books item flows.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PvpContextReader {
    final Client client;
    PvpContext context = PvpContext.NONE;

    /**
     * The context sampled on the latest tick (or by {@link #sample}); presentation and disposition
     * input only.
     */
    PvpContext context() {
        return context;
    }

    /** Re-reads the client signals now (death/loot events may precede the next tick sample). */
    PvpContext sample(Player localPlayer) {
        int regionId =
                localPlayer == null || localPlayer.getWorldLocation() == null
                        ? 0
                        : localPlayer.getWorldLocation().getRegionID();
        context =
                PvpContext.classify(
                        client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1,
                        client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1,
                        client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) == 1,
                        client.getVarbitValue(VarbitID.THIS_IS_A_PVP_ARENA_WORLD) == 1,
                        regionId);
        return context;
    }

    /** Game tick: re-classify the PvP context; disposition input only. */
    void onGameTick(Player localPlayer) {
        sample(localPlayer);
    }
}

/**
 * Bookkeeping-only PvP context. Detection never creates, renames or splits a session and never
 * becomes location analytics.
 *
 * <p>Precedence is fail-closed: a positively identified safe/minigame arena wins; otherwise only
 * confident item-risk evidence (inside the Wilderness, or attackable on a PvP/BH world) is
 * dangerous; anything conflicting or insufficient is {@link #NONE}. A manual session name such as
 * "PK Trip" is never an input.
 */
enum PvpContext {
    NONE,
    SAFE_PVP_OR_MINIGAME,
    DANGEROUS_PVP;

    /** Safe PvP arenas beyond LMS: Castle Wars, Soul Wars, TzHaar Fight Pit. */
    static final int[] SAFE_ARENA_REGIONS = {9520, 8237, 8493, 8749, 9552};

    /**
     * @param insideWilderness {@code INSIDE_WILDERNESS} varbit
     * @param pvpSpecOrb {@code PVP_SPEC_ORB} varbit — shown only where the player is attackable
     * @param pvpOrBhWorld {@code THIS_IS_A_PVP_OR_BH_WORLD} varbit
     * @param pvpArenaWorld {@code THIS_IS_A_PVP_ARENA_WORLD} varbit (safe PvP Arena)
     * @param regionId current region, or {@code <= 0} when unknown
     */
    static PvpContext classify(
            boolean insideWilderness,
            boolean pvpSpecOrb,
            boolean pvpOrBhWorld,
            boolean pvpArenaWorld,
            int regionId) {
        if (pvpArenaWorld || isSafeArenaRegion(regionId)) return SAFE_PVP_OR_MINIGAME;
        if (insideWilderness || (pvpOrBhWorld && pvpSpecOrb)) return DANGEROUS_PVP;
        return NONE;
    }

    static boolean isSafeArenaRegion(int regionId) {
        if (MinigameRegionHints.isLmsRegion(regionId)) return true;
        if (regionId > 0) {
            for (int region : SAFE_ARENA_REGIONS) {
                if (region == regionId) return true;
            }
        }
        return false;
    }

    boolean isDangerous() {
        return this == DANGEROUS_PVP;
    }

    boolean isSafe() {
        return this == SAFE_PVP_OR_MINIGAME;
    }
}

/**
 * One Grind's PvP record: kills, deaths, streaks and the best kill. A kill's money is the net of
 * the receipts carrying its id (loot less the supplies attached to it). While those receipts are
 * live the best kill is read from them, so a correction or undo shows at once.
 */
class PkTally {
    static final String KILL = "kill:";
    static final String DEATH = "death:";

    int kills;
    int deaths;

    /** Kills since the last death, or minus the deaths since the last kill. */
    int streak;

    int bestStreak;

    /** Best net of a kill whose receipts have all been compacted. */
    long bestKill;

    /** Net already compacted for a kill that still has live receipts. */
    Map<String, Long> compactedKillNet = new HashMap<>();

    /** Counts one kill or death and returns the id its receipts carry. */
    String record(boolean kill) {
        if (kill) {
            kills++;
            streak = max(streak, 0) + 1;
            bestStreak = max(bestStreak, streak);
        } else {
            deaths++;
            streak = min(streak, 0) - 1;
        }
        return (kill ? KILL : DEATH) + UUID.randomUUID();
    }

    int encounters() {
        return kills + deaths;
    }

    static boolean isEncounter(String encounterId) {
        return encounterId.startsWith(KILL) || encounterId.startsWith(DEATH);
    }

    /**
     * A kill receipt left the ledger: keep its net, and fold the kill into the best once none of
     * its receipts remain live.
     */
    void compact(Transaction receipt, List<Transaction> live) {
        String id = receipt.getEncounterId();
        if (!id.startsWith(KILL)) return;
        long net = compactedKillNet.merge(id, net(receipt), SafeMath::safeAdd);
        for (Transaction transaction : live) {
            if (id.equals(transaction.getEncounterId())) return;
        }
        compactedKillNet.remove(id);
        bestKill = max(bestKill, net);
    }

    long bestKill(List<Transaction> live) {
        var nets = new HashMap<>(compactedKillNet);
        for (Transaction transaction : live) {
            String id = transaction.getEncounterId();
            if (id.startsWith(KILL)) nets.merge(id, net(transaction), SafeMath::safeAdd);
        }
        long best = bestKill;
        for (long net : nets.values()) best = max(best, net);
        return nonNeg(best);
    }

    static long net(Transaction transaction) {
        Accounting.Amounts amounts = Accounting.transaction(transaction);
        return amounts.available && amounts.included ? amounts.getNet() : 0L;
    }
}

/**
 * Interface-visibility reads on the client thread. The client retains bank contents after the
 * interface closes, so only widget visibility is evidence of an open UI; cached ownership data must
 * never suppress later gathering or supply consumption.
 */
class Widgets {
    /** Bank main or deposit-box UI visible — drives Banking header and Withdrew buffer. */
    static boolean isBankOpen(Client client) {
        return visible(client, InterfaceID.Bankmain.ITEMS_CONTAINER)
                || visible(client, InterfaceID.BankDepositbox.INVENTORY)
                || visible(client, InterfaceID.BankDepositbox.CONTENTS);
    }

    /** Tool Leprechaun store (either panel) visible; its withdrawals and deposits are storage. */
    static boolean isToolStoreOpen(Client client) {
        return visible(client, InterfaceID.FarmingTools.FRAME)
                || visible(client, InterfaceID.FarmingToolsSide.UNIVERSE);
    }

    /**
     * Stores and exchanges whose own open window proves a move neutral (d29): its note, else null.
     */
    static final java.util.List<String[]> STORES = GameData.rows("store_windows");

    /** Shop windows whose own open UI turns a coin movement into a trade (owner 1.3.4). */
    static final java.util.List<String[]> SHOPS = GameData.rows("shop_windows");

    static String store(Client client) {
        for (String[] row : STORES) if (visible(client, Integer.parseInt(row[0]))) return row[2];
        return null;
    }

    /** A shop window (classic or omni) visible: purchases and sales are traded, not Review. */
    static boolean isShopOpen(Client client) {
        for (String[] row : SHOPS) if (visible(client, Integer.parseInt(row[0]))) return true;
        return false;
    }

    static boolean isGeOpen(Client client) {
        // A banker's or clerk's collection box collects offers like the exchange itself (1.2).
        return visible(client, InterfaceID.GeOffers.UNIVERSE)
                || visible(client, InterfaceID.GeOffersSide.ITEMS)
                || visible(client, InterfaceID.GeCollect.UNIVERSE);
    }

    /** A visible widget's text, "" when it is absent or hidden. */
    static String text(Client client, int componentId) {
        Widget widget = client.getWidget(componentId);
        return widget == null || widget.isHidden() ? "" : String.valueOf(widget.getText());
    }

    static boolean visible(Client client, int componentId) {
        try {
            Widget widget = client == null ? null : client.getWidget(componentId);
            return widget != null && !widget.isHidden();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
