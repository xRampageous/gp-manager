package com.gpmanager;
/**
* While a clue/casket/key path is active, digs / teleports / key spends attach as
* costs against that clue's Pending Rewards → Claimed batch. Unmatched supply
* spends stay normal Used.
*/
class ClueCostPairing {
String activeClueEncounterId;
String activeClueLabel;
synchronized void beginClue(String encounterId, String label) {
this.activeClueEncounterId = emptyToNull(encounterId);
this.activeClueLabel = emptyToNull(label);
}

synchronized void endClue() {
activeClueEncounterId = null;
activeClueLabel = null;
}

synchronized boolean isActive() {
return activeClueEncounterId != null;
}

synchronized String getActiveEncounterId() {
return activeClueEncounterId;
}

/**
* Classify a spend note while a clue path may be open.
* Dig / teleport / clue key → CLUE_COST; food / potions → UNRELATED_USED.
*/
synchronized boolean isClueCost(String noteOrOption) {
String lower = ModelText.norm(noteOrOption);
return activeClueEncounterId != null && lower != null && looksLikeClueCost(lower);
}

static boolean looksLikeClueCost(String lowerNote) {
if (ModelText.empty(lowerNote)) return false;
return lowerNote.contains("dig") || lowerNote.contains("teleport") || lowerNote.contains("tele ")
|| lowerNote.contains("clue") || lowerNote.contains("casket") || lowerNote.contains("spade")
|| lowerNote.contains("key") && (ModelText.has(lowerNote, "clue", "chest"));
}

static boolean looksLikeClueActivity(String name) {
String lower = ModelText.norm(name);
return lower != null && (ModelText.has(lower, "clue", "casket", "treasure trail"));
}

synchronized String costNote() {
String label = activeClueLabel == null ? "clue" : activeClueLabel;
return "Clue cost paired to " + label;
}

synchronized void clear() {
endClue();
}

static String emptyToNull(String value) {
if (ModelText.blank(value)) return null;
return value.trim();
}
}
