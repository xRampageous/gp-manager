package com.gpmanager;

import static com.gpmanager.GameData.msg;

import lombok.AllArgsConstructor;

/**
 * Optional item eligibility filter shared by reward presentation and all local accounting
 * projections. Stored source history is never deleted.
 */
@AllArgsConstructor
public enum LootFilter {
    ALL_ITEMS("All items"),
    FOLLOW_GROUND_ITEMS(msg("follow-ground")),
    HIGHLIGHTED_LIST_ONLY(msg("highlighted-list"));
    String label;

    public String toString() {
        return label;
    }
}
