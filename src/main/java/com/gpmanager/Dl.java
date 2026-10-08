package com.gpmanager;
import static com.gpmanager.Ak.msg;
import lombok.AllArgsConstructor;
/**
* Optional item eligibility filter shared by reward presentation and all
* local accounting projections. Stored source history is never deleted.
*/
@AllArgsConstructor
public enum Dl {
ALL_ITEMS("All items"), FOLLOW_GROUND_ITEMS(msg("a0")), HIGHLIGHTED_LIST_ONLY(msg("eo"));
String label;
public String toString() {
 return label;
}
}
