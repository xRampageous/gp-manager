package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.CostKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Books Grand Exchange seller tax on collect without double-counting invent
 * shortfall already embedded in sell+collect TRADE deltas.
 */
public final class GeSellTaxBooking
{
    /** Sentinel item id for an explicit tax cost row (not a real OSRS item). */
    public static final int TAX_ITEM_ID = CostKind.GE_TAX_ITEM_ID;

    public static final String TAX_ITEM_NAME = "GE sell tax";
    public static final String WHY = "GE tax 2% (capped)";

    /**
     * Items exempt from the GE convenience fee, per the wiki's Grand Exchange tax
     * exemption list (checked 2026-09-13). Ids are the pinned RuneLite gameval
     * {@code ItemID} constants. Items under 50 gp pay nothing anyway through rounding,
     * so most tools here matter only when their price spikes.
     */
    private static final Set<Integer> EXEMPT_IDS;
    static
    {
        Set<Integer> ids = new HashSet<>();
        // Bonds
        ids.add(13190); // Old school bond
        ids.add(13191); // Old school bond (bought)
        ids.add(13192); // Old school bond (untradeable)
        // Low-level combat consumables
        ids.add(882);   // Bronze arrow
        ids.add(884);   // Iron arrow
        ids.add(886);   // Steel arrow
        ids.add(806);   // Bronze dart
        ids.add(807);   // Iron dart
        ids.add(808);   // Steel dart
        ids.add(558);   // Mind rune
        // Low-level food
        ids.add(365);   // Bass
        ids.add(2309);  // Bread
        ids.add(1891);  // Cake
        ids.add(2140);  // Cooked chicken
        ids.add(2142);  // Cooked meat
        ids.add(347);   // Herring
        ids.add(379);   // Lobster
        ids.add(355);   // Mackerel
        ids.add(2327);  // Meat pie
        ids.add(351);   // Pike
        ids.add(329);   // Salmon
        ids.add(315);   // Shrimps
        ids.add(361);   // Tuna
        // Energy potion (all doses)
        ids.add(3008);  // Energy potion(4)
        ids.add(3010);  // Energy potion(3)
        ids.add(3012);  // Energy potion(2)
        ids.add(3014);  // Energy potion(1)
        // Teleports
        ids.add(8011);  // Ardougne teleport (tablet)
        ids.add(8010);  // Camelot teleport (tablet)
        ids.add(28824); // Civitas illa Fortis teleport (tablet)
        ids.add(8009);  // Falador teleport (tablet)
        ids.add(28790); // Kourend castle teleport (tablet)
        ids.add(8008);  // Lumbridge teleport (tablet)
        ids.add(8013);  // Teleport to house (tablet)
        ids.add(8007);  // Varrock teleport (tablet)
        ids.add(3853);  // Games necklace(8)
        ids.add(2552);  // Ring of dueling(8)
        // Tools
        ids.add(1755);  // Chisel
        ids.add(5325);  // Gardening trowel
        ids.add(1785);  // Glassblowing pipe
        ids.add(2347);  // Hammer
        ids.add(1733);  // Needle
        ids.add(233);   // Pestle and mortar
        ids.add(5341);  // Rake
        ids.add(8794);  // Saw
        ids.add(5329);  // Secateurs
        ids.add(5343);  // Seed dibber
        ids.add(1735);  // Shears
        ids.add(952);   // Spade
        ids.add(5331);  // Watering can
        EXEMPT_IDS = Collections.unmodifiableSet(ids);
    }

    public static final class Result
    {
        public final List<ItemFlow> flows;
        public final long taxBooked;
        public final boolean explicitRow;
        public final String explanationSuffix;

        Result(List<ItemFlow> flows, long taxBooked, boolean explicitRow, String explanationSuffix)
        {
            this.flows = flows;
            this.taxBooked = taxBooked;
            this.explicitRow = explicitRow;
            this.explanationSuffix = explanationSuffix == null ? "" : explanationSuffix;
        }
    }

    public static final class PendingSale
    {
        public final int itemId;
        public final long qty;
        public final int unitPrice;
        public final boolean itemLossCounted;

        public PendingSale(int itemId, long qty, int unitPrice, boolean itemLossCounted)
        {
            this.itemId = itemId;
            this.qty = qty;
            this.unitPrice = Math.max(0, unitPrice);
            this.itemLossCounted = itemLossCounted;
        }

        public long preTaxTotal()
        {
            try
            {
                return Math.multiplyExact(unitPrice, qty);
            }
            catch (ArithmeticException ex)
            {
                return Long.MAX_VALUE;
            }
        }

        public long formulaTax()
        {
            return GeSellTax.taxForSale(unitPrice, qty, isExempt(itemId));
        }
    }

    private GeSellTaxBooking()
    {
    }

    public static boolean isExempt(int itemId)
    {
        return EXEMPT_IDS.contains(itemId);
    }

    public static boolean isTaxFlow(ItemFlow flow)
    {
        return flow != null && flow.getItemId() == TAX_ITEM_ID;
    }

    public static boolean looksLikeGeSellOffer(String note)
    {
        String n = norm(note);
        // Shop and GE both use sell*; collect matching is the GE discriminator.
        // Player trades never arm tax even if note mentions sell.
        if (n.contains("player trade") || n.contains("shop"))
        {
            return false;
        }
        return n.startsWith("sell");
    }

    public static boolean looksLikeGeCollect(String note)
    {
        String n = norm(note);
        if (n.isEmpty() || n.startsWith("buy") || n.contains("player trade") || n.contains("shop"))
        {
            return false;
        }
        // GE Collect / Collect-X — shops do not use this path.
        return n.startsWith("collect") || n.contains(" collect");
    }

    /**
     * Record item losses from a GE sell-offer settle for later collect matching.
     */
    public static List<PendingSale> capturePendingSales(
        List<ItemFlow> flows,
        String note,
        boolean itemLossCounted)
    {
        if (!looksLikeGeSellOffer(note) || flows == null)
        {
            return Collections.emptyList();
        }
        List<PendingSale> pending = new ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow == null || flow.getItemId() == 995 || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            pending.add(new PendingSale(
                flow.getItemId(),
                Math.abs(flow.getQuantityDelta()),
                flow.getUnitPrice(),
                itemLossCounted));
        }
        return pending;
    }

    /**
     * Apply tax on GE collect. When prior sell item-loss was already counted,
     * invent shortfall across sell+collect already embeds tax — annotate only.
     * Otherwise book an explicit tax cost row (XOR vs invent shortfall in this settle).
     */
    public static Result applyOnCollect(
        List<ItemFlow> flows,
        String note,
        boolean enabled,
        List<PendingSale> pendingSales)
    {
        return applyOnCollect(flows, note, enabled, pendingSales, 0L);
    }

    /** Same reconciliation, with the current evidence-capture time for a synthetic tax flow. */
    public static Result applyOnCollect(
        List<ItemFlow> flows,
        String note,
        boolean enabled,
        List<PendingSale> pendingSales,
        long priceCapturedAtEpochMillis)
    {
        List<ItemFlow> safeFlows = flows == null ? Collections.emptyList() : flows;
        if (!enabled || !looksLikeGeCollect(note))
        {
            return new Result(safeFlows, 0L, false, "");
        }

        long coinsReceived = coinGains(safeFlows);
        if (coinsReceived <= 0L)
        {
            // A cancelled offer can return items through the same Collect menu.
            // No coin receipt means no sale confirmation and no tax to book.
            return new Result(safeFlows, 0L, false, "");
        }
        List<PendingSale> matched = takeMatchingSales(pendingSales, coinsReceived);
        if (matched.isEmpty() && hasItemLosses(safeFlows))
        {
            // Rare same-settle sell+collect — derive from item losses in this window.
            matched = capturePendingSales(safeFlows, "sell grand exchange", true);
        }
        if (matched.isEmpty())
        {
            return new Result(safeFlows, 0L, false, "");
        }

        long preTax = 0L;
        long formula = 0L;
        boolean anyItemLossCounted = false;
        for (PendingSale sale : matched)
        {
            preTax = safeAdd(preTax, sale.preTaxTotal());
            formula = safeAdd(formula, sale.formulaTax());
            anyItemLossCounted |= sale.itemLossCounted;
        }
        long tax = GeSellTax.reconcileTax(preTax, coinsReceived, formula);
        if (tax <= 0L)
        {
            return new Result(safeFlows, 0L, false, "");
        }

        // Prior counted sell already put -preTax in costs; collect +coins → net ≈ -tax.
        if (anyItemLossCounted)
        {
            return new Result(safeFlows, tax, false, WHY);
        }

        // Same-settle invent (items out + coins in) already embeds tax — annotate only.
        long shortfall = preTax > 0L && coinsReceived >= 0L ? preTax - coinsReceived : -1L;
        boolean inventBooksTax = hasItemLosses(safeFlows)
            && shortfall >= 0L
            && Math.abs(shortfall - tax) <= Math.max(1L, tax / 1000L);
        if (inventBooksTax)
        {
            return new Result(safeFlows, tax, false, WHY);
        }

        List<ItemFlow> withTax = new ArrayList<>(safeFlows);
        withTax.add(new ItemFlow(
            TAX_ITEM_ID,
            TAX_ITEM_NAME,
            -1L,
            tax > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tax,
            -tax,
            ItemPriceSource.GRAND_EXCHANGE,
            Math.max(0L, priceCapturedAtEpochMillis)));
        return new Result(withTax, tax, true, WHY);
    }

    private static List<PendingSale> takeMatchingSales(List<PendingSale> pending, long coinsReceived)
    {
        if (pending == null || pending.isEmpty())
        {
            return Collections.emptyList();
        }
        // Prefer the pending sale whose post-tax coins best match Collect receipts
        // (avoids FIFO attaching sale A tax when collecting sale B).
        if (coinsReceived > 0L && pending.size() > 1)
        {
            PendingSale best = null;
            long bestErr = Long.MAX_VALUE;
            for (PendingSale sale : pending)
            {
                if (sale == null)
                {
                    continue;
                }
                long expectedCoins = sale.preTaxTotal() - sale.formulaTax();
                long err = Math.abs(expectedCoins - coinsReceived);
                if (err < bestErr)
                {
                    bestErr = err;
                    best = sale;
                }
            }
            long slack = Math.max(1L, coinsReceived / 100L);
            if (best != null && bestErr <= slack)
            {
                pending.remove(best);
                return Collections.singletonList(best);
            }
        }
        List<PendingSale> matched = new ArrayList<>();
        long covered = 0L;
        Iterator<PendingSale> it = pending.iterator();
        while (it.hasNext())
        {
            PendingSale sale = it.next();
            matched.add(sale);
            it.remove();
            covered = safeAdd(covered, sale.preTaxTotal());
            // Stop once covered pre-tax meets or exceeds coins (plus a little slack for tax).
            // Partial Collect: coins may be less than one full sale — still take the
            // first FIFO sale so reconcileTax can book the partial shortfall.
            if (coinsReceived > 0L && covered >= coinsReceived)
            {
                break;
            }
            if (coinsReceived <= 0L && matched.size() >= 1)
            {
                break;
            }
        }
        return matched;
    }

    private static long coinGains(List<ItemFlow> flows)
    {
        long total = 0L;
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getItemId() == 995 && flow.getQuantityDelta() > 0L)
            {
                total = safeAdd(total, flow.getQuantityDelta());
            }
        }
        return total;
    }

    private static boolean hasItemLosses(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getItemId() != 995 && flow.getQuantityDelta() < 0L)
            {
                return true;
            }
        }
        return false;
    }

    private static String norm(String note)
    {
        return note == null ? "" : note.trim().toLowerCase(Locale.ROOT);
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return Long.MAX_VALUE;
        }
    }
}
