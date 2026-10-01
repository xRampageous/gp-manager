package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2B.3: schema-105 durable proven action labels are bounded presentation metadata that
 * survive restart, repository round trips and profile backup, while never touching money.
 */
public class Schema105ActionLabelPersistenceTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final String PROFILE = "profile-key-105";

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exactLabelIsDurableInSchema105StateAndReloads()
    {
        Session session = new Session("General", 1_000L);
        Transaction labelled = cast("Ice Burst", 2_000L);
        Transaction generic = genericCast(3_000L);
        session.addTransaction(labelled, 100);
        session.addTransaction(generic, 100);
        SavedState state = new SavedState(session, null, false, Collections.emptyList());
        assertEquals(108, state.schemaVersion);
        String json = new Gson().toJson(state);
        assertTrue("the bounded label is a stable JSON field",
            json.contains("\"observedActionLabel\":\"Ice Burst\""));

        SavedState loaded = new Gson().fromJson(json, SavedState.class);
        loaded.normalizeActionLabels();
        Transaction restored = loaded.getActiveSession().getTransactions().get(0);
        assertNotNull(restored.getObservedActionLabel());
        assertEquals("Ice Burst", restored.getObservedActionLabel().value());
        assertEquals("the reloaded receipt keeps its money", labelled.getNet(), restored.getNet());
        assertNull("an unlabelled receipt stays generic",
            loaded.getActiveSession().getTransactions().get(1).getObservedActionLabel());
    }

    @Test
    public void malformedOversizedControlAndGenericLabelsNormalizeToNull()
    {
        assertNull("invalid punctuation is rejected",
            normalizedWire("\"Ice Burst!!!\"", false).getObservedActionLabel());
        assertNull("oversized labels are rejected",
            normalizedWire("\"" + repeat("Ice Burst ", 7) + "\"", false).getObservedActionLabel());
        assertNull("control characters are rejected",
            normalizedWire("\"Ice\\u0000Burst\"", false).getObservedActionLabel());
        assertNull("generic Cast text never becomes an exact label",
            normalizedWire("\"Cast\"", false).getObservedActionLabel());
        assertNull("Autocast text never becomes an exact label",
            normalizedWire("\"Autocast\"", false).getObservedActionLabel());

        Transaction sanitized = normalizedWire("\"<col=ff9040>Ice Burst</col>\"", false);
        assertNotNull(sanitized.getObservedActionLabel());
        assertEquals("markup is stripped, never displayed", "Ice Burst",
            sanitized.getObservedActionLabel().value());
        assertFalse(sanitized.getObservedActionLabel().value().contains("<"));

        Transaction nonCast = normalizedWire("\"Ice Burst\"", true);
        assertNull("a non-CAST receipt cannot keep a stale spell label",
            nonCast.getObservedActionLabel());
    }

    @Test
    public void labelMutationsNeverDriftFinancialFingerprint()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        Transaction transaction = cast(null, T0 + 100L);
        engine.getActiveSession().addTransaction(transaction, 100);
        String before = fingerprint(transaction);
        long netBefore = engine.getMetrics(T0 + 200L).net;

        transaction.setObservedActionLabel(ActionLabel.of("Ice Burst"));
        assertEquals("setting a label never changes money", before, fingerprint(transaction));
        transaction.setObservedActionLabel(ActionLabel.of("Smoke Barrage"));
        assertEquals("changing a label never changes money", before, fingerprint(transaction));
        transaction.setObservedActionLabel(null);
        assertEquals("clearing a label never changes money", before, fingerprint(transaction));
        assertEquals(netBefore, engine.getMetrics(T0 + 200L).net);

        Transaction trade = trade();
        String tradeBefore = fingerprint(trade);
        trade.setObservedActionLabel(ActionLabel.of("Ice Burst"));
        assertNull("a non-CAST receipt never reads a spell label", trade.getObservedActionLabel());
        assertEquals("GE-shaped receipts stay financially identical", tradeBefore, fingerprint(trade));
    }

    @Test
    public void ownerFenceKeepsBookedLabelsAndClearsOnlyPendingEvidence()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        Transaction cast = cast("Ice Burst", T0 + 100L);
        engine.getActiveSession().addTransaction(cast, 100);
        long net = engine.getMetrics(T0 + 200L).net;

        engine.clearPendingActionEvidence();

        assertNotNull("booked labels survive the presentation owner fence",
            cast.getObservedActionLabel());
        assertEquals("Ice Burst", cast.getObservedActionLabel().value());
        assertEquals("the fence never touches money", net, engine.getMetrics(T0 + 200L).net);
    }

    @Test
    public void onlyTheOneZeroSchemaIsRead()
    {
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
        SavedState old = new SavedState();
        old.setSchemaVersion(107);
        assertFalse("a pre-1.0 schema is never read", old.isSupportedSchema());
        assertFalse((old.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        SavedState future = new SavedState();
        future.setSchemaVersion(109);
        assertTrue((future.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        assertFalse("a newer schema is refused/read-only", future.isSupportedSchema());
        assertEquals("a restored profile saves as the current schema", 108,
            engine().createSavedState().schemaVersion);
    }

    @Test
    public void exactSpellGroupsSurviveRestartAndMergeLaterCasts()
    {
        long now = System.currentTimeMillis();
        Engine source = engine();
        source.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        source.getActiveSession().addTransaction(cast("Ice Burst", now + 1_000L), 100);
        source.getActiveSession().addTransaction(cast("Ice Burst", now + 2_000L), 100);
        // Runes that name no spell (a staff covered them) keep the click; runes that do name one win.
        Transaction smoke = genericCast(now + 3_000L);
        smoke.setObservedActionLabel(ActionLabel.of("Smoke Barrage"));
        source.getActiveSession().addTransaction(smoke, 100);
        source.getActiveSession().addTransaction(genericCast(now + 4_000L), 100);
        SavedState detached = source.createSavedState();
        assertEquals("detach keeps the bounded label", "Ice Burst",
            detached.getActiveSession().getTransactions().get(0).getObservedActionLabel().value());

        Engine restored = engine();
        restored.restore(detached, now + 5_000L);
        Session session = restored.getActiveSession();
        List<Transaction> restoredTransactions = session.getTransactions();
        assertEquals("Ice Burst", restoredTransactions.get(0).getObservedActionLabel().value());

        SemanticFinancialProjection.Result before = SemanticFinancialProjection.capture(
            restoredTransactions, null, session.getId(), null);
        SemanticFinancialProjection.Group burst = groupNamed(before.groups, "Ice Burst");
        assertNotNull(burst);
        assertEquals(2, burst.receiptCount);
        String stableGroupId = burst.semanticGroupId;
        assertTrue("search terms survive restore", burst.searchTerms.contains("Ice Burst"));

        session.addTransaction(cast("Ice Burst", now + 6_000L), 100);
        SemanticFinancialProjection.Result after = SemanticFinancialProjection.capture(
            session.getTransactions(), null, session.getId(), null);
        SemanticFinancialProjection.Group merged = groupNamed(after.groups, "Ice Burst");
        assertNotNull(merged);
        assertEquals("a later exact cast merges into the restored group", 3, merged.receiptCount);
        assertEquals("semantic identity stays stable for search and deep links",
            stableGroupId, merged.semanticGroupId);
        assertNotNull("Smoke Barrage stays separate",
            groupNamed(after.groups, "Smoke Barrage"));
        SemanticFinancialProjection.Group generic = groupNamed(after.groups, "Cast");
        assertNotNull("the old generic Cast stays generic", generic);
        assertNotEquals(merged.semanticGroupId, generic.semanticGroupId);
    }

    @Test
    public void repositoryRoundTripAndBackupRotationPreserveTheLabel() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Session session = new Session("General", 1_000L);
        Transaction labelled = cast("Ice Burst", 2_000L);
        session.addTransaction(labelled, 100);
        SavedState state = new SavedState(session, null, false, Collections.emptyList());
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state);
        // Ordinary next save rotates the previous labelled generation into the backup file.
        session.addTransaction(cast("Smoke Barrage", 3_000L), 100);
        PersistenceProbe.save(repository, new SavedState(session, null, false, Collections.emptyList()));

        SavedState reloaded = new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load();
        assertEquals(108, reloaded.schemaVersion);
        Transaction restored = reloaded.getActiveSession().getTransactions().get(0);
        assertEquals(labelled.getId(), restored.getId());
        assertEquals(labelled.getFlows().size(), restored.getFlows().size());
        assertEquals(labelled.getNet(), restored.getNet());
        assertEquals("Ice Burst", restored.getObservedActionLabel().value());
        assertTrue("backup rotation does not strip the field",
            Files.readString(directory.resolve("sessions.backup.json")).contains("Ice Burst"));
    }

    @Test
    public void compactionDoesNotArchiveOrInferActionNames()
    {
        long old = T0 - 400L * 24L * 60L * 60L * 1_000L;
        Engine engine = engine();
        engine.startCustomSession("Old Grind", SessionMode.GENERAL, old);
        engine.getActiveSession().addTransaction(cast("Ice Burst", old + 1_000L), 100);
        String sessionId = engine.getActiveSession().getId();
        engine.finishCustomSession(old + 2_000L);
        engine.compactOlderThan(1, T0);

        Session compacted = engine.getHistorySession(sessionId);
        assertNotNull(compacted);
        assertTrue("the receipt detail is compacted away",
            compacted.getTransactions().isEmpty());
        assertTrue(compacted.compactedTransactionCount > 0);
        String json = new Gson().toJson(engine.createSavedState());
        assertFalse("a compacted receipt takes its action name with it",
            json.contains("Ice Burst"));
        assertFalse("no sidecar action-label archive exists",
            json.contains("actionLabelHistory"));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.DAYS_365;
                }
            });
    }

    private static SavedState stateWith(Transaction transaction)
    {
        Session session = new Session("General", 1_000L);
        session.addTransaction(transaction, 100);
        return new SavedState(session, null, false, Collections.emptyList());
    }

    /** Serialize, inject one raw wire value, reload and run the production normalization pass. */
    private static Transaction normalizedWire(String rawJsonValue, boolean nonCast)
    {
        Transaction source = nonCast ? eat("Ice Burst") : cast("Ice Burst", 2_000L);
        String json = new Gson().toJson(stateWith(source))
            .replace("\"observedActionLabel\":\"Ice Burst\"", "\"observedActionLabel\":" + rawJsonValue);
        SavedState loaded = new Gson().fromJson(json, SavedState.class);
        loaded.normalizeActionLabels();
        return loaded.getActiveSession().getTransactions().get(0);
    }

    private static Transaction cast(String label, long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(560, "Death rune", -2L, 188, -376L, PriceSource.GRAND_EXCHANGE),
                new Flow(562, "Chaos rune", -4L, 106, -424L, PriceSource.GRAND_EXCHANGE),
                new Flow(555, "Water rune", -4L, 5, -20L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        if (label != null)
        {
            transaction.setObservedActionLabel(ActionLabel.of(label));
        }
        return transaction;
    }

    /** A Cast whose runes no spell pays (a Nature rune too), so it stays generic, never Ice Burst. */
    private static Transaction genericCast(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(560, "Death rune", -2L, 188, -376L, PriceSource.GRAND_EXCHANGE),
                new Flow(561, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static Transaction eat(String label)
    {
        Transaction transaction = new Transaction(T0 + 100L, null,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(385, "Shark", -1L, 950, -950L,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Food", null);
        transaction.setActionKind(ActionKind.EAT);
        if (label != null)
        {
            transaction.setObservedActionLabel(ActionLabel.of(label));
        }
        return transaction;
    }

    private static Transaction trade()
    {
        Transaction transaction = new Transaction(T0 + 100L, null, TransactionType.TRADE,
            Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(561, "Nature rune", -10L, 100, -1_000L,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "GE sale", null);
        return transaction;
    }

    private static String fingerprint(Transaction transaction)
    {
        return transaction.getNet() + "|" + transaction.getRevenue() + "|" + transaction.getCosts()
            + "|" + transaction.isCounted() + "|" + transaction.getType() + "|"
            + transaction.getContext() + "|" + transaction.getCorrection() + "|"
            + transaction.getActionKind() + "|"
            + new Gson().toJson(transaction.getFlows());
    }

    private static String repeat(String text, int times)
    {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            out.append(text);
        }
        return out.toString();
    }

    private static SemanticFinancialProjection.Group groupNamed(
        List<SemanticFinancialProjection.Group> groups, String name)
    {
        for (SemanticFinancialProjection.Group group : groups)
        {
            if (name.equals(group.primaryName))
            {
                return group;
            }
        }
        return null;
    }
}
