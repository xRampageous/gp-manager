package com.gpmanager;
import static com.gpmanager.Ak.msg;
import lombok.AllArgsConstructor;
/**
* Ownership-neutral world / coin storages — treat like bank TRANSFER.
* Never invent profit/loss on deposit or withdraw.
*/
class NeutralStorageClassifier {
/**
* Each storage with its transfer note and its phrases, checked in this order: {@code |}
* separates alternatives and {@code +} joins words that must all appear.
*/
@AllArgsConstructor
enum Kind {
 SEED_VAULT("Seed vault transfer", "seed vault"), TOOL_LEPRECHAUN(msg("dq"), msg("dp")),
 NMZ_COFFER("NMZ coffer transfer", msg("dr")), BLAST_FURNACE_COFFER(msg("dt"), msg("ds")),
 LMS_COFFER("LMS coffer transfer", msg("du")), MLM_HOPPER(msg("dw"), msg("dv")), GIM_SHARED(msg("dy"), msg("dx")),
 RAID_PRIVATE_BAG(msg("ea"), msg("dz")), RAID_SHARED_BAG(msg("ec"), msg("eb")),
 STASH_UNIT("STASH unit transfer", "stash unit|stash "), POH_COSTUME(msg("ee"), msg("ed")),
 DEPOSIT_BOX(msg("ef"), "deposit box"), UNKNOWN_NEUTRAL(msg("eg"), "");
 final String note;
 final String phrases;
}

/** @return storage kind, or null when not a neutral storage signal. */
static Kind classify(String noteOrTarget) {
 String lower = Ag.norm(noteOrTarget);
 for (Kind kind : Kind.values()) {
  for (String phrase : kind.phrases.split("\\|")) {
   if (lower != null && !phrase.isEmpty() && Ag.qk(lower, phrase.split("\\+"))) return kind;
  }
 }
 return null;
}

static String ajw(Kind kind) {
 return (kind == null ? Kind.UNKNOWN_NEUTRAL : kind).note;
}
}
