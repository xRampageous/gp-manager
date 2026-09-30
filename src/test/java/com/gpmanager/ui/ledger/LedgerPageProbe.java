package com.gpmanager;

import com.gpmanager.Br.Receipt;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.*;
import javax.swing.JPopupMenu;

/** Test-side access to LedgerPage state; moved out of production to keep the plugin small. */
public final class LedgerPageProbe
{

    /** The page's current read model. */
    public static Ao data(LedgerPage page)
    {
        return page.data;
    }
    private LedgerPageProbe()
    {
    }

    /** OVERVIEW, RECEIPTS (a group's list) or EXACT (a receipt selected under it). */
    public static String drill(LedgerPage p)
    {
        return p.data == null || p.data.detail == null || p.detail.getComponentCount() == 0 ? "OVERVIEW"
            : p.data.detail.exact != null ? "EXACT" : "RECEIPTS";
    }

    /** The selected receipt's Correct ▾ menu labels, in order. */
    public static List<String> correctMenu(LedgerPage p)
    {
        List<String> labels = new ArrayList<>();
        if (p.data != null && p.data.detail != null && p.data.detail.exact != null)
        {
            for (Component item : p.correctMenu(p.data.detail.exact).getComponents())
            {
                labels.add(((JMenuItem) item).getText());
            }
        }
        return labels;
    }

    /** Runs one entry of the selected receipt's Correct ▾ menu. */
    public static void chooseCorrection(LedgerPage p, String label)
    {
        for (Component item : p.correctMenu(p.data.detail.exact).getComponents())
        {
            if (((JMenuItem) item).getText().equals(label))
            {
                ((JMenuItem) item).doClick();
            }
        }
    }

    /** The one effective Net contribution of a booked settlement, as the old proof worded it. */
    public static String netContributionText(Bi.Row market)
    {
        if (!market.realizedResultCorrectionAware)
        {
            return "0 · not counted";
        }
        if (market.correctionApplied)
        {
            return Fmt.ru(market.realizedResultGp) + " gp · corrected";
        }
        if (market.knownCostOnly)
        {
            return Fmt.ru(market.realizedResultGp) + " gp · GE tax";
        }
        boolean knownResult = market.coverage != Bi.Coverage.FULLY_UNKNOWN
            || market.manualFinancialResult;
        return knownResult && market.settledQty > 0L
            ? Fmt.ru(market.realizedResultGp) + " gp · Result"
            : "0 · not counted";
    }

    /** One rendered Market receipt row as "title|sub|value". */
    public static String marketReceiptRowText(Receipt receipt, long capturedAt)
    {
        boolean realized = Dc.zq(receipt);
        return Dc.aab(receipt) + "|" + Dc.aaa(receipt, capturedAt) + "|"
            + (realized ? Fmt.ru(Dc.zx(receipt)) + " gp"
                : Dc.avj(receipt.marketSettlement));
    }


    public static List<String> overviewRows(LedgerPage p, LedgerPageProbe.FinancialTable table)
    {
        return p.data == null || p.data.detail != null ? Collections.emptyList() : TableRows.names(axd(p, table));
    }

    public static String overviewPageLabel(LedgerPage p, LedgerPageProbe.FinancialTable table)
    {
        return p.data == null || p.data.detail != null ? "" : TableRows.pageText(axd(p, table));
    }

    public static void toggleTable(LedgerPage p, LedgerPageProbe.FinancialTable table)
    {
        axd(p, table).setFolded(!axd(p, table).isFolded());
    }

    public static void toggleCorrected(LedgerPage p)
    {
        p.showCorrected = !p.showCorrected;
        if (p.data != null)
        {
            p.apply(p.data);
        }
    }

    public static void clickReceipt(LedgerPage p, String transactionId)
    {
        if (p.data == null || p.data.detail == null)
        {
            return;
        }
        if (p.data.detail.group.market)
        {
            for (Receipt receipt : p.data.detail.receipts)
            {
                if (receipt.transactionId.equals(transactionId))
                {
                    p.clickReceipt(receipt.transactionId, receipt.contributionId);
                    return;
                }
            }
            return;
        }
        for (Ao.Card card : Ao.cards(p.data.detail.group))
        {
            if (card.transactionId.equals(transactionId))
            {
                p.clickReceipt(card.transactionId, card.contributionId);
                return;
            }
        }
    }

    public static List<String> receiptCardTransactionIds(LedgerPage p)
    {
        List<String> ids = new ArrayList<>();
        if (!"OVERVIEW".equals(drill(p)))
        {
            if (p.data.detail.group.market)
            {
                for (Receipt receipt : p.data.detail.receipts)
                {
                    if (!ids.contains(receipt.transactionId))
                    {
                        ids.add(receipt.transactionId);
                    }
                }
            }
            else
            {
                for (Ao.Card card : Ao.cards(p.data.detail.group))
                {
                    ids.add(card.transactionId);
                }
            }
        }
        return ids;
    }

    public static List<String> detailReceiptRowTexts(LedgerPage p)
    {
        List<String> texts = new ArrayList<>();
        if ("OVERVIEW".equals(drill(p)))
        {
            return texts;
        }
        Br.Group group = p.data.detail.group;
        if (group.market)
        {
            for (Receipt receipt : p.data.detail.receipts)
            {
                texts.add(marketReceiptRowText(receipt, p.data.capturedAt));
            }
            return texts;
        }
        for (Ao.Card card : Ao.cards(group))
        {
            texts.add(group.primaryName + "|" + card.summaryText() + "|"
                + (card.incomplete ? "incomplete" : Fmt.ru(card.value) + " gp"));
        }
        return texts;
    }

    /** The detail's one Back: straight to the Ledger overview. */
    public static void back(LedgerPage p)
    {
        p.actions.selectionChanged(null, null, null);
    }

    public static void preview(LedgerPage p, Ao.Ef preview, Ah correction)
    {
        p.pendingPreview = preview;
        p.pendingCorrection = correction;
        p.pendingTransaction = p.data == null || p.data.detail == null || p.data.detail.exact == null ? null
            : p.data.detail.exact.transactionId;
    }

    public static void select(LedgerPage p, String transactionId)
    {
        p.actions.selectionChanged(transactionId == null || transactionId.isEmpty() ? null : transactionId, null, null);
    }

    public static String marketRowSemantics(LedgerPage p, String groupId)
    {
        for (Br.Group group : p.data == null ? Collections.<Br.Group>emptyList()
            : p.data.market.groups)
        {
            if (group.semanticGroupId.equals(groupId))
            {
                return Fmt.signed(Dc.zw(p.data, group)) + "|" + Dc.aag(p.data, group);
            }
        }
        return "";
    }

    public static JPopupMenu reviewMenu(LedgerPage p, Cu row)
    {
        JPopupMenu menu = new JPopupMenu();
        for (Cl decision : Cl.values())
        {
            if (row.validDecisions.contains(decision))
            {
                menu.add(Kit.item(LedgerPage.ahh(decision), () -> p.actions.decide(row.transactionId, decision)));
            }
        }
        return menu;
    }

    public static List<String> detailTexts(LedgerPage p)
    {
        List<String> texts = new ArrayList<>();
        walk(p.detail, texts, false);
        return texts;
    }

    public static List<String> detailTooltips(LedgerPage p)
    {
        List<String> tips = new ArrayList<>();
        walk(p.detail, tips, true);
        return tips;
    }
    public enum FinancialTable { GAINS, COSTS, MARKET }
    private static Table axd(LedgerPage p, FinancialTable table) {
        return table == FinancialTable.GAINS ? p.gains : table == FinancialTable.MARKET ? p.market : p.costs;
    }

    static void walk(Component component, List<String> out, boolean tips) {
        String text = tips ? component instanceof JComponent ? ((JComponent) component).getToolTipText() : null
            : component instanceof JLabel ? ((JLabel) component).getText() : null;
        if (text != null && !text.isEmpty()) {
            out.add(text);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                walk(child, out, tips);
            }
        }
    }
}
