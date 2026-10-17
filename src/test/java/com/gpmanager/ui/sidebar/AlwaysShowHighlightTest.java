package com.gpmanager;

import com.gpmanager.GpManagerConfig.LootFilter;

import java.awt.Color;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.5.0, after Better Loot Notifier: an Always show list beats Ground Items' hidden list and
 * the minimum value (Hidden Recent items still wins), and Ground Items-highlighted drops stand out
 * in its highlight colour.
 */
public class AlwaysShowHighlightTest
{
    static final Color PINK = new Color(255, 0, 255);

    static LootPresentationFilterService service()
    {
        GroundItemsPrefs prefs = new GroundItemsPrefs(true, "Abyssal whip", "Bones,Coins", false);
        prefs.highlight = PINK;
        return new LootPresentationFilterService(prefs);
    }

    static RecentFilter filter(String always, String hiddenRecent)
    {
        LootPresentationFilterService service = service();
        return new RecentFilter()
        {
            public boolean isFlowIncluded(Flow flow)
            {
                return service.isFlowIncluded(flow, LootFilter.FOLLOW_GROUND_ITEMS);
            }

            public boolean alwaysShows(Flow flow)
            {
                return LootPresentationFilterService.listState(always, "", flow.itemName, Math.abs(flow.quantityDelta))
                    == LootPresentationFilterService.HIGHLIGHTED;
            }

            public Color highlight(String name, long quantity)
            {
                return service.highlight(name, quantity, LootFilter.FOLLOW_GROUND_ITEMS);
            }

            public boolean showRecent(String name, long quantity, boolean charge)
            {
                return LootPresentationFilterService.listState("", hiddenRecent, name, quantity)
                    != LootPresentationFilterService.HIDDEN;
            }
        };
    }

    static LiveContext context(RecentFilter filter, long minimum)
    {
        return new LiveContext(false, filter, minimum, PvpState.NONE, "", false, "", false);
    }

    @Test
    public void alwaysShowBeatsGroundItemsHiddenAndTheMinimum()
    {
        Flow bones = new Flow(526, "Bones", 1L, 90, 90L);
        Flow coins = new Flow(995, "Coins", 500L, 1, 500L);
        assertFalse("Ground Items hides bones", context(filter("", ""), 0L).flowVisible(bones));
        assertTrue("Always show brings them back", context(filter("Bones", ""), 0L).flowVisible(bones));
        assertFalse("under the minimum value", context(filter("", ""), 1_000L).flowVisible(new Flow(1513, "Magic logs", 1L, 700, 700L)));
        assertTrue("a wildcard always-show beats the minimum too",
            context(filter("Magic*", ""), 1_000L).flowVisible(new Flow(1513, "Magic logs", 1L, 700, 700L)));
        assertTrue(context(filter("Coins", ""), 0L).flowVisible(coins));
    }

    @Test
    public void hiddenRecentStillWinsOnRecent()
    {
        Transaction loot = Tx.of(1_000L, TransactionType.LOOT, Context.LOOT, "Vorkath", true,
            List.of(new Flow(526, "Bones", 1L, 90, 90L)));
        assertEquals(1, LiveProbe.recent(List.of(loot), context(filter("Bones", ""), 0L)).size());
        assertTrue("Hidden Recent items is the last word",
            LiveProbe.recent(List.of(loot), context(filter("Bones", "Bones"), 0L)).isEmpty());
    }

    @Test
    public void groundItemsHighlightsStandOut()
    {
        LootPresentationFilterService service = service();
        assertEquals(PINK, service.highlight("Abyssal whip", 1L, LootFilter.FOLLOW_GROUND_ITEMS));
        assertEquals(PINK, service.highlight("Abyssal whip", 1L, LootFilter.HIGHLIGHTED_LIST_ONLY));
        assertNull("All items ignores Ground Items", service.highlight("Abyssal whip", 1L, LootFilter.ALL_ITEMS));
        assertNull(service.highlight("Dragon bones", 1L, LootFilter.FOLLOW_GROUND_ITEMS));
        assertNull("disabled Ground Items highlights nothing",
            new LootPresentationFilterService(GroundItemsPrefs.disabled()).highlight("Abyssal whip", 1L, LootFilter.FOLLOW_GROUND_ITEMS));

        Transaction whip = Tx.of(1_000L, TransactionType.LOOT, Context.LOOT, "Abyssal demon", true,
            List.of(new Flow(4151, "Abyssal whip", 1L, 1_500_000, 1_500_000L)));
        List<LiveSnapshot.Recent> rows = LiveProbe.recent(List.of(whip), context(filter("", ""), 0L));
        assertEquals(PINK, rows.get(0).highlight);
        Transaction shark = Tx.of(2_000L, TransactionType.CONSUMPTION, Context.GENERIC, "Ate", true,
            List.of(new Flow(385, "Shark", -1L, 900, -900L)));
        assertNull("a cost is never highlighted",
            LiveProbe.recent(List.of(shark), context(filter("", ""), 0L)).get(0).highlight);
    }

    @Test
    public void alwaysShowIsEmptyByDefault()
    {
        assertEquals("", new GpManagerConfig() {}.alwaysShowItems());
    }
}
