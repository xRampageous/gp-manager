package com.gpmanager;
import lombok.RequiredArgsConstructor;
import javax.inject.*;
import static com.gpmanager.Ag.*;
/**
* Client-thread activity naming: a two-hit candidate that holds until something else is seen, reseeded from the
* active session on owner change. Accounting never reads the title; the engine only receives
* {@link Am#setDetectedActivity} (session activity hint / auto-start) and the
* PRODUCTION context window that lets a transform skill's XP confirm a processing receipt.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ActivityDetector {
static final long MILLIS_PER_GAME_TICK = 600L;
final GpManagerConfig config;
final Am engine;
/** The activity with nothing more specific detected. */
static final String G = "General";
String activityCandidate = G;
int activityEvidence;
String detectedActivity = G;
String activitySessionId;
long productionWindowExpiresAtEpochMillis;
/**
* The skill a gather click asked for ("Chop down": Woodcutting). A click proves nothing (no axe,
* still walking), so the skill's first XP names it at once instead (owner 1.1).
*/
String gatherSkill = "";
/** How long a Make-X run stays open for its skill's XP after the last production evidence. */
long productionRunUntilEpochMillis;
void abm(String /* activity */a, boolean /* immediate */b) {
 if (!config.autoActivityDetection() || blank(a)) return;
 String /* next */c = a.trim();
 if ("NPC loot".equalsIgnoreCase(c)) {
  // A generic fallback is not an activity; the previous real label stands.
  return;
 }
 if (c.equalsIgnoreCase(activityCandidate)) {
  activityEvidence++;
 } else {
  activityCandidate = c;
  activityEvidence = 1;
 }
 if (b || activityEvidence >= 2) {
  detectedActivity = c;
  engine.setDetectedActivity(c, Ae.now());
 }
}

/** Settled transaction types that name the activity (PK, named loot, trade). */
void abw(Ac /* transaction */a) {
 if (a == null) return;
 switch (a.getType()) {
  case PK_LOOT:
  case PK_SUPPLY_COST:
  case PK_DEATH_LOSS:
  case PK_FEE:
  abm("PKing", true);
  break;
  case LOOT:
  String /* amd */b = a.getActivityName();
  if (b != null && !b.trim().isEmpty() && !G.equalsIgnoreCase(b.trim())) {
   abm(b.trim(), true);
  }
  break;
  case TRADE:
  abm("Trading", true);
  break;
  default:
  break;
 }
}

/** Active-owner change reseeds the candidate from the session hint. */
void ajd() {
 Ad /* active */a = engine.getActiveSession();
 String /* amm */b = a == null ? null : a.getId();
 if (b == null ? activitySessionId == null : b.equals(activitySessionId)) return;
 activitySessionId = b;
 String /* initial */c = a == null ? G : a.getActivityHint();
 detectedActivity = awq(c, G);
 activityCandidate = detectedActivity;
 activityEvidence = 0;
}

/** Local death drops the detected activity so the killer cannot title the next session. */
void pe() {
 agp(true);
}

void agp(boolean /* resetHold */a) {
 if (!G.equalsIgnoreCase(detectedActivity) || a) {
  detectedActivity = G;
  activityCandidate = G;
  activityEvidence = 0;
  if (config.autoActivityDetection()) engine.setDetectedActivity(G, Ae.now());
 }
}

/**
* Positive XP for {@code skill}: names the activity (Agility prefers the course) and, inside
* an open PRODUCTION window, re-arms the context so the processing receipt can settle.
*/
void acj(String /* skillName */b, String /* agilityCourse */c, boolean /* fighting */d, long /* now */e) {
 if ("Agility".equalsIgnoreCase(b) && c != null) abm(c, true);
 // Combat XP, and Magic in a fight, names nothing: the NPC's loot does. Alching is Magic.
 else if (!"Prayer".equalsIgnoreCase(b) && !ActivityLabel.COMBAT.contains(b)
 && !(d && "Magic".equals(b))) abm(b.trim(), b.equalsIgnoreCase(gatherSkill));
 // A Make-X run has actions with no XP (a failed iron smelt), so a production skill's XP
 // re-arms the context for 30 s after the last one, not only inside the six-tick window.
 if (e <= productionWindowExpiresAtEpochMillis
 || Dw.TRANSFORM.containsValue(b) || "Smithing".equals(b)) {
  qf(b, e);
 }
}

/** Evidence the Make-X run goes on (its XP, a failed smelt): re-arms it within 30 s of the last. */
void qf(String /* note */a, long /* now */b) {
 if (b <= productionRunUntilEpochMillis) le(a, b);
}

/** Enabling during Make-X needs fresh production XP, without a click made while disabled. */
void rb(long a) {
 productionWindowExpiresAtEpochMillis = 0L;
 productionRunUntilEpochMillis = a + 30_000L;
 gatherSkill = "";
}

/** Station/menu hint: arm PRODUCTION context for the correlation window. */
void le(String /* skillNote */a) {
 le(a, Ae.now());
}

void le(String /* skillNote */a, long /* now */b) {
 int /* ticks */c = 6;
 String /* note */d = awq(a, "Production");
 engine.markContext(Aj.PRODUCTION, c, d);
 productionWindowExpiresAtEpochMillis = b + c * MILLIS_PER_GAME_TICK;
 productionRunUntilEpochMillis = b + 30_000L;
}
}
