package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.ItemPriceSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

@Singleton
public class ItemValuationService implements FlowValuator
{
    private static final Logger log = LoggerFactory.getLogger(ItemValuationService.class);
    private final java.util.function.IntFunction<ItemComposition> compositions;
    private final java.util.function.IntUnaryOperator marketPrices;
    private final GpManagerConfig config;

    @Inject
    public ItemValuationService(ItemManager itemManager, GpManagerConfig config)
    {
        this(config, itemManager::getItemComposition, itemManager::getItemPrice);
    }

    ItemValuationService(GpManagerConfig config,
        java.util.function.IntFunction<ItemComposition> compositions,
        java.util.function.IntUnaryOperator marketPrices)
    {
        this.config = config;
        this.compositions = compositions;
        this.marketPrices = marketPrices;
    }

    @Override
    public List<ItemFlow> value(Map<Integer, Long> quantityDeltas)
    {
        return value(quantityDeltas, 0L);
    }

    @Override
    public List<ItemFlow> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis)
    {
        List<ItemFlow> flows = new ArrayList<>();
        Set<Integer> ignoredIds = ItemRuleParser.parseIgnoredIds(config.ignoredItemIds());
        Map<Integer, Integer> priceOverrides = ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());

        for (Map.Entry<Integer, Long> entry : quantityDeltas.entrySet())
        {
            ItemFlow flow = valueOne(entry.getKey(), entry.getValue(), ignoredIds, priceOverrides,
                priceCapturedAtEpochMillis);
            if (flow != null)
            {
                flows.add(flow);
            }
        }

        flows.sort(Comparator.comparingLong((ItemFlow flow) -> safeAbsolute(flow.getValueDelta())).reversed());
        return flows;
    }

    /**
     * Returns the same unit-price decision used for a receipt, without applying
     * accounting ignore-id rules. Wealth is a read-only view of held items and
     * must not silently omit an item because it is excluded from Net.
     */
    public ItemFlow quoteForWealth(int itemId, long priceCapturedAtEpochMillis)
    {
        Map<Integer, Integer> priceOverrides = ItemRuleParser.parsePriceOverrides(config.manualPriceOverrides());
        return valueOne(itemId, 1L, java.util.Collections.emptySet(), priceOverrides,
            priceCapturedAtEpochMillis);
    }

    private ItemFlow valueOne(int itemId, long quantityDelta, Set<Integer> ignoredIds,
        Map<Integer, Integer> priceOverrides, long priceCapturedAtEpochMillis)
    {
        if (ignoredIds.contains(itemId))
        {
            return null;
        }

        boolean deferredClaim = KeyChestCatalogue.isDeferredClaimKey(itemId);

        try
        {
            ItemComposition composition = null;
            try
            {
                composition = compositions.apply(itemId);
            }
            catch (RuntimeException ex)
            {
                log.debug("Item definition unavailable for " + itemId, ex);
            }
            String name = composition == null || composition.getName() == null
                ? "Item " + itemId
                : composition.getName();
            boolean manualOverride = priceOverrides.containsKey(itemId);
            // A user override is authoritative even before RuneLite prices load.
            int marketPrice = 0;
            if (!manualOverride && !deferredClaim)
            {
                try
                {
                    marketPrice = Math.max(0, marketPrices.applyAsInt(itemId));
                }
                catch (RuntimeException ex)
                {
                    log.debug("Market price unavailable for " + itemId, ex);
                }
            }
            int highAlchemyPrice = Math.max(0, composition == null ? 0 : composition.getHaPrice());
            int unitPrice;
            ItemPriceSource priceSource;
            if (deferredClaim)
            {
                // A non-tradeable key is an unopened claim, not held wealth. Its
                // contents become ordinary valued inventory gains only on receipt.
                unitPrice = 0;
                priceSource = ItemPriceSource.DEFERRED_CLAIM;
            }
            else if (manualOverride)
            {
                unitPrice = priceOverrides.get(itemId);
                priceSource = ItemPriceSource.MANUAL_OVERRIDE;
            }
            else if (config.useCurrencyProxies() && CurrencyProxyCatalogue.isMapped(itemId))
            {
                CurrencyProxyCatalogue.Proxy proxy = CurrencyProxyCatalogue.proxyFor(itemId);
                int counterpartPrice = 0;
                if (proxy != null)
                {
                    try
                    {
                        counterpartPrice = Math.max(0, marketPrices.applyAsInt(proxy.counterpartItemId));
                    }
                    catch (RuntimeException ex)
                    {
                        log.debug("Counterpart price unavailable for " + proxy.counterpartItemId, ex);
                    }
                }
                unitPrice = CurrencyProxyCatalogue.unitPriceFromCounterpart(proxy, counterpartPrice);
                if (unitPrice > 0)
                {
                    priceSource = ItemPriceSource.CURRENCY_PROXY;
                }
                else if (CurrencyProxyCatalogue.isCoinAnchored(itemId))
                {
                    // Coin-anchored catalogue rows are honesty placeholders — stay
                    // unpriced until the user sets a manual override (which already
                    // short-circuits above). Do not invent GE/HA for these.
                    unitPrice = 0;
                    priceSource = ItemPriceSource.UNPRICED;
                }
                else if (marketPrice > 0)
                {
                    unitPrice = marketPrice;
                    priceSource = ItemPriceSource.GRAND_EXCHANGE;
                }
                else if (config.useHighAlchemyFallback() && highAlchemyPrice > 0)
                {
                    unitPrice = highAlchemyPrice;
                    priceSource = ItemPriceSource.HIGH_ALCHEMY;
                }
                else
                {
                    unitPrice = 0;
                    priceSource = ItemPriceSource.UNPRICED;
                }
            }
            else if (marketPrice > 0)
            {
                unitPrice = marketPrice;
                priceSource = ItemPriceSource.GRAND_EXCHANGE;
            }
            else if (config.useHighAlchemyFallback() && highAlchemyPrice > 0)
            {
                unitPrice = highAlchemyPrice;
                priceSource = ItemPriceSource.HIGH_ALCHEMY;
            }
            else
            {
                unitPrice = 0;
                priceSource = ItemPriceSource.UNPRICED;
            }

            if (unitPrice == 0 && config.ignoreUnpricedItems() && !deferredClaim)
            {
                return null;
            }

            long valueDelta = safeMultiply(quantityDelta, unitPrice);
            // Only a non-zero selected price is a captured price. Unpriced/deferred flows
            // deliberately retain zero so consumers cannot present a fabricated capture time.
            long capturedAt = unitPrice > 0 && priceSource != ItemPriceSource.UNKNOWN
                ? Math.max(0L, priceCapturedAtEpochMillis)
                : 0L;
            return new ItemFlow(itemId, name, quantityDelta, unitPrice, valueDelta, priceSource, capturedAt);
        }
        catch (RuntimeException ex)
        {
            log.debug("Unable to value item " + itemId, ex);
            if (config.ignoreUnpricedItems() && !deferredClaim)
            {
                return null;
            }
            return new ItemFlow(
                itemId,
                "Item " + itemId,
                quantityDelta,
                0,
                0L,
                deferredClaim ? ItemPriceSource.DEFERRED_CLAIM : ItemPriceSource.UNPRICED);
        }
    }

    private static long safeAbsolute(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static long safeMultiply(long quantity, int price)
    {
        try
        {
            return Math.multiplyExact(quantity, (long) price);
        }
        catch (ArithmeticException ex)
        {
            return quantity >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
