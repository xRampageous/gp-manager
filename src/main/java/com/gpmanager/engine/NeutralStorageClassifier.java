package com.gpmanager;
import static com.gpmanager.Ak.msg;
/**
* Ownership-neutral world / coin storages — treat like bank TRANSFER.
* Never invent profit/loss on deposit or withdraw.
*/
class NeutralStorageClassifier {
/**
* Each storage with its transfer note and its phrases, checked in this order: {@code |}
* separates alternatives and {@code +} joins words that must all appear.
*/
enum Kind {
 SEED_VAULT, TOOL_LEPRECHAUN, NMZ_COFFER, BLAST_FURNACE_COFFER, LMS_COFFER, MLM_HOPPER, GIM_SHARED, RAID_PRIVATE_BAG, RAID_SHARED_BAG, STASH_UNIT, POH_COSTUME, DEPOSIT_BOX, IMP_BOX, CHUG_BARREL, SCROLL_BOOK, UNKNOWN_NEUTRAL;
 String note;
 String phrases;
 Kind() {
  String[] a = Ak.v("d26", name());
  note = Ak.t(a[3]); phrases = Ak.t(a[1]);
 }
}

/** @return storage kind, or null when not a neutral storage signal. */


static String ajw(Kind /* kind */a) {
 return (a == null ? Kind.UNKNOWN_NEUTRAL : a).note;
}
/** The original enum order is stored in d26; compiled clauses retain norm normalization. */
static final java.util.Map<String, Ag.P[][]> P = Ak.r("d26");
static final java.util.Map<String, Kind> R = new java.util.LinkedHashMap<>();
static {
 for (String[] a : Ak.x("d26")) if (!a[0].startsWith("m")) R.put(a[0], Kind.valueOf(a[2]));
}
static Kind a1(String a) {
 String b = Ag.norm(a);
 for (var c : R.entrySet()) if (Ag.has(b, P.get(c.getKey()))) return c.getValue();
 return null;
}

}
