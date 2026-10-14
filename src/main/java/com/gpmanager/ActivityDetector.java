package com.gpmanager;

import static com.gpmanager.Phrases.*;

import lombok.RequiredArgsConstructor;

import javax.inject.*;

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
