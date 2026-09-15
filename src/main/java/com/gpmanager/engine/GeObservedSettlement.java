package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.runelite.api.GrandExchangeOfferState;

/**
 * What one comparable offer transition settles, derived from the observed slot deltas alone
 * (pass 10 step 45): the item side at its reference price, the coin side from the raw
 * {@code getSpent()} difference. No menu text, no inventory delta.
 *
 * <p>The sell-side meaning of {@code getSpent()} — whether the coins reported are before or after
 * the Grand Exchange tax — is not confirmed by the pinned API. {@link #from} takes that as an
 * explicit argument: when the coins are already net of tax nothing is added (a fee embedded in
 * net proceeds is never booked twice); when they are gross, the formula tax is booked as its own
 * row so the counted proceeds match what actually reached the player.
 */
public final class GeObservedSettlement
{
    public static final int COINS = 995;

    public enum Side
    {
        BUY,
        SELL
    }

    private final Side side;
    private final int slot;
    private final int itemId;
    private final String itemName;
    private final long quantity;
    private final long coins;
    private final long tax;
    private final List<ItemFlow> flows;
    private final GrandExchangeOfferState state;

    private GeObservedSettlement(Side side, int slot, int itemId, String itemName, long quantity, long coins, long tax,
        List<ItemFlow> flows, GrandExchangeOfferState state)
    {
        this.side = side;
        this.slot = slot;
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.coins = coins;
        this.tax = tax;
        this.flows = Collections.unmodifiableList(new ArrayList<>(flows));
        this.state = state;
    }

    /**
     * @param transition       a ledger transition; only comparable ones with traded progress settle
     * @param itemName         display name for the item flow
     * @param unitPrice        the reference (GE) price of the item, for the item side's valuation
     * @param sellSpentIsNet   true when the sell-side {@code getSpent()} already excludes the tax
     * @param applyTax         whether a tax row may be booked at all (config.applyGeSellTax)
     * @param now              the settlement time stamped on the flows
     */
    public static Optional<GeObservedSettlement> from(GeOfferLedger.Transition transition, String itemName, int unitPrice,
        boolean sellSpentIsNet, boolean applyTax, long now)
    {
        if (transition == null || !transition.hasComparableProgress() || transition.getQuantityTradedDelta() <= 0)
        {
            return Optional.empty();
        }
        GeOfferLedger.Snapshot current = transition.getCurrent();
        Side side = sideOf(current.getState());
        if (side == null || current.getItemId() <= 0)
        {
            return Optional.empty();
        }
        long quantity = transition.getQuantityTradedDelta();
        long coins = Math.max(0L, transition.getSpentDelta());
        String name = itemName == null || itemName.trim().isEmpty() ? "Item " + current.getItemId() : itemName.trim();
        int price = Math.max(0, unitPrice);
        List<ItemFlow> flows = new ArrayList<>();
        long tax = 0L;
        if (side == Side.BUY)
        {
            flows.add(new ItemFlow(current.getItemId(), name, quantity, price, quantity * price, ItemPriceSource.GRAND_EXCHANGE, now));
            if (coins > 0L)
            {
                flows.add(new ItemFlow(COINS, "Coins", -coins, 1, -coins, ItemPriceSource.GRAND_EXCHANGE, now));
            }
        }
        else
        {
            flows.add(new ItemFlow(current.getItemId(), name, -quantity, price, -quantity * price, ItemPriceSource.GRAND_EXCHANGE, now));
            if (coins > 0L)
            {
                flows.add(new ItemFlow(COINS, "Coins", coins, 1, coins, ItemPriceSource.GRAND_EXCHANGE, now));
            }
            if (applyTax && !sellSpentIsNet && !GeSellTaxBooking.isExempt(current.getItemId()))
            {
                // Coins were reported gross: the tax the exchange kept is a cost of its own.
                long perUnit = quantity == 0L ? 0L : coins / quantity;
                tax = GeSellTax.taxForSale((int) Math.min(Integer.MAX_VALUE, perUnit), quantity, false);
                if (tax > 0L)
                {
                    flows.add(new ItemFlow(GeSellTaxBooking.TAX_ITEM_ID, GeSellTaxBooking.TAX_ITEM_NAME, -1L, (int) Math.min(Integer.MAX_VALUE, tax),
                        -tax, ItemPriceSource.GRAND_EXCHANGE, now));
                }
            }
        }
        return Optional.of(new GeObservedSettlement(side, current.getSlot(), current.getItemId(), name, quantity, coins, tax,
            flows, current.getState()));
    }

    static Side sideOf(GrandExchangeOfferState state)
    {
        if (state == null)
        {
            return null;
        }
        switch (state)
        {
            case BUYING:
            case BOUGHT:
            case CANCELLED_BUY:
                return Side.BUY;
            case SELLING:
            case SOLD:
            case CANCELLED_SELL:
                return Side.SELL;
            default:
                return null;
        }
    }

    public Side getSide() { return side; }
    public int getSlot() { return slot; }
    public int getItemId() { return itemId; }
    public String getItemName() { return itemName; }
    public long getQuantity() { return quantity; }
    /** Coins moved as observed; buy side spent, sell side received. */
    public long getCoins() { return coins; }
    /** Tax booked as its own row; zero when the coins were already net or the item is exempt. */
    public long getTax() { return tax; }
    public List<ItemFlow> getFlows() { return flows; }
    public GrandExchangeOfferState getState() { return state; }

    /** Sum of the flows: the settlement's effect on net. */
    public long getNet()
    {
        long net = 0L;
        for (ItemFlow flow : flows)
        {
            net += flow.getValueDelta();
        }
        return net;
    }

    public String describe()
    {
        return (side == Side.BUY ? "GE buy" : "GE sell") + " · " + quantity + " × " + itemName + " · slot " + (slot + 1)
            + (coins > 0L ? " · " + coins + " gp" : "") + (tax > 0L ? " · tax " + tax : "");
    }
}
