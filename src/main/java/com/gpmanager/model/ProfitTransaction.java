package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

public class ProfitTransaction implements com.gpmanager.persistence.UnknownFieldPreservation.RetainsUnknownFields
{
    /** Unknown JSON fields carried through load → save (pass 10 step 43); never persisted directly. */
    private transient java.util.Map<String, com.google.gson.JsonElement> unknownJsonFields = java.util.Collections.emptyMap();

    @Override
    public java.util.Map<String, com.google.gson.JsonElement> getUnknownJsonFields()
    {
        return unknownJsonFields == null ? java.util.Collections.emptyMap() : unknownJsonFields;
    }

    @Override
    public void setUnknownJsonFields(java.util.Map<String, com.google.gson.JsonElement> fields)
    {
        unknownJsonFields = fields == null || fields.isEmpty() ? java.util.Collections.emptyMap() : new java.util.LinkedHashMap<>(fields);
    }

    private String id;
    private long timestampEpochMillis;
    private Long activeElapsedMillis;
    private TransactionType type;
    private TrackingContext context;
    private String note;
    private String activityName;
    private boolean counted;
    private List<ItemFlow> flows;
    private long revenue;
    private long costs;
    private long net;
    private ClassificationConfidence confidence;
    private String explanation;
    private TransactionCorrection correction;
    private long correctedAtEpochMillis;
    private String correctionReason;
    private String encounterId;
    /** Session-owned run assignment; null when the receipt is explicitly unassigned. */
    private String runId;
    /** Keeps unresolved delayed claims out of the current run until provenance resolves. */
    private boolean runAssignmentPending;
    /** Observed action wire name (B10 presentation evidence); null when unsupported/legacy. */
    private String actionKind;
    /** Per-key loot provenance; presentation metadata only, never a source of item flows. */
    private List<LootKeyProvenance> lootKeyProvenance;
    /** Ordinary untradeable key claim state; separate from PvP loot-key manifests. */
    private DeferredClaimProvenance deferredClaimProvenance;
    /** Ambiguous charge-load review context; presentation only, never accounting evidence. */
    private ChargeLoadReviewProvenance chargeLoadReviewProvenance;
    /** Associated GE offer observation; presentation only, never accounting evidence. */
    private GeOfferProvenance geOfferProvenance;

    public ProfitTransaction()
    {
        // Gson
    }

    public ProfitTransaction(
        long timestampEpochMillis,
        TransactionType type,
        TrackingContext context,
        String note,
        boolean counted,
        List<ItemFlow> flows)
    {
        this(timestampEpochMillis, null, type, context, note, "General", counted, flows);
    }

    public ProfitTransaction(
        long timestampEpochMillis,
        Long activeElapsedMillis,
        TransactionType type,
        TrackingContext context,
        String note,
        boolean counted,
        List<ItemFlow> flows)
    {
        this(timestampEpochMillis, activeElapsedMillis, type, context, note, "General", counted, flows);
    }

    public ProfitTransaction(
        long timestampEpochMillis,
        Long activeElapsedMillis,
        TransactionType type,
        TrackingContext context,
        String note,
        String activityName,
        boolean counted,
        List<ItemFlow> flows)
    {
        this(
            timestampEpochMillis,
            activeElapsedMillis,
            type,
            context,
            note,
            activityName,
            counted,
            flows,
            ClassificationConfidence.LIKELY,
            "Automatically classified from a stable inventory/equipment change.",
            null);
    }

    public ProfitTransaction(
        long timestampEpochMillis,
        Long activeElapsedMillis,
        TransactionType type,
        TrackingContext context,
        String note,
        String activityName,
        boolean counted,
        List<ItemFlow> flows,
        ClassificationConfidence confidence,
        String explanation,
        String encounterId)
    {
        this.id = UUID.randomUUID().toString();
        this.timestampEpochMillis = timestampEpochMillis;
        this.activeElapsedMillis = activeElapsedMillis;
        this.type = type;
        this.context = context;
        this.note = note == null ? "" : note;
        this.activityName = normalizeActivity(activityName);
        this.counted = counted;
        this.flows = flows == null ? new ArrayList<>() : new ArrayList<>(flows);
        this.confidence = confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
        this.explanation = explanation == null ? "" : explanation;
        this.correction = TransactionCorrection.AUTO;
        this.correctionReason = "";
        this.encounterId = encounterId;
        recalculate();
    }

    private void recalculate()
    {
        revenue = 0L;
        costs = 0L;

        if (flows == null)
        {
            flows = new ArrayList<>();
        }

        for (ItemFlow flow : flows)
        {
            if (flow.getValueDelta() > 0)
            {
                revenue = safeAdd(revenue, flow.getValueDelta());
            }
            else if (flow.getValueDelta() < 0)
            {
                costs = safeAdd(costs, safeAbs(flow.getValueDelta()));
            }
        }

        net = safeSubtract(revenue, costs);
    }

    public void applyCorrection(TransactionCorrection newCorrection, long now)
    {
        applyCorrection(newCorrection, now, "Manual correction");
    }

    public void applyCorrection(TransactionCorrection newCorrection, long now, String reason)
    {
        correction = newCorrection == null ? TransactionCorrection.AUTO : newCorrection;
        correctedAtEpochMillis = now;
        correctionReason = normalizeCorrectionReason(reason);
    }

    /**
     * Shrinks an unrecovered own-drop cost flow. Returns remaining unrecovered
     * quantity for {@code itemId} after this recovery (0 when fully recovered).
     * Does not apply a correction by itself — callers IGNORE when remaining is 0.
     */
    public long applyDropRecovery(int itemId, long quantity)
    {
        if (quantity <= 0L || flows == null || flows.isEmpty())
        {
            return remainingCostQuantity(itemId);
        }
        long toRecover = quantity;
        for (int index = 0; index < flows.size() && toRecover > 0L; index++)
        {
            ItemFlow flow = flows.get(index);
            if (flow == null || flow.getItemId() != itemId || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            long lostQty = Math.abs(flow.getQuantityDelta());
            long recover = Math.min(toRecover, lostQty);
            long remain = lostQty - recover;
            toRecover -= recover;
            if (remain <= 0L)
            {
                flows.remove(index);
                index--;
            }
            else
            {
                long unit = flow.getUnitPrice();
                long value = unit == 0L && lostQty > 0L
                    ? (flow.getValueDelta() / -lostQty) * -remain
                    : -remain * unit;
                flows.set(index, new ItemFlow(
                    flow.getItemId(),
                    flow.getItemName(),
                    -remain,
                    flow.getUnitPrice(),
                    value,
                    flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
            }
        }
        recalculate();
        return remainingCostQuantity(itemId);
    }

    /**
     * Shrinks gain flows for {@code itemId} so personal Net keeps only
     * {@code keepQuantity}. Given-away qty is removed (not booked as Used).
     * Returns remaining gain quantity for the item after the shrink.
     */
    public long applyGainKeep(int itemId, long keepQuantity)
    {
        if (flows == null || flows.isEmpty() || itemId <= 0)
        {
            return remainingGainQuantity(itemId);
        }
        long keep = Math.max(0L, keepQuantity);
        long totalGain = remainingGainQuantity(itemId);
        if (totalGain <= 0L)
        {
            return 0L;
        }
        if (keep >= totalGain)
        {
            return totalGain;
        }
        long toDrop = totalGain - keep;
        for (int index = flows.size() - 1; index >= 0 && toDrop > 0L; index--)
        {
            ItemFlow flow = flows.get(index);
            if (flow == null || flow.getItemId() != itemId || flow.getQuantityDelta() <= 0L)
            {
                continue;
            }
            long gained = flow.getQuantityDelta();
            long drop = Math.min(toDrop, gained);
            long remain = gained - drop;
            toDrop -= drop;
            if (remain <= 0L)
            {
                flows.remove(index);
            }
            else
            {
                long unit = flow.getUnitPrice();
                long value = unit == 0L && gained > 0L
                    ? (flow.getValueDelta() / gained) * remain
                    : remain * unit;
                flows.set(index, new ItemFlow(
                    flow.getItemId(),
                    flow.getItemName(),
                    remain,
                    flow.getUnitPrice(),
                    value,
                    flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
            }
        }
        recalculate();
        return remainingGainQuantity(itemId);
    }

    /** Copies current flows for correction/split undo snapshots. */
    public List<ItemFlow> copyFlowsSnapshot()
    {
        List<ItemFlow> copy = new ArrayList<>();
        if (flows == null)
        {
            return copy;
        }
        for (ItemFlow flow : flows)
        {
            if (flow == null)
            {
                continue;
            }
            copy.add(new ItemFlow(
                flow.getItemId(),
                flow.getItemName(),
                flow.getQuantityDelta(),
                flow.getUnitPrice(),
                flow.getValueDelta(),
                flow.getPriceSource(), flow.getPriceCapturedAtEpochMillis()));
        }
        return copy;
    }

    public void replaceFlows(List<ItemFlow> nextFlows)
    {
        flows = nextFlows == null ? new ArrayList<>() : new ArrayList<>(nextFlows);
        recalculate();
    }

    /**
     * Resolve an automatic charge-load Review row as a neutral transfer after
     * a same-target measured Check confirms its component quantities.
     */
    public boolean resolveChargeLoadReviewAsTransfer(List<ItemFlow> confirmedFlows)
    {
        if (getAutomaticType() != TransactionType.UNCERTAIN
            || getCorrection() != TransactionCorrection.AUTO
            || chargeLoadReviewProvenance == null
            || confirmedFlows == null || confirmedFlows.isEmpty())
        {
            return false;
        }
        type = TransactionType.TRANSFER;
        context = TrackingContext.TRANSFER;
        note = "Charge load transfer";
        activityName = "Charge load";
        counted = false;
        confidence = ClassificationConfidence.CONFIRMED;
        explanation = "A same-target measured Check confirmed these component quantities were loaded into the charged item; later measured Check differences own charge-use costs.";
        replaceFlows(confirmedFlows);
        return true;
    }

    /**
     * Stamps split provenance on explanation/reason without changing the
     * correction enum (partial keep stays counted).
     */
    public void stampSplitProvenance(String reason, long now)
    {
        String mark = reason == null ? ItemSplitAccounting.SPLIT_SHARE_MARK : reason.trim();
        if (mark.isEmpty())
        {
            mark = ItemSplitAccounting.SPLIT_SHARE_MARK;
        }
        correctionReason = normalizeCorrectionReason(mark);
        correctedAtEpochMillis = now;
        String current = getExplanation();
        if (!ItemSplitAccounting.isSplitReason(current))
        {
            explanation = current.isEmpty() ? mark : current + " — " + mark;
        }
        else if (!current.contains(mark))
        {
            explanation = current + " — " + mark;
        }
    }

    public void rewriteExplanation(String next)
    {
        explanation = next == null ? "" : next;
    }

    private long remainingCostQuantity(int itemId)
    {
        long remaining = 0L;
        if (flows == null)
        {
            return 0L;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getItemId() == itemId && flow.getQuantityDelta() < 0L)
            {
                remaining += Math.abs(flow.getQuantityDelta());
            }
        }
        return remaining;
    }

    private long remainingGainQuantity(int itemId)
    {
        long remaining = 0L;
        if (flows == null)
        {
            return 0L;
        }
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getItemId() == itemId && flow.getQuantityDelta() > 0L)
            {
                remaining += flow.getQuantityDelta();
            }
        }
        return remaining;
    }

    public void assignEncounter(String newEncounterId, boolean asPkSupplyCost)
    {
        encounterId = newEncounterId;
        if (asPkSupplyCost && getAutomaticType() == TransactionType.CONSUMPTION)
        {
            type = TransactionType.PK_SUPPLY_COST;
            activityName = "PKing";
            confidence = ClassificationConfidence.LIKELY;
            explanation = "Supply loss occurred shortly before a confirmed player-loot event.";
        }
    }

    private static String normalizeActivity(String value)
    {
        return value == null || value.trim().isEmpty() ? "General" : value.trim();
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long safeSubtract(long left, long right)
    {
        try
        {
            return Math.subtractExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return left >= right ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long safeAbs(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private long gross()
    {
        return safeAdd(revenue, costs);
    }

    public String getId()
    {
        if (id == null || id.isEmpty())
        {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public Long getActiveElapsedMillis() { return activeElapsedMillis; }

    public TransactionType getAutomaticType()
    {
        return type == null ? TransactionType.ADJUSTMENT : type;
    }

    public TransactionType getType()
    {
        switch (getCorrection())
        {
            case REVENUE:
                return TransactionType.GAIN;
            case COST:
                return TransactionType.CONSUMPTION;
            case TRANSFER:
                return TransactionType.TRANSFER;
            case IGNORE:
                return TransactionType.ADJUSTMENT;
            case AUTO:
            default:
                return getAutomaticType();
        }
    }

    public TrackingContext getContext() { return context == null ? TrackingContext.GENERIC : context; }
    public String getNote() { return note == null ? "" : note; }
    /** Presentation-only audit-row note update; accounting fields are untouched. */
    public void setNote(String note) { this.note = note == null ? "" : note; }
    public String getActivityName()
    {
        if (activityName != null && !activityName.trim().isEmpty())
        {
            return activityName.trim();
        }
        String safeNote = getNote();
        String prefix = "Loot from ";
        return safeNote.startsWith(prefix) && safeNote.length() > prefix.length()
            ? safeNote.substring(prefix.length()).trim()
            : "General";
    }

    public boolean isCounted()
    {
        TransactionCorrection safeCorrection = getCorrection();
        return safeCorrection == TransactionCorrection.REVENUE
            || safeCorrection == TransactionCorrection.COST
            || (safeCorrection == TransactionCorrection.AUTO && counted);
    }

    public List<ItemFlow> getFlows()
    {
        if (flows == null)
        {
            flows = new ArrayList<>();
        }
        return Collections.unmodifiableList(flows);
    }

    /**
     * Non-persistent presentation projection retaining this transaction's
     * identity and correction metadata while removing ineligible item flows.
     */
    public ProfitTransaction presentationProjection(Predicate<ItemFlow> eligible)
    {
        if (eligible == null || flows == null || flows.isEmpty())
        {
            return this;
        }
        List<ItemFlow> visible = new ArrayList<>();
        for (ItemFlow flow : flows)
        {
            if (flow != null && eligible.test(flow))
            {
                visible.add(flow);
            }
        }
        if (visible.size() == flows.size())
        {
            return this;
        }
        ProfitTransaction projection = new ProfitTransaction(timestampEpochMillis, activeElapsedMillis,
            type, context, note, activityName, counted, visible, confidence, explanation, encounterId);
        projection.id = id;
        projection.correction = correction;
        projection.correctedAtEpochMillis = correctedAtEpochMillis;
        projection.correctionReason = correctionReason;
        projection.actionKind = actionKind;
        projection.runId = runId;
        projection.runAssignmentPending = runAssignmentPending;
        projection.setLootKeyProvenance(getLootKeyProvenance());
        projection.setDeferredClaimProvenance(getDeferredClaimProvenance());
        projection.setChargeLoadReviewProvenance(getChargeLoadReviewProvenance());
        projection.setGeOfferProvenance(getGeOfferProvenance());
        return projection;
    }

    public long getAutomaticRevenue() { return revenue; }
    public long getAutomaticCosts() { return costs; }
    public long getAutomaticNet() { return net; }

    public long getRevenue()
    {
        switch (getCorrection())
        {
            case REVENUE:
                return gross();
            case COST:
            case TRANSFER:
            case IGNORE:
                return 0L;
            case AUTO:
            default:
                return revenue;
        }
    }

    public long getCosts()
    {
        switch (getCorrection())
        {
            case COST:
                return gross();
            case REVENUE:
            case TRANSFER:
            case IGNORE:
                return 0L;
            case AUTO:
            default:
                return costs;
        }
    }

    public long getNet()
    {
        return safeSubtract(getRevenue(), getCosts());
    }

    public ClassificationConfidence getConfidence()
    {
        return confidence == null ? ClassificationConfidence.UNCERTAIN : confidence;
    }

    public String getExplanation()
    {
        return explanation == null ? "" : explanation;
    }

    /**
     * Short, human-readable pricing provenance for transaction tooltips and
     * diagnostics. It is derived from the persisted item flows so old sessions
     * remain readable even when they predate provenance tracking.
     */
    public String getPricingSummary()
    {
        Map<ItemPriceSource, Integer> counts = new EnumMap<>(ItemPriceSource.class);
        for (ItemFlow flow : getFlows())
        {
            if (flow == null)
            {
                continue;
            }
            ItemPriceSource source = flow.getPriceSource();
            counts.put(source, counts.getOrDefault(source, 0) + 1);
        }
        if (counts.isEmpty())
        {
            return "Pricing: none";
        }

        StringBuilder summary = new StringBuilder("Pricing: ");
        boolean first = true;
        for (ItemPriceSource source : ItemPriceSource.values())
        {
            Integer count = counts.get(source);
            if (count == null || count == 0)
            {
                continue;
            }
            if (!first)
            {
                summary.append(", ");
            }
            summary.append(source).append(" (").append(count).append(")");
            first = false;
        }
        return summary.toString();
    }

    public TransactionCorrection getCorrection()
    {
        return correction == null ? TransactionCorrection.AUTO : correction;
    }

    public long getCorrectedAtEpochMillis() { return correctedAtEpochMillis; }
    public String getCorrectionReason() { return correctionReason == null ? "" : correctionReason.trim(); }
    public String getEncounterId() { return encounterId == null ? "" : encounterId; }
    public String getRunId() { return runId == null || runId.isEmpty() ? null : runId; }

    /** Assigns this receipt to an owning session run; null explicitly leaves it unassigned. */
    public void setRunId(@Nullable String runId)
    {
        this.runId = runId == null || runId.trim().isEmpty() ? null : runId.trim();
        this.runAssignmentPending = false;
    }

    /** Marks an unresolved delayed receipt pending instead of assigning it by time. */
    public void markRunAssignmentPending()
    {
        this.runId = null;
        this.runAssignmentPending = true;
    }

    public boolean isRunAssignmentPending() { return runAssignmentPending; }

    /** Observed action behind this change, or null when no honest verb was evidenced. */
    @Nullable
    public ActionKind getActionKind()
    {
        return ActionKind.fromWireName(actionKind);
    }

    /** Presentation-only stamp; never alters type, valuation, counted state or ownership. */
    public void setActionKind(@Nullable ActionKind kind)
    {
        this.actionKind = kind == null ? null : kind.wireName();
    }

    @Nullable
    public DeferredClaimProvenance getDeferredClaimProvenance()
    {
        return deferredClaimProvenance == null ? null : deferredClaimProvenance.copy();
    }

    /** Presentation metadata only; does not alter type, flows, value or counted state. */
    public void setDeferredClaimProvenance(@Nullable DeferredClaimProvenance provenance)
    {
        deferredClaimProvenance = provenance == null ? null : provenance.copy();
    }

    public boolean hasPendingDeferredClaim()
    {
        return deferredClaimProvenance != null && deferredClaimProvenance.isPending();
    }

    public String getDeferredClaimSummary()
    {
        return deferredClaimProvenance == null ? "" : deferredClaimProvenance.summary();
    }

    @Nullable
    public ChargeLoadReviewProvenance getChargeLoadReviewProvenance()
    {
        return chargeLoadReviewProvenance == null ? null : chargeLoadReviewProvenance.copy();
    }

    /** Presentation metadata only; does not alter type, flows, value or counted state. */
    public void setChargeLoadReviewProvenance(@Nullable ChargeLoadReviewProvenance provenance)
    {
        chargeLoadReviewProvenance = provenance == null ? null : provenance.copy();
    }

    @Nullable
    public GeOfferProvenance getGeOfferProvenance()
    {
        return geOfferProvenance == null ? null : geOfferProvenance.copy();
    }

    /** Presentation metadata only; does not alter type, flows, value or counted state. */
    public void setGeOfferProvenance(@Nullable GeOfferProvenance provenance)
    {
        geOfferProvenance = provenance == null ? null : provenance.copy();
    }

    public List<LootKeyProvenance> getLootKeyProvenance()
    {
        if (lootKeyProvenance == null)
        {
            lootKeyProvenance = new ArrayList<>();
        }
        List<LootKeyProvenance> copy = new ArrayList<>();
        for (LootKeyProvenance entry : lootKeyProvenance)
        {
            if (entry != null)
            {
                copy.add(entry.copy());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    /** Presentation metadata only; does not alter type, flows, value or counted state. */
    public void setLootKeyProvenance(List<LootKeyProvenance> provenance)
    {
        lootKeyProvenance = new ArrayList<>();
        if (provenance != null)
        {
            for (LootKeyProvenance entry : provenance)
            {
                if (entry != null)
                {
                    lootKeyProvenance.add(entry.copy());
                }
            }
        }
    }

    public void addLootKeyProvenance(LootKeyProvenance provenance)
    {
        if (provenance == null)
        {
            return;
        }
        if (lootKeyProvenance == null)
        {
            lootKeyProvenance = new ArrayList<>();
        }
        lootKeyProvenance.add(provenance.copy());
    }

    /** True while this deferred key audit row is still waiting for a claim or death. */
    public boolean hasPendingLootKeyProvenance()
    {
        if (lootKeyProvenance == null)
        {
            return false;
        }
        for (LootKeyProvenance entry : lootKeyProvenance)
        {
            if (entry != null && !entry.isClaimReceipt()
                && entry.getStatus() == LootKeyProvenance.Status.PENDING
                && (entry.getRemainingKeyQuantity() > 0L || entry.hasUnclaimedManifest()))
            {
                return true;
            }
        }
        return false;
    }

    /** Presentation-only loot-key provenance for ledger receipts and tooltips. */
    public String getLootKeySummary()
    {
        if (lootKeyProvenance == null || lootKeyProvenance.isEmpty())
        {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (LootKeyProvenance entry : lootKeyProvenance)
        {
            if (entry == null)
            {
                continue;
            }
            String line = entry.ledgerSummary();
            if (line == null || line.trim().isEmpty())
            {
                continue;
            }
            if (summary.length() > 0)
            {
                summary.append("; ");
            }
            summary.append(line);
        }
        return summary.toString();
    }

    private static String normalizeCorrectionReason(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return "Manual correction";
        }
        String trimmed = value.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
