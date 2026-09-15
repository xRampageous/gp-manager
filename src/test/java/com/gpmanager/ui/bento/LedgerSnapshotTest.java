package com.gpmanager.ui.bento;

import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Section policy from SIDEBAR_BENTO.md §4, checked on hand-built receipts. */
public class LedgerSnapshotTest
{
    @Test
    public void receiptsLandInTheSectionThatMatchesHowTheMoneyMoved()
    {
        ProfitTransaction gain = tx(TransactionType.GAIN, flow(1519, "Willow logs", 27, 48), null, true);
        ProfitTransaction drank = tx(TransactionType.CONSUMPTION, flow(2434, "Prayer potion(4)", -1, 8_700), ActionKind.DRINK, true);
        ProfitTransaction cast = tx(TransactionType.CONSUMPTION, flow(554, "Fire rune", -45, 4), ActionKind.CAST, true);
        ProfitTransaction bank = tx(TransactionType.TRANSFER, flow(995, "Coins", -50_000, 1), null, false);
        bank.setNote("Bank deposit");
        ProfitTransaction death = tx(TransactionType.TRANSFER, flow(995, "Coins", -100_000, 1), null, true);
        death.setNote("Death: retrieval fee");
        ProfitTransaction sale = tx(TransactionType.TRADE, flow(1333, "Rune scimitar", -120, 14_980), null, true);
        ProfitTransaction ignored = tx(TransactionType.GAIN, flow(4151, "Abyssal whip", 1, 1_500_000), null, true);
        ignored.applyCorrection(TransactionCorrection.IGNORE, 1L, "test");

        LedgerSnapshot s = LedgerSnapshot.build(LedgerSnapshot.Scope.SESSION,
            Arrays.asList(gain, drank, cast, bank, death, sale, ignored), null);

        assertEquals("Willow logs", s.rows(LedgerSnapshot.Section.GAINS).get(0).name);
        List<LedgerSnapshot.Row> supplies = s.rows(LedgerSnapshot.Section.SUPPLIES);
        assertEquals(2, supplies.size());
        assertEquals("Prayer potion(4)", supplies.get(0).name);
        assertEquals("drank", supplies.get(0).verb);
        assertEquals("quiet", supplies.get(1).tag);
        assertEquals(1, s.rows(LedgerSnapshot.Section.MARKET).size());
        assertEquals("sold", s.rows(LedgerSnapshot.Section.MARKET).get(0).verb);
        assertEquals(1, s.rows(LedgerSnapshot.Section.LOSSES).size());
        assertEquals(-100_000L, s.rows(LedgerSnapshot.Section.LOSSES).get(0).value);
        List<LedgerSnapshot.Row> neutral = s.rows(LedgerSnapshot.Section.NEUTRAL);
        assertEquals(2, neutral.size());
        boolean ignoredTagged = false;
        for (LedgerSnapshot.Row r : neutral)
        {
            ignoredTagged |= "ignored".equals(r.tag);
        }
        assertTrue(ignoredTagged);
        assertEquals(0L, s.subtotal(LedgerSnapshot.Section.NEUTRAL));
        assertEquals(27L * 48L, s.subtotal(LedgerSnapshot.Section.GAINS));
        assertEquals(-(8_700L + 180L), s.subtotal(LedgerSnapshot.Section.SUPPLIES));
        assertEquals(7, s.receiptCount);
    }

    @Test
    public void searchNarrowsByNameAndReviewIsCounted()
    {
        ProfitTransaction gain = tx(TransactionType.GAIN, flow(1519, "Willow logs", 27, 48), null, true);
        ProfitTransaction odd = new ProfitTransaction(1_000L, null, TransactionType.UNCERTAIN, TrackingContext.GENERIC, "",
            "General", true, Collections.singletonList(flow(28790, "Unknown item", 1, 0)),
            ClassificationConfidence.UNCERTAIN, "no price", null);
        LedgerSnapshot all = LedgerSnapshot.build(LedgerSnapshot.Scope.SESSION, Arrays.asList(gain, odd), null);
        assertEquals(1, all.reviewCount);
        LedgerSnapshot narrowed = LedgerSnapshot.build(LedgerSnapshot.Scope.SESSION, Arrays.asList(gain, odd), "willow");
        assertEquals(1, narrowed.receiptCount);
        assertEquals(0, narrowed.reviewCount);
    }

    private static ItemFlow flow(int id, String name, long qty, int price)
    {
        return new ItemFlow(id, name, qty, price, qty * price, ItemPriceSource.GRAND_EXCHANGE);
    }

    private static ProfitTransaction tx(TransactionType type, ItemFlow flow, ActionKind kind, boolean counted)
    {
        ProfitTransaction t = new ProfitTransaction(1_000L, null, type, TrackingContext.GENERIC, "", "General", counted,
            Collections.singletonList(flow), ClassificationConfidence.LIKELY, "test", null);
        if (kind != null)
        {
            t.setActionKind(kind);
        }
        return t;
    }
}
