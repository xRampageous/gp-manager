package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PkEncounter
{
    private static final int CURRENT_FINANCIAL_SUMMARY_VERSION = 2;

    private String id;
    private PkEncounterType type;
    private long timestampEpochMillis;
    private String label;
    /** Nullable region/zone text for presentation only; never an accounting input. */
    private String locationLabel;
    private ClassificationConfidence confidence;
    private String explanation;
    private List<String> transactionIds;
    /** Zero means a legacy encounter whose financial summary has not been rebuilt. */
    private int financialSummaryVersion;
    /** Contributions still backed by transaction detail, indexed for correction and undo. */
    private Map<String, FinancialContribution> financialContributions;
    /** Contributions whose transaction detail has been compacted. */
    private long retainedFinancialNetGp;
    private long retainedFinancialCostsGp;
    private long retainedFinancialSuppliesCostsGp;
    private boolean financialCostSplitComplete = true;

    public PkEncounter()
    {
        // Gson
    }

    public PkEncounter(
        PkEncounterType type,
        long timestampEpochMillis,
        String label,
        ClassificationConfidence confidence,
        String explanation)
    {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.timestampEpochMillis = timestampEpochMillis;
        this.label = label == null || label.trim().isEmpty()
            ? (type == PkEncounterType.DEATH ? "Player death" : "Player kill")
            : label.trim();
        this.confidence = confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
        this.explanation = explanation == null ? "" : explanation;
        this.transactionIds = new ArrayList<>();
        this.financialSummaryVersion = CURRENT_FINANCIAL_SUMMARY_VERSION;
        this.financialContributions = new LinkedHashMap<>();
    }

    public void addTransactionId(String transactionId)
    {
        if (transactionId == null || transactionId.isEmpty())
        {
            return;
        }
        ensureTransactionIds();
        if (!transactionIds.contains(transactionId))
        {
            transactionIds.add(transactionId);
        }
    }

    public void removeTransactionId(String transactionId)
    {
        ensureTransactionIds();
        transactionIds.remove(transactionId);
    }

    private void ensureTransactionIds()
    {
        if (transactionIds == null)
        {
            transactionIds = new ArrayList<>();
        }
    }

    public String getId()
    {
        if (id == null || id.isEmpty())
        {
            id = UUID.randomUUID().toString();
        }
        return id;
    }
    public PkEncounterType getType() { return type == null ? PkEncounterType.KILL : type; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public String getLabel() { return label == null ? "PK encounter" : label; }
    public String getLocationLabel() { return locationLabel; }
    public void setLocationLabel(String value)
    {
        locationLabel = value == null || value.trim().isEmpty() ? null : value.trim();
    }
    public ClassificationConfidence getConfidence()
    {
        return confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
    }
    public String getExplanation() { return explanation == null ? "" : explanation; }
    public List<String> getTransactionIds()
    {
        ensureTransactionIds();
        return Collections.unmodifiableList(transactionIds);
    }

    /**
     * Replaces this transaction's live financial snapshot using the shared,
     * correction-aware accounting projection. Repeated observations of the
     * same transaction id therefore update rather than double count it.
     *
     * <p>Legacy encounters stay unavailable until the caller rebuilds their
     * entire summary with {@link #rebuildFinancialContributions(Iterable)}.</p>
     */
    public void setFinancialContribution(ProfitTransaction transaction)
    {
        if (!isFinancialSummaryAvailable() || transaction == null)
        {
            return;
        }
        String transactionId = transaction.getId();
        if (transactionId == null || transactionId.trim().isEmpty())
        {
            return;
        }
        ensureFinancialContributions();
        financialContributions.put(transactionId, contributionOf(transaction));
    }

    /** Removes one un-compacted receipt contribution, for correction undo. */
    public boolean removeFinancialContribution(String transactionId)
    {
        if (!isFinancialSummaryAvailable() || transactionId == null)
        {
            return false;
        }
        ensureFinancialContributions();
        return financialContributions.remove(transactionId) != null;
    }

    /**
     * Moves one detailed receipt contribution into durable retained totals
     * before its transaction is compacted. Compacted contributions are no
     * longer individually undoable; callers should only compact receipts that
     * have left the correction/undo window.
     */
    public boolean compactFinancialContribution(String transactionId)
    {
        if (!isFinancialSummaryAvailable() || transactionId == null)
        {
            return false;
        }
        ensureFinancialContributions();
        FinancialContribution contribution = financialContributions.remove(transactionId);
        if (contribution == null)
        {
            return false;
        }
        retainedFinancialNetGp = safeAdd(retainedFinancialNetGp, contribution.netGp);
        retainedFinancialCostsGp = safeAdd(retainedFinancialCostsGp, contribution.costsGp);
        if (contribution.costSplitAvailable)
        {
            retainedFinancialSuppliesCostsGp = safeAdd(retainedFinancialSuppliesCostsGp,
                contribution.suppliesCostsGp);
        }
        else
        {
            financialCostSplitComplete = false;
        }
        return true;
    }

    /**
     * Atomically rebuilds the encounter summary from all receipts still
     * attributable to it. Callers must verify that the iterable is complete;
     * omitted or compacted history cannot be inferred from transaction ids.
     */
    public void rebuildFinancialContributions(Iterable<ProfitTransaction> transactions)
    {
        Map<String, FinancialContribution> rebuilt = new LinkedHashMap<>();
        if (transactions != null)
        {
            for (ProfitTransaction transaction : transactions)
            {
                if (transaction == null)
                {
                    continue;
                }
                String transactionId = transaction.getId();
                if (transactionId != null && !transactionId.trim().isEmpty())
                {
                    rebuilt.put(transactionId, contributionOf(transaction));
                }
            }
        }
        financialContributions = rebuilt;
        retainedFinancialNetGp = 0L;
        retainedFinancialCostsGp = 0L;
        retainedFinancialSuppliesCostsGp = 0L;
        financialCostSplitComplete = true;
        financialSummaryVersion = CURRENT_FINANCIAL_SUMMARY_VERSION;
    }

    public int getFinancialSummaryVersion()
    {
        return financialSummaryVersion;
    }

    public boolean isFinancialSummaryAvailable()
    {
        return financialSummaryVersion == CURRENT_FINANCIAL_SUMMARY_VERSION;
    }

    /** Net GP across retained and per-transaction contributions; zero if unavailable. */
    public long getFinancialNetGp()
    {
        if (!isFinancialSummaryAvailable())
        {
            return 0L;
        }
        long total = retainedFinancialNetGp;
        for (FinancialContribution contribution : getFinancialContributionSnapshots().values())
        {
            total = safeAdd(total, contribution.netGp);
        }
        return total;
    }

    /** Total counted costs associated with this encounter; zero if unavailable. */
    public long getFinancialCostGp()
    {
        if (!isFinancialSummaryAvailable())
        {
            return 0L;
        }
        long total = retainedFinancialCostsGp;
        for (FinancialContribution contribution : getFinancialContributionSnapshots().values())
        {
            total = safeAdd(total, contribution.costsGp);
        }
        return total;
    }

    /** Encounter loss follows the existing PK metric definition: max(0, costs - revenue). */
    public long getFinancialLossGp()
    {
        long net = getFinancialNetGp();
        return net == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(0L, -net);
    }

    /** Supplies among attached costs; availability is separate from a zero value. */
    public long getFinancialSuppliesCostGp()
    {
        if (!isFinancialSummaryAvailable()) return 0L;
        long total = retainedFinancialSuppliesCostsGp;
        for (FinancialContribution contribution : getFinancialContributionSnapshots().values())
        {
            total = safeAdd(total, contribution.suppliesCostsGp);
        }
        return total;
    }

    public boolean isFinancialCostSplitAvailable()
    {
        if (!isFinancialSummaryAvailable() || !financialCostSplitComplete) return false;
        for (FinancialContribution contribution : getFinancialContributionSnapshots().values())
        {
            if (!contribution.costSplitAvailable) return false;
        }
        return true;
    }

    /** Immutable view of detailed per-receipt snapshots; retained rows are aggregated. */
    public Map<String, FinancialContribution> getFinancialContributionSnapshots()
    {
        if (!isFinancialSummaryAvailable())
        {
            return Collections.emptyMap();
        }
        ensureFinancialContributions();
        return Collections.unmodifiableMap(new LinkedHashMap<>(financialContributions));
    }

    /** Retained net aggregate (zero when the versioned summary is unavailable). */
    public long getRetainedFinancialNetGp()
    {
        return isFinancialSummaryAvailable() ? retainedFinancialNetGp : 0L;
    }

    /** Retained cost aggregate (zero when the versioned summary is unavailable). */
    public long getRetainedFinancialCostsGp()
    {
        return isFinancialSummaryAvailable() ? retainedFinancialCostsGp : 0L;
    }

    private static FinancialContribution contributionOf(ProfitTransaction transaction)
    {
        AccountingProjection.TransactionAmounts amounts =
            AccountingProjection.transaction(transaction, null);
        if (!amounts.isAvailable() || !amounts.isIncluded())
        {
            return new FinancialContribution(0L, 0L, 0L, true);
        }
        if (amounts.getCosts() <= 0L)
        {
            return new FinancialContribution(amounts.getNet(), 0L, 0L, true);
        }
        long supplies = 0L;
        if (transaction.getFlows().isEmpty())
        {
            return new FinancialContribution(amounts.getNet(), amounts.getCosts(), 0L, false);
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null) continue;
            AccountingProjection.TransactionAmounts flowAmounts =
                AccountingProjection.flow(transaction, flow, null);
            if (!flowAmounts.isAvailable())
            {
                return new FinancialContribution(amounts.getNet(), amounts.getCosts(), 0L, false);
            }
            if (flowAmounts.isIncluded() && flowAmounts.getCosts() > 0L
                && CostKind.of(transaction, flow) == CostKind.SUPPLIES)
            {
                supplies = safeAdd(supplies, flowAmounts.getCosts());
            }
        }
        return new FinancialContribution(amounts.getNet(), amounts.getCosts(), supplies,
            supplies <= amounts.getCosts());
    }

    private void ensureFinancialContributions()
    {
        if (financialContributions == null)
        {
            financialContributions = new LinkedHashMap<>();
        }
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** Immutable persisted accounting snapshot for one transaction id. */
    public static final class FinancialContribution
    {
        private long netGp;
        private long costsGp;
        private long suppliesCostsGp;
        private boolean costSplitAvailable;

        private FinancialContribution()
        {
            // Gson
        }

        private FinancialContribution(long netGp, long costsGp)
        {
            this(netGp, costsGp, 0L, false);
        }

        private FinancialContribution(long netGp, long costsGp, long suppliesCostsGp,
            boolean costSplitAvailable)
        {
            this.netGp = netGp;
            this.costsGp = Math.max(0L, costsGp);
            this.suppliesCostsGp = Math.max(0L, suppliesCostsGp);
            this.costSplitAvailable = costSplitAvailable;
        }

        public long getNetGp() { return netGp; }
        public long getCostsGp() { return Math.max(0L, costsGp); }
        public long getSuppliesCostsGp() { return Math.max(0L, suppliesCostsGp); }
        public boolean isCostSplitAvailable() { return costSplitAvailable; }
    }
}
