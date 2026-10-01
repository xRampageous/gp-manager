package com.gpmanager;
import lombok.RequiredArgsConstructor;
import javax.inject.*;
import static com.gpmanager.ModelText.*;
/**
* Client-thread activity naming: a two-hit candidate that holds until something else is seen, reseeded from the
* active session on owner change. Accounting never reads the title; the engine only receives
* {@link Engine#setDetectedActivity} (session activity hint / auto-start) and the
* PRODUCTION context window that lets a transform skill's XP confirm a processing receipt.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ActivityDetector {
static final long MILLIS_PER_GAME_TICK = 600L;
final GpManagerConfig config;
final Engine engine;
String activityCandidate = "General";
int activityEvidence;
String detectedActivity = "General";
String activitySessionId;
long productionWindowExpiresAtEpochMillis;
/** How long a Make-X run stays open for its skill's XP after the last production evidence. */
long productionRunUntilEpochMillis;
void observeActivity(String activity, boolean immediate) {
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
engine.setDetectedActivity(next, System.currentTimeMillis());
}
}

/** Settled transaction types that name the activity (PK, named loot, trade). */
void observeTransactionActivity(Transaction transaction) {
if (transaction == null) return;
switch (transaction.getType()) {
case PK_LOOT:
case PK_SUPPLY_COST:
case PK_DEATH_LOSS:
case PK_FEE:
observeActivity("PKing", true);
break;
case LOOT:
String lootActivity = transaction.getActivityName();
if (lootActivity != null && !lootActivity.trim().isEmpty() && !"General".equalsIgnoreCase(lootActivity.trim())) {
observeActivity(lootActivity.trim(), true);
}
break;
case TRADE:
observeActivity("Trading", true);
break;
default:
break;
}
}

/** Active-owner change reseeds the candidate from the session hint. */
void syncActivitySession() {
Session active = engine.getActiveSession();
String nextSessionId = active == null ? null : active.getId();
if (nextSessionId == null ? activitySessionId == null : nextSessionId.equals(activitySessionId)) return;
activitySessionId = nextSessionId;
String initial = active == null ? "General" : active.getActivityHint();
detectedActivity = nonBlank(initial, "General");
activityCandidate = detectedActivity;
activityEvidence = 0;
}

/** Local death drops the detected activity so the killer cannot title the next session. */
void clearOnLocalDeath() {
resetToGeneral(true);
}

void resetToGeneral(boolean resetHold) {
if (!"General".equalsIgnoreCase(detectedActivity) || resetHold) {
detectedActivity = "General";
activityCandidate = "General";
activityEvidence = 0;
if (config.autoActivityDetection()) engine.setDetectedActivity("General", System.currentTimeMillis());
}
}

/**
* Positive XP for {@code skill}: names the activity (Agility prefers the course) and, inside
* an open PRODUCTION window, re-arms the context so the processing receipt can settle.
*/
void observeSkillXp(String skillName, String agilityCourse, long now) {
if ("Agility".equalsIgnoreCase(skillName) && agilityCourse != null) observeActivity(agilityCourse, true);
else if (!"Prayer".equalsIgnoreCase(skillName)) observeActivity(skillName.trim(), false);
// A Make-X run has actions with no XP (a failed iron smelt), so a production skill's XP
// re-arms the context for 30 s after the last one, not only inside the six-tick window.
if (now <= productionWindowExpiresAtEpochMillis
|| ActionSignals.TRANSFORM.containsValue(skillName) || "Smithing".equals(skillName)) {
continueProductionRun(skillName, now);
}
}

/** Evidence the Make-X run goes on (its XP, a failed smelt): re-arms it within 30 s of the last. */
void continueProductionRun(String note, long now) {
if (now <= productionRunUntilEpochMillis) armProductionContext(note, now);
}

/** Station/menu hint: arm PRODUCTION context for the correlation window. */
void armProductionContext(String skillNote) {
armProductionContext(skillNote, System.currentTimeMillis());
}

void armProductionContext(String skillNote, long now) {
int ticks = 6;
String note = nonBlank(skillNote, "Production");
engine.markContext(Context.PRODUCTION, ticks, note);
productionWindowExpiresAtEpochMillis = now + ticks * MILLIS_PER_GAME_TICK;
productionRunUntilEpochMillis = now + 30_000L;
}
}
