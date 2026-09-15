package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.BiPredicate;

public class ProfitSession implements com.gpmanager.persistence.UnknownFieldPreservation.RetainsUnknownFields
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

    private static final int MAX_UNDO_HISTORY = 32;
    private String id;
    private String name;
    private String activityHint;
    private SessionMode mode;
    /** Explicit owner provenance; legacy saves intentionally deserialize as UNKNOWN. */
    private SessionOwnerKind ownerKind = SessionOwnerKind.UNKNOWN;
    /** Runtime-only signal/revision for profile totals derived from session-day summaries. */
    private transient Runnable analyticsChangeListener;
    /** Active-time-only deltas can update one or more cached local-day rows. */
    private transient Consumer<List<AnalyticsActiveTimeDelta>> analyticsActiveTimeListener;
    private transient long analyticsRevision;
    /** Nullable explicit category; legacy sessions fall back to {@link #getCategory()}'s derivation. */
    private SessionCategory categoryOverride;
    private long startedAtEpochMillis;
    private long endedAtEpochMillis;
    /** Null while open and for legacy sessions whose close reason was never recorded. */
    private SessionEndReason endReason;
    private boolean paused;
    private boolean stopped;
    private PauseReason pauseReason = PauseReason.NONE;
    private long pausedAtEpochMillis;
    private long totalPausedMillis;
    private int actionCount;
    private Map<String, Integer> activityActions;
    private List<ActivitySegment> activitySegments;
    private List<ProfitTransaction> transactions;
    private List<PkEncounter> pkEncounters;
    private List<CorrectionRecord> correctionHistory;
    private List<UndoRecord> undoHistory;
    /** Run boundaries and run-attributed retained projections are session-owned metadata. */
    private List<Run> runs;
    private String currentRunId;
    private Map<String, RunRetainedAggregate> runRetainedAggregates;
    private int transactionLimit;
    private long retainedRevenue;
    private long retainedCosts;
    private long retainedSuppliesCosts;
    /** Zero/false when an old compacted profile did not retain cost categories. */
    private int retainedCostSplitVersion;
    private boolean retainedCostSplitComplete;
    private int retainedCountedTransactions;
    private int retainedTransfers;
    /** Compaction-safe count of counted receipt rows that contain a positive gain. */
    private long retainedGainedReceiptCount;
    private int retainedGainedReceiptCountVersion;
    private boolean retainedGainedReceiptCountComplete;
    // Item-level compacted accounting lets presentation filters remain correct
    // after detail rows are evicted. Null is retained for Gson compatibility
    // with saves written before this field existed.
    private Map<Integer, RetainedItemContribution> retainedItemContributions;
    // Version 1 records item totals after applying any manual transaction
    // correction. Missing/old versions cannot safely be recalculated.
    private int retainedItemContributionsVersion;
    private boolean retainedItemContributionsComplete;
    private long compactedTransactionCount;
    private List<String> tags;
    private boolean excludedFromAverages;
    private boolean recoveredFromCrash;
    private String notes;
    private boolean favorite;
    /** Final, presentation-only party snapshot; null on sessions without a saved party summary. */
    private PartySummary partySummary;
    // Nullable by design: a target is an owner-local presentation goal, never
    // an accounting input or generated transaction.
    private Long profitTargetGp;
    // UTC daily summaries are bounded to the most recent 400 days. Older
    // saves begin collecting from their first post-migration observation.
    private List<TrackingDaySummary> analyticsDays;
    /** Durable observed encounter evidence; separate from receipts and HUD lifetime. */
    private EncounterTotals encounterTotals = new EncounterTotals();
    private boolean encounterTotalsAvailable;
    /** Bounded date-addressable place time; absent on profiles saved before schema 22. */
    private PkLocationLedger pkLocationLedger;
    /** False when the bounded day list has evicted item quantities needed for lifetime views. */
    private boolean analyticsItemHistoryComplete = true;
    /** Local-day cutoff whose PvM evidence was discarded by a zone/rebase rebuild. */
    private String encounterDailyUnavailableThroughDay = "";
    private String encounterDailyUnavailableZoneId = "";
    private long analyticsStartedAtEpochMillis;
    private long analyticsLastActiveAtEpochMillis;
    /** IANA zone used for this session's daily summaries; legacy summaries are UTC. */
    private String analyticsZoneId = "UTC";
    private static final int MAX_ANALYTICS_DAYS = 400;

    public ProfitSession()
    {
        // Gson
    }

    public ProfitSession(String name, long startedAtEpochMillis)
    {
        this(name, startedAtEpochMillis, SessionMode.GENERAL);
    }

    public ProfitSession(String name, long startedAtEpochMillis, SessionMode mode)
    {
        this.id = UUID.randomUUID().toString();
        this.name = normalizeName(name);
        this.mode = mode == null ? SessionMode.GENERAL : mode;
        this.activityHint = this.mode == SessionMode.PK ? "PKing" : "General";
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.activityActions = new LinkedHashMap<>();
        this.activitySegments = new ArrayList<>();
        this.activitySegments.add(new ActivitySegment(this.activityHint, startedAtEpochMillis));
        this.transactions = new ArrayList<>();
        this.pkEncounters = new ArrayList<>();
        this.correctionHistory = new ArrayList<>();
        this.undoHistory = new ArrayList<>();
        this.runs = new ArrayList<>();
        this.runRetainedAggregates = new LinkedHashMap<>();
        Run firstRun = new Run("Run 1", 0L, false, "SESSION_START", startedAtEpochMillis);
        this.runs.add(firstRun);
        this.currentRunId = firstRun.getId();
        this.transactionLimit = 2_000;
        this.retainedItemContributions = new LinkedHashMap<>();
        this.retainedItemContributionsVersion = 1;
        this.retainedItemContributionsComplete = true;
        this.retainedGainedReceiptCountVersion = 1;
        this.retainedGainedReceiptCountComplete = true;
        this.retainedCostSplitVersion = 1;
        this.retainedCostSplitComplete = true;
        this.tags = new ArrayList<>();
        this.notes = "";
        this.analyticsDays = new ArrayList<>();
        this.encounterTotals = new EncounterTotals();
        this.encounterTotalsAvailable = true;
        this.pkLocationLedger = new PkLocationLedger(startedAtEpochMillis);
        this.analyticsZoneId = "UTC";
        this.analyticsLastActiveAtEpochMillis = startedAtEpochMillis;
    }

    /**
     * One session that stands for {@code parts} (closed, in start order): the first one's identity,
     * the last one's close, every receipt / run / encounter / correction in order, retained aggregates
     * summed, day summaries absorbed by day key, the gaps between parts counted as paused time so the
     * active total is exactly the sum of the parts. The parts themselves are left untouched, so an
     * undo can put them back as they were.
     */
    public static ProfitSession mergedFrom(List<ProfitSession> parts, long now)
    {
        if (parts == null || parts.size() < 2) throw new IllegalArgumentException("parts");
        ProfitSession first = parts.get(0);
        ProfitSession last = parts.get(parts.size() - 1);
        ProfitSession m = new ProfitSession(first.name, first.startedAtEpochMillis, first.mode);
        m.ownerKind = first.ownerKind;
        m.analyticsZoneId = first.analyticsZoneId;
        m.analyticsStartedAtEpochMillis = first.analyticsStartedAtEpochMillis;
        m.analyticsLastActiveAtEpochMillis = last.analyticsLastActiveAtEpochMillis;
        m.ensureTransactions();
        m.ensureRunState();
        m.ensureActivityActions();
        m.ensureAnalyticsDays();
        if (m.pkEncounters == null) m.pkEncounters = new ArrayList<>();
        if (m.correctionHistory == null) m.correctionHistory = new ArrayList<>();
        if (m.activitySegments == null) m.activitySegments = new ArrayList<>();
        if (m.tags == null) m.tags = new ArrayList<>();
        if (m.retainedItemContributions == null) m.retainedItemContributions = new LinkedHashMap<>();
        m.retainedCostSplitVersion = Integer.MAX_VALUE;
        m.retainedCostSplitComplete = true;
        m.retainedGainedReceiptCountVersion = Integer.MAX_VALUE;
        m.retainedGainedReceiptCountComplete = true;
        m.retainedItemContributionsVersion = Integer.MAX_VALUE;
        m.retainedItemContributionsComplete = true;
        m.encounterTotalsAvailable = true;
        m.analyticsItemHistoryComplete = true;
        m.pkLocationLedger = null;
        StringBuilder notes = new StringBuilder();
        long previousEnd = 0L;
        for (ProfitSession p : parts)
        {
            if (p.categoryOverride != null && m.categoryOverride == null) m.categoryOverride = p.categoryOverride;
            if (p.activityHint != null && !p.activityHint.trim().isEmpty()) m.activityHint = p.activityHint;
            if (previousEnd > 0L && p.startedAtEpochMillis > previousEnd)
            {
                m.totalPausedMillis += p.startedAtEpochMillis - previousEnd;
            }
            previousEnd = Math.max(previousEnd, p.endedAtEpochMillis);
            m.totalPausedMillis += Math.max(0L, p.totalPausedMillis);
            m.actionCount += p.actionCount;
            if (p.activityActions != null)
            {
                for (Map.Entry<String, Integer> e : p.activityActions.entrySet()) m.activityActions.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            if (p.activitySegments != null) m.activitySegments.addAll(p.activitySegments);
            if (p.transactions != null) m.transactions.addAll(p.transactions);
            if (p.pkEncounters != null) m.pkEncounters.addAll(p.pkEncounters);
            if (p.correctionHistory != null) m.correctionHistory.addAll(p.correctionHistory);
            if (p.runs != null) m.runs.addAll(p.runs);
            if (p.runRetainedAggregates != null) m.runRetainedAggregates.putAll(p.runRetainedAggregates);
            m.transactionLimit = Math.max(m.transactionLimit, p.transactionLimit);
            m.retainedRevenue += p.retainedRevenue;
            m.retainedCosts += p.retainedCosts;
            m.retainedSuppliesCosts += p.retainedSuppliesCosts;
            m.retainedCostSplitVersion = Math.min(m.retainedCostSplitVersion, p.retainedCostSplitVersion);
            m.retainedCostSplitComplete &= p.retainedCostSplitComplete || p.compactedTransactionCount == 0L;
            m.retainedCountedTransactions += p.retainedCountedTransactions;
            m.retainedTransfers += p.retainedTransfers;
            m.retainedGainedReceiptCount += p.retainedGainedReceiptCount;
            m.retainedGainedReceiptCountVersion = Math.min(m.retainedGainedReceiptCountVersion, p.retainedGainedReceiptCountVersion);
            m.retainedGainedReceiptCountComplete &= p.retainedGainedReceiptCountComplete || p.compactedTransactionCount == 0L;
            if (p.retainedItemContributions != null)
            {
                for (Map.Entry<Integer, RetainedItemContribution> e : p.retainedItemContributions.entrySet())
                {
                    RetainedItemContribution mine = m.retainedItemContributions.get(e.getKey());
                    if (mine == null) m.retainedItemContributions.put(e.getKey(), e.getValue().copy());
                    else mine.absorb(e.getValue());
                }
            }
            m.retainedItemContributionsVersion = Math.min(m.retainedItemContributionsVersion, p.retainedItemContributionsVersion);
            m.retainedItemContributionsComplete &= p.retainedItemContributionsComplete || p.compactedTransactionCount == 0L;
            m.compactedTransactionCount += p.compactedTransactionCount;
            if (p.tags != null) for (String tag : p.tags) if (tag != null && !m.tags.contains(tag)) m.tags.add(tag);
            m.excludedFromAverages |= p.excludedFromAverages;
            m.recoveredFromCrash |= p.recoveredFromCrash;
            m.favorite |= p.favorite;
            if (p.notes != null && !p.notes.trim().isEmpty())
            {
                if (notes.length() > 0) notes.append("\n");
                notes.append(p.notes.trim());
            }
            if (m.partySummary == null) m.partySummary = p.partySummary;
            if (m.profitTargetGp == null) m.profitTargetGp = p.profitTargetGp;
            if (p.analyticsDays != null)
            {
                for (TrackingDaySummary day : p.analyticsDays)
                {
                    if (day == null) continue;
                    TrackingDaySummary mine = m.findAnalyticsDay(day.getDayKey(), day.getZone());
                    if (mine == null)
                    {
                        mine = new TrackingDaySummary(day.getDay(), day.getZone());
                        m.analyticsDays.add(mine);
                    }
                    mine.absorb(day);
                }
            }
            m.encounterTotalsAvailable &= p.encounterTotalsAvailable;
            m.analyticsItemHistoryComplete &= p.analyticsItemHistoryComplete;
            if (p.encounterDailyUnavailableThroughDay != null && p.encounterDailyUnavailableThroughDay.compareTo(m.encounterDailyUnavailableThroughDay) > 0)
            {
                m.encounterDailyUnavailableThroughDay = p.encounterDailyUnavailableThroughDay;
                m.encounterDailyUnavailableZoneId = p.encounterDailyUnavailableZoneId;
            }
            if (p.pkLocationLedger != null)
            {
                if (m.pkLocationLedger == null) m.pkLocationLedger = p.pkLocationLedger.copy();
                else m.pkLocationLedger.absorb(p.pkLocationLedger, now);
            }
        }
        if (m.retainedCostSplitVersion == Integer.MAX_VALUE) m.retainedCostSplitVersion = 0;
        if (m.retainedGainedReceiptCountVersion == Integer.MAX_VALUE) m.retainedGainedReceiptCountVersion = 0;
        if (m.retainedItemContributionsVersion == Integer.MAX_VALUE) m.retainedItemContributionsVersion = 0;
        m.analyticsDays.sort(Comparator.comparing(TrackingDaySummary::getDay).thenComparing(TrackingDaySummary::getZoneId));
        m.notes = notes.length() == 0 ? null : notes.toString();
        m.currentRunId = last.currentRunId;
        m.endedAtEpochMillis = last.endedAtEpochMillis;
        m.endReason = last.endReason;
        m.paused = false;
        m.stopped = false;
        m.pauseReason = PauseReason.NONE;
        m.pausedAtEpochMillis = 0L;
        return m;
    }

    /** One item's counted net across a session: retained (compacted) contributions plus live receipts. */
    public static final class ItemNet
    {
        private final int itemId;
        private final String itemName;
        private long net;

        ItemNet(int itemId, String itemName)
        {
            this.itemId = itemId;
            this.itemName = itemName == null ? "" : itemName;
        }

        public int getItemId() { return itemId; }
        public String getItemName() { return itemName; }
        public long getNet() { return net; }
    }

    /**
     * Counted net per item, or null when compaction removed rows whose item split was never retained
     * (the total is still known, the per-item breakdown is not).
     */
    public Map<Integer, ItemNet> getItemNets()
    {
        if (compactedTransactionCount > 0L && !retainedItemContributionsComplete) return null;
        Map<Integer, ItemNet> result = new LinkedHashMap<>();
        if (retainedItemContributions != null)
        {
            for (RetainedItemContribution c : retainedItemContributions.values())
            {
                if (c == null) continue;
                ItemNet item = result.computeIfAbsent(c.getItemId(), id -> new ItemNet(id, c.getItemName()));
                item.net = safeAdd(item.net, safeAdd(c.getRevenue(), -c.getCosts()));
            }
        }
        for (ProfitTransaction t : getTransactions())
        {
            if (t == null || !t.isCounted()) continue;
            for (ItemFlow flow : t.getFlows())
            {
                if (flow == null || flow.getValueDelta() == 0L) continue;
                ItemNet item = result.computeIfAbsent(flow.getItemId(), id -> new ItemNet(id, flow.getItemName()));
                item.net = safeAdd(item.net, flow.getValueDelta());
            }
        }
        return result;
    }

    public void addTransaction(ProfitTransaction transaction, int maxTransactions)
    {
        if (transaction == null)
        {
            return;
        }

        ensureRunState();
        resolveRunAssignment(transaction);

        ensureTransactions();
        transactionLimit = Math.max(1, maxTransactions);
        transactions.add(transaction);
        resolvePendingRunAssignments(transaction);
        recordAnalyticsTransaction(transaction);

        int overflow = transactions.size() - transactionLimit;
        if (overflow > 0)
        {
            List<ProfitTransaction> removed = new ArrayList<>(transactions.subList(0, overflow));
            transactions.subList(0, overflow).clear();
            for (ProfitTransaction transactionToRemove : removed)
            {
                compactTransaction(transactionToRemove);
            }
        }
    }

    public void recordAction(String hint)
    {
        recordAction(hint, System.currentTimeMillis());
    }

    public void recordAction(String hint, long now)
    {
        String activity = normalizeActivity(hint);
        actionCount++;
        ensureActivityActions();
        activityActions.merge(activity, 1, Integer::sum);
        setActivityHint(activity, now);
    }

    public void setActivityHint(String hint)
    {
        setActivityHint(hint, System.currentTimeMillis());
    }

    public void setActivityHint(String hint, long now)
    {
        if (hint == null || hint.trim().isEmpty())
        {
            return;
        }

        recordAnalyticsActiveTime(now);
        String next = normalizeActivity(hint);
        String current = getActivityHint();
        if (next.equalsIgnoreCase(current))
        {
            return;
        }

        ensureActivitySegments();
        if (!activitySegments.isEmpty())
        {
            activitySegments.get(activitySegments.size() - 1).close(now);
        }
        activityHint = next;
        activitySegments.add(new ActivitySegment(next, now));
    }

    public void rename(String value)
    {
        name = normalizeName(value);
    }

    public void setTags(String commaSeparated)
    {
        ensureTags();
        tags.clear();
        if (commaSeparated == null || commaSeparated.trim().isEmpty())
        {
            return;
        }

        for (String rawTag : commaSeparated.split(","))
        {
            String tag = rawTag == null ? "" : rawTag.trim();
            if (!tag.isEmpty() && !containsIgnoreCase(tags, tag))
            {
                tags.add(tag);
            }
        }
    }

    /**
     * Sets an explicit presentation category. PKing is accounting-significant,
     * so selecting it also makes an AUTO/General session a PK session. A PK
     * session cannot be relabelled as a non-PK category.
     */
    public boolean setCategoryOverride(SessionCategory category)
    {
        if (category == SessionCategory.ALL)
        {
            return false;
        }
        if (category == null)
        {
            categoryOverride = null;
            return true;
        }
        if (getMode() == SessionMode.PK && category != SessionCategory.PKING)
        {
            return false;
        }
        if (category == SessionCategory.PKING && getMode() != SessionMode.PK)
        {
            setMode(SessionMode.PK);
        }
        categoryOverride = category;
        return true;
    }

    public SessionCategory getCategoryOverride()
    {
        return categoryOverride;
    }

    /**
     * One-time, idempotent migration for category tags written before category
     * became a persisted field. Conflicting category tags are left unresolved.
     */
    public boolean migrateCategoryOverrideFromTags()
    {
        if (categoryOverride != null)
        {
            return false;
        }
        SessionCategory tagged = null;
        for (String tag : getTags())
        {
            SessionCategory candidate = SessionCategory.fromSidebarTag(tag);
            if (candidate == null)
            {
                continue;
            }
            if (tagged != null && tagged != candidate)
            {
                return false;
            }
            tagged = candidate;
        }
        return tagged != null && setCategoryOverride(tagged);
    }

    public void setExcludedFromAverages(boolean excluded)
    {
        excludedFromAverages = excluded;
    }

    public void setNotes(String value)
    {
        notes = value == null ? "" : value.trim();
    }

    public void setFavorite(boolean value)
    {
        favorite = value;
    }

    public boolean setProfitTargetGp(Long value)
    {
        if (value != null && value <= 0L)
        {
            return false;
        }
        profitTargetGp = value;
        return true;
    }

    public void markRecoveredFromCrash()
    {
        if (!isClosed())
        {
            recoveredFromCrash = true;
        }
    }

    public SessionCategory getCategory()
    {
        // PK accounting is authoritative even if a malformed/older save carries
        // an incompatible explicit override.
        if (getMode() == SessionMode.PK)
        {
            return SessionCategory.PKING;
        }
        if (categoryOverride != null)
        {
            return categoryOverride;
        }
        if (getMode() == SessionMode.MIXED)
        {
            return SessionCategory.MIXED;
        }

        ensureTransactions();
        ensurePkEncounters();
        boolean pvm = false;
        boolean skilling = false;
        boolean trading = false;
        boolean pking = !pkEncounters.isEmpty();

        for (ProfitTransaction transaction : transactions)
        {
            TrackingContext transactionContext = transaction.getContext();
            TransactionType transactionType = transaction.getAutomaticType();

            if (transactionContext == TrackingContext.PK_LOOT
                || transactionContext == TrackingContext.PK_DEATH
                || transactionType == TransactionType.PK_LOOT
                || transactionType == TransactionType.PK_SUPPLY_COST
                || transactionType == TransactionType.PK_DEATH_LOSS
                || transactionType == TransactionType.PK_FEE)
            {
                pking = true;
            }
            else if (transactionContext == TrackingContext.MARKET
                || transactionType == TransactionType.TRADE)
            {
                trading = true;
            }
            else if (transactionContext == TrackingContext.PRODUCTION
                || transactionType == TransactionType.PROCESSING)
            {
                skilling = true;
            }
            else if (transactionContext == TrackingContext.LOOT
                || transactionType == TransactionType.LOOT)
            {
                pvm = true;
            }
        }

        String hint = getActivityHint().toLowerCase(java.util.Locale.ROOT);
        if (containsAny(hint, "pking", "pk", "wilderness", "bounty hunter", "last man standing"))
        {
            pking = true;
        }
        else if (containsAny(hint, "grand exchange", "flipping", "flip", "merchant", "trading", "shop"))
        {
            trading = true;
        }
        else if (containsAny(hint,
            "mining", "smithing", "fishing", "cooking", "woodcutting",
            "fletching", "crafting", "runecraft", "herblore", "agility",
            "thieving", "hunter", "farming", "construction", "firemaking"))
        {
            skilling = true;
        }

        int categoryCount = (pvm ? 1 : 0)
            + (skilling ? 1 : 0)
            + (trading ? 1 : 0)
            + (pking ? 1 : 0);
        if (categoryCount > 1)
        {
            return SessionCategory.MIXED;
        }
        if (pking)
        {
            return SessionCategory.PKING;
        }
        if (trading)
        {
            return SessionCategory.TRADING;
        }
        if (skilling)
        {
            return SessionCategory.SKILLING;
        }
        if (pvm)
        {
            return SessionCategory.PVM;
        }
        return SessionCategory.GENERAL;
    }

    public boolean matchesSearch(String searchText)
    {
        String search = searchText == null ? "" : searchText.trim().toLowerCase(java.util.Locale.ROOT);
        if (search.isEmpty())
        {
            return true;
        }
        return getName().toLowerCase(java.util.Locale.ROOT).contains(search)
            || getActivityHint().toLowerCase(java.util.Locale.ROOT).contains(search)
            || getCategory().toString().toLowerCase(java.util.Locale.ROOT).contains(search)
            || getTagsDisplay().toLowerCase(java.util.Locale.ROOT).contains(search)
            || getNotes().toLowerCase(java.util.Locale.ROOT).contains(search);
    }

    private static boolean containsAny(String value, String... candidates)
    {
        for (String candidate : candidates)
        {
            if (value.contains(candidate))
            {
                return true;
            }
        }
        return false;
    }

    public void setMode(SessionMode newMode)
    {
        SessionMode previous = getMode();
        SessionMode requested = newMode == null ? SessionMode.GENERAL : newMode;
        if (requested != SessionMode.PK && categoryOverride == SessionCategory.PKING)
        {
            // A category override that means PK accounting cannot outlive PK
            // mode. Changing mode explicitly clears that implication.
            categoryOverride = null;
        }
        mode = requested;
        if (mode == SessionMode.PK)
        {
            // PK mode has an accounting meaning and cannot coexist with a
            // contradictory presentation override.
            categoryOverride = SessionCategory.PKING;
        }
        if (mode == SessionMode.PK
            && (activityHint == null || activityHint.trim().isEmpty()
                || "General".equalsIgnoreCase(activityHint)))
        {
            activityHint = "PKing";
        }
        else if ((mode == SessionMode.GENERAL || mode == SessionMode.AUTO)
            && previous == SessionMode.PK
            && "PKing".equalsIgnoreCase(activityHint))
        {
            activityHint = "General";
        }
    }

    public void pause(long now)
    {
        pause(now, PauseReason.MANUAL);
    }

    public void pause(long now, PauseReason reason)
    {
        if (!paused && endedAtEpochMillis == 0L)
        {
            recordAnalyticsActiveTime(now);
            if (pkLocationLedger != null) pkLocationLedger.pause(now);
            paused = true;
            pausedAtEpochMillis = now;
            pauseReason = reason == null ? PauseReason.MANUAL : reason;
        }
    }

    public void resume(long now)
    {
        ensureRunState();
        stopped = false;
        if (paused && endedAtEpochMillis == 0L)
        {
            totalPausedMillis = safeAdd(totalPausedMillis, Math.max(0L, now - pausedAtEpochMillis));
            pausedAtEpochMillis = 0L;
            paused = false;
            if (pkLocationLedger != null) pkLocationLedger.resume(now);
        }
        pauseReason = PauseReason.NONE;
        analyticsLastActiveAtEpochMillis = Math.max(
            analyticsLastActiveAtEpochMillis, Math.max(0L, now));
        if (endedAtEpochMillis == 0L && currentRunId == null)
        {
            Run last = lastRun();
            if (isEmptyStoppedRun(last))
            {
                last.reopen(getElapsedMillis(now), now);
                currentRunId = last.getId();
            }
            else
            {
                openNextRun(getElapsedMillis(now), now);
            }
        }
    }

    public void stop(long now)
    {
        ensureRunState();
        pause(now, PauseReason.STOPPED);
        stopped = true;
        pauseReason = PauseReason.STOPPED;
        closeCurrentRun(getElapsedMillis(now), "SESSION_STOP");
    }

    public boolean isStopped() { return stopped; }
    public PauseReason getPauseReason()
    {
        if (!paused)
        {
            return PauseReason.NONE;
        }
        if (stopped)
        {
            return PauseReason.STOPPED;
        }
        return pauseReason == null || pauseReason == PauseReason.NONE ? PauseReason.MANUAL : pauseReason;
    }

    public void setPauseReason(PauseReason value)
    {
        if (paused && !stopped)
        {
            pauseReason = value == null ? PauseReason.MANUAL : value;
        }
    }

    public void close(long now)
    {
        if (endedAtEpochMillis != 0L)
        {
            return;
        }

        ensureRunState();

        recordAnalyticsActiveTime(now);

        endedAtEpochMillis = Math.max(now, startedAtEpochMillis);
        if (pkLocationLedger != null) pkLocationLedger.pause(endedAtEpochMillis);
        closeCurrentRun(getElapsedMillis(now), "SESSION_END");
        ensureActivitySegments();
        if (!activitySegments.isEmpty())
        {
            activitySegments.get(activitySegments.size() - 1).close(endedAtEpochMillis);
        }
    }

    public boolean correctTransaction(
        String transactionId,
        TransactionCorrection correction,
        long now)
    {
        return correctTransaction(transactionId, correction, now, "Manual correction");
    }

    public boolean correctTransaction(
        String transactionId,
        TransactionCorrection correction,
        long now,
        String reason)
    {
        ProfitTransaction transaction = findTransaction(transactionId);
        if (transaction == null)
        {
            return false;
        }

        TransactionCorrection previous = transaction.getCorrection();
        removeAnalyticsTransaction(transaction);
        TransactionCorrection next = correction == null ? TransactionCorrection.AUTO : correction;
        transaction.applyCorrection(next, now, reason);
        recordAnalyticsTransaction(transaction);
        ensureCorrectionHistory();
        correctionHistory.add(new CorrectionRecord(transactionId, now, previous, next, reason));
        return true;
    }

    /**
     * Applies one decision to the still-unresolved transactions and stores one
     * correction record, so a single undo restores the complete operation.
     */
    public int correctPendingTransactions(
        List<String> transactionIds,
        TransactionCorrection correction,
        long now,
        String reason)
    {
        TransactionCorrection next = correction == null ? TransactionCorrection.AUTO : correction;
        if (transactionIds == null || transactionIds.isEmpty() || next == TransactionCorrection.AUTO)
        {
            return 0;
        }

        List<ProfitTransaction> targets = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String transactionId : transactionIds)
        {
            if (transactionId == null || transactionId.trim().isEmpty() || !seen.add(transactionId))
            {
                continue;
            }
            ProfitTransaction transaction = findTransaction(transactionId);
            if (ReviewEligibility.needsOwnerDecision(transaction))
            {
                targets.add(transaction);
            }
        }
        if (targets.isEmpty())
        {
            return 0;
        }

        List<CorrectionRecord.Change> changes = new ArrayList<>(targets.size());
        for (ProfitTransaction transaction : targets)
        {
            changes.add(new CorrectionRecord.Change(transaction.getId(), transaction.getCorrection(),
                next, null, null));
        }
        String safeReason = reason == null || reason.trim().isEmpty()
            ? "Bulk review decision" : reason.trim();
        for (ProfitTransaction transaction : targets)
        {
            removeAnalyticsTransaction(transaction);
            transaction.applyCorrection(next, now, safeReason);
            recordAnalyticsTransaction(transaction);
        }
        ensureCorrectionHistory();
        correctionHistory.add(CorrectionRecord.batch(now, safeReason, changes));
        return targets.size();
    }

    /**
     * Personal item split: Net keeps only {@code keepQuantity} of {@code itemId}.
     * Given-away qty is removed from counted gain (not Used). Full give-away on a
     * single-item row IGNORE-corrects. Shares the correction undo stack.
     */
    public boolean applyItemSplit(
        String transactionId,
        int itemId,
        long keepQuantity,
        long now,
        String optionalNote)
    {
        ProfitTransaction transaction = findTransaction(transactionId);
        if (transaction == null || itemId <= 0)
        {
            return false;
        }
        long totalGain = ItemSplitAccounting.gainQuantity(transaction, itemId);
        if (totalGain <= 0L)
        {
            return false;
        }
        long keep = Math.max(0L, Math.min(keepQuantity, totalGain));
        if (keep == totalGain)
        {
            return false;
        }
        String reason = ItemSplitAccounting.reason(keep, totalGain, optionalNote);
        TransactionCorrection previous = transaction.getCorrection();
        List<ItemFlow> flowSnapshot = transaction.copyFlowsSnapshot();
        String explanationSnapshot = transaction.getExplanation();
        removeAnalyticsTransaction(transaction);
        TransactionCorrection next = previous;
        if (keep <= 0L && onlyGainItem(transaction, itemId))
        {
            next = TransactionCorrection.IGNORE;
            transaction.applyCorrection(next, now, reason);
            transaction.stampSplitProvenance(reason, now);
        }
        else
        {
            transaction.applyGainKeep(itemId, keep);
            transaction.stampSplitProvenance(reason, now);
            next = transaction.getCorrection();
        }
        recordAnalyticsTransaction(transaction);
        ensureCorrectionHistory();
        correctionHistory.add(new CorrectionRecord(
            transactionId, now, previous, next, reason, flowSnapshot, explanationSnapshot));
        return true;
    }

    /**
     * Reverts the most recent correction/split when a flow snapshot is present,
     * or restores the previous correction enum otherwise.
     */
    public boolean undoLastCorrection(long now)
    {
        ensureCorrectionHistory();
        if (correctionHistory.isEmpty())
        {
            return false;
        }
        CorrectionRecord last = null;
        for (int index = correctionHistory.size() - 1; index >= 0; index--)
        {
            CorrectionRecord candidate = correctionHistory.get(index);
            if (candidate != null && !candidate.isUndone())
            {
                last = candidate;
                break;
            }
        }
        if (last == null)
        {
            return false;
        }
        List<CorrectionRecord.Change> changes = last.getChanges();
        if (changes.isEmpty())
        {
            return false;
        }

        List<ProfitTransaction> targets = new ArrayList<>(changes.size());
        for (CorrectionRecord.Change change : changes)
        {
            ProfitTransaction transaction = findTransaction(change.getTransactionId());
            if (transaction == null)
            {
                // Fail closed: never partially undo a batch after retention or deletion.
                return false;
            }
            targets.add(transaction);
        }

        for (int index = changes.size() - 1; index >= 0; index--)
        {
            CorrectionRecord.Change change = changes.get(index);
            ProfitTransaction transaction = targets.get(index);
            removeAnalyticsTransaction(transaction);
            if (change.hasFlowSnapshot())
            {
                transaction.replaceFlows(new ArrayList<>(change.getFlowSnapshot()));
            }
            if (change.getExplanationSnapshot() != null)
            {
                transaction.rewriteExplanation(change.getExplanationSnapshot());
            }
            transaction.applyCorrection(change.getPreviousCorrection(), now,
                "Undo correction: " + last.getReason());
            recordAnalyticsTransaction(transaction);
        }
        last.markUndone(now);
        return true;
    }

    private static boolean onlyGainItem(ProfitTransaction transaction, int itemId)
    {
        if (transaction == null)
        {
            return false;
        }
        boolean sawTarget = false;
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getQuantityDelta() == 0L)
            {
                continue;
            }
            if (flow.getItemId() != itemId)
            {
                return false;
            }
            if (flow.getQuantityDelta() > 0L)
            {
                sawTarget = true;
            }
        }
        return sawTarget;
    }

    /**
     * Reverses unrecovered own-drop cost at original valuation. Full recovery
     * IGNORE-corrects the drop row; partial recovery shrinks its cost flows.
     */
    public boolean recoverOwnDropCosts(
        String transactionId,
        int itemId,
        long quantity,
        long now,
        String reason)
    {
        ProfitTransaction transaction = findTransaction(transactionId);
        long beforeQuantity = costQuantity(transaction, itemId);
        if (transaction == null || quantity <= 0L || beforeQuantity <= 0L)
        {
            return false;
        }
        List<ItemFlow> flowSnapshot = transaction.copyFlowsSnapshot();
        String explanationSnapshot = transaction.getExplanation();
        TransactionCorrection previous = transaction.getCorrection();
        removeAnalyticsTransaction(transaction);
        long remaining = transaction.applyDropRecovery(itemId, quantity);
        TransactionCorrection next = previous;
        if (remaining <= 0L)
        {
            next = TransactionCorrection.IGNORE;
            transaction.applyCorrection(next, now, reason);
        }
        ensureCorrectionHistory();
        correctionHistory.add(new CorrectionRecord(
            transactionId, now, previous, next, reason, flowSnapshot, explanationSnapshot));
        recordAnalyticsTransaction(transaction);
        return true;
    }

    private static long costQuantity(ProfitTransaction transaction, int itemId)
    {
        if (transaction == null || itemId <= 0)
        {
            return 0L;
        }
        long total = 0L;
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getItemId() != itemId || flow.getQuantityDelta() >= 0L)
            {
                continue;
            }
            long quantity = flow.getQuantityDelta() == Long.MIN_VALUE
                ? Long.MAX_VALUE : -flow.getQuantityDelta();
            try
            {
                total = Math.addExact(total, quantity);
            }
            catch (ArithmeticException ex)
            {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    public ProfitTransaction undoLastTransaction()
    {
        return undoLastTransaction(System.currentTimeMillis());
    }

    public ProfitTransaction undoLastTransaction(long now)
    {
        ensureRunState();
        ensureTransactions();
        if (transactions.isEmpty())
        {
            return null;
        }

        ProfitTransaction removed = transactions.remove(transactions.size() - 1);
        removeAnalyticsTransaction(removed);
        removeTransactionFromEncounters(removed.getId());
        ensureUndoHistory();
        undoHistory.add(new UndoRecord(removed, now));
        trimUndoHistory();
        return removed;
    }

    public ProfitTransaction restoreLastUndo()
    {
        return restoreLastUndo(System.currentTimeMillis());
    }

    public ProfitTransaction restoreLastUndo(long now)
    {
        ensureRunState();
        ensureUndoHistory();
        for (int index = undoHistory.size() - 1; index >= 0; index--)
        {
            UndoRecord record = undoHistory.get(index);
            if (record == null || record.isRestored() || record.getTransaction() == null)
            {
                continue;
            }
            ProfitTransaction restored = record.getTransaction();
            if (restored.getRunId() == null && !restored.isRunAssignmentPending())
            {
                String legacyRunId = findLegacyRunId();
                if (legacyRunId != null)
                {
                    restored.setRunId(legacyRunId);
                }
                else
                {
                    resolveRunAssignment(restored);
                }
            }
            ensureTransactions();
            int effectiveLimit = transactionLimit <= 0 ? 2_000 : transactionLimit;
            int overflow = transactions.size() + 1 - effectiveLimit;
            if (overflow > 0)
            {
                List<ProfitTransaction> evicted = new ArrayList<>(transactions.subList(0, overflow));
                transactions.subList(0, overflow).clear();
                for (ProfitTransaction transactionToRemove : evicted)
                {
                    // Restoring an undo is also a bounded-ledger insertion.
                    // The row displaced to make room must be summarized exactly
                    // as it would be for a newly observed transaction.
                    compactTransaction(transactionToRemove);
                }
            }
            transactions.add(restored);
            recordAnalyticsTransaction(restored);
            record.markRestored(now);
            return restored;
        }
        return null;
    }

    public ProfitTransaction findTransaction(String transactionId)
    {
        if (transactionId == null || transactionId.isEmpty())
        {
            return null;
        }
        ensureTransactions();
        for (ProfitTransaction transaction : transactions)
        {
            if (transactionId.equals(transaction.getId()))
            {
                return transaction;
            }
        }
        return null;
    }

    public PkEncounter addPkEncounter(
        PkEncounterType type,
        long now,
        String label,
        ClassificationConfidence confidence,
        String explanation)
    {
        ensurePkEncounters();
        PkEncounter encounter = new PkEncounter(type, now, label, confidence, explanation);
        pkEncounters.add(encounter);
        notifyAnalyticsChanged();
        recordAction("PKing", now);
        return encounter;
    }

    public void attachTransactionToEncounter(
        String transactionId,
        String encounterId,
        boolean asPkSupplyCost)
    {
        ProfitTransaction transaction = findTransaction(transactionId);
        PkEncounter encounter = findPkEncounter(encounterId);
        if (transaction == null || encounter == null)
        {
            return;
        }

        transaction.assignEncounter(encounterId, asPkSupplyCost);
        encounter.addTransactionId(transactionId);
        encounter.setFinancialContribution(transaction);
    }

    public void attachRecentCostsToEncounter(
        String encounterId,
        long now,
        long lookbackMillis)
    {
        ensureTransactions();
        long cutoff = Math.max(0L, now - Math.max(0L, lookbackMillis));
        for (int index = transactions.size() - 1; index >= 0; index--)
        {
            ProfitTransaction transaction = transactions.get(index);
            if (transaction.getTimestampEpochMillis() < cutoff)
            {
                break;
            }
            if (!transaction.getEncounterId().isEmpty())
            {
                continue;
            }
            if (transaction.getAutomaticRevenue() == 0L
                && transaction.getAutomaticCosts() > 0L
                && transaction.getAutomaticType() == TransactionType.CONSUMPTION)
            {
                attachTransactionToEncounter(transaction.getId(), encounterId, true);
            }
        }
    }

    public PkEncounter findPkEncounter(String encounterId)
    {
        if (encounterId == null || encounterId.isEmpty())
        {
            return null;
        }
        ensurePkEncounters();
        for (PkEncounter encounter : pkEncounters)
        {
            if (encounterId.equals(encounter.getId()))
            {
                return encounter;
            }
        }
        return null;
    }

    private void removeTransactionFromEncounters(String transactionId)
    {
        ensurePkEncounters();
        for (PkEncounter encounter : pkEncounters)
        {
            encounter.removeTransactionId(transactionId);
            encounter.removeFinancialContribution(transactionId);
        }
    }

    private void compactTransactionFromEncounters(String transactionId)
    {
        ensurePkEncounters();
        for (PkEncounter encounter : pkEncounters)
        {
            if (encounter.getTransactionIds().contains(transactionId))
            {
                encounter.compactFinancialContribution(transactionId);
                encounter.removeTransactionId(transactionId);
            }
        }
    }

    private void compactTransaction(ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return;
        }

        if (compactedTransactionCount == 0L && retainedCostSplitVersion < 1)
        {
            // A pre-split session with no compacted history still has all rows
            // needed to build the new aggregate exactly as they are compacted.
            retainedCostSplitVersion = 1;
            retainedCostSplitComplete = true;
        }

        recordRunCompaction(transaction);

        compactedTransactionCount++;
        if (transaction.getType() == TransactionType.TRANSFER)
        {
            retainedTransfers++;
            compactTransactionFromEncounters(transaction.getId());
            return;
        }
        if (transaction.isCounted())
        {
            retainedCountedTransactions++;
            if (retainedGainedReceiptCountVersion >= 1 && retainedGainedReceiptCountComplete
                && transaction.getRevenue() > 0L)
            {
                retainedGainedReceiptCount = safeAdd(retainedGainedReceiptCount, 1L);
            }
            retainedRevenue = safeAdd(retainedRevenue, transaction.getRevenue());
            retainedCosts = safeAdd(retainedCosts, transaction.getCosts());
            CostSplit split = costSplit(transaction, null);
            if (!split.available || retainedCostSplitVersion < 1)
            {
                retainedCostSplitComplete = false;
            }
            else
            {
                retainedSuppliesCosts = safeAdd(retainedSuppliesCosts, split.supplies);
            }
            if (transaction.getFlows().isEmpty())
            {
                retainedItemContributionsComplete = false;
            }
            for (ItemFlow flow : transaction.getFlows())
            {
                if (flow == null || flow.getValueDelta() == 0L)
                {
                    continue;
                }
                retainedItemContributions()
                    .computeIfAbsent(flow.getItemId(),
                        ignored -> new RetainedItemContribution(flow.getItemId(), flow.getItemName()))
                    .addProjectedValueDelta(transaction, flow);
            }
        }
        compactTransactionFromEncounters(transaction.getId());
    }

    private static CostSplit costSplit(
        ProfitTransaction transaction,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        AccountingProjection.TransactionAmounts total =
            AccountingProjection.transaction(transaction, eligibility);
        if (!total.isAvailable()) return new CostSplit(0L, 0L, false);
        if (!total.isIncluded() || total.getCosts() <= 0L)
        {
            return new CostSplit(0L, 0L, true);
        }
        if (transaction.getFlows().isEmpty()) return new CostSplit(0L, 0L, false);

        long supplies = 0L;
        long other = 0L;
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null) continue;
            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.flow(transaction, flow, eligibility);
            if (!amounts.isAvailable()) return new CostSplit(0L, 0L, false);
            if (!amounts.isIncluded() || amounts.getCosts() <= 0L) continue;
            CostKind kind = CostKind.of(transaction, flow);
            if (kind == CostKind.SUPPLIES)
            {
                supplies = safeAdd(supplies, amounts.getCosts());
            }
            else if (kind == CostKind.LOSS || kind == CostKind.MARKET)
            {
                other = safeAdd(other, amounts.getCosts());
            }
            else
            {
                return new CostSplit(0L, 0L, false);
            }
        }
        return new CostSplit(supplies, other,
            safeAdd(supplies, other) == total.getCosts());
    }

    private static final class CostSplit
    {
        private final long supplies;
        private final long other;
        private final boolean available;

        private CostSplit(long supplies, long other, boolean available)
        {
            this.supplies = supplies;
            this.other = other;
            this.available = available;
        }
    }

    /** Starts a fresh run after closing the current boundary; the session remains the accounting owner. */
    public Run startRun(String name, long now)
    {
        ensureRunState();
        if (isClosed() || paused)
        {
            return null;
        }
        closeCurrentRun(getElapsedMillis(now), "EXPLICIT_SPLIT");
        Run run = new Run(name, getElapsedMillis(now), false, "EXPLICIT_START", now);
        ensureRuns();
        runs.add(run);
        currentRunId = run.getId();
        notifyAnalyticsChanged();
        return run;
    }

    /** Closes the current run without ending or pausing its owning session. */
    public boolean stopRun(long now)
    {
        ensureRunState();
        if (isClosed() || currentRunId == null)
        {
            return false;
        }
        return closeCurrentRun(getElapsedMillis(now), "EXPLICIT_STOP");
    }

    public String getCurrentRunId(long now)
    {
        ensureRunState();
        return currentRunId;
    }

    public List<Run> getRuns(long now)
    {
        ensureRunState();
        return Collections.unmodifiableList(new ArrayList<>(runs));
    }

    public RunHistorySnapshot runHistorySnapshot(long now)
    {
        return runHistorySnapshot(now, null);
    }

    /** Builds statements from this session's retained aggregates and its own receipts. */
    public RunHistorySnapshot runHistorySnapshot(
        long now,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility)
    {
        ensureRunState();
        List<RunStatementSnapshot> statements = new ArrayList<>();
        for (Run run : runs)
        {
            if (run != null)
            {
                statements.add(statementFor(run, now, contributionEligibility));
            }
        }
        RunTotals unassigned = unassignedTotals(contributionEligibility);
        return new RunHistorySnapshot(getId(), getName(), statements,
            unassigned.receiptCount,
            unassigned.available ? safeSubtract(unassigned.revenue, unassigned.costs) : 0L,
            unassigned.status);
    }

    public RunComparisonSnapshot compareRuns(String leftRunId, String rightRunId, long now)
    {
        return compareRuns(leftRunId, rightRunId, now, null);
    }

    public RunComparisonSnapshot compareRuns(
        String leftRunId,
        String rightRunId,
        long now,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility)
    {
        RunHistorySnapshot history = runHistorySnapshot(now, contributionEligibility);
        RunStatementSnapshot left = null;
        RunStatementSnapshot right = null;
        for (RunStatementSnapshot statement : history.getStatements())
        {
            if (statement.getRunId().equals(leftRunId)) left = statement;
            if (statement.getRunId().equals(rightRunId)) right = statement;
        }
        return new RunComparisonSnapshot(left, right);
    }

    private RunStatementSnapshot statementFor(
        Run run,
        long now,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        RunRetainedAggregate retained = runRetainedAggregates().get(run.getId());
        if (retained == null)
        {
            retained = newRunRetainedAggregate();
        }
        RunTotals totals = new RunTotals();
        boolean filtered = eligibility != null;
        if (filtered && retained.compactedTransactions > 0L
            && (retained.version < 1 || !retained.complete))
        {
            totals.available = false;
            totals.status = "UNAVAILABLE_COMPACTED_RUN_FLOWS";
        }
        else if (!filtered)
        {
            totals.revenue = retained.revenue;
            totals.costs = retained.costs;
            totals.suppliesCosts = retained.suppliesCosts;
            totals.costSplitAvailable = retained.compactedTransactions == 0L
                || (retained.costSplitVersion >= 1 && retained.costSplitComplete);
            totals.receiptCount = saturatedCount(retained.countedTransactions);
        }
        else
        {
            totals.costSplitAvailable = retained.compactedTransactions == 0L
                || (retained.costSplitVersion >= 1 && retained.costSplitComplete);
            for (RetainedItemContribution contribution : retained.itemContributions().values())
            {
                if (contribution == null)
                {
                    continue;
                }
                ItemFlow gainFlow = new ItemFlow(contribution.getItemId(),
                    contribution.getItemName(), 1L, 0, contribution.getRevenue());
                ItemFlow costFlow = new ItemFlow(contribution.getItemId(),
                    contribution.getItemName(), -1L, 0, -contribution.getCosts());
                boolean includeGain = contribution.getRevenue() > 0L
                    && eligibility.test(null, gainFlow);
                boolean includeCost = contribution.getCosts() > 0L
                    && eligibility.test(null, costFlow);
                if (!includeGain && !includeCost)
                {
                    continue;
                }
                if (includeGain) totals.revenue = safeAdd(totals.revenue, contribution.getRevenue());
                if (includeCost)
                {
                    totals.costs = safeAdd(totals.costs, contribution.getCosts());
                    if (!contribution.isCostSplitComplete()
                        || safeAdd(contribution.getSuppliesCosts(), contribution.getOtherCosts())
                            != contribution.getCosts())
                    {
                        totals.costSplitAvailable = false;
                    }
                    else
                    {
                        totals.suppliesCosts = safeAdd(totals.suppliesCosts,
                            contribution.getSuppliesCosts());
                    }
                }
            }
        }

        ensureTransactions();
        for (ProfitTransaction transaction : transactions)
        {
            if (transaction == null || !run.getId().equals(transaction.getRunId()))
            {
                continue;
            }
            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.transaction(transaction, eligibility);
            if (!amounts.isAvailable())
            {
                totals.available = false;
                totals.status = "UNAVAILABLE_TRANSACTION_FLOW_DETAIL";
                continue;
            }
            if (amounts.isIncluded())
            {
                totals.revenue = safeAdd(totals.revenue, amounts.getRevenue());
                totals.costs = safeAdd(totals.costs, amounts.getCosts());
                CostSplit split = costSplit(transaction, eligibility);
                if (!split.available)
                {
                    totals.costSplitAvailable = false;
                }
                else
                {
                    totals.suppliesCosts = safeAdd(totals.suppliesCosts, split.supplies);
                }
                if (transaction.isCounted())
                {
                    totals.receiptCount++;
                }
            }
        }

        boolean receiptCountAvailable = !filtered || retained.compactedTransactions == 0L;
        long duration = run.durationAt(getElapsedMillis(now));
        long net = safeSubtract(totals.revenue, totals.costs);
        if (totals.suppliesCosts > totals.costs)
        {
            totals.costSplitAvailable = false;
        }
        String status = run.isLegacyUnsplit() ? "LEGACY_UNSPLIT" : run.isClosed() ? "CLOSED" : "OPEN";
        return new RunStatementSnapshot(run.getId(), run.getName(), run.isLegacyUnsplit(), status,
            duration, totals.available ? totals.revenue : 0L, totals.available ? totals.costs : 0L,
            totals.available ? net : 0L, totals.available ? hourly(net, duration) : 0L,
            totals.available, totals.status, totals.receiptCount, receiptCountAvailable,
            totals.costSplitAvailable ? totals.suppliesCosts : 0L,
            totals.costSplitAvailable ? safeSubtract(totals.costs, totals.suppliesCosts) : 0L,
            totals.costSplitAvailable);
    }

    private RunTotals unassignedTotals(BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        RunRetainedAggregate retained = runRetainedAggregates().get("UNASSIGNED");
        if (retained == null)
        {
            retained = newRunRetainedAggregate();
        }
        RunTotals totals = new RunTotals();
        boolean filtered = eligibility != null;
        if (filtered && retained.compactedTransactions > 0L
            && (retained.version < 1 || !retained.complete))
        {
            totals.available = false;
            totals.status = "UNAVAILABLE_UNASSIGNED_COMPACTION";
            totals.receiptCount = saturatedCount(retained.countedTransactions);
        }
        else if (!filtered)
        {
            totals.revenue = retained.revenue;
            totals.costs = retained.costs;
            totals.receiptCount = saturatedCount(retained.countedTransactions);
        }
        else
        {
            for (RetainedItemContribution contribution : retained.itemContributions().values())
            {
                if (contribution == null) continue;
                long signedValue = safeSubtract(contribution.getRevenue(), contribution.getCosts());
                ItemFlow flow = new ItemFlow(contribution.getItemId(), contribution.getItemName(),
                    signedValue < 0L ? -1L : 1L, 0, signedValue);
                if (eligibility.test(null, flow))
                {
                    totals.revenue = safeAdd(totals.revenue, contribution.getRevenue());
                    totals.costs = safeAdd(totals.costs, contribution.getCosts());
                }
            }
        }
        ensureTransactions();
        for (ProfitTransaction transaction : transactions)
        {
            if (transaction == null || transaction.getRunId() != null)
            {
                continue;
            }
            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.transaction(transaction, eligibility);
            if (!amounts.isAvailable())
            {
                totals.available = false;
                totals.status = "UNAVAILABLE_UNASSIGNED_RECEIPT_DETAIL";
                continue;
            }
            if (amounts.isIncluded())
            {
                totals.revenue = safeAdd(totals.revenue, amounts.getRevenue());
                totals.costs = safeAdd(totals.costs, amounts.getCosts());
                if (transaction.isCounted()) totals.receiptCount++;
            }
        }
        if (retained.compactedTransactions == 0L && totals.available)
        {
            totals.status = "AVAILABLE";
        }
        return totals;
    }

    private boolean closeCurrentRun(long activeElapsedMillis, String reason)
    {
        Run run = findRun(currentRunId);
        if (run == null || run.isClosed())
        {
            currentRunId = null;
            return false;
        }
        run.close(activeElapsedMillis, reason);
        currentRunId = null;
        return true;
    }

    private Run findRun(String runId)
    {
        if (runId == null)
        {
            return null;
        }
        ensureRuns();
        for (Run run : runs)
        {
            if (run != null && runId.equals(run.getId()))
            {
                return run;
            }
        }
        return null;
    }

    private Run lastRun()
    {
        ensureRuns();
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    private boolean isEmptyStoppedRun(Run run)
    {
        if (run == null || !run.isClosed()
            || run.getEndedActiveElapsedMillis() != run.getStartedActiveElapsedMillis())
        {
            return false;
        }
        String runId = run.getId();
        for (ProfitTransaction transaction : getTransactions())
        {
            if (transaction != null && runId.equals(transaction.getRunId()))
            {
                return false;
            }
        }
        // The presence of a retained aggregate means detail has already crossed
        // the run boundary, even if its numeric totals happen to be zero.
        return !runRetainedAggregates().containsKey(runId);
    }

    private String findLegacyRunId()
    {
        ensureRuns();
        for (Run run : runs)
        {
            if (run != null && run.isLegacyUnsplit())
            {
                return run.getId();
            }
        }
        return null;
    }

    private void ensureRunState()
    {
        if (runs == null || runs.isEmpty())
        {
            long elapsed = isClosed() ? getElapsedMillis(endedAtEpochMillis) : 0L;
            Run legacy = new Run("LEGACY_UNSPLIT", 0L, true, "LEGACY_MIGRATION");
            if (isClosed())
            {
                legacy.close(elapsed, "LEGACY_SESSION_END");
            }
            runs = new ArrayList<>();
            runs.add(legacy);
            currentRunId = legacy.isClosed() ? null : legacy.getId();
            runRetainedAggregates = new LinkedHashMap<>();
            RunRetainedAggregate retained = newRunRetainedAggregate();
            retained.revenue = retainedRevenue;
            retained.costs = retainedCosts;
            retained.countedTransactions = retainedCountedTransactions;
            retained.transfers = retainedTransfers;
            retained.compactedTransactions = compactedTransactionCount;
            retained.suppliesCosts = retainedSuppliesCosts;
            retained.costSplitVersion = retainedCostSplitVersion;
            retained.costSplitComplete = retainedCostSplitComplete;
            retained.version = retainedItemContributionsVersion > 0
                ? retainedItemContributionsVersion : compactedTransactionCount == 0L ? 1 : 0;
            retained.complete = compactedTransactionCount == 0L || retainedItemContributionsComplete;
            if (retainedItemContributions != null)
            {
                for (Map.Entry<Integer, RetainedItemContribution> entry : retainedItemContributions.entrySet())
                {
                    if (entry.getValue() != null)
                    {
                        retained.itemContributions().put(entry.getKey(), entry.getValue().copy());
                    }
                }
            }
            runRetainedAggregates.put(legacy.getId(), retained);
            ensureTransactions();
            for (ProfitTransaction transaction : transactions)
            {
                if (transaction != null && transaction.getRunId() == null
                    && !transaction.isRunAssignmentPending())
                {
                    transaction.setRunId(legacy.getId());
                }
            }
        }
        ensureRuns();
        if (runRetainedAggregates == null)
        {
            runRetainedAggregates = new LinkedHashMap<>();
        }
    }

    private void ensureRuns()
    {
        if (runs == null)
        {
            runs = new ArrayList<>();
        }
    }

    private static final class RunTotals
    {
        private long revenue;
        private long costs;
        private long suppliesCosts;
        private int receiptCount;
        private boolean available = true;
        private boolean costSplitAvailable = true;
        private String status = "AVAILABLE";
    }

    /** Compact summary remains owned by the session and is keyed by its run id. */
    private static final class RunRetainedAggregate
    {
        private long revenue;
        private long costs;
        private long countedTransactions;
        private long transfers;
        private long compactedTransactions;
        private int version = 1;
        private boolean complete = true;
        private long suppliesCosts;
        /** Zero/false for compacted run data saved before the Supplies/Loss split. */
        private int costSplitVersion;
        private boolean costSplitComplete;
        private Map<Integer, RetainedItemContribution> itemContributions = new LinkedHashMap<>();

        private Map<Integer, RetainedItemContribution> itemContributions()
        {
            if (itemContributions == null)
            {
                itemContributions = new LinkedHashMap<>();
            }
            return itemContributions;
        }
    }

    private void resolveRunAssignment(ProfitTransaction transaction)
    {
        if (transaction.isRunAssignmentPending() || transaction.getRunId() != null)
        {
            return;
        }
        if (hasLootKeyClaimReceipt(transaction))
        {
            String encounterId = claimReceiptEncounterId(transaction);
            String sourceRunId = findUniqueRunForEncounter(encounterId);
            if (!encounterId.isEmpty() && sourceRunId != null)
            {
                transaction.setRunId(sourceRunId);
            }
            else
            {
                transaction.markRunAssignmentPending();
            }
            return;
        }
        if (currentRunId == null && !paused && !isClosed())
        {
            openNextRun(transactionActiveElapsed(transaction), transaction.getTimestampEpochMillis());
        }
        transaction.setRunId(currentRunId);
    }

    private long transactionActiveElapsed(ProfitTransaction transaction)
    {
        Long activeElapsed = transaction.getActiveElapsedMillis();
        if (activeElapsed != null)
        {
            return Math.max(0L, activeElapsed);
        }
        return Math.max(0L, transaction.getTimestampEpochMillis() - startedAtEpochMillis - totalPausedMillis);
    }

    private Run openNextRun(long activeElapsedMillis, long startedAtEpochMillis)
    {
        ensureRuns();
        Run run = new Run("Run " + (runs.size() + 1), activeElapsedMillis,
            false, "RESUME_OR_ACTIVITY", startedAtEpochMillis);
        runs.add(run);
        currentRunId = run.getId();
        return run;
    }

    private static boolean hasLootKeyClaimReceipt(ProfitTransaction transaction)
    {
        for (LootKeyProvenance entry : transaction.getLootKeyProvenance())
        {
            if (entry != null && entry.isClaimReceipt())
            {
                return true;
            }
        }
        return false;
    }

    private static String claimReceiptEncounterId(ProfitTransaction transaction)
    {
        String source = null;
        for (LootKeyProvenance entry : transaction.getLootKeyProvenance())
        {
            if (entry == null || !entry.isClaimReceipt())
            {
                continue;
            }
            String encounterId = entry.getEncounterId();
            if (encounterId.isEmpty() || (source != null && !source.equals(encounterId)))
            {
                return "";
            }
            source = encounterId;
        }
        if (source == null || source.isEmpty())
        {
            return transaction.getEncounterId();
        }
        String transactionEncounter = transaction.getEncounterId();
        return transactionEncounter.isEmpty() || transactionEncounter.equals(source) ? source : "";
    }

    private String findUniqueRunForEncounter(String encounterId)
    {
        String found = null;
        ensureTransactions();
        for (ProfitTransaction existing : transactions)
        {
            if (existing == null || !encounterId.equals(existing.getEncounterId())
                || existing.getRunId() == null)
            {
                continue;
            }
            if (found != null && !found.equals(existing.getRunId()))
            {
                return null;
            }
            found = existing.getRunId();
        }
        return found;
    }

    /** Resolve detailed delayed claims only when their encounter has one unambiguous run. */
    private void resolvePendingRunAssignments(ProfitTransaction source)
    {
        if (source == null || source.getRunId() == null || hasLootKeyClaimReceipt(source))
        {
            return;
        }
        String encounterId = source.getEncounterId();
        if (encounterId.isEmpty())
        {
            return;
        }
        String sourceRunId = findUniqueRunForEncounter(encounterId);
        if (sourceRunId == null)
        {
            return;
        }
        ensureTransactions();
        for (ProfitTransaction candidate : transactions)
        {
            if (candidate == null || !candidate.isRunAssignmentPending()
                || !hasLootKeyClaimReceipt(candidate)
                || !encounterId.equals(claimReceiptEncounterId(candidate)))
            {
                continue;
            }
            candidate.setRunId(sourceRunId);
        }
    }

    private void recordRunCompaction(ProfitTransaction transaction)
    {
        String key = transaction.getRunId() == null ? "UNASSIGNED" : transaction.getRunId();
        RunRetainedAggregate aggregate = runRetainedAggregates().computeIfAbsent(key,
            ignored -> newRunRetainedAggregate());
        if (aggregate.compactedTransactions == 0L && compactedTransactionCount == 0L
            && aggregate.costSplitVersion < 1)
        {
            aggregate.costSplitVersion = 1;
            aggregate.costSplitComplete = true;
        }
        aggregate.compactedTransactions++;
        if (transaction.getType() == TransactionType.TRANSFER)
        {
            aggregate.transfers++;
            return;
        }
        if (!transaction.isCounted())
        {
            return;
        }
        aggregate.countedTransactions++;
        aggregate.revenue = safeAdd(aggregate.revenue, transaction.getRevenue());
        aggregate.costs = safeAdd(aggregate.costs, transaction.getCosts());
        CostSplit split = costSplit(transaction, null);
        if (!split.available || aggregate.costSplitVersion < 1)
        {
            aggregate.costSplitComplete = false;
        }
        else
        {
            aggregate.suppliesCosts = safeAdd(aggregate.suppliesCosts, split.supplies);
        }
        if (transaction.getFlows().isEmpty())
        {
            aggregate.complete = false;
        }
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null || flow.getValueDelta() == 0L)
            {
                continue;
            }
            aggregate.itemContributions().computeIfAbsent(flow.getItemId(),
                ignored -> new RetainedItemContribution(flow.getItemId(), flow.getItemName()))
                .addProjectedValueDelta(transaction, flow);
        }
    }

    private static RunRetainedAggregate newRunRetainedAggregate()
    {
        RunRetainedAggregate aggregate = new RunRetainedAggregate();
        aggregate.costSplitVersion = 1;
        aggregate.costSplitComplete = true;
        return aggregate;
    }

    private Map<String, RunRetainedAggregate> runRetainedAggregates()
    {
        if (runRetainedAggregates == null)
        {
            runRetainedAggregates = new LinkedHashMap<>();
        }
        return runRetainedAggregates;
    }

    /** Durable UTC-day contribution retained independently of detail compaction. */
    private void recordAnalyticsTransaction(ProfitTransaction transaction)
    {
        if (transaction == null) return;
        ZoneId zone = analyticsZone();
        TrackingDaySummary day = analyticsDay(transaction.getTimestampEpochMillis(), zone);
        day.addTransaction(transaction, zone);
        notifyAnalyticsChanged();
        refreshFinancialEncounterContribution(transaction);
    }

    private void refreshFinancialEncounterContribution(ProfitTransaction transaction)
    {
        if (transaction == null || transaction.getEncounterId().isEmpty()) return;
        PkEncounter encounter = findPkEncounter(transaction.getEncounterId());
        if (encounter != null) encounter.setFinancialContribution(transaction);
    }

    private void removeAnalyticsTransaction(ProfitTransaction transaction)
    {
        removeAnalyticsTransaction(transaction, analyticsZone());
    }

    private void removeAnalyticsTransaction(ProfitTransaction transaction, ZoneId zone)
    {
        if (transaction == null || analyticsDays == null) return;
        TrackingDaySummary day = findAnalyticsDay(dayKey(transaction.getTimestampEpochMillis(), zone), zone);
        if (day != null)
        {
            day.removeTransaction(transaction, zone);
            notifyAnalyticsChanged();
        }
    }

    private void recordAnalyticsActiveTime(long now)
    {
        if (now <= 0L || paused || endedAtEpochMillis != 0L) return;
        long target = Math.max(startedAtEpochMillis, now);
        if (analyticsLastActiveAtEpochMillis <= 0L)
        {
            // Migrated sessions have no fabricated historical daily timing.
            analyticsLastActiveAtEpochMillis = target;
            return;
        }
        ZoneId zone = analyticsZone();
        long cursor = analyticsLastActiveAtEpochMillis;
        // Delayed or out-of-order event timestamps must never rewind the cursor.
        if (target <= cursor) return;
        boolean changed = false;
        List<AnalyticsActiveTimeDelta> deltas = new ArrayList<>();
        while (cursor < target)
        {
            long nextDay = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long end = Math.min(target, nextDay);
            long deltaMillis = Math.max(0L, end - cursor);
            analyticsDay(cursor, zone).addActiveMillis(
                deltaMillis, cursor, zone, getActivityHint());
            if (deltaMillis > 0L)
            {
                deltas.add(new AnalyticsActiveTimeDelta(
                    Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate(), zone.getId(), deltaMillis));
            }
            changed = true;
            cursor = end;
        }
        analyticsLastActiveAtEpochMillis = target;
        if (changed) notifyAnalyticsActiveTimeChanged(deltas);
    }

    /**
     * Closed sessions whose day summaries claim more active time than the session could have
     * played (the pre-step-34 read-accrual defect) are clamped, each day scaled by the same factor,
     * and the clamped days marked rebuilt. Returns the number of days clamped; idempotent.
     */
    public int repairInflatedActiveTime()
    {
        if (endedAtEpochMillis <= 0L || analyticsDays == null || analyticsDays.isEmpty()) return 0;
        long bound = getElapsedMillis(endedAtEpochMillis);
        long claimed = 0L;
        for (TrackingDaySummary day : analyticsDays) if (day != null) claimed = safeAdd(claimed, day.getActiveMillis());
        // A minute of slack covers the tick after close and rounding at midnight boundaries.
        if (claimed <= bound + 60_000L) return 0;
        double factor = claimed == 0L ? 0d : bound / (double) claimed;
        int repaired = 0;
        for (TrackingDaySummary day : analyticsDays)
        {
            if (day == null) continue;
            if (day.clampActiveMillis((long) Math.floor(day.getActiveMillis() * factor))) repaired++;
        }
        if (repaired > 0) notifyAnalyticsChanged();
        return repaired;
    }

    /** Advances session-day active time from an engine-owned clock event. */
    public void advanceAnalyticsActiveTime(long now)
    {
        recordAnalyticsActiveTime(now);
    }

    private TrackingDaySummary analyticsDay(long timestamp, ZoneId zone)
    {
        ensureAnalyticsDays();
        String key = dayKey(timestamp, zone);
        TrackingDaySummary existing = findAnalyticsDay(key, zone);
        if (existing != null)
        {
            if (isEncounterDayUncertain(key, zone)) existing.markEncounterTotalsUnavailable();
            return existing;
        }
        boolean evictingDay = analyticsDays.size() >= MAX_ANALYTICS_DAYS;
        TrackingDaySummary created = new TrackingDaySummary(key, zone);
        if (isEncounterDayUncertain(key, zone)) created.markEncounterTotalsUnavailable();
        analyticsDays.add(created);
        analyticsDays.sort(Comparator.comparing(TrackingDaySummary::getDay)
            .thenComparing(TrackingDaySummary::getZoneId));
        while (analyticsDays.size() > MAX_ANALYTICS_DAYS) analyticsDays.remove(0);
        if (evictingDay)
        {
            analyticsItemHistoryComplete = false;
            // A bounded day-list eviction can remove accounting and active-time
            // inputs from profile totals, so discard any derived profile cache.
            notifyAnalyticsChanged();
        }
        if (analyticsStartedAtEpochMillis <= 0L) analyticsStartedAtEpochMillis = timestamp;
        return created;
    }

    private boolean isEncounterDayUncertain(String day, ZoneId zone)
    {
        return day != null && zone != null
            && zone.getId().equals(encounterDailyUnavailableZoneId)
            && !encounterDailyUnavailableThroughDay.isEmpty()
            && day.compareTo(encounterDailyUnavailableThroughDay) <= 0;
    }

    private TrackingDaySummary findAnalyticsDay(String key, ZoneId zone)
    {
        if (analyticsDays == null) return null;
        for (TrackingDaySummary day : analyticsDays)
        {
            if (day != null && key.equals(day.getDay())
                && zone.getId().equals(day.getZoneId())) return day;
        }
        return null;
    }

    private void ensureAnalyticsDays()
    {
        if (analyticsDays == null) analyticsDays = new ArrayList<>();
    }

    private static String dayKey(long timestamp, ZoneId zone)
    {
        return Instant.ofEpochMilli(Math.max(0L, timestamp)).atZone(zone).toLocalDate().toString();
    }

    /**
     * Re-bases legacy session analytics onto the profile's persisted local date
     * zone. Retained UTC-only summaries remain separate and are reported as
     * partial by profile Insights when they cannot be safely rebinned.
     */
    public void configureAnalyticsTimeZone(String zoneId, boolean migrateLegacy)
    {
        configureAnalyticsTimeZone(zoneId, migrateLegacy, 0L);
    }

    /** Re-bases daily analytics while preserving an explicit PvM uncertainty cutoff. */
    public void configureAnalyticsTimeZone(String zoneId, boolean migrateLegacy,
        long rebaseAtEpochMillis)
    {
        ZoneId target;
        try { target = ZoneId.of(zoneId); }
        catch (RuntimeException ex) { target = ZoneOffset.UTC; }
        ZoneId previous = analyticsZone();
        boolean mustRebuild = migrateLegacy || !previous.equals(target);
        if (!mustRebuild) return;

        long uncertaintyClock = rebaseAtEpochMillis > 0L ? rebaseAtEpochMillis
            : (analyticsLastActiveAtEpochMillis > 0L ? analyticsLastActiveAtEpochMillis
                : startedAtEpochMillis);
        if (uncertaintyClock > 0L)
        {
            encounterDailyUnavailableThroughDay = Instant.ofEpochMilli(uncertaintyClock)
                .atZone(target).toLocalDate().toString();
            encounterDailyUnavailableZoneId = target.getId();
        }

        ensureAnalyticsDays();
        List<ProfitTransaction> current = new ArrayList<>(getTransactions());
        boolean hasCompacted = compactedTransactionCount > 0L;
        if (mustRebuild)
        {
            if (hasCompacted)
            {
                // Keep old compacted UTC slices, but move still-detailed rows
                // to the new zone so corrections and undo remain symmetric.
                for (ProfitTransaction transaction : current)
                {
                    removeAnalyticsTransaction(transaction, previous);
                }
                for (TrackingDaySummary day : analyticsDays)
                {
                    if (day != null && previous.getId().equals(day.getZoneId()))
                    {
                        day.markActiveTimeUnavailable();
                        day.markFourHourBucketsUnavailable();
                        day.markActivityActiveMillisUnavailable();
                        day.markItemSummariesPartial();
                    }
                }
            }
            else
            {
                analyticsDays.clear();
                analyticsStartedAtEpochMillis = startedAtEpochMillis;
            }
        }
        analyticsZoneId = target.getId();
        if (mustRebuild)
        {
            for (ProfitTransaction transaction : current)
            {
                recordAnalyticsTransaction(transaction);
            }
            if (!hasCompacted)
            {
                for (TrackingDaySummary day : analyticsDays)
                {
                    if (day != null && target.getId().equals(day.getZoneId()))
                    {
                        day.markActiveTimeUnavailable();
                        day.markFourHourBucketsUnavailable();
                        day.markActivityActiveMillisUnavailable();
                    }
                }
            }
            // Encounter summaries cannot be redistributed across new local-day
            // boundaries: durable timestamps retain only each source's last sighting.
            // Rebuilt target-zone days must not present false zeroes.
            for (TrackingDaySummary day : analyticsDays)
            {
                if (day != null && target.getId().equals(day.getZoneId()))
                {
                    day.markEncounterTotalsUnavailable();
                }
            }
        }
        notifyAnalyticsChanged();
    }

    public String getAnalyticsTimeZoneId()
    {
        return analyticsZone().getId();
    }

    private ZoneId analyticsZone()
    {
        try { return ZoneId.of(analyticsZoneId == null ? "UTC" : analyticsZoneId); }
        catch (RuntimeException ex) { return ZoneOffset.UTC; }
    }

    public PkMetrics pkMetrics()
    {
        return pkMetrics(null);
    }

    public PkMetrics pkMetrics(BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility)
    {
        ensureTransactions();
        ensurePkEncounters();
        if (contributionEligibility == null)
        {
            rebuildLegacyPkFinancialSummariesWhenComplete();
        }
        boolean projectionAvailable = isPkProjectionAvailable(contributionEligibility);
        if (!projectionAvailable)
        {
            return new PkMetrics(0, 0, 0, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, false);
        }
        if (contributionEligibility == null)
        {
            return pkMetricsFromFinancialSummaries();
        }
        Map<String, ProfitTransaction> byId = new HashMap<>();
        for (ProfitTransaction transaction : transactions)
        {
            byId.put(transaction.getId(), transaction);
        }

        int kills = 0;
        int deaths = 0;
        int streak = 0;
        long revenue = 0L;
        long costs = 0L;
        long suppliesCosts = 0L;
        boolean costSplitAvailable = true;
        long bestKill = 0L;
        long largestDeathLoss = 0L;
        long totalKillNet = 0L;
        long totalDeathLoss = 0L;
        List<Long> killNets = new ArrayList<>();
        List<Long> deathLosses = new ArrayList<>();

        List<PkEncounter> chronological = new ArrayList<>(pkEncounters);
        chronological.sort(Comparator.comparingLong(PkEncounter::getTimestampEpochMillis));
        for (PkEncounter encounter : chronological)
        {
            long encounterRevenue = 0L;
            long encounterCosts = 0L;
            for (String transactionId : encounter.getTransactionIds())
            {
                ProfitTransaction transaction = byId.get(transactionId);
                if (transaction == null)
                {
                    continue;
                }
                AccountingProjection.TransactionAmounts amounts =
                    AccountingProjection.transaction(transaction, contributionEligibility);
                if (!amounts.isAvailable() || !amounts.isIncluded())
                {
                    continue;
                }
                encounterRevenue = safeAdd(encounterRevenue, amounts.getRevenue());
                encounterCosts = safeAdd(encounterCosts, amounts.getCosts());
                CostSplit split = costSplit(transaction, contributionEligibility);
                if (!split.available)
                {
                    costSplitAvailable = false;
                }
                else
                {
                    suppliesCosts = safeAdd(suppliesCosts, split.supplies);
                }
            }

            revenue = safeAdd(revenue, encounterRevenue);
            costs = safeAdd(costs, encounterCosts);
            long encounterNet = safeSubtract(encounterRevenue, encounterCosts);
            if (encounter.getType() == PkEncounterType.KILL)
            {
                kills++;
                streak = streak < 0 ? 1 : streak + 1;
                killNets.add(encounterNet);
                totalKillNet = safeAdd(totalKillNet, encounterNet);
                bestKill = Math.max(bestKill, encounterNet);
            }
            else
            {
                deaths++;
                streak = streak > 0 ? -1 : streak - 1;
                long deathLoss = Math.max(0L, -encounterNet);
                deathLosses.add(deathLoss);
                totalDeathLoss = safeAdd(totalDeathLoss, deathLoss);
                largestDeathLoss = Math.max(largestDeathLoss, deathLoss);
            }
        }

        return new PkMetrics(
            kills,
            deaths,
            streak,
            revenue,
            costs,
            safeSubtract(revenue, costs),
            bestKill,
            largestDeathLoss,
            totalKillNet,
            totalDeathLoss,
            projectionAvailable,
            suppliesCosts,
            costSplitAvailable ? safeSubtract(costs, suppliesCosts) : 0L,
            costSplitAvailable && suppliesCosts <= costs,
            PkMetrics.median(killNets),
            PkMetrics.median(deathLosses));
    }

    private void rebuildLegacyPkFinancialSummariesWhenComplete()
    {
        if (compactedTransactionCount > 0L) return;
        Map<String, ProfitTransaction> byId = new HashMap<>();
        for (ProfitTransaction transaction : getTransactions())
        {
            if (transaction != null) byId.put(transaction.getId(), transaction);
        }
        for (PkEncounter encounter : getPkEncounters())
        {
            if (encounter == null || encounter.isFinancialSummaryAvailable()) continue;
            List<ProfitTransaction> attached = new ArrayList<>();
            for (String transactionId : encounter.getTransactionIds())
            {
                ProfitTransaction transaction = byId.get(transactionId);
                if (transaction == null) return;
                attached.add(transaction);
            }
            encounter.rebuildFinancialContributions(attached);
        }
    }

    private PkMetrics pkMetricsFromFinancialSummaries()
    {
        int kills = 0;
        int deaths = 0;
        int streak = 0;
        long revenue = 0L;
        long costs = 0L;
        long suppliesCosts = 0L;
        boolean costSplitAvailable = true;
        long bestKill = 0L;
        long largestDeathLoss = 0L;
        long totalKillNet = 0L;
        long totalDeathLoss = 0L;
        List<Long> killNets = new ArrayList<>();
        List<Long> deathLosses = new ArrayList<>();
        List<PkEncounter> chronological = new ArrayList<>(getPkEncounters());
        chronological.sort(Comparator.comparingLong(PkEncounter::getTimestampEpochMillis));
        for (PkEncounter encounter : chronological)
        {
            if (encounter == null) continue;
            long encounterNet = encounter.getFinancialNetGp();
            long encounterCosts = encounter.getFinancialCostGp();
            revenue = safeAdd(revenue, safeAdd(encounterNet, encounterCosts));
            costs = safeAdd(costs, encounterCosts);
            if (encounter.isFinancialCostSplitAvailable())
            {
                suppliesCosts = safeAdd(suppliesCosts, encounter.getFinancialSuppliesCostGp());
            }
            else
            {
                costSplitAvailable = false;
            }
            if (encounter.getType() == PkEncounterType.KILL)
            {
                kills++;
                streak = streak < 0 ? 1 : streak + 1;
                killNets.add(encounterNet);
                totalKillNet = safeAdd(totalKillNet, encounterNet);
                bestKill = Math.max(bestKill, encounterNet);
            }
            else
            {
                deaths++;
                streak = streak > 0 ? -1 : streak - 1;
                long deathLoss = encounter.getFinancialLossGp();
                deathLosses.add(deathLoss);
                totalDeathLoss = safeAdd(totalDeathLoss, deathLoss);
                largestDeathLoss = Math.max(largestDeathLoss, deathLoss);
            }
        }
        boolean splitAvailable = costSplitAvailable && suppliesCosts <= costs;
        return new PkMetrics(kills, deaths, streak, revenue, costs,
            safeSubtract(revenue, costs), bestKill, largestDeathLoss,
            totalKillNet, totalDeathLoss, true, suppliesCosts,
            splitAvailable ? safeSubtract(costs, suppliesCosts) : 0L, splitAvailable,
            PkMetrics.median(killNets), PkMetrics.median(deathLosses));
    }

    public SessionMetrics metrics(long now, long rollingWindowMillis)
    {
        return metrics(now, rollingWindowMillis, null);
    }

    /**
     * Builds a read-only filtered metrics projection.
     */
    public SessionMetrics metrics(long now, long rollingWindowMillis,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility)
    {
        ensureTransactions();
        long revenue = 0L;
        long costs = 0L;
        long suppliesCosts = 0L;
        boolean costSplitAvailable = true;
        String projectionStatus = accountingProjectionStatus(contributionEligibility);
        boolean projectionAvailable = !projectionStatus.startsWith("UNAVAILABLE");
        boolean filtered = contributionEligibility != null;
        boolean transactionCountAvailable = !filtered || compactedTransactionCount == 0L;
        // The rolling calculation needs receipt timestamps, which compacted
        // rows no longer retain. Full-session aggregates remain exact, but a
        // rolling window must not silently omit the compacted portion.
        boolean rollingRateAvailable = compactedTransactionCount == 0L;
        int counted = filtered ? 0 : retainedCountedTransactions;
        int transfers = retainedTransfers;
        if (contributionEligibility == null)
        {
            // Legacy-compatible projection: retained aggregate totals include
            // compacted rows that predate item-level retention.
            revenue = retainedRevenue;
            costs = retainedCosts;
            suppliesCosts = retainedSuppliesCosts;
            costSplitAvailable = compactedTransactionCount == 0L
                || (retainedCostSplitVersion >= 1 && retainedCostSplitComplete);
        }
        else if (projectionAvailable)
        {
            if (retainedItemContributions != null)
            {
                for (RetainedItemContribution contribution : retainedItemContributions.values())
                {
                    if (contribution == null)
                    {
                        continue;
                    }
                    if (contribution.getRevenue() > 0L && contributionEligibility.test(null,
                        new ItemFlow(contribution.getItemId(), contribution.getItemName(), 1L, 0,
                            contribution.getRevenue())))
                    {
                        revenue = safeAdd(revenue, contribution.getRevenue());
                    }
                    if (contribution.getCosts() > 0L && contributionEligibility.test(null,
                        new ItemFlow(contribution.getItemId(), contribution.getItemName(), -1L, 0,
                            -contribution.getCosts())))
                    {
                        costs = safeAdd(costs, contribution.getCosts());
                        if (!contribution.isCostSplitComplete()
                            || safeAdd(contribution.getSuppliesCosts(), contribution.getOtherCosts())
                                != contribution.getCosts())
                        {
                            costSplitAvailable = false;
                        }
                        else
                        {
                            suppliesCosts = safeAdd(suppliesCosts,
                                contribution.getSuppliesCosts());
                        }
                    }
                }
            }
            if (compactedTransactionCount > 0L
                && (retainedCostSplitVersion < 1 || !retainedCostSplitComplete))
            {
                costSplitAvailable = false;
            }
        }

        for (ProfitTransaction transaction : transactions)
        {
            if (transaction == null)
            {
                continue;
            }
            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.transaction(transaction, contributionEligibility);
            if (amounts.isTransfer())
            {
                transfers++;
                continue;
            }
            if (!amounts.isAvailable() || !amounts.isIncluded())
            {
                continue;
            }
            if (amounts.isIncluded())
            {
                counted++;
                revenue = safeAdd(revenue, amounts.getRevenue());
                costs = safeAdd(costs, amounts.getCosts());
                CostSplit split = costSplit(transaction, contributionEligibility);
                if (!split.available)
                {
                    costSplitAvailable = false;
                }
                else
                {
                    suppliesCosts = safeAdd(suppliesCosts, split.supplies);
                }
            }
        }

        if (filtered && !projectionAvailable)
        {
            // The numbers are intentionally unavailable rather than silently
            // reverting to the raw compacted totals or implying a real zero.
            revenue = 0L;
            costs = 0L;
            suppliesCosts = 0L;
            costSplitAvailable = false;
            counted = 0;
        }

        if (suppliesCosts > costs)
        {
            costSplitAvailable = false;
        }

        long net = safeSubtract(revenue, costs);
        long elapsed = getElapsedMillis(now);
        long fullRate = hourly(net, elapsed);
        long rollingRate = projectionAvailable && rollingRateAvailable
            ? calculateRollingRate(elapsed, rollingWindowMillis, contributionEligibility) : 0L;

        return new SessionMetrics(
            getName(),
            getActivityHint(),
            paused,
            elapsed,
            revenue,
            costs,
            net,
            fullRate,
            rollingRate,
            counted,
            transfers,
            filtered ? 0 : actionCount,
            projectionAvailable,
            transactionCountAvailable,
            rollingRateAvailable,
            !filtered,
            projectionStatus,
            costSplitAvailable ? suppliesCosts : 0L,
            costSplitAvailable ? safeSubtract(costs, suppliesCosts) : 0L,
            costSplitAvailable);
    }

    public String accountingProjectionStatus(BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        if (eligibility == null)
        {
            return "RAW_ALL_ITEMS";
        }
        if (compactedTransactionCount > 0L
            && (retainedItemContributions == null || retainedItemContributionsVersion < 1
                || !retainedItemContributionsComplete))
        {
            return "UNAVAILABLE_LEGACY_COMPACTION";
        }
        for (ProfitTransaction transaction : getTransactions())
        {
            if (transaction != null && transaction.isCounted()
                && transaction.getFlows().isEmpty())
            {
                return "UNAVAILABLE_MISSING_FLOW_DETAIL";
            }
        }
        return "AVAILABLE_FILTERED";
    }

    /**
     * Indicates whether retained per-transaction rows are complete for export.
     * Compacted totals can remain filterable while their individual receipt rows
     * are no longer available.
     */
    public String detailProjectionStatus(BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        String status = accountingProjectionStatus(eligibility);
        return compactedTransactionCount > 0L
            ? status + ";UNAVAILABLE_COMPACTED_TRANSACTION_ROWS" : status;
    }

    public boolean isActivityProjectionAvailable(
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        return compactedTransactionCount == 0L
            && !accountingProjectionStatus(eligibility).startsWith("UNAVAILABLE");
    }

    public String activityProjectionStatus(
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        if (eligibility != null && accountingProjectionStatus(eligibility).startsWith("UNAVAILABLE"))
        {
            return accountingProjectionStatus(eligibility) + ";ACTIVITY_DETAIL_UNAVAILABLE";
        }
        return compactedTransactionCount == 0L ? accountingProjectionStatus(eligibility)
            : "UNAVAILABLE_COMPACTED_ACTIVITY_DETAIL";
    }

    public boolean isPkProjectionAvailable(
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        if (accountingProjectionStatus(eligibility).startsWith("UNAVAILABLE")) return false;
        if (eligibility != null) return compactedTransactionCount == 0L;
        for (PkEncounter encounter : getPkEncounters())
        {
            if (encounter != null && !encounter.isFinancialSummaryAvailable()) return false;
        }
        return true;
    }

    public String pkProjectionStatus(
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        if (eligibility != null && accountingProjectionStatus(eligibility).startsWith("UNAVAILABLE"))
        {
            return accountingProjectionStatus(eligibility) + ";PK_DETAIL_UNAVAILABLE";
        }
        if (eligibility != null && compactedTransactionCount > 0L)
            return "UNAVAILABLE_COMPACTED_PK_DETAIL";
        for (PkEncounter encounter : getPkEncounters())
        {
            if (encounter != null && !encounter.isFinancialSummaryAvailable())
                return "UNAVAILABLE_LEGACY_PK_FINANCIAL_SUMMARY";
        }
        return accountingProjectionStatus(eligibility);
    }

    public long getCompactedTransactionCount() { return compactedTransactionCount; }
    public long getCompactedRevenue() { return retainedRevenue; }
    public long getCompactedCosts() { return retainedCosts; }
    public long getAnalyticsStartedAtEpochMillis() { return analyticsStartedAtEpochMillis; }
    public List<TrackingDaySummary> getAnalyticsDays()
    {
        ensureAnalyticsDays();
        return Collections.unmodifiableList(new ArrayList<>(analyticsDays));
    }

    /** True only when the encounter lifetime was observed from its beginning. */
    public boolean isEncounterTotalsAvailable()
    {
        return encounterTotalsAvailable;
    }

    /** True only while retained daily item totals still cover the whole session lifetime. */
    public boolean isAnalyticsItemHistoryComplete()
    {
        return analyticsItemHistoryComplete;
    }

    public EncounterTotals getEncounterTotals()
    {
        return encounterTotals == null ? new EncounterTotals() : encounterTotals.copy();
    }

    /**
     * Records evidence already deduplicated by RewardPresentationModel. This metadata
     * never creates or changes an accounting transaction.
     */
    public boolean recordObservedEncounter(String sourceName, long multiplicity,
        long lootValue, boolean lootValueKnown, long currentStreak, long timestampEpochMillis)
    {
        if (sourceName == null || sourceName.trim().isEmpty() || multiplicity <= 0L
            || timestampEpochMillis <= 0L)
        {
            return false;
        }
        if (encounterTotals == null) encounterTotals = new EncounterTotals();
        encounterTotals.record(sourceName, multiplicity, lootValue, lootValueKnown,
            currentStreak, timestampEpochMillis);
        analyticsDay(timestampEpochMillis, analyticsZone()).recordEncounter(sourceName,
            multiplicity, lootValue, lootValueKnown, currentStreak, timestampEpochMillis);
        notifyAnalyticsChanged();
        return true;
    }

    /** Marks encounter fields absent from a pre-Step-29 profile as unknown. */
    public void markLegacyEncounterHistoryUnavailable()
    {
        markLegacyEncounterHistoryUnavailable(0L, getAnalyticsTimeZoneId());
    }

    /** Marks existing day evidence and future-created summaries through the migration day unknown. */
    public void markLegacyEncounterHistoryUnavailable(long migrationAtEpochMillis,
        String profileZoneId)
    {
        encounterTotalsAvailable = false;
        ZoneId profileZone;
        try { profileZone = ZoneId.of(profileZoneId); }
        catch (RuntimeException ex) { profileZone = analyticsZone(); }
        long uncertaintyClock = migrationAtEpochMillis > 0L ? migrationAtEpochMillis
            : (analyticsLastActiveAtEpochMillis > 0L ? analyticsLastActiveAtEpochMillis
                : startedAtEpochMillis);
        if (uncertaintyClock > 0L)
        {
            encounterDailyUnavailableThroughDay = Instant.ofEpochMilli(uncertaintyClock)
                .atZone(profileZone).toLocalDate().toString();
            encounterDailyUnavailableZoneId = profileZone.getId();
        }
        // At the day cap an older profile may already have evicted earlier quantities.
        if (analyticsDays != null && analyticsDays.size() >= MAX_ANALYTICS_DAYS)
        {
            analyticsItemHistoryComplete = false;
        }
        if (analyticsDays != null)
        {
            for (TrackingDaySummary day : analyticsDays)
            {
                if (day != null) day.markEncounterTotalsUnavailable();
            }
        }
        if (compactedTransactionCount > 0L && retainedGainedReceiptCountVersion < 1)
        {
            retainedGainedReceiptCountComplete = false;
        }
    }

    /** Receipt count is independent of item quantity and remains bounded after compaction. */
    public long getCountedGainedReceiptCount()
    {
        if (compactedTransactionCount > 0L
            && (retainedGainedReceiptCountVersion < 1 || !retainedGainedReceiptCountComplete))
        {
            return -1L;
        }
        long total = retainedGainedReceiptCount;
        for (ProfitTransaction transaction : getTransactions())
        {
            if (transaction != null && transaction.isCounted() && transaction.getRevenue() > 0L)
            {
                total = safeAdd(total, 1L);
            }
        }
        return total;
    }

    /** Applies the fixed Sessions-card priority and exposes independent raw parts for formatting. */
    public SessionHighlight getSessionHighlight()
    {
        SessionHighlight selected = selectSessionHighlight();
        PkMetrics pk = pkMetrics();
        EncounterTotals encounters = getEncounterTotals();
        long kills = getMode() == SessionMode.PK ? pk.getKills() : encounters.getTotalEncounterCount();
        boolean killsAvailable = getMode() == SessionMode.PK
            ? pk.isProjectionAvailable() : encounterTotalsAvailable;
        List<EncounterTotals.SourceSnapshot> sources = encounters.getNamedSources();
        boolean sourceAvailable = encounterTotalsAvailable && !encounters.hasRemainder();
        String sourceName = sourceAvailable && !sources.isEmpty()
            ? sources.get(0).getSourceName() : "";
        long sourceKills = sourceAvailable && !sources.isEmpty()
            ? sources.get(0).getEncounterCount() : 0L;

        Map<String, DailyRollup.ItemTotal> gathered = new LinkedHashMap<>();
        boolean itemsAvailable = analyticsItemHistoryComplete;
        for (TrackingDaySummary day : getAnalyticsDays())
        {
            if (day == null) continue;
            if (!day.isGainedItemTotalsAvailable())
            {
                itemsAvailable = false;
                break;
            }
            for (Map.Entry<String, DailyRollup.ItemTotal> entry : day.getGainedItemTotals().entrySet())
            {
                DailyRollup.ItemTotal item = entry.getValue();
                if (item == null) continue;
                String key = item.getItemId() > 0 ? "id:" + item.getItemId()
                    : "name:" + item.getItemName().toLowerCase(java.util.Locale.ROOT);
                DailyRollup.ItemTotal prior = gathered.get(key);
                gathered.put(key, new DailyRollup.ItemTotal(item.getItemId(), item.getItemName(),
                    safeAdd(prior == null ? 0L : prior.getQuantity(), item.getQuantity()),
                    safeAdd(prior == null ? 0L : prior.getValueGp(), item.getValueGp())));
            }
        }
        DailyRollup.ItemTotal topItem = null;
        if (itemsAvailable)
        {
            for (DailyRollup.ItemTotal item : gathered.values())
            {
                if (topItem == null || item.getQuantity() > topItem.getQuantity()
                    || (item.getQuantity() == topItem.getQuantity()
                        && item.getItemName().compareToIgnoreCase(topItem.getItemName()) < 0))
                {
                    topItem = item;
                }
            }
        }
        long drops = getCountedGainedReceiptCount();
        return selected.withRawParts(kills, killsAvailable, sourceName, sourceKills,
            sourceAvailable, topItem == null ? 0 : topItem.getItemId(),
            topItem == null ? "" : topItem.getItemName(),
            topItem == null ? 0L : topItem.getQuantity(), itemsAvailable,
            Math.max(0L, drops), drops >= 0L);
    }

    /** Unknown higher-priority data never falls through to a lower-priority statistic. */
    private SessionHighlight selectSessionHighlight()
    {
        if (getMode() == SessionMode.PK)
        {
            PkMetrics pk = pkMetrics();
            return pk.isProjectionAvailable()
                ? SessionHighlight.of(SessionHighlight.Kind.PK_KILLS, pk.getKills(), "")
                : SessionHighlight.unavailable("PK_HISTORY_UNAVAILABLE");
        }
        if (!encounterTotalsAvailable)
        {
            return SessionHighlight.unavailable("ENCOUNTER_HISTORY_UNAVAILABLE");
        }
        EncounterTotals encounters = getEncounterTotals();
        if (encounters.hasRemainder())
        {
            return SessionHighlight.unavailable("ENCOUNTER_SOURCE_REMAINDER");
        }
        if (encounters.getTotalEncounterCount() > 0L)
        {
            List<EncounterTotals.SourceSnapshot> sources = encounters.getNamedSources();
            if (sources.isEmpty())
            {
                return SessionHighlight.unavailable("ENCOUNTER_SOURCE_UNAVAILABLE");
            }
            EncounterTotals.SourceSnapshot top = sources.get(0);
            return SessionHighlight.of(SessionHighlight.Kind.KILLS, top.getEncounterCount(),
                top.getSourceName());
        }

        if (!analyticsItemHistoryComplete)
        {
            return SessionHighlight.unavailable("GAINED_ITEM_HISTORY_TRUNCATED");
        }

        Map<String, DailyRollup.ItemTotal> gathered = new LinkedHashMap<>();
        boolean sawItemCoverage = false;
        for (TrackingDaySummary day : getAnalyticsDays())
        {
            if (day == null) continue;
            if (!day.isGainedItemTotalsAvailable())
            {
                return SessionHighlight.unavailable("GAINED_ITEM_HISTORY_UNAVAILABLE");
            }
            sawItemCoverage = true;
            for (Map.Entry<String, DailyRollup.ItemTotal> entry : day.getGainedItemTotals().entrySet())
            {
                DailyRollup.ItemTotal item = entry.getValue();
                if (item == null) continue;
                String key = item.getItemId() > 0 ? "id:" + item.getItemId()
                    : "name:" + item.getItemName().toLowerCase(java.util.Locale.ROOT);
                DailyRollup.ItemTotal prior = gathered.get(key);
                gathered.put(key, new DailyRollup.ItemTotal(item.getItemId(), item.getItemName(),
                    safeAdd(prior == null ? 0L : prior.getQuantity(), item.getQuantity()),
                    safeAdd(prior == null ? 0L : prior.getValueGp(), item.getValueGp())));
            }
        }
        DailyRollup.ItemTotal topGathered = null;
        for (DailyRollup.ItemTotal item : gathered.values())
        {
            if (topGathered == null || item.getQuantity() > topGathered.getQuantity()
                || (item.getQuantity() == topGathered.getQuantity()
                    && item.getItemName().compareToIgnoreCase(topGathered.getItemName()) < 0))
            {
                topGathered = item;
            }
        }
        if (topGathered != null && topGathered.getQuantity() >= 20L)
        {
            return SessionHighlight.gathered(topGathered.getItemId(), topGathered.getItemName(),
                topGathered.getQuantity());
        }
        if (!sawItemCoverage && getCountedGainedReceiptCount() <= 0L)
        {
            return SessionHighlight.unavailable("NO_HIGHLIGHT_DATA");
        }
        long drops = getCountedGainedReceiptCount();
        if (drops < 0L)
        {
            return SessionHighlight.unavailable("DROP_HISTORY_UNAVAILABLE");
        }
        if (drops > 0L)
        {
            return SessionHighlight.of(SessionHighlight.Kind.DROPS, drops, "");
        }
        return SessionHighlight.unavailable("NO_HIGHLIGHT_DATA");
    }

    public List<ActivityMetrics> activityBreakdown()
    {
        return activityBreakdown(null);
    }

    public List<ActivityMetrics> activityBreakdown(
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility)
    {
        ensureTransactions();
        ensureActivityActions();
        if (!isActivityProjectionAvailable(contributionEligibility))
        {
            return Collections.emptyList();
        }

        Map<String, MutableActivity> grouped = new LinkedHashMap<>();
        if (contributionEligibility == null && activityActions.isEmpty() && actionCount > 0)
        {
            MutableActivity legacy = new MutableActivity(getActivityHint());
            legacy.actions = actionCount;
            grouped.put(legacy.name, legacy);
        }
        if (contributionEligibility == null)
        {
            for (Map.Entry<String, Integer> entry : activityActions.entrySet())
            {
                MutableActivity activity = grouped.computeIfAbsent(
                    normalizeActivity(entry.getKey()),
                    MutableActivity::new);
                activity.actions = Math.max(activity.actions, Math.max(0, entry.getValue()));
            }
        }

        for (ProfitTransaction transaction : transactions)
        {
            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.transaction(transaction, contributionEligibility);
            if (!amounts.isAvailable() || !amounts.isIncluded())
            {
                continue;
            }

            MutableActivity activity = grouped.computeIfAbsent(
                transaction.getActivityName(),
                MutableActivity::new);
            activity.transactions++;
            activity.revenue = safeAdd(activity.revenue, amounts.getRevenue());
            activity.costs = safeAdd(activity.costs, amounts.getCosts());
        }

        if (contributionEligibility == null && grouped.isEmpty() && actionCount > 0)
        {
            MutableActivity fallback = new MutableActivity(getActivityHint());
            fallback.actions = actionCount;
            grouped.put(fallback.name, fallback);
        }

        List<ActivityMetrics> result = new ArrayList<>();
        for (MutableActivity activity : grouped.values())
        {
            result.add(new ActivityMetrics(
                activity.name,
                activity.actions,
                activity.transactions,
                activity.revenue,
                activity.costs,
                safeSubtract(activity.revenue, activity.costs),
                contributionEligibility == null));
        }

        result.sort(Comparator
            .comparingLong(ActivityMetrics::getNet)
            .reversed()
            .thenComparing(ActivityMetrics::getActivityName));
        return Collections.unmodifiableList(result);
    }

    private long calculateRollingRate(long effectiveElapsedMillis, long windowMillis,
        BiPredicate<ProfitTransaction, ItemFlow> contributionEligibility)
    {
        long effectiveWindow = Math.max(60_000L, windowMillis);
        long cutoff = Math.max(0L, effectiveElapsedMillis - effectiveWindow);
        long netValue = 0L;
        long firstActiveTimestamp = effectiveElapsedMillis;
        boolean found = false;

        for (ProfitTransaction transaction : transactions)
        {
            if (transaction == null)
            {
                continue;
            }

            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.transaction(transaction, contributionEligibility);
            if (!amounts.isAvailable() || !amounts.isIncluded())
            {
                continue;
            }

            long transactionActiveTimestamp = resolveActiveTimestamp(transaction, effectiveElapsedMillis);
            if (transactionActiveTimestamp < cutoff)
            {
                continue;
            }

            found = true;
            firstActiveTimestamp = Math.min(firstActiveTimestamp, transactionActiveTimestamp);
            netValue = safeAdd(netValue, safeSubtract(amounts.getRevenue(), amounts.getCosts()));
        }

        if (!found)
        {
            return 0L;
        }

        long denominator = Math.max(1_000L,
            effectiveElapsedMillis - Math.max(cutoff, firstActiveTimestamp));
        return hourly(netValue, denominator);
    }

    private long resolveActiveTimestamp(
        ProfitTransaction transaction,
        long effectiveElapsedMillis)
    {
        Long storedActiveElapsed = transaction.getActiveElapsedMillis();
        if (storedActiveElapsed != null)
        {
            return Math.max(0L, Math.min(effectiveElapsedMillis, storedActiveElapsed));
        }

        long wallElapsed = Math.max(0L, transaction.getTimestampEpochMillis() - startedAtEpochMillis);
        return Math.min(effectiveElapsedMillis, wallElapsed);
    }

    private static long hourly(long value, long elapsedMillis)
    {
        if (elapsedMillis <= 0L)
        {
            return 0L;
        }

        double rate = value * (3_600_000.0d / elapsedMillis);
        if (rate >= Long.MAX_VALUE)
        {
            return Long.MAX_VALUE;
        }
        if (rate <= Long.MIN_VALUE)
        {
            return Long.MIN_VALUE;
        }
        return Math.round(rate);
    }

    public long getElapsedMillis(long now)
    {
        long endpoint = endedAtEpochMillis == 0L ? now : endedAtEpochMillis;
        long activePaused = paused ? Math.max(0L, endpoint - pausedAtEpochMillis) : 0L;
        return Math.max(0L, endpoint - startedAtEpochMillis - totalPausedMillis - activePaused);
    }

    private static String normalizeName(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return com.gpmanager.ui.SessionOwnerLabels.DURABLE_OWNER_NAME;
        }
        return value.trim();
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

    private static int saturatedCount(long value)
    {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
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

    private void ensureTransactions()
    {
        if (transactions == null)
        {
            transactions = new ArrayList<>();
        }
    }

    private Map<Integer, RetainedItemContribution> retainedItemContributions()
    {
        if (retainedItemContributions == null)
        {
            retainedItemContributions = new LinkedHashMap<>();
        }
        return retainedItemContributions;
    }

    private void ensureActivityActions()
    {
        if (activityActions == null)
        {
            activityActions = new LinkedHashMap<>();
        }
    }

    private void ensureActivitySegments()
    {
        if (activitySegments == null)
        {
            activitySegments = new ArrayList<>();
        }
    }

    private void ensurePkEncounters()
    {
        if (pkEncounters == null)
        {
            pkEncounters = new ArrayList<>();
        }
    }

    private void ensureCorrectionHistory()
    {
        if (correctionHistory == null)
        {
            correctionHistory = new ArrayList<>();
        }
    }

    private void ensureUndoHistory()
    {
        if (undoHistory == null)
        {
            undoHistory = new ArrayList<>();
        }
    }

    private void trimUndoHistory()
    {
        ensureUndoHistory();
        int overflow = undoHistory.size() - MAX_UNDO_HISTORY;
        if (overflow > 0)
        {
            undoHistory.subList(0, overflow).clear();
        }
    }

    private void ensureTags()
    {
        if (tags == null)
        {
            tags = new ArrayList<>();
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate)
    {
        for (String value : values)
        {
            if (value.equalsIgnoreCase(candidate))
            {
                return true;
            }
        }
        return false;
    }

    public String getId()
    {
        if (id == null || id.isEmpty())
        {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public String getName() { return normalizeName(name); }
    public SessionOwnerKind getOwnerKind()
    {
        return ownerKind == null ? SessionOwnerKind.UNKNOWN : ownerKind;
    }
    public void setOwnerKind(SessionOwnerKind value)
    {
        SessionOwnerKind next = value == null ? SessionOwnerKind.UNKNOWN : value;
        if (getOwnerKind() != next)
        {
            ownerKind = next;
            notifyAnalyticsChanged();
        }
    }
    public long getAnalyticsRevision() { return analyticsRevision; }
    public void setAnalyticsChangeListener(Runnable listener) { analyticsChangeListener = listener; }
    public void setAnalyticsActiveTimeListener(Consumer<List<AnalyticsActiveTimeDelta>> listener)
    {
        analyticsActiveTimeListener = listener;
    }

    private void notifyAnalyticsChanged()
    {
        advanceAnalyticsRevision();
        Runnable listener = analyticsChangeListener;
        if (listener != null) listener.run();
    }

    private void notifyAnalyticsActiveTimeChanged(List<AnalyticsActiveTimeDelta> deltas)
    {
        advanceAnalyticsRevision();
        Consumer<List<AnalyticsActiveTimeDelta>> listener = analyticsActiveTimeListener;
        if (listener != null)
        {
            listener.accept(Collections.unmodifiableList(new ArrayList<>(deltas)));
            return;
        }
        // Preserve prior invalidation behavior for consumers without the targeted listener.
        Runnable fallback = analyticsChangeListener;
        if (fallback != null) fallback.run();
    }

    private void advanceAnalyticsRevision()
    {
        analyticsRevision = analyticsRevision == Long.MAX_VALUE ? 1L : analyticsRevision + 1L;
    }

    /** One exact active-time increment for a single local-date and zone bucket. */
    public static final class AnalyticsActiveTimeDelta
    {
        private final LocalDate date;
        private final String zoneId;
        private final long activeMillis;

        private AnalyticsActiveTimeDelta(LocalDate date, String zoneId, long activeMillis)
        {
            this.date = date;
            this.zoneId = zoneId;
            this.activeMillis = Math.max(0L, activeMillis);
        }

        public LocalDate getDate() { return date; }
        public String getZoneId() { return zoneId; }
        public long getActiveMillis() { return activeMillis; }
    }
    public String getActivityHint()
    {
        String normalized = normalizeActivity(activityHint);
        if (getMode() == SessionMode.PK && "General".equalsIgnoreCase(normalized))
        {
            return "PKing";
        }
        return normalized;
    }
    public SessionMode getMode() { return mode == null ? SessionMode.GENERAL : mode; }
    public long getStartedAtEpochMillis() { return startedAtEpochMillis; }
    public long getEndedAtEpochMillis() { return endedAtEpochMillis; }
    public SessionEndReason getEndReason() { return endReason; }
    public void setEndReason(SessionEndReason value) { endReason = value; }
    public boolean isPaused() { return paused; }
    public long getPausedAtEpochMillis() { return paused ? Math.max(0L, pausedAtEpochMillis) : 0L; }
    public boolean isClosed() { return endedAtEpochMillis != 0L; }
    public int getActionCount() { return actionCount; }
    public boolean isExcludedFromAverages() { return excludedFromAverages; }
    public boolean isRecoveredFromCrash() { return recoveredFromCrash; }
    public String getNotes() { return notes == null ? "" : notes; }
    public boolean isFavorite() { return favorite; }
    public Long getProfitTargetGp() { return profitTargetGp != null && profitTargetGp > 0L ? profitTargetGp : null; }

    public PartySummary getPartySummary()
    {
        return partySummary == null ? null : partySummary.copy();
    }

    public void setPartySummary(PartySummary summary)
    {
        partySummary = summary == null ? null : summary.copy();
    }

    public List<String> getTags()
    {
        ensureTags();
        return Collections.unmodifiableList(tags);
    }

    public String getTagsDisplay()
    {
        ensureTags();
        return String.join(", ", tags);
    }

    public List<ActivitySegment> getActivitySegments()
    {
        ensureActivitySegments();
        return Collections.unmodifiableList(activitySegments);
    }

    public List<ProfitTransaction> getTransactions()
    {
        ensureTransactions();
        return Collections.unmodifiableList(transactions);
    }

    /**
     * Moves every remaining detail row through the normal retained-aggregate
     * path. Closed-session and retention eligibility are enforced by callers;
     * open sessions are rejected here as a final guard.
     *
     * @return number of receipt rows compacted
     */
    public int compactAllTransactions()
    {
        if (!isClosed())
        {
            return 0;
        }
        ensureTransactions();
        if (transactions.isEmpty())
        {
            return 0;
        }
        List<ProfitTransaction> toCompact = new ArrayList<>(transactions);
        transactions.clear();
        for (ProfitTransaction transaction : toCompact)
        {
            compactTransaction(transaction);
        }
        return toCompact.size();
    }

    public List<PkEncounter> getPkEncounters()
    {
        ensurePkEncounters();
        return Collections.unmodifiableList(pkEncounters);
    }

    /**
     * Adds a presentation-only location sample to this session's bounded
     * active-time ledger. A missing ledger on a legacy profile begins exact
     * coverage at this first new observation; earlier time remains unavailable.
     */
    public void observePkLocation(String locationLabel, long now)
    {
        if (isClosed() || paused) return;
        if (pkLocationLedger == null)
        {
            pkLocationLedger = new PkLocationLedger(now);
        }
        pkLocationLedger.observe(locationLabel, now);
    }

    public boolean hasPkLocationLedger()
    {
        return pkLocationLedger != null && pkLocationLedger.isAvailable();
    }

    /** Returns a detached snapshot so callers cannot mutate persisted evidence. */
    public PkLocationLedger getPkLocationLedger()
    {
        return pkLocationLedger == null ? null : pkLocationLedger.copy();
    }

    public List<CorrectionRecord> getCorrectionHistory()
    {
        ensureCorrectionHistory();
        List<CorrectionRecord> active = new ArrayList<>();
        for (CorrectionRecord record : correctionHistory)
        {
            if (record != null && !record.isUndone())
            {
                active.add(record);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /** Full persisted correction/apply history, including entries with an undo link. */
    public List<CorrectionRecord> getCorrectionLog()
    {
        ensureCorrectionHistory();
        return Collections.unmodifiableList(correctionHistory);
    }

    public List<UndoRecord> getUndoHistory()
    {
        ensureUndoHistory();
        return Collections.unmodifiableList(undoHistory);
    }

    private static class MutableActivity
    {
        private final String name;
        private int actions;
        private int transactions;
        private long revenue;
        private long costs;

        private MutableActivity(String name)
        {
            this.name = normalizeActivity(name);
        }
    }
}
