package com.gpmanager;
import static com.gpmanager.Ak.msg;
import lombok.AllArgsConstructor;
/**
* Ownership-neutral world / coin storages — treat like bank TRANSFER.
* Never invent profit/loss on deposit or withdraw.
*/
class Nso {
/**
* Each storage with its transfer note and its phrases, checked in this order: {@code |}
* separates alternatives and {@code +} joins words that must all appear.
*/
@AllArgsConstructor
enum Kind {
 SEED_VAULT(msg("bu"), "seed vault"), TOOL_LEPRECHAUN(msg("dq"), msg("dp")),
 NMZ_COFFER(msg("a9"), msg("dr")), BLAST_FURNACE_COFFER(msg("dt"), msg("ds")),
 LMS_COFFER(msg("a8"), msg("du")), MLM_HOPPER(msg("dw"), msg("dv")), GIM_SHARED(msg("dy"), msg("dx")),
 RAID_PRIVATE_BAG(msg("ea"), msg("dz")), RAID_SHARED_BAG(msg("ec"), msg("eb")),
 STASH_UNIT(msg("a7"), "stash unit|stash "), POH_COSTUME(msg("ee"), msg("ed")),
 DEPOSIT_BOX(msg("ef"), "deposit box"), UNKNOWN_NEUTRAL(msg("eg"), "");
 String note;
 String phrases;
}

/** @return storage kind, or null when not a neutral storage signal. */
static Kind classify(String /* noteOrTarget */a) {
 String /* lower */b = Ag.norm(a);
 for (Kind /* kind */c : Kind.values()) {
  for (String /* phrase */d : c.phrases.split("\\|")) {
   if (b != null && !d.isEmpty() && Ag.qk(b, d.split("\\+"))) return c;
  }
 }
 return null;
}

static String ajw(Kind /* kind */a) {
 return (a == null ? Kind.UNKNOWN_NEUTRAL : a).note;
}
}
