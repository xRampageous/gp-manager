package com.gpmanager;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.function.IntSupplier;
import net.runelite.api.Client;
import org.junit.Test;

import static com.gpmanager.HudBuilderTest.NOW;
import static com.gpmanager.HudBuilderTest.grind;
import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.*;

/** Real plugin booking delivery groups chips by client tick, independently of wall-clock milliseconds. */
public class HudBatchPresentationTest
{
    @Test public void twoReceiptsInTheSameTickProduceOneCompleteChip()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.book(41, NOW + 10, 526, "Bones", 2, 40);
        assertEquals(31_879L, fixture.builder.tray.lastDelta());
        assertEquals("+31.8k", fixture.frame(NOW + 20).trip);
    }

    @Test public void theNextTickReplacesTheChipEvenWhileItIsStillVisible()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.book(42, NOW, 526, "Bones", 2, 40);
        assertEquals("same milliseconds cannot merge different game ticks", 80L, fixture.builder.tray.lastDelta());
    }

    @Test public void newestBatchLeadsAndItsMostValuableItemComesBeforeCheaperArrivals()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.book(40, NOW - 600, 4151, "Older expensive item", 1, 500_000);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.book(41, NOW + 10, 526, "Bones", 2, 40);
        HudFrame frame = fixture.frame(NOW + 20);
        assertEquals("Rune kiteshield ×1", frame.rows.get(0).name);
        assertEquals("Bones ×2", frame.rows.get(1).name);
        assertEquals("Older expensive item ×1", frame.rows.get(2).name);
    }

    @Test public void highlightedItemsLeadOnlyWithinTheirNewestBatch()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.builder.filter = new RecentFilter()
        {
            @Override public boolean isFlowIncluded(Flow item) { return true; }
            @Override public Color highlight(String name, long quantity) { return name.contains("Marked") ? Color.MAGENTA : null; }
        };
        fixture.book(40, NOW - 600, 200, "Marked old item", 1, 500_000);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.book(41, NOW + 10, 201, "Marked new item", 1, 20);
        fixture.book(41, NOW + 20, 526, "Bones", 1, 40);
        HudFrame frame = fixture.frame(NOW + 30);
        assertEquals("Marked new item ×1", frame.rows.get(0).name);
        assertEquals("Rune kiteshield ×1", frame.rows.get(1).name);
    }

    @Test public void compactRowsKeepTheIconAndShortenValueAndQuantity()
    {
        Fixture fixture = new Fixture(true, true);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        HudFrame.Row row = fixture.frame(NOW + 20).rows.get(0);
        assertNotNull(row.icon);
        assertEquals("", row.name);
        assertEquals("31.7k ×1", row.value);
    }

    @Test public void namesRemainTheDefaultAndTheFallbackWhenNoIconIsAvailable()
    {
        for (Fixture fixture : new Fixture[]{new Fixture(false, true), new Fixture(true, false)})
        {
            fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
            assertEquals("Rune kiteshield ×1", fixture.frame(NOW + 20).rows.get(0).name);
        }
        Fixture loading = new Fixture(true, true);
        loading.builder = new HudBuilder(loading.config, null);
        loading.plugin.hud = loading.builder;
        loading.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        assertEquals("Rune kiteshield ×1", loading.frame(NOW + 20).rows.get(0).name);
    }

    @Test public void aContainerOpeningAndAnotherReceiptInItsTickShareTheChip()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.plugin.transactionBooked(receipt(NOW, TransactionType.LOOT,
            flow(11238, "Baby impling jar", -1, 200), flow(1201, "Rune kiteshield", 1, 31_799)), NOW);
        fixture.book(0, NOW + 10, 526, "Bones", 2, 40);
        assertEquals(31_679L, fixture.builder.tray.lastDelta());
    }

    @Test public void routineCostsAndNeutralTransfersNeverInflateTheLootChip()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.plugin.transactionBooked(receipt(NOW + 1, TransactionType.TRANSFER,
            flow(526, "Bones", 10, 40)), NOW + 1);
        fixture.plugin.transactionBooked(receipt(NOW + 2, TransactionType.CONSUMPTION,
            flow(560, "Death rune", -2, 100)), NOW + 2);
        assertEquals(31_799L, fixture.builder.tray.lastDelta());
    }

    @Test public void chipExpiryAndClearingTheOwnerStillWork()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.book(41, NOW + 10, 526, "Bones", 2, 40);
        assertEquals("", fixture.frame(NOW + HudBuilder.CHIP_MILLIS).trip);
        fixture.builder.tray.clear();
        fixture.book(41, NOW + 20, 526, "Bones", 1, 40);
        assertEquals(40L, fixture.builder.tray.lastDelta());
    }

    @Test public void aLaterMarketReceiptDoesNotReplaceTheLootChipWithItsCashFlow()
    {
        Fixture fixture = new Fixture(false, true);
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        fixture.tick[0] = 42;
        fixture.plugin.transactionBooked(SavedSessionTest.trade(NOW + 600), NOW + 600);
        assertEquals(31_799L, fixture.builder.tray.lastDelta());
        assertEquals(NOW, fixture.builder.tray.lastDeltaAt());
    }

    @Test public void chipAndRunningRowsSaturateInsteadOfWrappingAtLongBounds()
    {
        Fixture fixture = new Fixture(false, true);
        Transaction huge = receipt(NOW, TransactionType.LOOT,
            new Flow(100, "Huge stack", Long.MAX_VALUE, 1, Long.MAX_VALUE, PriceSource.FACE_VALUE),
            new Flow(101, "Second stack", Long.MAX_VALUE, 1, Long.MAX_VALUE, PriceSource.FACE_VALUE));
        fixture.plugin.transactionBooked(huge, NOW);
        assertEquals(Long.MAX_VALUE, fixture.builder.tray.lastDelta());
        fixture.plugin.transactionBooked(receipt(NOW + 1, TransactionType.LOOT,
            new Flow(100, "Huge stack", 1, 1, 1, PriceSource.FACE_VALUE)), NOW + 1);
        HudTray.Entry entry = fixture.builder.tray.entries().stream().filter(item -> item.item == 100).findFirst().orElseThrow();
        assertEquals(Long.MAX_VALUE, entry.quantity);
        assertEquals(Long.MAX_VALUE, entry.value);
    }

    @Test public void compactRowsKeepUnknownAndValuelessItemsDistinct()
    {
        Fixture unknown = new Fixture(true, true);
        unknown.book(41, NOW, 100, "Unpriced item", 2, 0);
        assertEquals("? ×2", unknown.frame(NOW + 1).rows.get(0).value);
        Fixture worthless = new Fixture(true, true);
        worthless.plugin.transactionBooked(receipt(NOW, TransactionType.LOOT,
            new Flow(101, "Valueless ticket", 2, 0, 0, PriceSource.NO_VALUE)), NOW);
        assertEquals("×2", worthless.frame(NOW + 1).rows.get(0).value);
    }

    @Test public void compactLostItemsKeepTheirMinusSignAndQuantity()
    {
        Fixture fixture = new Fixture(true, true);
        fixture.plugin.transactionBooked(new Transaction(NOW, TransactionType.CONSUMPTION, Context.GENERIC,
            "Dropped", "General", true, java.util.List.of(flow(1201, "Rune kiteshield", -2, 31_799)),
            Confidence.CONFIRMED, "fixture", null), NOW);
        HudFrame.Row row = fixture.frame(NOW + 1).rows.get(0);
        assertEquals("−63.5k ×2", row.value);
        assertEquals(Kit.Tone.LOSS.color, row.color);
    }

    @Test public void compactFiguresStayShortBesideTheIconAtEveryWidth()
    {
        for (int width : new int[]{120, 213, 225, 300})
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override public boolean hudCompactItems() { return true; }
                @Override public boolean hudHideWhenIdle() { return false; }
                @Override public int hudMaxWidth() { return width; }
                @Override public boolean reducedMotion() { return true; }
            };
            HudBuilder builder = new HudBuilder(config, (id, quantity) -> new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
            builder.tray.booked(receipt(NOW, TransactionType.LOOT, flow(100, "Stacked loot", 20_000, 10_000)), NOW, false, NOW);
            HudFrame frame = builder.update(grind(200_000_000L, null), item -> true, NOW + 1000);
            HudOverlay overlay = new HudOverlay(builder, config);
            java.awt.Graphics2D graphics = new BufferedImage(640, 300, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try
            {
                java.awt.Dimension size = overlay.render(graphics);
                assertEquals("hidden names never leave an ellipsis beside the figures", "", overlay.names[0]);
                String value = frame.rows.get(0).value;
                int figures = graphics.getFontMetrics(overlay.body).stringWidth(value);
                assertTrue(value, figures + overlay.indent + HudOverlay.PAD * 2 <= size.width);
                assertEquals("200M ×20.0k", value);
            }
            finally { graphics.dispose(); }
        }
    }

    @Test public void hiddenNamesPlaceTheFiguresBesideTheIconEvenWhenTheHudIsWider()
    {
        Fixture fixture = new Fixture(true, true);
        fixture.builder.filter = new RecentFilter()
        {
            @Override public boolean isFlowIncluded(Flow item) { return true; }
            @Override public Color highlight(String name, long quantity) { return Color.MAGENTA; }
        };
        fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        LiveSnapshot snapshot = grind(31_879L, null);
        snapshot.reviews = 99;
        fixture.builder.update(snapshot, item -> true, NOW + 1000);
        BufferedImage image = new BufferedImage(640, 300, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        HudOverlay overlay = new HudOverlay(fixture.builder, fixture.config);
        try { overlay.render(graphics); }
        finally { graphics.dispose(); }
        int firstFigure = Integer.MAX_VALUE;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if (image.getRGB(x, y) == Color.MAGENTA.getRGB()) firstFigure = Math.min(firstFigure, x);
        int besideIcon = HudOverlay.PAD + overlay.indent;
        assertTrue("figures start beside the icon, at " + firstFigure,
            firstFigure >= besideIcon && firstFigure <= besideIcon + 3);
    }

    static final class Fixture
    {
        final int[] tick = {0};
        final GpManagerConfig config;
        final GpManagerPlugin plugin = new GpManagerPlugin();
        HudBuilder builder;

        Fixture(boolean compact, boolean icons)
        {
            this(compact, icons, false, 225);
        }

        Fixture(boolean compact, boolean icons, boolean semiCompact, int width)
        {
            config = new GpManagerConfig()
            {
                @Override public boolean hudCompactItems() { return compact; }
                @Override public boolean hudSemiCompactValues() { return semiCompact; }
                @Override public int hudMaxWidth() { return width; }
                @Override public boolean showItemIcons() { return icons; }
                @Override public boolean hudHideWhenIdle() { return false; }
                @Override public boolean reducedMotion() { return true; }
            };
            BufferedImage sprite = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            builder = new HudBuilder(config, (id, quantity) -> sprite);
            plugin.hud = builder;
            plugin.config = config;
            plugin.client = ReviewFixesTest.proxy(Client.class, Map.of("getTickCount", (IntSupplier) () -> tick[0]));
            plugin.filter = new LootPresentationFilterService(GroundItemsPrefs.disabled());
        }

        void book(int gameTick, long at, int id, String name, long quantity, int price)
        {
            tick[0] = gameTick;
            plugin.transactionBooked(receipt(at, TransactionType.LOOT, flow(id, name, quantity, price)), at);
        }

        HudFrame frame(long now) { return builder.update(grind(31_879L, null), item -> true, now); }
    }
}
