package com.gpmanager;
import static com.gpmanager.GameData.msg;
import lombok.AllArgsConstructor;
/**
* Optional item eligibility filter shared by reward presentation and all
* local accounting projections. Stored source history is never deleted.
*/
@AllArgsConstructor
public enum LootPresentationFilter {
ALL_ITEMS("All items"), FOLLOW_GROUND_ITEMS("Follow Ground Items"), HIGHLIGHTED_LIST_ONLY(msg("eo"));
final String label;
public String toString() {
 return label;
}
}
