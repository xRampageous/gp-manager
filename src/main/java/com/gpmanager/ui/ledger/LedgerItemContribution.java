package com.gpmanager.ui.ledger;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionCorrection;
import java.util.Objects;

/**
 * One item-flow contribution extracted from a retained transaction for By-item
 * presentation. Does not mutate accounting.
 */
public final class LedgerItemContribution
{
    private final String contributionId;
    private final String transactionId;
    private final long timestampEpochMillis;
    private final int itemId;
    private final String itemName;
    private final long quantityDelta;
    private final long valueDelta;
    private final ItemPriceSource priceSource;
    private final boolean counted;
    private final TransactionCorrection correction;
    private final String confidenceName;
    private final boolean needsReview;
    private final int flowCountInTransaction;
    private final String explanation;
    private final String correctionReason;
    private final String typeName;
    private final String lootKeySummary;

    public LedgerItemContribution(
        String contributionId,
        String transactionId,
        long timestampEpochMillis,
        int itemId,
        String itemName,
        long quantityDelta,
        long valueDelta,
        ItemPriceSource priceSource,
        boolean counted,
        TransactionCorrection correction,
        String confidenceName,
        boolean needsReview,
        int flowCountInTransaction)
    {
        this(contributionId, transactionId, timestampEpochMillis, itemId, itemName, quantityDelta,
            valueDelta, priceSource, counted, correction, confidenceName, needsReview,
            flowCountInTransaction, "", "", "", "");
    }

    public LedgerItemContribution(
        String contributionId,
        String transactionId,
        long timestampEpochMillis,
        int itemId,
        String itemName,
        long quantityDelta,
        long valueDelta,
        ItemPriceSource priceSource,
        boolean counted,
        TransactionCorrection correction,
        String confidenceName,
        boolean needsReview,
        int flowCountInTransaction,
        String explanation,
        String correctionReason,
        String typeName)
    {
        this(contributionId, transactionId, timestampEpochMillis, itemId, itemName, quantityDelta,
            valueDelta, priceSource, counted, correction, confidenceName, needsReview,
            flowCountInTransaction, explanation, correctionReason, typeName, "");
    }

    public LedgerItemContribution(
        String contributionId,
        String transactionId,
        long timestampEpochMillis,
        int itemId,
        String itemName,
        long quantityDelta,
        long valueDelta,
        ItemPriceSource priceSource,
        boolean counted,
        TransactionCorrection correction,
        String confidenceName,
        boolean needsReview,
        int flowCountInTransaction,
        String explanation,
        String correctionReason,
        String typeName,
        String lootKeySummary)
    {
        this.contributionId = contributionId == null ? "" : contributionId;
        this.transactionId = transactionId == null ? "" : transactionId;
        this.timestampEpochMillis = timestampEpochMillis;
        this.itemId = itemId;
        this.itemName = itemName == null || itemName.trim().isEmpty() ? "Unknown item" : itemName.trim();
        this.quantityDelta = quantityDelta;
        this.valueDelta = valueDelta;
        this.priceSource = priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
        this.counted = counted;
        this.correction = correction == null ? TransactionCorrection.AUTO : correction;
        this.confidenceName = confidenceName == null ? "" : confidenceName;
        this.needsReview = needsReview;
        this.flowCountInTransaction = Math.max(1, flowCountInTransaction);
        this.explanation = explanation == null ? "" : explanation;
        this.correctionReason = correctionReason == null ? "" : correctionReason;
        this.typeName = typeName == null ? "" : typeName;
        this.lootKeySummary = lootKeySummary == null ? "" : lootKeySummary;
    }

    public static LedgerItemContribution from(ProfitTransaction transaction, ItemFlow flow, int flowIndex)
    {
        if (transaction == null || flow == null)
        {
            return null;
        }
        boolean unpriced = flow.getPriceSource() == ItemPriceSource.UNKNOWN
            || flow.getPriceSource() == ItemPriceSource.UNPRICED;
        boolean uncertain = transaction.getCorrection() == TransactionCorrection.AUTO
            && transaction.getConfidence() == com.gpmanager.model.ClassificationConfidence.UNCERTAIN;
        String explanation = transaction.getExplanation() == null ? "" : transaction.getExplanation();
        String correctionReason = transaction.getCorrectionReason() == null
            ? "" : transaction.getCorrectionReason();
        boolean chargeOrContainerEdge = isChargeOrContainerReviewEdge(explanation, correctionReason);
        String id = transaction.getId() + "#" + flowIndex + "#" + flow.getItemId();
        return new LedgerItemContribution(
            id,
            transaction.getId(),
            transaction.getTimestampEpochMillis(),
            flow.getItemId(),
            flow.getItemName(),
            flow.getQuantityDelta(),
            flow.getValueDelta(),
            flow.getPriceSource(),
            transaction.isCounted(),
            transaction.getCorrection(),
            transaction.getConfidence() == null ? "" : transaction.getConfidence().name(),
            uncertain || unpriced || chargeOrContainerEdge,
            transaction.getFlows() == null ? 1 : transaction.getFlows().size(),
            explanation,
            correctionReason,
            transaction.getType() == null ? "" : transaction.getType().name(),
            transaction.getLootKeySummary());
    }

    private static boolean isChargeOrContainerReviewEdge(String explanation, String correctionReason)
    {
        String blob = ((explanation == null ? "" : explanation) + " "
            + (correctionReason == null ? "" : correctionReason)).toLowerCase();
        return blob.contains("calibrat")
            || blob.contains("uncertain") && (blob.contains("plank") || blob.contains("sack")
                || blob.contains("pouch") || blob.contains("bag") || blob.contains("container"))
            || blob.contains("charge") && blob.contains("warn");
    }

    /** One stable parent for each actual item identity. */
    public String groupKey()
    {
        return Integer.toString(itemId);
    }

    public boolean isGain()
    {
        return quantityDelta > 0L;
    }

    public boolean isCost()
    {
        return quantityDelta < 0L;
    }

    public boolean isUnpriced()
    {
        return priceSource == ItemPriceSource.UNKNOWN || priceSource == ItemPriceSource.UNPRICED;
    }

    public String getContributionId()
    {
        return contributionId;
    }

    public String getTransactionId()
    {
        return transactionId;
    }

    public long getTimestampEpochMillis()
    {
        return timestampEpochMillis;
    }

    public int getItemId()
    {
        return itemId;
    }

    public String getItemName()
    {
        return itemName;
    }

    public long getQuantityDelta()
    {
        return quantityDelta;
    }

    public long getValueDelta()
    {
        return valueDelta;
    }

    public ItemPriceSource getPriceSource()
    {
        return priceSource;
    }

    public boolean isCounted()
    {
        return counted;
    }

    public TransactionCorrection getCorrection()
    {
        return correction;
    }

    public boolean isNeedsReview()
    {
        return needsReview;
    }

    public int getFlowCountInTransaction()
    {
        return flowCountInTransaction;
    }

    public String getExplanation()
    {
        return explanation;
    }

    public String getCorrectionReason()
    {
        return correctionReason;
    }

    public String getTypeName()
    {
        return typeName;
    }

    public String getLootKeySummary()
    {
        return lootKeySummary;
    }

    /** Tooltip / why-counted line from stored records only — never invents confidence. */
    public String whyCountedSummary()
    {
        StringBuilder sb = new StringBuilder();
        if (!typeName.isEmpty())
        {
            sb.append(typeName);
        }
        sb.append(counted ? " · counted" : " · not counted");
        if (!confidenceName.isEmpty())
        {
            sb.append(" · ").append(confidenceName);
        }
        sb.append(" · price ").append(priceSourceLabel(priceSource));
        if (priceSource == ItemPriceSource.CURRENCY_PROXY)
        {
            String proxyNote = com.gpmanager.engine.CurrencyProxyCatalogue.whyCountedNote(itemId);
            if (!proxyNote.isEmpty())
            {
                sb.append(" (").append(proxyNote).append(")");
            }
        }
        appendProvenanceTag(sb, explanation, correctionReason);
        if (!explanation.isEmpty())
        {
            sb.append(" — ").append(explanation);
        }
        if (correction != TransactionCorrection.AUTO)
        {
            sb.append(" · correction ").append(correction);
            if (!correctionReason.isEmpty())
            {
                sb.append(": ").append(correctionReason);
            }
        }
        else if (com.gpmanager.model.ItemSplitAccounting.isSplitReason(correctionReason)
            && (explanation.isEmpty() || !explanation.contains(correctionReason)))
        {
            sb.append(" · ").append(correctionReason);
        }
        if (!lootKeySummary.isEmpty())
        {
            sb.append(" · ").append(lootKeySummary);
        }
        return sb.toString();
    }

    private static void appendProvenanceTag(StringBuilder sb, String explanation, String correctionReason)
    {
        String blob = ((explanation == null ? "" : explanation) + " "
            + (correctionReason == null ? "" : correctionReason)).toLowerCase();
        if (blob.contains("ge tax") || blob.contains("sell tax"))
        {
            sb.append(" · GE tax");
        }
        if (blob.contains("charge") || blob.contains("calibrat"))
        {
            sb.append(" · charge");
        }
        if (blob.contains("death") && (blob.contains("fee") || blob.contains("reclaim") || blob.contains("grave")))
        {
            sb.append(" · death fee");
        }
        if (blob.contains("loot key") || blob.contains("loot-key") || blob.contains("deferred"))
        {
            sb.append(" · loot-key deferred");
        }
        if (com.gpmanager.model.ItemSplitAccounting.isSplitReason(explanation)
            || com.gpmanager.model.ItemSplitAccounting.isSplitReason(correctionReason))
        {
            sb.append(" · split share");
        }
    }

    private static String priceSourceLabel(ItemPriceSource source)
    {
        if (source == null)
        {
            return "Unknown";
        }
        switch (source)
        {
            case MANUAL_OVERRIDE:
                return "Manual override";
            case CURRENCY_PROXY:
                return "Currency proxy";
            case GRAND_EXCHANGE:
                return "RuneLite market";
            case HIGH_ALCHEMY:
                return "High alchemy";
            case DEFERRED_CLAIM:
                return "Claim on open";
            case UNPRICED:
                return "Unpriced";
            case UNKNOWN:
            default:
                return "Unknown";
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof LedgerItemContribution))
        {
            return false;
        }
        return Objects.equals(contributionId, ((LedgerItemContribution) o).contributionId);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(contributionId);
    }
}
