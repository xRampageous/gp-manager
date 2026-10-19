package com.gpmanager;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static com.gpmanager.HudBuilderTest.NOW;
import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.*;

/** Optional full small values retain the existing icon layout and compact fallback. */
public class HudSemiCompactTest
{
    @Test public void valuesBelowTenMillionAreExactAndBothSignsUseTheSameBoundary()
    {
        long[] amounts = {31_799L, 9_999_999L, 10_000_000L, 200_000_000L};
        String[] expected = {"31,799", "9,999,999", "10.0M", "200M"};
        for (int i = 0; i < amounts.length; i++)
            for (int sign : new int[]{1, -1})
            {
                var fixture = fixture(true, true, true, 225);
                Transaction transaction = sign > 0 ? receipt(NOW, TransactionType.LOOT,
                    flow(1201, "Rune kiteshield", 1, (int) amounts[i]))
                    : new Transaction(NOW, TransactionType.CONSUMPTION, Context.GENERIC, "Dropped", "General", true,
                        List.of(flow(1201, "Rune kiteshield", -1, (int) amounts[i])), Confidence.CONFIRMED, "fixture", null);
                fixture.plugin.transactionBooked(transaction, NOW);
                HudFrame.Row row = fixture.frame(NOW + 1000).rows.get(0);
                assertEquals((sign < 0 ? "−" : "") + expected[i] + " ×1", row.value);
                assertEquals("", row.name);
                assertNotNull(row.icon);
                if (sign < 0) assertEquals(Kit.Tone.LOSS.color, row.color);
            }
    }

    @Test public void unknownAndValuelessItemsStayDistinct()
    {
        var unknown = fixture(true, true, true, 225);
        unknown.book(41, NOW, 100, "Unpriced item", 2, 0);
        assertEquals("? ×2", unknown.frame(NOW + 1000).rows.get(0).value);
        var worthless = fixture(true, true, true, 225);
        worthless.plugin.transactionBooked(receipt(NOW, TransactionType.LOOT,
            new Flow(101, "Valueless ticket", 2, 0, 0, PriceSource.NO_VALUE)), NOW);
        assertEquals("×2", worthless.frame(NOW + 1000).rows.get(0).value);
    }

    @Test public void namedAndMissingIconRowsKeepTheirExistingPresentation()
    {
        for (var fixture : new HudBatchPresentationTest.Fixture[]{fixture(false, true, true, 225),
            fixture(true, false, true, 225)})
        {
            fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
            HudFrame.Row row = fixture.frame(NOW + 1000).rows.get(0);
            assertEquals("Rune kiteshield ×1", row.name);
            assertEquals("+31.7k", row.value);
        }
    }

    @Test public void narrowHudRendersTheCompleteCompactFallbackInsteadOfClippingFullFigures()
    {
        var compact = fixture(true, true, false, 120);
        var semi = fixture(true, true, true, 120);
        for (var fixture : new HudBatchPresentationTest.Fixture[]{compact, semi})
            fixture.book(41, NOW, 1201, "Stacked loot", 20_000, 499);
        assertEquals("9,980,000 ×20.0k", semi.frame(NOW + 1000).rows.get(0).value);
        assertArrayEquals(pixels(render(compact)), pixels(render(semi)));
    }

    @Test public void wideHudRendersTheFullValueBesideItsIconAndKeepsHighlightColor()
    {
        var compact = fixture(true, true, false, 225);
        var semi = fixture(true, true, true, 225);
        for (var fixture : new HudBatchPresentationTest.Fixture[]{compact, semi})
        {
            fixture.builder.filter = new RecentFilter()
            {
                @Override public boolean isFlowIncluded(Flow item) { return true; }
                @Override public Color highlight(String name, long quantity) { return Color.MAGENTA; }
            };
            fixture.book(41, NOW, 1201, "Rune kiteshield", 1, 31_799);
        }
        BufferedImage shown = render(semi);
        assertFalse("full figures are actually painted", Arrays.equals(pixels(render(compact)), pixels(shown)));
        int left = Integer.MAX_VALUE;
        for (int y = 0; y < shown.getHeight(); y++)
            for (int x = 0; x < shown.getWidth(); x++)
                if (shown.getRGB(x, y) == Color.MAGENTA.getRGB()) left = Math.min(left, x);
        assertTrue("highlighted figures stay beside the icon", left >= 27 && left <= 30);
    }

    static HudBatchPresentationTest.Fixture fixture(boolean compact, boolean icons, boolean semi, int width)
    {
        return new HudBatchPresentationTest.Fixture(compact, icons, semi, width);
    }

    static BufferedImage render(HudBatchPresentationTest.Fixture fixture)
    {
        fixture.frame(NOW + 1000);
        var image = new BufferedImage(640, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            Dimension size = new HudOverlay(fixture.builder, fixture.config).render(graphics);
            assertTrue(size.width <= fixture.config.hudMaxWidth());
        }
        finally { graphics.dispose(); }
        return image;
    }

    static int[] pixels(BufferedImage image)
    {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
