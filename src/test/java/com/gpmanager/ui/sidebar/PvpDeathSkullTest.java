package com.gpmanager;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Completed, correction-aware losses use BH colours; unknown values never claim a tier. */
public class PvpDeathSkullTest {
    java.util.function.LongSupplier previous;
    @Before public void clock() { previous = SafeMath.clock; SafeMath.clock = () -> 120000; }
    @After public void restoreClock() { SafeMath.clock = previous; }

    static Transaction loss(long value, String fight) {
        return DeathRowGroupingTest.loss(fight, false,
                new Flow(995, "Coins", -value, 1, -value, PriceSource.FACE_VALUE));
    }
    static Semantics.Group group(Transaction... rows) {
        return DeathRowGroupingTest.capture(rows).groups.get(0);
    }

    @Test public void exactBoundariesUseTheLowerTierAndLargeLossesDoNotOverflow() {
        long[] values = {1, 200_000, 200_001, 800_000, 800_001, 2_000_000,
                2_000_001, 8_000_000, 8_000_001, Long.MAX_VALUE};
        int[] tiers = {1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        for (int i = 0; i < values.length; i++) {
            var row = loss(values[i], "death:" + i);
            assertEquals("boundary " + values[i], -100000 - tiers[i], group(row).item);
            assertEquals(-values[i], group(row).value);
            assertEquals(group(row).item, LiveProbe.recent(List.of(row), LiveContext.NONE).get(0).item);
        }
        assertEquals(List.of(0, 200000, 800000, 2000000, 8000000),
                GameData.rows("pvp_skulls").stream().map(r -> Integer.parseInt(r[0]))
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test public void bagAndGearCrossTheThresholdOnlyAfterTheWholeDeathIsGrouped() {
        var gear = loss(180_000, "death:one");
        var bag = DeathRowGroupingTest.loss("death:one", true,
                new Flow(995, "Coins", -30_000, 1, -30_000, PriceSource.FACE_VALUE));
        for (var rows : List.of(new Transaction[]{gear, bag}, new Transaction[]{bag, gear})) {
            var grouped = group(rows);
            assertEquals(-210000, grouped.value);
            assertEquals(-100002, grouped.item);
            assertEquals(2, grouped.entries.size());
        }
        assertEquals(2, DeathRowGroupingTest.capture(gear, loss(30000, "death:two")).groups.size());
    }

    @Test public void unknownAndUncountedReviewValuesKeepThePlainSkull() {
        var unknown = DeathRowGroupingTest.loss("death:one", true,
                new Flow(999999, "Unknown", -1, 0, 0, PriceSource.UNPRICED));
        assertTrue(group(loss(9_000_000, "death:one"), unknown).incomplete());
        assertEquals(-100000, group(loss(9_000_000, "death:one"), unknown).item);
        var review = new Transaction(1000, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH,
                "PK death loss", "PKing", false, loss(9_000_000, "death:review").flows(),
                Confidence.UNCERTAIN, "Needs review", "death:review");
        assertTrue(group(review).review);
        assertEquals(-100000, group(review).item);
    }

    @Test public void ignoredAndReclassifiedEntriesNeverColourAnUnbookedLoss() {
        var row = loss(9_000_000, "death:one");
        row.applyCorrection(Correction.IGNORE, "Owner correction");
        assertEquals(0, group(row).value);
        assertEquals(-100000, group(row).item);
        row.applyCorrection(Correction.REVENUE, "Owner correction");
        assertTrue(group(row).value > 0);
        assertEquals(-100000, group(row).item);
    }

    @Test public void pveUsesThePlainSkullAndGravestonesRemainNeutral() {
        var pve = new Transaction(1000, TransactionType.CONSUMPTION, Context.GENERIC,
                GameData.msg("pvm-loss"), "Death reclaim", true,
                List.of(new Flow(995, "Coins", -9_000_000, 1, -9_000_000, PriceSource.FACE_VALUE)),
                Confidence.CONFIRMED, GameData.msg("pvm-loss"), null);
        assertEquals(-100000, group(pve).item);
        for (boolean recovered : new boolean[]{false, true})
            assertTrue(DeathRowGroupingTest.capture(DeathViewTest.grave(recovered)).groups.isEmpty());
    }

    @Test public void allSixSpritesAreCachedAndRespectTheIconsSetting() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var panel = SavedSessionTest.panel(DeathViewTest.populated());
            var pixels = new HashSet<Integer>();
            for (int tier = 0; tier <= 5; tier++) {
                BufferedImage image = panel.sprite(-100000 - tier);
                assertNotNull(image);
                assertSame(image, panel.sprite(-100000 - tier));
                assertEquals(Kit.ICON_BOX, image.getWidth());
                assertTrue(Kit.visible(image).width > 10);
                pixels.add(Arrays.hashCode(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth())));
            }
            assertEquals(6, pixels.size());
            var hidden = new SidebarPanel(DeathViewTest.populated(), new GpManagerConfig() {
                @Override public boolean showItemIcons() { return false; }
            }, null, null, null);
            for (int tier = 0; tier <= 5; tier++) assertNull(hidden.sprite(-100000 - tier));
            return null;
        });
    }

    @Test public void recentLedgerAndDeathDetailsShareTheIconWithoutChangingSavedMoney() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var engine = LiveCategoryLayoutTest.engine();
            var row = loss(8_000_001, "death:red");
            engine.session().addTransaction(row, 2000, false);
            String before = new Gson().toJson(engine.exportState());
            var panel = SavedSessionTest.panel(engine);
            var recent = panel.live.rowOf(panel.snapshot.recent.get(0));
            var entry = LiveCategoryLayoutTest.category(Semantics.Table.DEATH);
            var data = LedgerData.capture(engine, LiveCategoryLayoutTest.NOW, entry);
            var death = data.cost.groups.get(0);
            panel.ledger.apply(data);
            assertSame(((ImageIcon) recent.icon).getImage(), ((ImageIcon) panel.ledger.groupRow(death, Kit.Tone.LOSS).icon).getImage());
            var detail = LedgerData.capture(engine, LiveCategoryLayoutTest.NOW,
                    entry.withSelection(null, null, death.id));
            panel.ledger.apply(detail);
            assertTrue(hasIcon(panel.ledger.detail, ((ImageIcon) recent.icon).getImage()));
            assertEquals(before, new Gson().toJson(engine.exportState()));
            return null;
        });
    }

    static boolean hasIcon(Container parent, java.awt.Image image) {
        for (var child : parent.getComponents()) {
            if (child instanceof JLabel && ((JLabel) child).getIcon() instanceof ImageIcon
                    && ((ImageIcon) ((JLabel) child).getIcon()).getImage() == image) return true;
            if (child instanceof Container && hasIcon((Container) child, image)) return true;
        }
        return false;
    }

    static void previews(File out) throws java.io.IOException {
        var previous = SafeMath.clock;
        try {
        var engine = LiveCategoryLayoutTest.engine();
        long[] values = {150000, 500000, 1000000, 4000000, 9000000};
        for (int i = 0; i < values.length; i++) engine.session().addTransaction(loss(values[i], "death:" + i), 2000, false);
        for (int width : new int[]{213, 225, 300}) {
            var panel = SavedSessionTest.panel(engine);
            var frame = new JFrame("Offline death skull preview");
            frame.setUndecorated(true); frame.setFocusableWindowState(false); frame.setLocation(-10000, -10000);
            try {
                frame.add(panel); frame.pack(); frame.setSize(width, 560); frame.setVisible(true);
                shot(panel, frame, out, "recent", width);
                panel.live.actions.openCategory(Semantics.Table.DEATH);
                shot(panel, frame, out, "death", width);
                var data = LedgerData.capture(engine, LiveCategoryLayoutTest.NOW,
                        LiveCategoryLayoutTest.category(Semantics.Table.DEATH));
                var red = data.cost.groups.stream().filter(g -> g.value == -9000000).findFirst().orElseThrow();
                panel.categoryPage.actions.selectionChanged(null, null, red.id);
                shot(panel, frame, out, "detail", width);
            } finally { frame.dispose(); }
        }
        } finally { SafeMath.clock = previous; }
    }
    static void shot(SidebarPanel panel, JFrame frame, File out, String state, int width) throws java.io.IOException {
        for (int i = 0; i < 2; i++) { SidebarPanelProbe.refresh(panel); frame.validate(); }
        if (state.equals("detail")) assertNotNull(panel.categoryPage.data.detail);
        var image = new BufferedImage(width, 560, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); panel.printAll(g); g.dispose();
        javax.imageio.ImageIO.write(image, "png", new File(out, "pvp-skulls-" + state + "-" + width + ".png"));
    }

}
