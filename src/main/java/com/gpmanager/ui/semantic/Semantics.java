package com.gpmanager;

import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.TransactionType.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static java.util.Locale.*;

import com.gpmanager.Accounting.Amounts;
import com.gpmanager.Contribution.Category;
import com.gpmanager.MarketRows.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import lombok.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.GeOffer.Side.*;
import com.gpmanager.Semantics.*;
import static com.gpmanager.MarketRows.Lifecycle.*;
import com.gpmanager.Semantics.Table;
import java.time.*;
import net.runelite.api.gameval.ItemID;

/**
 * The one derived, read-only semantic financial projection every presentation surface shares.
 *
 * <p>It turns canonical transactions and the derived Market settlement context into living semantic
 * groups: repeated compatible activity updates one group, totals stay additive, and no group is
 * ever a correction identity. It never books, never reprices and never persists.
 *
 * <p>Grouping laws:
 *
 * <ul>
 *   <li>Exact named {@code Cast} groups key on the proven spell name; different names never merge
 *       and exact never merges with generic.
 *   <li>Generic action groups key on a canonical input-composition signature (action kind,
 *       financial category, sorted resource item ids, a scale-normalised quantity ratio where it is
 *       safely derivable, and price/coverage compatibility). The signature is presentation grouping
 *       only and is never rendered as a spell name; ambiguous composition splits instead of
 *       over-merging.
 *   <li>Measured charge receipts become their actual booked resource rows with a {@code Used by
 *       <equipment>} context. The equipment is never a financial row and no component ever carries
 *       the whole parent Net.
 *   <li>Action composites require pure counted cost contributions of one financial category: no
 *       Gain, Market, Transfer, Review, Corrected, split or claim-linked row may collapse into one.
 *   <li>Unpriced/incomplete groups stay in their financial table with an incomplete marker; only
 *       transactions that independently need an owner decision are review-required.
 *   <li>Sort truth is {@code latestActivityAt DESC} with a stable semantic-group-id tie-break.
 * </ul>
 */
class Semantics {
    /** Financial table destination for the future stacked Ledger and the Live consumer. */
    enum Table {
        GAINS, COSTS_SUPPLIES, COSTS_LOSS, MARKET, AUDIT
    }

    /** Value coverage: an incomplete group never presents itself as an exact headline. */
    enum Coverage {
        COMPLETE, INCOMPLETE
    }

    /**
     * One exact canonical contribution receipt behind a group; the only correction target layer.
     * Built once by the projection and shared by the Ledger tables, the receipt list and the
     * Corrected audit.
     */
    @AllArgsConstructor
    static class Receipt {
        String contributionId;
        String transactionId;
        long at;
        int itemId;
        String itemName;

        /**
         * Semantic magnitude where known; for measured charge spends this is the normalized
         * quantity.
         */
        long quantity;

        /**
         * Effective, correction-aware additive value; 0 when the receipt is neutral or unpriced.
         */
        long value;

        /** Raw automatic value, retained for audit and correction comparison. */
        long automaticValue;

        boolean unpriced;
        boolean neutral;
        boolean review;
        boolean corrected;
        boolean split;
        Category category;
        String verb;
        String actionDisplayName;
        String why;
        String correctionLabel;
        String source;

        /**
         * Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary resources.
         */
        String usedBy;

        /**
         * Charges: the exact captured unit price and its source; empty for other receipts (F11).
         */
        String priceBasis;

        public Row marketSettlement;

        /** Shop trades: the other side of the trade, named and counted ("Bought Death rune ×50"). */
        String tradedItems;

        /** The same list alone ("Death rune ×50"), for a currency entry row's name. */
        String tradedItemsShort;

        boolean gain() {
            return category == Category.GAIN;
        }

        /** The Corrected audit search; financial table search uses the projection group instead. */
        boolean matchesSearch(String needle) {
            return empty(needle) || Phrases.containsFolded(needle.toLowerCase(ROOT), itemName, verb, actionDisplayName,
                            source, why, correctionLabel, transactionId, contributionId);
        }
    }

    /**
     * One semantic presentation group; never a correction identity and never persisted. Each unit
     * of activity starts as a group; units sharing a merge key agree on everything the key encodes
     * (table, flags, consume shape), so the newest keeps those and later ones only add value, time,
     * quantity, receipts and search terms.
     */
    static class Group {
        String semanticGroupId;

        /** Display item id for the row sprite; -1 for action composites. */
        int itemId;

        Table table;
        String primaryName;

        /** The honest secondary line: "Used by Toxic blowpipe", "3 rune types", "Sell". */
        String contextLine;

        String actionLabel;

        /** Measured-charge equipment context ("Toxic blowpipe"), or empty for ordinary rows. */
        String usedBy;

        /** Measured or estimated charge use: the Losses Charges chip, never a Supplies row. */
        boolean chargeUse;

        long latestActivityAt;
        long value;
        Coverage coverage;

        /** Semantic quantity where honestly known; -1 when it cannot be stated. */
        long quantity;

        /** +1 claim pickup, -1 claim spend, 0 for an ordinary resource row. */
        int claim;

        boolean normalizedConsume;
        int receiptCount = 1;
        boolean composite;
        boolean neutral;
        boolean market;
        boolean reviewRequired;
        boolean corrected;
        String marketPresentationId;
        String representativeTransactionId;
        String representativeContributionId;
        public Set<String> receiptIds = new LinkedHashSet<>();
        public List<Receipt> receipts = new ArrayList<>();
        public Set<String> searchTerms = new LinkedHashSet<>();

        void search(String... terms) {
            Collections.addAll(searchTerms, terms);
        }

        /** A charge or dose row: charge use, a normalized dose pair, or a dose-suffixed spend. */
        boolean chargeRow() {
            if (chargeUse || normalizedConsume) return true;
            if (ActionReader.trailingDose(primaryName) > 0) return true;
            for (Receipt receipt : receipts) {
                if (receipt.itemName != null && ActionReader.trailingDose(receipt.itemName) > 0)
                    return true;
            }
            return false;
        }

        int units = 1;

        /** Units of a production run's input its failures lost (a failed iron smelt's ore). */
        long failed;

        Group(String mergeKey, int itemId, Table table, String primaryName, String contextLine, String actionLabel,
                String usedBy, boolean chargeUse, long at, long value, Coverage coverage, long quantity,
                boolean normalizedConsume, boolean composite, boolean neutral, boolean market, boolean reviewRequired,
                boolean corrected, String marketPresentationId, String transactionId,
                String representativeContributionId, int claim) {
            if (GROUP_IDS.size() > 50_000) GROUP_IDS.clear();
            semanticGroupId = GROUP_IDS.computeIfAbsent(mergeKey, key -> "sg:" + UUID.nameUUIDFromBytes(
                                                    key.getBytes(StandardCharsets.UTF_8)));
            this.itemId = itemId;
            this.table = table;
            this.primaryName = primaryName;
            this.contextLine = contextLine;
            this.actionLabel = actionLabel;
            this.usedBy = usedBy;
            this.chargeUse = chargeUse;
            latestActivityAt = at;
            this.value = value;
            this.coverage = coverage;
            this.quantity = quantity;
            this.normalizedConsume = normalizedConsume;
            this.composite = composite;
            this.neutral = neutral;
            this.market = market;
            this.reviewRequired = reviewRequired;
            this.corrected = corrected;
            this.marketPresentationId = marketPresentationId;
            representativeTransactionId = transactionId;
            this.representativeContributionId = representativeContributionId;
            this.claim = claim;
            receiptIds.add(transactionId.isEmpty() ? representativeContributionId : transactionId);
            receiptIds.remove("");
        }

        /** Adds a later unit with the same merge key. */
        Group absorb(Group unit) {
            value = safeAdd(value, unit.value);
            latestActivityAt = Math.max(latestActivityAt, unit.latestActivityAt);
            coverage = unit.coverage == Coverage.INCOMPLETE ? Coverage.INCOMPLETE : coverage;
            quantity = quantity < 0L || unit.quantity < 0L ? -1L : safeAdd(quantity, unit.quantity);
            units++;
            receiptIds.addAll(unit.receiptIds);
            receiptCount = receiptIds.isEmpty() ? units : receiptIds.size();
            receipts.addAll(unit.receipts);
            searchTerms.addAll(unit.searchTerms);
            failed += unit.failed;
            return this;
        }

        /** Owner 1.1: "16/28" when 16 of a run's 28 units failed; "" when none or all did. */
        String failedOf() {
            return failed > 0L && failed < quantity ? failed + "/" + quantity : "";
        }

        String failedTip() {
            return failed + " of " + quantity + " failed";
        }

        boolean incomplete() {
            return coverage == Coverage.INCOMPLETE;
        }

        /** True when this group owns the given semantic group or contribution identity. */
        boolean containsContribution(String id) {
            if (empty(id)) return false;
            if (semanticGroupId.equals(id)) return true;
            for (Receipt receipt : receipts) {
                if (id.equals(receipt.contributionId)) return true;
            }
            return false;
        }

        /** True when this group represents the given canonical transaction identity. */
        boolean containsTransaction(String id) {
            if (empty(id)) return false;
            if (id.equals(representativeTransactionId)) return true;
            if (receiptIds.contains(id)) return true;
            for (Receipt receipt : receipts) {
                if (id.equals(receipt.transactionId)) return true;
            }
            return false;
        }

        /** True when this group is a composite row (action or note), not a resource row. */
        boolean actionGroup() {
            return composite && !market;
        }

        /**
         * Prepared for PRE-R5C.2B: text search decides whether a COMPLETE group matches by looking
         * at group metadata and underlying resources; a match keeps the full total.
         */
        boolean matchesText(String query) {
            if (query == null) return true;
            String needle = Phrases.lower(query);
            if (needle.isEmpty()) return true;
            for (String term : searchTerms) {
                if (term != null && term.toLowerCase(ROOT).contains(needle)) return true;
            }
            return false;
        }
    }

    /** The full derived read: the groups, newest first. */
    static class Result {
        List<Group> groups;

        Result(List<Group> groups) {
            this.groups = unmodifiableList(groups);
        }
    }

    /**
     * Reconciled financial counts over one group set. `entries` counts financial groups across
     * Gains + Costs + Market (review-required and Audit rows excluded); `receipts` counts the
     * unique counted canonical receipt identities those entries represent (neutral transfers,
     * pending/unrealised Market and Review-only receipts are excluded).
     */
    @AllArgsConstructor
    static class Summary {
        /** Sum of financial Gain groups (review-required rows excluded). */
        long gains;

        /** Sum of financial cost groups, negative (review-required rows excluded). */
        long costs;

        /** Sum of realised Market results (review-required and unrealised rows excluded). */
        long market;

        int entries;
        int receipts;

        /** True when every counted financial group had complete value coverage. */
        boolean coverageComplete;

        /** Canonical Net this summary accounts for when coverage is complete. */
        long net() {
            return safeAdd(safeAdd(gains, costs), market);
        }
    }

    /** Stable group ids by merge key; hashing is paid once per key, not on every tick. */
    static final Map<String, String> GROUP_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Pure capture over explicit data; used by Live, tests, previews and the Ledger. */
    static Result capture(List<Transaction> transactions, List<Row> marketRows, String sessionId, Predicate<Flow> td) {
        Set<String> sessions = sessionId == null || sessionId.isEmpty() ? emptySet() : singleton(sessionId);
        return capture(transactions, marketRows, sessions, td);
    }

    /** Multi-session scope: Today and future period consumers merge compatible activity. */
    static Result capture(List<Transaction> transactions, List<Row> marketRows, Set<String> sessionIds,
            Predicate<Flow> td) {
        Predicate<Flow> visibility = td == null ? flow -> true : td;
        var units = new ArrayList<Group>();
        var representedSettlements = new LinkedHashSet<String>();
        var transactionsById = new LinkedHashMap<String, Transaction>();
        if (transactions != null) {
            for (Transaction transaction : transactions) {
                if (transaction != null) transactionsById.put(transaction.getId(), transaction);
            }
        }
        if (marketRows != null) {
            for (Row market : marketRows) {
                if (market == null || sessionIds == null || !sessionIds.contains(market.scopeSessionId)) continue;
                // Every collect of the offer folds into its one market row (owner 2026-10-09).
                var settlements = new ArrayList<Transaction>();
                for (String id : (market.settlementId + market.earlierSettlements).split(" ")) {
                    representedSettlements.add(id);
                    if (transactionsById.containsKey(id)) settlements.add(transactionsById.get(id));
                }
                units.add(marketUnit(market, settlements));
            }
        }
        if (transactions != null) {
            for (Transaction transaction : transactions) {
                // An uncounted trade is custody's audit copy: its market row already shows the sale
                // (owner 2026-09-28: a second fill read "Bones ×2 · 0").
                if (transaction == null || representedSettlements.contains(transaction.getId())
                        || transaction.getType() == TransactionType.TRADE && !transaction.isCounted()) {
                    continue;
                }
                units.addAll(transactionUnits(transaction, visibility));
            }
        }
        units.sort(Comparator.comparingLong((Group unit) -> unit.latestActivityAt).reversed()
                        .thenComparing(unit -> unit.representativeContributionId));
        var buckets = new LinkedHashMap<String, Group>();
        for (Group unit : units) buckets.merge(unit.semanticGroupId, unit, Group::absorb);
        var groups = new ArrayList<Group>(buckets.values());
        groups.sort(Comparator.comparingLong((Group group) -> group.latestActivityAt).reversed()
                        .thenComparing(group -> group.semanticGroupId));
        return new Result(groups);
    }

    /** Reconciled counts over any group set (the search-filtered Ledger tables reuse this). */
    static Summary summarize(Collection<Group> groups) {
        long gains = 0L;
        long costs = 0L;
        long marketTotal = 0L;
        int entries = 0;
        boolean complete = true;
        var receipts = new LinkedHashSet<String>();
        if (groups == null) return new Summary(0L, 0L, 0L, 0, 0, true);
        for (Group group : groups) {
            if (group.reviewRequired || group.neutral || group.table == Table.AUDIT) continue;
            if (group.table == Table.MARKET) {
                if (group.coverage != Coverage.COMPLETE) {
                    // Pending/unrealised Market is context, not a counted financial entry.
                    complete = false;
                    continue;
                }
                marketTotal = safeAdd(marketTotal, group.value);
            } else if (group.table == Table.GAINS) {
                gains = safeAdd(gains, group.value);
            } else if (group.table == Table.COSTS_SUPPLIES || group.table == Table.COSTS_LOSS) {
                costs = safeAdd(costs, group.value);
            } else {
                continue;
            }
            if (group.coverage == Coverage.INCOMPLETE) complete = false;
            if (!group.corrected) {
                entries++;
                receipts.addAll(group.receiptIds);
            }
        }
        return new Summary(gains, costs, marketTotal, entries, receipts.size(), complete);
    }

    // ── unit construction ──────────────────────────────────────────────────────────────────────
    /**
     * A composite row's group: claim-spend, action and charge-cast units share the fixed tail (no
     * normalised consume, no market lifecycle, no claim count). Field order mirrors the
     * constructor's, so a transposition reads wrong at a glance.
     */
    static Group compositeGroup(String key, int itemId, Table table, String primaryName,
            String contextLine, String actionLabel, boolean chargeUse, long at, long value,
            boolean unpriced, long quantity, boolean neutral, boolean review, boolean corrected,
            String transactionId, String contributionId) {
        return new Group(key, itemId, table, primaryName, contextLine, actionLabel, "", chargeUse,
                at, value, coverage(unpriced), quantity, false, true, neutral, false, review,
                corrected, "", transactionId, contributionId, 0);
    }

    static List<Group> transactionUnits(Transaction transaction, Predicate<Flow> td) {
        List<Flow> flows = transaction.getFlows();
        boolean neutral = transaction.getType() == TransactionType.TRANSFER
                        || transaction.getCorrection() == Correction.TRANSFER;
        boolean review = ReviewRules.needsOwnerDecision(transaction);
        boolean corrected = transaction.getCorrection() != AUTO;
        boolean split = Contribution.isSplit(transaction);
        boolean claimLinked = !transaction.sourceClaimId().isEmpty()
                        || transaction.getActionKind() == ActionKind.DEFERRED_CLAIM;
        long at = transaction.timestampEpochMillis;
        String source = Contribution.sourceOf(transaction);
        if (flows.isEmpty()) return noteUnits(transaction, neutral, review, corrected, at, source);
        var projected = Contribution.project(transaction);
        // An opened claim's proceeds are the action's income: a loot minimum never hides them.
        boolean claimSpend = false;
        for (Contribution contribution : projected) {
            claimSpend |= contribution.priceSource == PriceSource.DEFERRED_CLAIM && contribution.quantityDelta < 0L;
        }
        var visible = new ArrayList<Contribution>();
        for (Contribution contribution : projected) {
            boolean visibleFlow = false;
            for (Flow raw : contribution.rawFlows) visibleFlow |= td.test(raw);
            if (visibleFlow || neutral || review || claimSpend || contribution.isUnpriced())
                visible.add(contribution);
        }
        if (visible.isEmpty()) return emptyList();
        // An opened claim (a coin pouch, an unopened key) is one action row: the claim count and
        // the net it yielded, never a claim leg plus separate proceeds (owner 2026-09-29).
        if (claimSpend && !neutral && !review && !corrected && !split) {
            long spent = 0L;
            long net = 0L;
            boolean unpriced = false;
            boolean claimOnly = true;
            String name = null;
            int icon = -1;
            for (Contribution contribution : visible) {
                long delta = contribution.quantityDelta;
                if (delta < 0L) {
                    if (contribution.priceSource != PriceSource.DEFERRED_CLAIM) {
                        claimOnly = false;
                        break;
                    }
                    spent -= delta;
                    if (name == null) {
                        name = contribution.itemName;
                        icon = contribution.itemId;
                    } else if (!name.equals(contribution.itemName)) {
                        name = "";
                        icon = -1;
                    }
                } else if (delta > 0L) {
                    net = safeAdd(net, contribution.effectiveValue);
                    unpriced |= contribution.isUnpriced();
                }
            }
            if (claimOnly && spent > 0L && net > 0L) {
                String verb = verbOf(transaction);
                String primary = name == null || name.isEmpty() ? verb : name;
                String key = "claim:" + primary + ":" + Long.signum(net) + ":" + coverageKey(unpriced) + ":" + verb
                                + ":" + transaction.autoType().name();
                var unit = new Group(key, icon, Table.GAINS, primary, "", verb, "", false, at, net, coverage(unpriced),
                                spent, false, false, false, false, false, false, "", transaction.getId(),
                                visible.get(0).contributionId, -1);
                for (Contribution contribution : visible)
                    unit.receipts.add(receipt(transaction, contribution, null, null));
                unit.search(primary, verb, source, transaction.getId());
                for (Contribution contribution : visible)
                    unit.searchTerms.add(contribution.itemName);
                return singletonList(unit);
            }
        }
        // A bank-paid fee is one action row named by its own note ("Recovered Junior Jim"): the
        // coins flow carries the value and the icon, and no quantity is stated (owner 2026-10-14).
        if (transaction.getActionKind() == ActionKind.FEE && !visible.isEmpty()) {
            long feeValue = 0L;
            boolean feeUnpriced = false;
            for (Contribution contribution : visible) {
                feeUnpriced |= contribution.isUnpriced();
                feeValue = safeAdd(feeValue, contribution.effectiveValue);
            }
            String feeName = transaction.getNote();
            var unit = compositeGroup("fee:" + feeName + ":" + coverageKey(feeUnpriced) + ":" + Long.signum(feeValue),
                            visible.get(0).itemId, tableFor(financialCategory(transaction, visible.get(0))), feeName,
                            "", "", false, at, feeValue, feeUnpriced, -1L, neutral, review, corrected,
                            transaction.getId(), visible.get(0).contributionId);
            for (Contribution contribution : visible) {
                unit.receipts.add(receipt(transaction, contribution, null, null));
            }
            unit.search(feeName, source, transaction.getId());
            return singletonList(unit);
        }
        // An estimated charge use is one row named by the weapon.
        String note = transaction.getNote();
        String prefix = msg("estimated-charge");
        String castName = note.startsWith(prefix) ? note.substring(prefix.length()).trim() : "";
        if (!castName.isEmpty()) {
            // A worn charge feeder (blood fury) is no cast: its estimate books the charged
            // resource "Used by" it — the same shape its measured Check produces (owner
            // 2026-09-29).
            ChargeCheck.ChargedItem feeder = ChargeCheck.variantForName(castName);
            // Owner 1.1: the blowpipe's rows are its Zulrah's scales (Charges) and darts
            // (Supplies);
            // blood fury stays one "Blood fury" row, as a shard covers 10,000 hits.
            if (feeder == ChargeCheck.ChargedItem.EYE_OF_AYAK || feeder == ChargeCheck.ChargedItem.BLOWPIPE) {
                var feasted = new ArrayList<Group>(visible.size());
                for (Contribution contribution : visible) {
                    feasted.add(resourceUnit(transaction, contribution, dartFree(castName, contribution), neutral,
                                    review, corrected, source, at));
                }
                return feasted;
            }
            return singletonList(chargeCastUnit(transaction, visible, castName, source, at, neutral, review,
                            corrected));
        }
        // A measured charge spend books its resources as rows "Used by" the weapon, never a
        // composite.
        String weapon = transaction.getActionKind() == ActionKind.FIRE || transaction.getActionKind() == ActionKind.CAST
                        ? measuredChargeWeaponName(transaction) : "";
        if (ChargeCheck.variantForName(weapon) == ChargeCheck.ChargedItem.BLOOD_FURY)
            return singletonList(chargeCastUnit(transaction, visible, weapon, source, at, neutral, review, corrected));
        if (weapon.isEmpty() && isActionComposite(
                        transaction, visible, neutral, review, corrected, split, claimLinked)) {
            return singletonList(actionUnit(transaction, visible, source, at));
        }
        var units = new ArrayList<Group>(visible.size());
        for (Contribution contribution : visible) {
            units.add(resourceUnit(transaction, contribution, dartFree(weapon, contribution), neutral, review,
                            corrected, source, at));
        }
        return units;
    }

    /** A blowpipe's darts are Supplies like any ammo, never a charge "used by" the weapon. */
    static String dartFree(String weapon, Contribution contribution) {
        return ChargeCheck.DARTS.containsValue(contribution.itemId) ? "" : weapon;
    }

    /**
     * A safe action composite: pure counted costs of one financial category from one spell cast or
     * one shot. Other actions (a sapling planted with its payment) stay per-item rows, as do mixed
     * gain+cost, Market, Transfer, Review, Corrected, split and claim-linked rows.
     */
    static boolean isActionComposite(Transaction transaction, List<Contribution> contributions, boolean neutral,
            boolean review, boolean corrected, boolean split, boolean claimLinked) {
        if (neutral || review || corrected || split || claimLinked || (transaction.getActionKind() != ActionKind.CAST
                        && transaction.getActionKind() != ActionKind.FIRE
                        && transaction.getActionKind() != ActionKind.DRINK)) {
            return false;
        }
        if (contributions.size() < 2) return false;
        // A named spell with a product (an alch's coins, Bones to Peaches' fruit) is one row by
        // its spell; a gain it does not make (a pickup) keeps the rows apart (owner live
        // 2026-10-16). Any other action keeps one shared cost category.
        Set<Integer> made = transaction.getActionKind() == ActionKind.CAST ? Spells.product(transaction.spellName())
                        : emptySet();
        boolean product = !made.isEmpty();
        Category category = financialCategory(transaction, contributions.get(0));
        if (!product && category != Category.SUPPLY && category != Category.LOSS) {
            return false;
        }
        for (Contribution contribution : contributions) {
            Category each = financialCategory(transaction, contribution);
            if ((product ? contribution.quantityDelta > 0L ? !made.contains(contribution.itemId)
                                    : each != Category.SUPPLY && each != Category.LOSS : each != category)
                    || !contribution.counted || contribution.auditOnly) {
                return false;
            }
        }
        return true;
    }

    static Group actionUnit(Transaction transaction, List<Contribution> contributions, String source, long at) {
        Category category = financialCategory(transaction, contributions.get(0));
        boolean unpriced = false;
        boolean mixed = false;
        long value = 0L;
        for (Contribution contribution : contributions) {
            unpriced |= contribution.isUnpriced();
            mixed |= financialCategory(transaction, contribution) != category;
            value = safeAdd(value, contribution.effectiveValue);
        }
        // A spell with a product sits by its net: an alch that made coins is a gain.
        if (mixed) category = value > 0L ? Category.GAIN : Category.SUPPLY;
        String exactName = transaction.spellName();
        String verb = verbOf(transaction);
        String primaryName = exactName.isEmpty() ? verb : exactName;
        String context = compositionText(transaction, contributions);
        String key = exactName.isEmpty() ? "action:" + transaction.getActionKind().wireName() + ":generic="
                                + compositionKey(contributions) + ":cov=" + coverageKey(unpriced) : "action:"
                                + transaction.getActionKind().wireName() + ":exact=" + exactName + ":cov="
                                + coverageKey(unpriced);
        String representative = contributions.get(0).contributionId;
        // A row named by a spell or an item shows its icon; any other shows its first item's.
        int icon = Spells.icon(primaryName);
        var unit = compositeGroup(key, icon != -1 ? icon : contributions.get(0).itemId, tableFor(category),
                        primaryName, context, verb, false, at, value, unpriced, -1L, false, false,
                        false, transaction.getId(), representative);
        for (Contribution contribution : contributions) {
            unit.receipts.add(receipt(transaction, contribution, null, null));
        }
        unit.search(primaryName, context, verb, source, transaction.getId());
        for (Contribution contribution : contributions) unit.searchTerms.add(contribution.itemName);
        return unit;
    }

    /** One resource row; with a weapon it is a measured charge spend "Used by" that weapon. */
    static Group resourceUnit(Transaction transaction, Contribution contribution, String weapon, boolean neutral,
            boolean review, boolean corrected, String source, long at) {
        Category category = financialCategory(transaction, contribution);
        boolean unpriced = contribution.isUnpriced();
        // An unopened claim (a key or a coin pouch) is held at zero in Recent, never shown as a
        // value there; the Ledger's Pending line shows an observed open chest's worth instead.
        int claimSign = contribution.priceSource == PriceSource.DEFERRED_CLAIM
                        ? (int) Long.signum(contribution.quantityDelta) : 0;
        long value = neutral || claimSign != 0 || contribution.auditOnly ? 0L : contribution.effectiveValue;
        String verb = verbOf(transaction);
        String label = contribution.actionDisplayName;
        String actionLabel = weapon.isEmpty() ? label.isEmpty() ? verb : verb + " \u00b7 " + label : "";
        String context = weapon.isEmpty() ? actionLabel : "Used by " + weapon;
        // Owner 1.1: a production run's input and the same item its failures lost are one row.
        boolean run = transaction.getContext() == Context.PRODUCTION && weapon.isEmpty()
                        && contribution.quantityDelta < 0L;
        String key = run ? "run:" + tableFor(category) + ":" + contribution.itemId + ":" + coverageKey(unpriced) + ":"
                                + review + ":" + corrected + ":" + neutral
                        : (weapon.isEmpty() ? "res:" : "charge:" + weapon + ":") + tableFor(category) + ":"
                                // Doses of one potion share a row whichever dose each came from
                                // (owner 2026-09-28).
                                + (contribution.normalizedConsume ? contribution.itemName : contribution.itemId) + ":"
                                + Long.signum(value) + ":" + coverageKey(unpriced) + ":"
                                + contribution.priceSource.name() + ":" + label + ":" + verb + ":"
                                + transaction.autoType().name() + ":"
                                + (contribution.normalizedConsume ? "norm" : "raw") + ":" + (review ? "review" : "auto")
                                + ":" + (corrected ? "corrected" : "auto") + ":"
                                + (neutral || claimSign != 0 ? "neutral" : "counted");
        var unit = new Group(key, contribution.itemId, tableFor(category), contribution.itemName, context, actionLabel,
                        weapon, !weapon.isEmpty(), at, value, coverage(unpriced), semanticQuantity(contribution),
                        contribution.normalizedConsume, false, neutral || claimSign != 0, false, review, corrected, "",
                        transaction.getId(), contribution.contributionId, claimSign);
        unit.receipts.add(receipt(transaction, contribution, weapon, null));
        if (run && verb.equals(msg("failed", "Failed"))) unit.failed = nonNeg(unit.quantity);
        unit.search(contribution.itemName, context, verb, label, weapon, source, transaction.getId());
        return unit;
    }

    static List<Group> noteUnits(Transaction transaction, boolean neutral, boolean review, boolean corrected, long at,
            String source) {
        Accounting.Amounts amounts = Accounting.transaction(transaction);
        if (!amounts.included && !review && !neutral) return emptyList();
        long value = neutral ? 0L : amounts.getNet();
        Category category = Contribution.noteCategory(transaction, amounts, neutral);
        String name = transaction.getNote().isEmpty() ? transaction.getActivityName() : transaction.getNote();
        String verb = verbOf(transaction);
        String key = "note:" + transaction.getId();
        var unit = compositeGroup(key, -1, tableFor(category), name, verb, verb, false, at, value, false, 0L,
                        neutral, review, corrected, transaction.getId(), transaction.getId() + ":composite");
        unit.receipts.add(new Receipt(transaction.getId() + ":composite", transaction.getId(), at, -1, name, 0L, value,
                        transaction.autoNet(), false, neutral, review, corrected, Contribution.isSplit(transaction),
                        category, verb, transaction.spellName(), why(transaction, false, neutral),
                        correctionLabel(transaction.getCorrection()), source, "", "", null, "", ""));
        unit.search(name, verb, source, transaction.getId());
        return singletonList(unit);
    }

    static Group marketUnit(Row market, List<Transaction> settlements) {
        boolean realized = market.isRealizedIncluded() && market.realizedResultCorrectionAware;
        // Unknown-basis liquidation has no available Result: never show 0 as if break-even were
        // known. Its one exact contribution is a proven GE tax, which stays a market cost.
        boolean knownResult = realized && (market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                || market.manualFinancialResult);
        boolean knownCost = realized && market.knownCostOnly;
        boolean knownContribution = knownResult || knownCost;
        // A sale whose receipt was compacted is history: its money is in the retained totals and
        // there is nothing left to decide, so it shows "result unavailable", never Review.
        boolean review = market.lifecycle == Lifecycle.AMBIGUOUS || market.lifecycle == Lifecycle.UNAVAILABLE;
        String side = market.side.name().equals("SELL") ? "Sell" : "Buy";
        String status = knownResult ? side : knownCost ? side + msg("tax-counted") : market.isRealizedIncluded()
                                        ? side + msg("result-unavailable") : lifecycleLabel(market);
        long quantity = market.isRealizedIncluded() ? market.settledQty : market.filledQty;
        String presentationId = market.presentationId;
        String key = "market:" + presentationId;
        var unit = new Group(key, market.itemId, Table.MARKET, market.itemName, status, "", "", false,
                        market.timestampEpochMillis, knownContribution ? market.realizedResultGp : 0L,
                        knownContribution ? Coverage.COMPLETE : Coverage.INCOMPLETE, quantity, false, true, false, true,
                        review, false, presentationId, market.settlementId, "market:" + presentationId, 0);
        unit.search(market.itemName, status, side, market.lifecycle.name(), market.side.name(), presentationId);
        if (!market.settlementId.isEmpty()) unit.searchTerms.add(market.settlementId);
        // D2: a market group owns only its sale item's receipt. Its settlement's Coins leg is never
        // listed as a separate receipt or grouped with an unclaimed row.
        for (Transaction settlement : settlements) {
            for (Contribution contribution : Contribution.project(settlement)) {
                if (contribution.itemId == market.itemId)
                    unit.receipts.add(receipt(settlement, contribution, null, market));
            }
        }
        return unit;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────
    static Receipt receipt(Transaction transaction, Contribution contribution, String usedBy, Row marketSettlement) {
        boolean neutral = contribution.category == Category.AUDIT && (transaction.getType() == TransactionType.TRANSFER
                                || transaction.getCorrection() == Correction.TRANSFER);
        boolean review = transaction.getCorrection() == AUTO && (contribution.needsReview
                                || ReviewRules.needsOwnerDecision(transaction));
        boolean valueKnown = !neutral && !contribution.auditOnly;
        Category category = contribution.category;
        String[] traded = tradedItemsParts(transaction, contribution);
        return new Receipt(contribution.contributionId, transaction.getId(), contribution.timestampEpochMillis,
                contribution.itemId, contribution.itemName, semanticQuantity(contribution),
                valueKnown ? contribution.effectiveValue : 0L, contribution.valueDelta, contribution.isUnpriced(),
                neutral, review, contribution.corrected, contribution.split, category, verbOf(transaction),
                contribution.actionDisplayName, why(transaction, contribution.isUnpriced(), neutral),
                correctionLabel(transaction.getCorrection()), Contribution.sourceOf(transaction),
                usedBy == null ? "" : usedBy, chargeBasis(transaction, contribution), marketSettlement,
                traded == null ? "" : traded[0] + " " + traded[1], traded == null ? "" : traded[1]);
    }

    static long semanticQuantity(Contribution contribution) {
        if (contribution.normalizedConsume) return contribution.normalizedQuantity;
        return SafeMath.abs(contribution.quantityDelta);
    }

    /** Display verb for a transaction: action kind first, then split/type direction. */
    static String verbOf(Transaction transaction) {
        if ("Dropped".equals(transaction.getNote())) return "Dropped";
        if (transaction.getActionKind() != null) return actionVerb(transaction.getActionKind());
        if (Contribution.isSplit(transaction) && transaction.getCorrection() == AUTO)
            return "Split";
        if (transaction.getType() == CONSUMPTION && transaction.getContext() == Context.PRODUCTION) {
            return msg("failed", "Failed");
        }
        if (shopExchange(transaction)) return msg("verb-exchanged", "Exchanged");
        return msg("verb." + transaction.getType(), transaction.getNet() < 0L ? "Used" : "Received");
    }

    static String actionVerb(ActionKind kind) {
        return msg("action." + kind, "Used");
    }

    /** Why a receipt counted, told from stored records only. */
    static String why(Transaction transaction, boolean unpriced, boolean neutral) {
        if (transaction.getCorrection() != AUTO)
            return msg("why-correction." + transaction.getCorrection());
        if (neutral) return msg("ownership-neutral-move");
        if (unpriced) return msg("price-known");
        if (!transaction.getFlows().isEmpty()
                && transaction.getFlows().stream().allMatch(flow -> flow.getPriceSource() == PriceSource.NO_VALUE))
            return msg("no-value");
        String note = transaction.getNote();
        // Owner 2026-10-01 (F11): a Charges receipt names its retained confidence.
        if (note.startsWith(msg("estimated-charge"))) {
            return msg("estimated-local");
        }
        if (note.startsWith(msg("measured-charge"))) {
            return msg("measured-exact");
        }
        if (ReviewRules.needsOwnerDecision(transaction)) return msg("awaiting-decision");
        if (transaction.getType() == CONSUMPTION && transaction.getContext() == Context.PRODUCTION)
            return msg("failed-inputs");
        return msg("why-type." + transaction.getType(),
                transaction.isCounted() ? msg("counted-automatically") : "Not counted.");
    }

    /**
     * The claimed shop click behind a trade's note ("buy"/"sell"), else empty. Player trades and
     * the Grand Exchange keep their own vocabulary.
     */
    static String shopClick(Transaction transaction) {
        if (transaction == null || transaction.getType() != TRADE) return "";
        String note = Phrases.lower(transaction.getNote());
        if (note.startsWith("buy")) return "buy";
        if (note.startsWith("sell")) return "sell";
        return "";
    }

    /** A shop trade paid in a currency other than coins (tokkul, spirit flakes, …). */
    static boolean nonCoinShopCurrency(Transaction transaction) {
        if (transaction == null) return false;
        var cash = GameData.pattern("cash");
        for (Flow flow : transaction.getFlows()) {
            if (CurrencyProxyCatalogue.isMapped(flow.itemId)) return true;
            String name = Phrases.lower(flow.itemName);
            if (!"coins".equals(name) && cash.matcher(name).matches()) return true;
        }
        return false;
    }

    /** A shop trade that reads "Exchanged": a claimed shop click paid in a non-coin currency. */
    static boolean shopExchange(Transaction transaction) {
        return !shopClick(transaction).isEmpty() && nonCoinShopCurrency(transaction);
    }

    /**
     * For a shop trade entry: the other side of the trade, named and counted, from the claimed
     * click ("buy"/"sell"). A tokkul spend entry reads "Bought Death rune ×50"; the rune's own
     * entry reads "Paid Tokkul ×14,550". Returns {"Bought", "Death rune ×50"} or null.
     */
    static String[] tradedItemsParts(Transaction transaction, Contribution self) {
        if (self == null) return null;
        String click = shopClick(transaction);
        if (click.isEmpty()) return null;
        boolean buying = "buy".equals(click);
        Map<Integer, String> names = new java.util.LinkedHashMap<>();
        Map<Integer, Long> quantities = new java.util.LinkedHashMap<>();
        boolean anyGain = false;
        boolean anyCost = false;
        for (Contribution item : Contribution.project(transaction)) {
            if (item == null || item.contributionId.equals(self.contributionId)) continue;
            long signed = item.normalizedConsume ? item.normalizedQuantity : item.quantityDelta;
            if (signed == 0L) continue;
            anyGain |= signed > 0L;
            anyCost |= signed < 0L;
            names.putIfAbsent(item.itemId, item.itemName);
            quantities.merge(item.itemId, Math.abs(signed), Long::sum);
        }
        if (quantities.isEmpty()) return null;
        var text = new StringBuilder();
        for (var entry : quantities.entrySet()) {
            if (text.length() > 0) text.append(" \u00b7 ");
            text.append(names.get(entry.getKey()));
            if (entry.getValue() > 1L) text.append(' ').append(Fmt.times(entry.getValue()));
        }
        String label = buying ? anyGain && !anyCost ? msg("traded-bought", "Bought") : msg("traded-paid", "Paid")
                        : anyGain && !anyCost ? msg("traded-sold-for", "Sold for") : msg("traded-sold", "Sold");
        return new String[] {label, text.toString()};
    }

    /** A shop trade receipt's other side with its Bought/Paid/Sold word, or empty. */
    static String tradedItems(Transaction transaction, Contribution self) {
        String[] parts = tradedItemsParts(transaction, self);
        return parts == null ? "" : parts[0] + " " + parts[1];
    }

    /**
     * Charges: the exact captured unit price and its source, or "unpriced". Retained flow facts
     * only - never re-derived from the value (owner 2026-10-01, F11).
     */
    static String chargeBasis(Transaction transaction, Contribution contribution) {
        String note = transaction.getNote();
        if (!note.startsWith(msg("estimated-charge")) && !note.startsWith(msg("measured-charge"))) {
            return "";
        }
        if (contribution.isUnpriced() || contribution.rawFlows.isEmpty()
                || contribution.rawFlows.get(0).unitPrice <= 0) {
            return "unpriced";
        }
        Flow flow = contribution.rawFlows.get(0);
        return Fmt.exact(flow.unitPrice) + msg("gp") + flow.getPriceSource();
    }

    static String correctionLabel(Correction correction) {
        return msg("counted-as." + correction, "Automatic");
    }

    /** Exact weapon identity already retained by the measured charge booking path. */
    /** One estimated charge cast: the weapon names a single cast row, like a spell. */
    static Group chargeCastUnit(Transaction transaction, List<Contribution> contributions, String weapon, String source,
            long at, boolean neutral, boolean review, boolean corrected) {
        boolean unpriced = false;
        long value = 0L;
        for (Contribution contribution : contributions) {
            unpriced |= contribution.isUnpriced();
            value = safeAdd(value, contribution.effectiveValue);
        }
        String verb = verbOf(transaction);
        String context = compositionText(transaction, contributions);
        String key = "chargecast:" + weapon + ":" + coverageKey(unpriced) + ":" + (neutral ? "neutral" : "counted")
                        + ":" + (review ? "review" : "auto") + ":" + (corrected ? "corrected" : "auto");
        int icon = transaction.castItemId;
        if (icon <= 0 && contributions.size() == 1) {
            // Owner live 2026-10-13: a charge row wears what it actually spent (the vial, the
            // rune), so a charged weapon never looks like a duplicate of its own loot row. A
            // cast spending several component types has no single "what it spent": it wears the
            // weapon (owner live 2026-10-14, the amulet's law rune read as a mismatch).
            icon = contributions.get(0).itemId;
        }
        if (icon <= 0) {
            // The weapon hint is transient; a restart must still draw the weapon sprite,
            // including the scythe, whose Check may not have been read yet.
            var variant = ChargeCheck.variantForName(weapon);
            if (variant != null) icon = variant.itemIds[0];
        }
        var unit = compositeGroup(key, icon, tableFor(financialCategory(transaction, contributions.get(0))),
                        weapon, context, verb, true, at, value, unpriced, 1L, neutral, review,
                        corrected, transaction.getId(), contributions.get(0).contributionId);
        for (Contribution contribution : contributions) {
            unit.receipts.add(receipt(transaction, contribution, null, null));
            unit.searchTerms.add(contribution.itemName);
        }
        unit.search(weapon, context, verb, source, transaction.getId());
        return unit;
    }

    static String measuredChargeWeaponName(Transaction transaction) {
        String note = transaction.getNote();
        String prefix = msg("measured-charge");
        if (!note.startsWith(prefix)) return "";
        String weapon = note.substring(prefix.length()).trim();
        return weapon.length() > 80 ? weapon.substring(0, 80).trim() : weapon;
    }

    static String compositionText(Transaction transaction, List<Contribution> contributions) {
        boolean allRunes = true;
        var runeTypes = new LinkedHashSet<Integer>();
        for (Contribution contribution : contributions) {
            String name = contribution.itemName.toLowerCase(ROOT);
            if (!name.endsWith(" rune")) allRunes = false;
            else runeTypes.add(contribution.itemId);
        }
        if (transaction.getActionKind() == ActionKind.CAST && allRunes)
            return runeTypes.size() + " rune types";
        return contributions.size() + (transaction.getActionKind() == ActionKind.CAST ? " components" : " items");
    }

    /**
     * Presentation grouping signature for generic action composites: sorted resource ids with a
     * GCD-normalised quantity ratio when every quantity is known, else raw quantities (split rather
     * than over-merge), plus the price source so priced/incomplete shapes stay distinct.
     */
    static String compositionKey(List<Contribution> contributions) {
        long gcd = 0L;
        boolean normalisable = true;
        for (Contribution contribution : contributions) {
            long quantity = SafeMath.abs(contribution.quantityDelta);
            if (quantity <= 0L) {
                normalisable = false;
                break;
            }
            gcd = gcd(gcd, quantity);
        }
        var sorted = new ArrayList<Contribution>(contributions);
        sorted.sort(Comparator.comparingInt((Contribution itemData) -> itemData.itemId)
                        .thenComparing((itemData -> itemData.contributionId)));
        var parts = new ArrayList<String>(sorted.size());
        for (Contribution contribution : sorted) {
            long quantity = SafeMath.abs(contribution.quantityDelta);
            long ratio = normalisable && gcd > 0L ? quantity / gcd : quantity;
            parts.add(contribution.itemId + ":" + ratio + ":" + (contribution.isUnpriced() ? "unpriced"
                                    : contribution.priceSource.name()));
        }
        return String.join(",", parts);
    }

    static long gcd(long left, long right) {
        long a = Math.abs(left);
        long b = Math.abs(right);
        while (b != 0L) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    /**
     * The financial destination of one contribution. A zero-value financial movement (an unpriced
     * cost or gain) is routed by its direction and positive evidence — never to Audit — so an
     * unpriced Supply stays in Costs/Supplies with an incomplete marker.
     */
    static Category financialCategory(Transaction transaction, Contribution contribution) {
        Category category = contribution.category;
        if (category != Category.AUDIT) return category;
        if (contribution.auditOnly || transaction.getType() == TransactionType.TRANSFER
                || transaction.getCorrection() == Correction.TRANSFER) {
            return Category.AUDIT;
        }
        long quantity = contribution.quantityDelta;
        if (quantity > 0L) return Category.GAIN;
        if (quantity == 0L) return Category.AUDIT;
        TransactionType type = transaction.autoType();
        if (type == TransactionType.TRADE) return Category.MARKET;
        if (type == TransactionType.PK_DEATH_LOSS || type == PK_SUPPLY_COST || type == TransactionType.PROCESSING
                || transaction.getActionKind() != null) {
            return type == TransactionType.PK_DEATH_LOSS ? Category.LOSS : Category.SUPPLY;
        }
        return Category.LOSS;
    }

    static Table tableFor(Category category) {
        switch (category) {
            case GAIN:
                return Table.GAINS;
            case SUPPLY:
                return Table.COSTS_SUPPLIES;
            case LOSS:
                return Table.COSTS_LOSS;
            case MARKET:
                return Table.MARKET;
            case AUDIT:
            default:
                return Table.AUDIT;
        }
    }

    static String coverageKey(boolean unpriced) {
        return unpriced ? "incomplete" : "complete";
    }

    static Coverage coverage(boolean unpriced) {
        return unpriced ? Coverage.INCOMPLETE : Coverage.COMPLETE;
    }

    static String lifecycleLabel(Row market) {
        boolean sell = market.side == GeOffer.Side.SELL;
        switch (market.lifecycle) {
            case PENDING:
                return "×" + market.offeredQty;
            case PARTIALLY_EXECUTED:
                return (sell ? "Part sold " : "Part bought ") + market.filledQty + "/" + market.offeredQty;
            case EXECUTED_UNSETTLED:
                return sell ? msg("sold-collect") : msg("bought-collect");
            case PARTIALLY_REALIZED:
                return msg("partially-realized");
            case CANCELLED_RETURNED:
                return "Returned";
            case AMBIGUOUS:
                return "Review";
            case UNAVAILABLE:
            default:
                return msg("detail-unavailable");
        }
    }
}

/**
 * One correction-aware, read-only financial contribution derived from a retained Flow. The parent
 * transaction remains the only correction identity and accounting authority.
 */
class Contribution {
    enum Category {
        GAIN, SUPPLY, LOSS, MARKET, AUDIT
    }

    String contributionId;
    long timestampEpochMillis;
    int flowOrdinal;
    int itemId;
    String itemName;
    long quantityDelta;

    /** Raw automatic Flow value, retained for audit and correction comparison. */
    long valueDelta;

    /** Effective, correction-aware additive value used by financial presentation. */
    long effectiveValue;

    PriceSource priceSource;
    boolean counted;
    Category category;

    /** Transient exact action label captured from trusted client evidence; display/search only. */
    String actionDisplayName;

    boolean needsReview;
    boolean corrected;
    boolean split;
    boolean auditOnly;
    boolean normalizedConsume;
    long normalizedQuantity;
    List<Flow> rawFlows;

    Contribution(Transaction transaction, int flowOrdinal, int itemId, String itemName, long quantityDelta,
            long valueDelta, long effectiveValue, PriceSource priceSource, Category category, boolean needsReview,
            boolean auditOnly, boolean normalizedConsume, long normalizedQuantity, List<Flow> rawFlows) {
        contributionId = transaction.getId() + (normalizedConsume ? ":consume:" + flowOrdinal : ":flow:" + flowOrdinal);
        this.timestampEpochMillis = transaction.timestampEpochMillis;
        this.flowOrdinal = flowOrdinal;
        this.itemId = itemId;
        this.itemName = nonBlank(itemName, "Unknown item");
        this.quantityDelta = quantityDelta;
        this.valueDelta = valueDelta;
        this.effectiveValue = effectiveValue;
        this.priceSource = priceSource == null ? UNKNOWN : priceSource;
        counted = transaction.isCounted();
        this.category = category == null ? Category.AUDIT : category;
        actionDisplayName = transaction.spellName();
        this.needsReview = needsReview;
        corrected = transaction.getCorrection() != null && transaction.getCorrection() != AUTO;
        split = isSplit(transaction);
        this.auditOnly = auditOnly;
        this.normalizedConsume = normalizedConsume;
        this.normalizedQuantity = normalizedQuantity;
        this.rawFlows = unmodifiableList(new ArrayList<>(rawFlows));
    }

    static Contribution from(Transaction transaction, Flow flow, int ordinal) {
        if (transaction == null || flow == null) return null;
        Amounts amounts = Accounting.flow(transaction, flow);
        long effective = amounts.included ? amounts.getNet() : 0L;
        Category category = category(transaction, flow, amounts);
        return new Contribution(transaction, ordinal, flow.itemId, flow.itemName, flow.quantityDelta, flow.valueDelta,
                effective, flow.getPriceSource(), category, reviewState(transaction, flow), !amounts.included, false,
                SafeMath.abs(flow.quantityDelta), singletonList(flow));
    }

    /**
     * Project one receipt into exact per-flow rows, coalescing only proven full→partial consume
     * pairs. Raw flows are retained on normalized rows for detail and audit.
     */
    static List<Contribution> project(Transaction transaction) {
        return transaction == null ? emptyList() : transaction.memo(10, () -> build(transaction));
    }

    static List<Contribution> build(Transaction transaction) {
        List<Flow> flows = transaction.getFlows();
        if (empty(flows)) return emptyList();
        var result = new ArrayList<Contribution>();
        var consumed = new HashSet<Integer>();
        for (ActionReader.PartialUse pair : Accounting.consumePairs(transaction)) {
            Flow loss = flows.get(pair.lossIndex);
            Flow remainder = flows.get(pair.remainderIndex);
            Amounts lossAmounts = Accounting.flow(transaction, loss);
            Amounts remainderAmounts = Accounting.flow(transaction, remainder);
            long effective = SafeMath.safeAdd(lossAmounts.getNet(), remainderAmounts.getNet());
            if (!lossAmounts.included || !remainderAmounts.included || effective > 0L) continue;
            int ordinal = min(pair.lossIndex, pair.remainderIndex);
            result.add(new Contribution(transaction, ordinal, loss.itemId, normalizedName(loss.itemName), -min(
                                    SafeMath.abs(loss.quantityDelta), SafeMath.abs(remainder.quantityDelta)),
                            SafeMath.safeAdd(loss.valueDelta, remainder.valueDelta), effective, loss.getPriceSource(),
                            Category.SUPPLY, reviewState(transaction, loss), false, true, min(
                                    SafeMath.abs(loss.quantityDelta), SafeMath.abs(remainder.quantityDelta)),
                            List.of(loss, remainder)));
            consumed.add(pair.lossIndex);
            consumed.add(pair.remainderIndex);
        }
        for (int i = 0; i < flows.size(); i++) {
            if (consumed.contains(i)) continue;
            Flow flow = flows.get(i);
            Amounts amounts = Accounting.flow(transaction, flow);
            // The last dose drunk, its vial smashed, is one more dose of the same potion.
            if (transaction.getActionKind() == DRINK && transaction.getCorrection() == AUTO && amounts.included
                    && (transaction.getType() == TransactionType.CONSUMPTION
                            || transaction.getType() == TransactionType.PK_SUPPLY_COST) && flow.quantityDelta < 0L
                    && ActionReader.trailingDose(flow.itemName) == 1) {
                long doses = SafeMath.abs(flow.quantityDelta);
                result.add(new Contribution(transaction, i, flow.itemId, normalizedName(flow.itemName), -doses,
                                flow.valueDelta, amounts.getNet(), flow.getPriceSource(), Category.SUPPLY,
                                reviewState(transaction, flow), false, true, doses, List.of(flow)));
                continue;
            }
            Contribution contribution = from(transaction, flow, i);
            if (contribution != null) result.add(contribution);
        }
        result.sort(Comparator.comparingInt((Contribution itemData) -> itemData.flowOrdinal)
                        .thenComparing((itemData -> itemData.contributionId)));
        return unmodifiableList(result);
    }

    static String normalizedName(String name) {
        if (name == null) return "Unknown item";
        String doseBase = ActionReader.doseBaseName(name);
        if (!doseBase.equals(name.trim())) return doseBase;
        String lower = Phrases.lower(name);
        if (has(lower, "pizza", "cake", "pie")) {
            for (String prefix : new String[] {"whole ", "full ", "half ", "1/2 "})
                if (lower.startsWith(prefix)) return name.trim().substring(prefix.length()).trim();
        }
        return name.trim();
    }

    static Category category(Transaction transaction, Flow flow, Amounts amounts) {
        if (!amounts.available || amounts.transfer || !amounts.included) return Category.AUDIT;
        if (amounts.revenue > 0L) return Category.GAIN;
        if (amounts.costs <= 0L) return Category.AUDIT;
        switch (CostKind.of(transaction, flow)) {
            case SUPPLIES:
                return Category.SUPPLY;
            case MARKET:
                return Category.MARKET;
            case LOSS:
                return Category.LOSS;
            default:
                return Category.LOSS;
        }
    }

    /** A shared-loot split share, told by the split marker on the reason or explanation. */
    static boolean isSplit(Transaction transaction) {
        return ItemSplits.isSplitReason(transaction.correctionReason())
                || ItemSplits.isSplitReason(transaction.getExplanation());
    }

    /** The receipt's activity as a display source; the default "General" reads as no source. */
    static String sourceOf(Transaction transaction) {
        String activity = transaction.getActivityName();
        return empty(activity) || "General".equalsIgnoreCase(activity) ? "" : activity;
    }

    /** Category of a flow-less note receipt: its own included Net decides the direction. */
    static Category noteCategory(Transaction transaction, Amounts amounts, boolean neutral) {
        if (neutral || !amounts.included) return Category.AUDIT;
        if (amounts.getNet() > 0L) return Category.GAIN;
        return transaction.autoType() == TransactionType.TRADE ? Category.MARKET : Category.LOSS;
    }

    static boolean reviewState(Transaction transaction, Flow flow) {
        boolean unpriced = flow.unitPrice <= 0
                        && (flow.getPriceSource() == UNKNOWN || flow.getPriceSource() == UNPRICED);
        boolean uncertain = transaction.getCorrection() == AUTO && transaction.getConfidence() == Confidence.UNCERTAIN;
        String blob = (orEmpty(transaction.getExplanation()) + " " + orEmpty(transaction.correctionReason()))
                        .toLowerCase(Locale.ROOT);
        return uncertain || unpriced || blob.contains("calibrat") || blob.contains("uncertain")
                        && (has(blob, "plank", "sack", "pouch", "bag", "container"))
                || blob.contains("charge") && blob.contains("warn");
    }

    boolean isUnpriced() {
        return itemUnitPriceUnavailable() && (priceSource == UNKNOWN || priceSource == UNPRICED);
    }

    boolean itemUnitPriceUnavailable() {
        for (Flow flow : rawFlows) {
            if (flow != null && flow.unitPrice > 0) return false;
        }
        return true;
    }
}

/**
 * One owned, unfiltered financial snapshot. Session save stamps cover close/history changes that do
 * not advance Engine.revision. Clock, pause and activity are overlays, never cached money. A
 * changed Market presentation reuses the same financial facts rather than refolding receipts.
 */
class MoneySnapshot {
    final List<Object> key;
    final List<Row> marketRows;
    final Map<Session, SessionFacts> facts;
    final Map<String, Rollup> rollups;

    /** Derived scopes memoize only from the captured facts; they cannot read mutable receipts. */
    final Map<List<Session>, SessionScope> scopes = new HashMap<>();

    static MoneySnapshot of(Engine engine) {
        List<Session> sessions = engine.uniqueSessions();
        var keyParts = new ArrayList<Object>();
        keyParts.add(engine.getRevision());
        keyParts.add(engine.getActiveSession());
        keyParts.add(engine.getHistory());
        for (Session session : sessions) {
            keyParts.add(session);
            keyParts.add(session == null ? 0L : session.saveStamp);
            keyParts.add(session == null ? "" : session.getGrindId());
        }
        for (SavedState.SavedGrind grind : engine.getSavedGrinds(true))
            keyParts.add(grind.getGrindId());
        List<Row> rows = engine.marketRows();
        MoneySnapshot cached = engine.snapshot;
        if (cached != null && cached.key.equals(keyParts) && cached.marketRows.equals(rows))
            return cached;
        return engine.snapshot = new MoneySnapshot(engine, sessions, keyParts, rows, cached);
    }

    MoneySnapshot(Engine engine, List<Session> sessions, List<Object> keyParts, List<Row> rows,
            MoneySnapshot previous) {
        key = unmodifiableList(keyParts);
        marketRows = unmodifiableList(new ArrayList<>(rows));
        if (previous != null && previous.key.equals(key)) {
            facts = previous.facts;
            rollups = previous.rollups;
            return;
        }
        var built = new IdentityHashMap<Session, SessionFacts>();
        for (Session session : sessions)
            if (session != null && !built.containsKey(session)) {
                SessionFacts reused = previous == null ? null : previous.facts.get(session);
                built.put(session, session.isClosed() && reused != null && reused.stamp == session.saveStamp ? reused
                                : new SessionFacts(session));
            }
        facts = unmodifiableMap(built);
        var byGrind = new HashMap<String, Rollup>();
        for (SavedState.SavedGrind grind : engine.getSavedGrinds(true))
            byGrind.put(grind.getGrindId(), new Rollup(engine.completedLinked(grind.getGrindId())));
        rollups = unmodifiableMap(byGrind);
    }

    /** Canonical metrics keep the exact live clock and Session's saturating rate formula. */
    Metrics metrics(Session session, long now) {
        return session == null ? Metrics.none() : fact(session).metrics(session, now);
    }

    SessionFacts fact(Session session) {
        return Objects.requireNonNull(facts.get(session), "Session outside captured scope");
    }

    /** One session's shared facts, read from its retained history copy when it has one. */
    GrindHistory.Run run(Engine engine, Session session, long now) {
        Session retained = engine.historySession(session.getId());
        return new GrindHistory.Run(session, metrics(retained != null ? retained : session, now));
    }

    SessionScope scope(List<Session> sessions) {
        List<Session> scopeKey = unmodifiableList(new ArrayList<>(sessions));
        return scopes.computeIfAbsent(scopeKey, SessionScope::new);
    }

    /**
     * Visibility derives another presentation from frozen receipts; hero folds remain unfiltered.
     */
    Semantics.Result view(SessionScope scope, Predicate<Flow> visible) {
        return visible == null ? scope.groups : Semantics.capture(scope.transactions, marketRows, scope.ids, visible);
    }

    /** Canonical per-session money and independently ordered Ledger deltas. */
    static class SessionFacts {
        /** Closed facts reuse the existing save stamp; a correction invalidates them. */
        final long stamp;

        final Metrics money;
        final List<Transaction> transactions;
        final List<Long> deltas;
        final boolean cut;
        final int review;
        final Accounting.MarketFold market;

        SessionFacts(Session session) {
            stamp = session.saveStamp;
            money = session.metrics(0L);
            Session.CompactedContribution compacted = session.compactedContribution();
            cut = compacted.rows > 0L || compacted.revenue != 0L || compacted.costs != 0L;
            var copies = new ArrayList<Transaction>();
            var deltaList = new ArrayList<Long>();
            deltaList.add(compacted.getNet());
            int reviews = 0;
            for (Transaction transaction : session.getTransactions()) {
                if (transaction == null) continue;
                Transaction copied = copy(transaction);
                copies.add(copied);
                Accounting.Amounts amounts = Accounting.transaction(copied);
                if (amounts.included) deltaList.add(amounts.getNet());
                if (ReviewRules.needsOwnerDecision(copied)) reviews++;
            }
            transactions = unmodifiableList(copies);
            deltas = unmodifiableList(deltaList);
            review = reviews;
            market = Accounting.marketFold(transactions);
        }

        Metrics metrics(Session session, long now) {
            long elapsed = session.getElapsedMillis(now);
            return new Metrics(session.getActivityHint(), session.paused, elapsed, money.revenue, money.costs,
                    money.net, Session.hourly(money.net, elapsed), money.suppliesCosts, money.otherCosts,
                    money.costSplitAvailable);
        }
    }

    /** The last/best rollup is over completed lineage members, in the original order. */
    class Rollup {
        final int count;
        final boolean has;
        final long last, at, best;

        /** Every finished run's Net and Active Time, for the Grind's average GP/h. */
        long total, millis;

        Rollup(List<Session> sessions) {
            Session latest = null;
            long bestNet = Long.MIN_VALUE, lastNet = 0L;
            for (Session session : sessions) {
                long net = runMoney(session);
                total += net;
                millis += session.getElapsedMillis(0L);
                if (latest == null || session.startedAtEpochMillis > latest.startedAtEpochMillis) {
                    latest = session;
                    lastNet = net;
                }
                bestNet = Math.max(bestNet, net);
            }
            count = sessions.size();
            has = latest != null;
            last = lastNet;
            at = has ? latest.startedAtEpochMillis : 0L;
            best = bestNet == Long.MIN_VALUE ? 0L : bestNet;
        }

        long runMoney(Session session) {
            return fact(session).money.net;
        }
    }

    /** Scope construction preserves saturation order, compaction, Market ownership and groups. */
    class SessionScope {
        final Set<String> ids;
        final List<Transaction> transactions;
        final Map<String, Row> markets;
        final long net;
        final boolean cut;
        final Semantics.Result groups;

        SessionScope(List<Session> sessions) {
            var idSet = new LinkedHashSet<String>();
            var all = new ArrayList<Transaction>();
            long total = 0L;
            boolean anyCut = false;
            for (Session session : sessions) {
                if (session == null) continue;
                SessionFacts sessionFacts = fact(session);
                idSet.add(session.getId());
                all.addAll(sessionFacts.transactions);
                anyCut |= sessionFacts.cut;
                for (long delta : sessionFacts.deltas) total = safeAdd(total, delta);
            }
            ids = unmodifiableSet(idSet);
            transactions = unmodifiableList(all);
            net = total;
            cut = anyCut;
            var byPresentation = new HashMap<String, Row>();
            for (Row row : marketRows)
                if (row != null && ids.contains(row.scopeSessionId))
                    byPresentation.put(row.presentationId, row);
            markets = unmodifiableMap(byPresentation);
            groups = Semantics.capture(transactions, marketRows, ids, null);
        }
    }

    /** Own the receipt and its flows: later corrections cannot mutate this financial revision. */
    static Transaction copy(Transaction source) {
        Transaction copy = new Transaction();
        copy.id = source.id;
        copy.timestampEpochMillis = source.timestampEpochMillis;
        copy.type = source.type;
        copy.context = source.context;
        copy.note = source.note;
        copy.activityName = source.activityName;
        copy.counted = source.counted;
        copy.revenue = source.revenue;
        copy.costs = source.costs;
        copy.confidence = source.confidence;
        copy.explanation = source.explanation;
        copy.correction = source.correction;
        copy.correctionReason = source.correctionReason;
        copy.encounterId = source.encounterId;
        copy.sourceClaimId = source.sourceClaimId;
        copy.actionKind = source.actionKind;
        copy.observedActionLabel = source.observedActionLabel;
        copy.castItemId = source.castItemId;
        var flows = new ArrayList<Flow>();
        for (Flow flow : source.getFlows()) {
            flows.add(new Flow(flow.itemId, flow.itemName, flow.quantityDelta, flow.unitPrice, flow.valueDelta,
                            flow.priceSource, flow.priceCapturedAtEpochMillis));
        }
        copy.flows = unmodifiableList(flows);
        return copy;
    }
}

/**
 * Derived, read-only Market settlement context over durable schema-104 custody lifecycles.
 *
 * <p>It never books GP, never owns corrections and never becomes a second financial authority. For
 * a REALIZED lifecycle it explains the canonical settlement already booked; pending Execution Edge
 * is exposed separately and is never folded into a realized total.
 */
class MarketRows {
    /** Behavioural lifecycle state; several durable stages may map to one state. */
    enum Lifecycle {
        PENDING, PARTIALLY_EXECUTED, EXECUTED_UNSETTLED, PARTIALLY_REALIZED, REALIZED, CANCELLED_RETURNED, AMBIGUOUS,
        UNAVAILABLE
    }

    /** Coverage of the realized quantity: proven prior tracked value, partial, or unknown. */
    enum Coverage {
        FULLY_KNOWN, PARTIALLY_KNOWN, FULLY_UNKNOWN
    }

    /** One immutable Market presentation row; no booking behaviour. */
    @EqualsAndHashCode
    static class Row {
        String presentationId;
        long timestampEpochMillis;
        int itemId;
        String itemName;
        GeOffer.Side side;
        long offeredQty;
        long filledQty;

        long settledQty;
        long observedSettlementGp;
        long settlementAdjustmentGp;

        /**
         * Observed gross-to-cash gap matching the per-item GE rule. It is the shared accepted-tax
         * predicate: when the booked settlement is a counted unknown-basis sale, this exact tax is
         * the booked Net contribution; otherwise it stays presentation evidence only.
         */
        long inferredGeTaxGp;

        /** Actual after-tax Received per unit when exactly divisible; -1 when not provable. */
        long receivedEachGp;

        /**
         * Realized execution/gross GP attributable to the collected quantity; 0 until collected.
         */
        /** Settlement/collection observation time; 0 when no exact settlement receipt resolves. */
        long settlementAtEpochMillis;

        long realizedResultGp;
        boolean realizedResultCorrectionAware;

        /**
         * An explicit counted correction can supply a Result even when automatic basis is unknown.
         */
        boolean manualFinancialResult;

        /**
         * The lifecycle failed closed and was quarantined: its booked allocation was never observed
         * as collected cash, so presentation must never show it as Received.
         */
        boolean collectionAmbiguous;

        /**
         * Booked contribution state: a fully-unknown sale whose counted AUTO settlement contributes
         * exactly {@code -inferredGeTaxGp}. Derivation is from the booked transaction (counted +
         * AUTO + effective net equals the shared accepted tax), never from coverage plus an
         * inferred tax alone; a pre-change uncounted sale stays UNCOUNTED.
         */
        boolean knownCostOnly;

        /**
         * SELL: known-coverage quantity consumed by realized settlements; BUY: realized quantity.
         */
        /** SELL: known basis consumed; BUY: created known basis (the exact spend). */
        long trackedBasisConsumedGp;

        Coverage coverage;
        Lifecycle lifecycle;
        String settlementId;

        /** The offer's earlier settlement ids, space-separated (a sale collected in parts). */
        String earlierSettlements;

        /**
         * The session whose scope shows this row: the session holding its booked settlement,
         * otherwise the listing (origin) session.
         */
        String scopeSessionId;

        /** Display name of the listing session when the sale settled in another one; else empty. */
        String listedDuring;

        Row(GeOffer record, java.util.function.Function<String, Transaction> lookup, Engine sessions) {
            presentationId = record.offerId;
            String originSessionId = record.originSessionId;
            Session holder = sessions == null || record.settlementId.isEmpty() ? null
                            : sessions.sessionHolding(record.settlementId);
            scopeSessionId = holder == null ? originSessionId : holder.getId();
            Session origin = holder == null || originSessionId.isEmpty() || originSessionId.equals(holder.getId())
                            ? null : sessions.sessionById(originSessionId);
            listedDuring = origin == null ? "" : origin.getName();
            timestampEpochMillis = record.placedAtEpochMillis;
            itemId = record.itemId;
            itemName = record.itemName;
            side = record.side;
            offeredQty = record.offeredQty;
            filledQty = record.filledQty;
            boolean sell = record.side == GeOffer.Side.SELL;
            long settled = record.settledQty;
            settledQty = settled;
            observedSettlementGp = record.settledCashGp;
            settlementAdjustmentGp = sell && settled > 0L ? record.settledCashGp - record.settledExecutionGp : 0L;
            // Actual after-tax settled unit value, only when the exact division is proven; -1
            // means "not exactly derivable" and is never silently rounded for display.
            long receivedCash = SafeMath.abs(record.settledCashGp);
            // One shared accepted-tax predicate for booking and presentation: the per-fill tax
            // when every fill was observed at its own price, else the uniform rule.
            inferredGeTaxGp = sell && record.fillTaxExact && record.settledTaxGp > 0L
                                    && record.settledExecutionGp - receivedCash == record.settledTaxGp
                            ? record.settledTaxGp : sell ? GeTaxRule.acceptedTax(record.itemId,
                                            record.settledExecutionGp, settled, receivedCash) : 0L;
            receivedEachGp = sell && settled > 0L && receivedCash > 0L && receivedCash % settled == 0L
                            ? receivedCash / settled : -1L;
            Transaction settlement = lookup == null || record.settlementId.isEmpty() ? null
                            : lookup.apply(record.settlementId);
            settlementAtEpochMillis = settlement == null ? 0L : settlement.timestampEpochMillis;
            if (settlement != null) {
                // Correction-aware: consume the effective canonical truth of every booked
                // settlement; an offer
                // collected in parts has one per collect (owner 2026-10-09).
                boolean missing = false;
                for (String id : (record.settlementId + Phrases.orEmpty(record.earlierSettlements)).split(" ")) {
                    Transaction part = id.isEmpty() ? null : lookup.apply(id);
                    // An earlier receipt compacted away: fall back to the booked accumulator.
                    missing |= part == null && !id.isEmpty();
                    if (part == null) continue;
                    realizedResultGp += Accounting.transaction(part).getNet();
                    manualFinancialResult |= part.getCorrection() == REVENUE || part.getCorrection() == COST;
                }
                if (missing) realizedResultGp = record.realizedResultGp;
                realizedResultCorrectionAware = !missing;
            } else {
                // The canonical receipt is no longer inspectable; the booked accumulator is
                // exposed but explicitly marked as not correction-recomputed.
                realizedResultGp = record.realizedResultGp;
            }
            long knownConsumed = sell ? Math.min(record.consumedTrackedQty, settled) : settled;
            trackedBasisConsumedGp = settled <= 0L ? 0L : sell ? record.consumedTrackedBasisGp
                                    : SafeMath.abs(record.settledCashGp);
            coverage = settled <= 0L ? Coverage.FULLY_UNKNOWN : settled - knownConsumed <= 0L ? Coverage.FULLY_KNOWN
                                    : knownConsumed <= 0L ? Coverage.FULLY_UNKNOWN : Coverage.PARTIALLY_KNOWN;
            // KNOWN_COST_ONLY is a booked state, not merely coverage plus an inferred tax: a
            // pre-change uncounted sale keeps UNCOUNTED even when its custody evidence still
            // proves the historical tax, and a corrected sale is no longer automatic.
            knownCostOnly = sell && settled > 0L && coverage == Coverage.FULLY_UNKNOWN && settlement != null
                            && settlement.autoCounted() && settlement.getCorrection() == AUTO && inferredGeTaxGp > 0L
                            && realizedResultGp == -inferredGeTaxGp;
            lifecycle = lifecycleOf(record);
            collectionAmbiguous = record.collectionAmbiguous;
            settlementId = record.settlementId;
            earlierSettlements = Phrases.orEmpty(record.earlierSettlements);
        }

        boolean isRealizedIncluded() {
            return lifecycle == Lifecycle.REALIZED || lifecycle == Lifecycle.PARTIALLY_REALIZED;
        }
    }

    /**
     * Project the durable custody lifecycles. {@code settlementLookup} resolves a settlement id to
     * its effective canonical transaction so corrections recompute the realized result; when it
     * cannot resolve one, the row fails closed and flags the value as not correction-recomputed.
     */
    static List<Row> rows(List<GeOffer> records, java.util.function.Function<String, Transaction> settlementLookup,
            Engine sessions) {
        if (Phrases.empty(records)) return Collections.emptyList();
        var rows = new ArrayList<Row>(records.size());
        for (GeOffer record : records) {
            if (record != null && !record.offerId.isEmpty())
                rows.add(new Row(record, settlementLookup, sessions));
        }
        return Collections.unmodifiableList(rows);
    }

    /** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
    static Lifecycle lifecycleOf(GeOffer record) {
        if (record == null) return Lifecycle.UNAVAILABLE;
        if (record.isUnbased()) return Lifecycle.UNAVAILABLE;
        if (record.confidence == GeOffer.CustodyConfidence.AMBIGUOUS || record.quantityModified
                || record.collectionAmbiguous) {
            return Lifecycle.AMBIGUOUS;
        }
        if (record.filledQty <= 0L) {
            if (record.isCancelled() || record.cleared) return Lifecycle.CANCELLED_RETURNED;
            return Lifecycle.PENDING;
        }
        if (record.settledQty > 0L && record.settledQty < record.filledQty) {
            return Lifecycle.PARTIALLY_REALIZED;
        }
        if (record.settledQty >= record.filledQty && record.settledQty > 0L)
            return Lifecycle.REALIZED;
        if (record.filledQty >= record.offeredQty) return Lifecycle.EXECUTED_UNSETTLED;
        return Lifecycle.PARTIALLY_EXECUTED;
    }
}

/**
 * The Ledger's Market wording: one source for rows, receipts and the short GE facts, so the page
 * and the read-model tests never drift apart.
 */
class MarketText {
    /** True when a Market group has proven settled quantity and an observed settlement amount. */
    static boolean marketSettled(Group group, Row market) {
        return group.market && market != null && market.isRealizedIncluded() && market.settledQty > 0L
                && market.observedSettlementGp != 0L;
    }

    /** The Market row's primary signed value: observed settlement, else the canonical result. */
    static long marketPrimary(LedgerData data, Group group) {
        Row market = data.marketRow(group.marketPresentationId);
        if (!marketSettled(group, market)) return group.value;
        boolean sell = "SELL".equals(market.side.name());
        return sell ? Math.abs(market.observedSettlementGp) : -Math.abs(market.observedSettlementGp);
    }

    /** The Market row's secondary line: explicit side + Result, or the lifecycle context. */
    static String marketLead(LedgerData data, Group group) {
        if (!group.market) return groupLead(group);
        Row market = data.marketRow(group.marketPresentationId);
        if (marketSettled(group, market)) {
            return ("SELL".equals(market.side.name()) ? "Sold" : "Bought") + " \u00b7 Result "
                    + (group.coverage == Semantics.Coverage.COMPLETE ? signed(group.value) : "\u2014");
        }
        if (group.coverage != Semantics.Coverage.COMPLETE) return group.contextLine;
        return "Result " + signed(group.value);
    }

    static boolean receiptRealized(Receipt receipt) {
        Row market = receipt.marketSettlement;
        return market != null && market.isRealizedIncluded() && market.settledQty > 0L;
    }

    /** Signed observed settlement cash for the collected quantity (SELL positive, BUY negative). */
    static long receiptPrimary(Receipt receipt) {
        Row market = receipt.marketSettlement;
        if (!receiptRealized(receipt)) return 0L;
        return market.side == SELL ? Math.abs(market.observedSettlementGp) : -Math.abs(market.observedSettlementGp);
    }

    static String receiptTitle(Receipt receipt) {
        Row market = receipt.marketSettlement;
        if (receiptRealized(receipt) && market.settledQty > 1L) {
            return receipt.itemName + "  " + times(market.settledQty);
        }
        return receipt.itemName;
    }

    /**
     * Sold/Bought with the canonical Result as secondary information and a settlement-relative age.
     */
    static String receiptSub(Receipt receipt, long capturedAt) {
        Row market = receipt.marketSettlement;
        String sub;
        if (receiptRealized(receipt)) {
            boolean resultAvailable = market.realizedResultCorrectionAware && (market.side != SELL
                                    || market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                    || market.manualFinancialResult);
            String result = resultAvailable ? "Result " + exactSigned(market.realizedResultGp) + " gp"
                            : "Result \u2014";
            sub = (market.side == SELL ? "Sold" : "Bought") + " \u00b7 " + result;
        } else {
            sub = market == null ? receipt.verb : orderStateText(market);
        }
        return sub + " \u00b7 " + age(SafeMath.nonNeg(capturedAt - receipt.at));
    }

    /**
     * One word for where an offer stands: Offering / Buying while it is up with nothing filled,
     * Pending while part-filled, Sold / Bought once the exchange completes it, Canceled when
     * returned.
     */
    static String stateWord(Row market) {
        if (market == null) return "Pending";
        boolean sell = market.side == SELL;
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return "Canceled";
            case AMBIGUOUS:
                return "Review";
            case EXECUTED_UNSETTLED:
            case REALIZED:
                return sell ? "Sold" : "Bought";
            case PENDING:
                return sell ? "Selling" : "Buying";
            default:
                return "Pending";
        }
    }

    /**
     * The exact human Market lines ("label|value") for one lifecycle row; the renderer and the
     * read-model tests consume this one source. Coverage-aware: a sale whose prior tracked value is
     * unknown shows liquidation cash with an unavailable Result, never a fake zero basis. Only a
     * booked, uncorrected PROVEN tax earns a main GE tax row; exempt, unproven and pre-change sales
     * and fully known results never show one.
     */
    static List<String> humanLines(Row market, boolean corrected) {
        var lines = new ArrayList<String>();
        String fillState = fillStateText(market);
        if (!fillState.isEmpty()) lines.add(fillState);
        if (!market.listedDuring.isEmpty()) {
            // Cross-Grind: booked where it was collected; the listing session is named, never
            // rewritten.
            lines.add("Listed during|" + market.listedDuring);
        }
        boolean sell = market.side == SELL;
        if (!market.isRealizedIncluded() || market.settledQty <= 0L) {
            if (sell && market.collectionAmbiguous && market.settledQty > 0L) {
                // D1: a quarantined settlement's cash was never observed; Received is never shown
                // as if the player had actually collected it.
                lines.add(msg("received-proven"));
            }
            return lines;
        }
        lines.add((sell ? "Received" : "Spent") + "|" + exact(Math.abs(market.observedSettlementGp)) + " gp");
        boolean unknown = sell && market.coverage == MarketRows.Coverage.FULLY_UNKNOWN;
        if (sell) {
            if (market.receivedEachGp > 0L) {
                // The actual after-tax settled unit value; shown only when exactly divisible.
                lines.add("Received each|" + exact(market.receivedEachGp) + " gp");
            }
            lines.add(msg("previously-counted") + (unknown ? "Unknown" : exact(market.trackedBasisConsumedGp) + " gp"));
            lines.add("Result|" + (unknown && !market.manualFinancialResult ? "\u2014"
                                    : market.realizedResultCorrectionAware ? exactSigned(market.realizedResultGp)
                                                    + " gp" + (market.coverage == MarketRows.Coverage.PARTIALLY_KNOWN
                                                            ? " \u00b7 Partial" : "")
                                                    + (corrected ? msg("corrected") : "") : "\u2014"));
            if (market.knownCostOnly) {
                // Only the BOOKED state with an AUTO correction earns this row: the tax is a real
                // market cost here. Uncounted/pre-change, corrected and fully known sales never
                // show it (a full Result already has the tax inside Received).
                lines.add("GE tax|" + exactSigned(-market.inferredGeTaxGp) + " gp");
            }
        } else {
            lines.add("Tracked value|" + exact(market.trackedBasisConsumedGp) + " gp");
            lines.add("Result|" + (market.realizedResultCorrectionAware ? exactSigned(market.realizedResultGp) + " gp"
                                            + (corrected ? msg("corrected") : "") : "0 gp"));
        }
        if (market.settlementAdjustmentGp != 0L && market.inferredGeTaxGp <= 0L) {
            // An unexplained gap is the receipt's own answer and must stay visible.
            lines.add("Adjustment|" + exactSigned(market.settlementAdjustmentGp) + " gp");
        }
        return lines;
    }

    /** Compact fill/collected state; omitted when offered, filled and collected agree. */
    static String fillStateText(Row market) {
        long offered = market.offeredQty;
        long filled = market.filledQty;
        long collected = market.settledQty;
        if (offered <= 0L || (filled == collected && collected == offered)) return "";
        if (filled == collected) return "Filled " + exact(collected) + " / " + exact(offered);
        return "Filled " + exact(filled) + " / " + exact(offered) + " \u00b7 collected " + exact(collected);
    }

    /** Order-state wording that never mislabels placement time as settlement time. */
    static String orderStateText(Row market) {
        boolean sell = market.side == SELL;
        String side = sell ? "Sell order" : "Buy order";
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return msg("returned-canceled");
            case AMBIGUOUS:
                return "Needs review";
            case UNAVAILABLE:
                return "Unavailable";
            case EXECUTED_UNSETTLED:
                return (sell ? "Sold " : "Bought ") + exact(market.filledQty) + "/" + exact(market.offeredQty)
                        + msg("awaiting-collection");
            case PARTIALLY_EXECUTED:
                return (sell ? "Part sold " : "Part bought ") + exact(market.filledQty) + "/" + exact(market.offeredQty)
                        + " \u00b7 Pending";
            default:
                return stateWord(market);
        }
    }

    static String orderWhy(Row market) {
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return msg("canceled-returned");
            case AMBIGUOUS:
                return msg("lifecycle-could-settled");
            case EXECUTED_UNSETTLED:
                return msg("filled-quantity");
            default:
                return msg("collected-settlement");
        }
    }

    static String groupLead(Group group) {
        if (group.market) return group.contextLine;
        if (!group.usedBy.isEmpty()) return "Used by " + group.usedBy;
        return group.actionLabel;
    }
}

/**
 * The stacked-table Ledger read model: three independently paginated financial tables (Gains, Costs
 * with a local Loss/Supplies selector, Market) built from the shared semantic projection, plus
 * compact Review/Corrected audit lists and a reconciled Total block.
 *
 * <p>Search runs against COMPLETE semantic groups: a group that owns a matching underlying resource
 * or receipt is kept whole, so a header total never becomes a partial child total. Correction
 * targets are always exact canonical contribution receipts, never a group.
 */
@AllArgsConstructor
class LedgerData {
    @RequiredArgsConstructor
    enum Scope {
        CURRENT_GRIND("Current Grind"), TODAY("Runs today"), HISTORY("Recent Grind");
        final String label;

        public String toString() {
            return label;
        }
    }

    /**
     * The Losses table's chips (owner 2026-09-28): LOSS is shown as Items; Charges are measured
     * charge use.
     */
    @RequiredArgsConstructor
    enum CostView {
        ALL("All"), SUPPLIES("Supplies"), LOSS("Items"), CHARGES("Charges");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** A not-yet-settled count in Pending: an unopened loot key or chest, or a death reclaim. */
    @AllArgsConstructor
    static class PendingItem {
        int itemId;
        String name;
        long quantity;
        String tip;

        /** An observed open chest's exact worth; 0 when unseen, looted or unpriced. */
        long value;
    }

    /** Compact audit subsections; never peer financial tabs. */
    @RequiredArgsConstructor
    enum Audit {
        REVIEW("Review"), CORRECTED("Corrected");
        final String label;

        public String toString() {
            return label;
        }
    }

    /** Raw Market settlement row for a semantic group's presentation id, or null. */
    Row marketRow(String presentationId) {
        return presentationId == null || presentationId.isEmpty() ? null : marketRows.get(presentationId);
    }

    /** Presentation-only Ledger query state; it never enters SavedState. */
    static class Entry {
        Scope scope;
        public String historySessionId;
        public String historyName;
        CostView costView;
        String search;
        public String highlightTransactionId;
        public String highlightContributionId;
        public String highlightGroupId;

        /** Deep-link request to expand one audit subsection. */
        public Audit focusAudit;

        Entry(Scope scope, String historySessionId, String historyName) {
            this(scope, historySessionId, historyName, CostView.ALL, "", null, null, null, null);
        }

        Entry(Scope scope, String historySessionId, String historyName, CostView costView, String search,
                String highlightTransactionId, String highlightContributionId, String highlightGroupId,
                Audit focusAudit) {
            this.scope = scope == null ? Scope.CURRENT_GRIND : scope;
            this.historySessionId = historySessionId;
            this.historyName = historyName;
            this.costView = costView == null ? CostView.ALL : costView;
            this.search = Phrases.orEmpty(search);
            this.highlightTransactionId = highlightTransactionId;
            this.highlightContributionId = highlightContributionId;
            this.highlightGroupId = highlightGroupId;
            this.focusAudit = focusAudit;
        }

        static Entry current() {
            return new Entry(Scope.CURRENT_GRIND, null, null);
        }

        static Entry current(Audit audit, String transactionId) {
            return new Entry(Scope.CURRENT_GRIND, null, null, CostView.ALL, "", transactionId, null, null, audit);
        }

        Entry withCostView(CostView next) {
            return new Entry(scope, historySessionId, historyName, next, search, null, null, null, null);
        }

        Entry withSearch(String next) {
            return new Entry(scope, historySessionId, historyName, costView, next, null, null, null, null);
        }

        Entry withScope(Scope next, String sessionId, String name) {
            return new Entry(next, sessionId, name, costView, search, null, null, null, null);
        }

        /** Selection stores only stable identity. */
        Entry withSelection(String transactionId, String contributionId, String groupId) {
            return new Entry(scope, historySessionId, historyName, costView, search, transactionId, contributionId,
                    groupId, focusAudit);
        }
    }

    /** One table's full filtered set and its header facts; the page table pages it. */
    @AllArgsConstructor
    static class TableData {
        List<Group> groups;
        long total;
        int entries;
        int receipts;
    }

    /** Review audit: exact review rows with the full eligible bulk count. */
    @AllArgsConstructor
    static class ReviewData {
        List<ReviewRow> rows;
        int scopeCount;
    }

    /** The open detail: one semantic group, its exact receipts, and any selected exact receipt. */
    @AllArgsConstructor
    static class Detail {
        Group group;
        List<Receipt> receipts;
        public Receipt exact;
    }

    /** One resource inside a receipt: "Chaos rune ×4" on its line. */
    @AllArgsConstructor
    static class ResourceSummary {
        int itemId;
        String itemName;
        String usedBy;
        long quantity;
        long value;

        /** Sum of only the known children; shown with explicit wording when incomplete. */
        long knownSubtotal;

        boolean incomplete;
    }

    /** One receipt row: exactly one canonical transaction inside the selected group. */
    @AllArgsConstructor
    static class Card {
        String transactionId;
        long at;
        long value;
        long knownSubtotal;
        boolean incomplete;

        /** Representative contribution id for selecting the receipt. */
        String contributionId;

        List<ResourceSummary> resources;

        /** "Chaos rune ×4 · Death rune ×2" for the row's tooltip. */
        String summaryText() {
            var text = new StringBuilder();
            for (ResourceSummary resource : resources) {
                if (text.length() > 0) text.append(" \u00b7 ");
                text.append(resource.itemName);
                if (resource.quantity > 1L) text.append(' ').append(Fmt.times(resource.quantity));
            }
            return text.toString();
        }
    }

    /** Additive facts shared by a receipt card and each resource inside it. */
    @RequiredArgsConstructor
    static class ReceiptTotal {
        final Receipt head;
        long quantity;
        long value;
        long known;
        long at;
        boolean incomplete;
        String representative = "";

        void add(Receipt receipt) {
            quantity += receipt.quantity;
            value += receipt.value;
            at = Math.max(at, receipt.at);
            incomplete |= receipt.unpriced;
            if (!receipt.unpriced) known += receipt.value;
            if (representative.isEmpty()) representative = receipt.contributionId;
        }
    }

    static class ReceiptCard extends ReceiptTotal {
        Map<String, ReceiptTotal> resources = new LinkedHashMap<>();

        ReceiptCard(Receipt head) {
            super(head);
        }

        void add(Receipt receipt) {
            super.add(receipt);
            resources.computeIfAbsent(receipt.itemId + "|" + receipt.usedBy, ignored -> new ReceiptTotal(receipt))
                    .add(receipt);
        }

        Card card() {
            var summaries = new ArrayList<ResourceSummary>();
            for (ReceiptTotal resource : resources.values()) {
                Receipt receipt = resource.head;
                summaries.add(new ResourceSummary(receipt.itemId, receipt.itemName, receipt.usedBy, resource.quantity,
                                resource.value, resource.known, resource.incomplete));
            }
            summaries.sort(Comparator.comparing((ResourceSummary summary) -> summary.itemName,
                                    String.CASE_INSENSITIVE_ORDER).thenComparingInt(summary -> summary.itemId));
            return new Card(head.transactionId, at, value, known, incomplete, representative, summaries);
        }
    }

    /** One pass builds each transaction card and its resources; newest cards first. */
    static List<Card> cards(Group group) {
        if (group == null) return emptyList();
        var byTransaction = new LinkedHashMap<String, ReceiptCard>();
        for (Receipt receipt : group.receipts) {
            byTransaction.computeIfAbsent(receipt.transactionId, ignored -> new ReceiptCard(receipt)).add(receipt);
        }
        var cards = new ArrayList<Card>(byTransaction.size());
        for (ReceiptCard card : byTransaction.values()) cards.add(card.card());
        cards.sort(Comparator.comparingLong((Card card) -> card.at).reversed()
                        .thenComparing(card -> card.transactionId));
        return cards;
    }

    /** The query this data answers: scope, Costs view, search and the selected receipt. */
    Entry entry;

    String scopeName;
    boolean readOnly;
    boolean compacted;
    boolean detailedHistoryAvailable;
    long net;

    /** Capture time for honest ages without leaking the engine. */
    long capturedAt;

    TableData gains;
    TableData costs;
    TableData market;
    ReviewData review;
    List<Receipt> corrected;
    Summary total;
    boolean reconciled;
    public Detail detail;

    /**
     * Raw Market settlement rows by presentation id; presentation-only lookup for value semantics.
     */
    Map<String, Row> marketRows;

    /** Group counts for the Losses chips by {@link CostView} ordinal, whatever view is selected. */
    int[] costCounts;

    /** Keys and reclaim waiting to settle; current scope only, with an exact observed chest value. */
    List<PendingItem> pendingItems;

    static LedgerData capture(Engine engine, long now, Entry entry) {
        Entry e = entry == null ? Entry.current() : entry;
        List<Session> sessions = sessionsFor(engine, e, now);
        String scopeName = scopeName(engine, e);
        MoneySnapshot summary = engine.summary();
        MoneySnapshot.SessionScope scope = summary.scope(sessions);
        Set<String> ids = scope.ids;
        List<Transaction> transactions = scope.transactions;
        boolean compacted = scope.cut;
        long scopeNet = scope.net;
        List<Row> marketRows = summary.marketRows;
        Map<String, Row> markets = scope.markets;
        Result result = scope.groups;
        var tables = new EnumMap<Table, List<Group>>(Table.class);
        for (Table table : Table.values()) tables.put(table, new ArrayList<>());
        for (Group group : result.groups) {
            if (!group.reviewRequired && group.matchesText(e.search))
                tables.get(group.table).add(group);
        }
        List<Group> gainsAll = tables.get(Table.GAINS);
        List<Group> suppliesAll = tables.get(Table.COSTS_SUPPLIES);
        List<Group> lossAll = tables.get(Table.COSTS_LOSS);
        List<Group> marketAll = tables.get(Table.MARKET);
        var supplies = new ArrayList<Group>();
        var charges = new ArrayList<Group>();
        for (Group group : suppliesAll) (group.chargeUse ? charges : supplies).add(group);
        var costsAll = new ArrayList<Group>(e.costView == CostView.LOSS ? lossAll
                                : e.costView == CostView.CHARGES ? charges : supplies);
        if (e.costView == CostView.ALL) {
            // All is every list together, newest first like every other table (owner 1.3.3).
            costsAll.addAll(charges);
            costsAll.addAll(lossAll);
            costsAll.sort(Comparator.comparingLong((Group group) -> group.latestActivityAt).reversed()
                            .thenComparing(group -> group.semanticGroupId));
        }
        TableData gains = tableData(gainsAll, false);
        TableData costs = tableData(costsAll, false);
        TableData market = tableData(marketAll, true);
        var financial = new ArrayList<Group>();
        financial.addAll(gainsAll);
        financial.addAll(suppliesAll);
        financial.addAll(lossAll);
        financial.addAll(marketAll);
        Summary total = Semantics.summarize(financial);
        // ── audit ────────────────────────────────────────────────────────────────────────────
        var reviewRows = new ArrayList<ReviewRow>();
        int reviewScopeCount = 0;
        for (ReviewRow reviewRow : engine.getReviewRows(now)) {
            if (reviewRow != null && ids.contains(reviewRow.sessionId)) {
                reviewScopeCount++;
                if (!readOnly(e) && matchesSearch(reviewRow, e.search)) reviewRows.add(reviewRow);
            }
        }
        var review = new ReviewData(reviewRows, reviewScopeCount);
        var correctedAll = new ArrayList<Receipt>();
        for (Group group : result.groups) {
            for (Receipt receipt : group.receipts) {
                if ((receipt.corrected || receipt.split) && receipt.matchesSearch(e.search))
                    correctedAll.add(receipt);
            }
        }
        correctedAll.sort(Comparator.comparingLong((Receipt receipt) -> receipt.at).reversed()
                        .thenComparing(receipt -> receipt.contributionId));
        // ── selected detail ──────────────────────────────────────────────────────────────────
        Detail detail = null;
        String anchorContribution = e.highlightContributionId;
        String anchorTransaction = e.highlightTransactionId;
        String anchorGroup = e.highlightGroupId;
        if (anchorGroup != null || anchorContribution != null || anchorTransaction != null) {
            Group group = findGroup(result.groups, anchorGroup, anchorContribution, anchorTransaction);
            if (group != null) {
                List<Receipt> receipts = group.receipts;
                Receipt exact = null;
                if (anchorContribution != null) {
                    for (Receipt receipt : receipts) {
                        if (receipt.contributionId.equals(anchorContribution)) {
                            exact = receipt;
                            break;
                        }
                    }
                }
                if (exact == null && anchorTransaction != null) {
                    exact = firstReceipt(receipts, anchorTransaction);
                }
                // A one-entry group opens on its entry: a list of one only repeated the header.
                if (exact == null && !group.market && oneEntry(receipts)) exact = receipts.get(0);
                detail = new Detail(group, receipts, exact);
            }
        }
        long net = scopeNet;
        boolean detailedHistoryAvailable = !compacted || total.entries > 0;
        boolean reconciled = total.coverageComplete && safeAdd(safeAdd(total.gains, total.costs), total.market) == net;
        return new LedgerData(e, scopeName, readOnly(e), compacted, detailedHistoryAvailable, net, now, gains, costs,
                market, review, correctedAll, total, reconciled, detail, markets,
                new int[] {0, supplies.size(), lossAll.size(), charges.size()}, pendingItems(engine, e, now));
    }

    static List<PendingItem> pendingItems(Engine engine, Entry e, long now) {
        var items = new ArrayList<PendingItem>();
        if (e.scope != Scope.CURRENT_GRIND || readOnly(e)) return items;
        var valued = new HashSet<Integer>();
        for (SavedState.PendingClaim claim : engine.getPendingClaims()) {
            if (claim == null || !claim.isValid()) continue;
            KeyChestCatalogue.Entry chest = KeyChestCatalogue.entryForKey(claim.itemOrKeyId);
            String name = chest != null ? chest.getChestName() + " key" : claim.itemOrKeyId >= ItemID.WILDY_LOOT_KEY0
                                            && claim.itemOrKeyId <= ItemID.WILDY_LOOT_KEY4 ? "Loot key"
                                    : "Key or chest";
            // One chest per key type: stacked claims of it name the same worth once.
            long value = valued.add(claim.itemOrKeyId) ? engine.pendingKeyValue(claim.itemOrKeyId, now) : 0L;
            items.add(new PendingItem(claim.itemOrKeyId, name, claim.getQuantity(), name + msg("opened-books"), value));
        }
        DeathReclaimStatus reclaim = engine.deathStatus();
        if (reclaim.awaiting) {
            items.add(new PendingItem(0, "Death reclaim", Math.max(1L, reclaim.outstandingItemCount),
                            msg("items-waiting-reclaimed"), 0L));
        }
        return items;
    }

    /** Ordinary tables sum every group; Market's header includes only complete results. */
    static TableData tableData(List<Group> groups, boolean market) {
        long total = 0L;
        var receipts = new LinkedHashSet<String>();
        for (Group group : groups) {
            if (!market || !group.incomplete()) total = safeAdd(total, group.value);
            receipts.addAll(group.receiptIds);
        }
        return new TableData(groups, total, groups.size(), receipts.size());
    }

    /** True when a Market lifecycle is genuinely pending rather than already realized. */
    static boolean isPending(Lifecycle lifecycle) {
        return lifecycle == PENDING || lifecycle == PARTIALLY_EXECUTED || lifecycle == EXECUTED_UNSETTLED
                || lifecycle == PARTIALLY_REALIZED;
    }

    static Group findGroup(List<Group> groups, String groupId, String contributionId, String transactionId) {
        // Most specific anchor first: a receipt's own contribution must win over the transaction
        // it came from, or a shop drill lands on the wrong side of the trade (one rune's group).
        for (Group group : groups) {
            if (!group.reviewRequired && group.containsContribution(groupId)) return group;
        }
        for (Group group : groups) {
            if (!group.reviewRequired && group.containsContribution(contributionId)) return group;
        }
        for (Group group : groups) {
            if (!group.reviewRequired && group.containsTransaction(transactionId)) return group;
        }
        // Review-required groups are still openable from the audit list detail.
        for (Group group : groups) {
            if (group.containsContribution(contributionId) || group.containsTransaction(transactionId)) return group;
        }
        return null;
    }

    /** True when every receipt belongs to one entry: a group whose list would hold one row. */
    static boolean oneEntry(List<Receipt> receipts) {
        return !receipts.isEmpty() && receipts.stream().allMatch(r -> r.transactionId.equals(receipts.get(0).transactionId));
    }

    static Receipt firstReceipt(List<Receipt> receipts, String transactionId) {
        Receipt found = null;
        for (Receipt receipt : receipts) {
            if (receipt.transactionId.equals(transactionId) && (found == null || receipt.at >= found.at)) {
                found = receipt;
            }
        }
        return found;
    }

    static List<Session> sessionsFor(Engine engine, Entry entry, long now) {
        var sessions = new ArrayList<Session>();
        if (entry.scope == Scope.HISTORY) {
            Session historical = entry.historySessionId == null ? null : engine.historySession(entry.historySessionId);
            if (historical != null) sessions.add(historical);
            return sessions;
        }
        Session active = engine.getActiveSession();
        if (active != null) sessions.add(active);
        if (entry.scope == Scope.TODAY) {
            ZoneId zone = engine.timeZone();
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            for (Session historical : engine.getHistory()) {
                if (historical == null || (active != null && historical.getId().equals(active.getId()))) continue;
                LocalDate day = Instant.ofEpochMilli(historical.startedAtEpochMillis).atZone(zone).toLocalDate();
                if (day.equals(today)) sessions.add(historical);
            }
        }
        return sessions;
    }

    static String scopeName(Engine engine, Entry entry) {
        if (entry.scope == Scope.HISTORY) {
            if (entry.historyName != null && !entry.historyName.isEmpty()) return entry.historyName;
            Session historical = entry.historySessionId == null ? null : engine.historySession(entry.historySessionId);
            return historical == null ? "Recent Grind" : historical.getName();
        }
        return entry.scope == Scope.TODAY ? "Runs today" : "Current Grind";
    }

    static boolean readOnly(Entry entry) {
        return entry.scope != Scope.CURRENT_GRIND;
    }

    static boolean matchesSearch(ReviewRow row, String query) {
        if (Phrases.blank(query)) return true;
        String needle = Phrases.lower(query);
        if (Phrases.containsFolded(needle, row.why)) return true;
        for (ReviewRow.Item item : row.items) {
            if (Phrases.containsFolded(needle, item.name)) return true;
        }
        return false;
    }

    // ── correction support (unchanged semantics: exact canonical ids only) ─────────────────────
    static class CorrectionPreview {
        long currentNet;
        long afterNet;
        long change;
        long revision;

        CorrectionPreview(long currentNet, long afterNet, long revision) {
            this.currentNet = currentNet;
            this.afterNet = afterNet;
            change = afterNet - currentNet;
            this.revision = revision;
        }

        String note() {
            return change == 0L ? msg("net-gp") : "Net changes by " + Fmt.signed(change) + " gp.";
        }
    }

    /**
     * Net if a split keeps {@code keep} of {@code itemId}: the split itself runs on a detached copy
     * of the receipt, so the preview and the applied split cannot differ.
     */
    static CorrectionPreview splitPreview(Engine engine, String transactionId, int itemId, long keep, long now) {
        synchronized (engine) {
            long currentNet = engine.getMetrics(now).net;
            Session session = engine.getActiveSession();
            Transaction transaction = session == null ? null : session.findTransaction(transactionId);
            if (transaction == null)
                return new CorrectionPreview(currentNet, currentNet, engine.getRevision());
            var gson = JsonCodec.gson();
            Transaction copy = gson.fromJson(gson.toJson(transaction), Transaction.class);
            copy.takeQuantity(itemId, true, copy.quantity(itemId, true) - keep);
            long before = transaction.isCounted() ? transaction.getNet() : 0L;
            long after = copy.isCounted() ? copy.getNet() : 0L;
            return new CorrectionPreview(currentNet, currentNet - before + after, engine.getRevision());
        }
    }

    static CorrectionPreview preview(Engine engine, String transactionId, Correction correction, long now) {
        Session session = engine.getActiveSession();
        long currentNet = engine.getMetrics(now).net;
        if (session == null || transactionId == null || correction == null) {
            return new CorrectionPreview(currentNet, currentNet, engine.getRevision());
        }
        Transaction transaction = session.findTransaction(transactionId);
        if (transaction == null)
            return new CorrectionPreview(currentNet, currentNet, engine.getRevision());
        long before = transaction.isCounted() ? transaction.getNet() : 0L;
        long after = transaction.netUnder(correction);
        return new CorrectionPreview(currentNet, currentNet - before + after, engine.getRevision());
    }
}

/** Detached, read-only summary of one transaction awaiting an owner decision. */
class ReviewRow {
    String transactionId;
    String sessionId;
    long timestampEpochMillis;
    long ageMillis;
    List<Item> items;
    long value;
    String why;
    Set<Decision> validDecisions;

    ReviewRow(String transactionId, String sessionId, long timestampEpochMillis, long ageMillis, List<Item> items,
            long value, String why, Set<Decision> validDecisions) {
        this.transactionId = orEmpty(transactionId);
        this.sessionId = orEmpty(sessionId);
        this.timestampEpochMillis = nonNeg(timestampEpochMillis);
        this.ageMillis = nonNeg(ageMillis);
        this.items = Collections.unmodifiableList(items == null ? new ArrayList<Item>() : new ArrayList<>(items));
        this.value = value;
        this.why = orEmpty(why);
        EnumSet<Decision> copy = validDecisions == null || validDecisions.isEmpty() ? EnumSet.noneOf(Decision.class)
                        : EnumSet.copyOf(validDecisions);
        this.validDecisions = Collections.unmodifiableSet(copy);
    }

    /** Detached item summary; value is the signed flow value for this item. */
    static class Item {
        int itemId;
        String name;
        long quantityDelta;
        long valueDelta;

        Item(int itemId, String name, long quantityDelta, long valueDelta) {
            this.itemId = itemId;
            this.name = orEmpty(name);
            this.quantityDelta = quantityDelta;
            this.valueDelta = valueDelta;
        }
    }
}
