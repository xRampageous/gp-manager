package com.gpmanager;
import static com.gpmanager.Ak.msg;
/**
* Labels for observed reclaim payments. Fee amounts are never inferred here: the engine books
* only the carried coins actually measured leaving the inventory during a reclaim, and the
* published service amount is explanation text.
*/
class DeathReclaimFees {
static String vc(boolean ironman) {
return ironman
? msg("ap")
: msg("ca");
}
}
