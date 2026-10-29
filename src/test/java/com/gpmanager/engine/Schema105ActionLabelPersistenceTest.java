package com.gpmanager;

import com.gpmanager.GpManagerConfig.Retention;

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
        Session session = new Session("General", 1_000L, SessionMode.GENERAL);
        Transaction labelled = cast("Ice Burst", 2_000L);
        Transaction generic = genericCast(3_000L);
        session.addTransaction(labelled, 100, false);
        session.addTransaction(generic, 100, false);
        SavedState state = new SavedState(session, null, Collections.emptyList());
        assertEquals(110, state.schemaVersion);
        String json = new Gson().toJson(state);
        assertTrue("the bounded label is a stable JSON field",
            json.contains("\"observedActionLabel\":\"Ice Burst\""));

        SavedState loaded = new Gson().fromJson(json, SavedState.class);
        Transaction restored = FixtureReads.currentSession(loaded).transactions().get(0);
        assertNotNull(restored.actionLabel());
        assertEquals("Ice Burst", restored.actionLabel().value);
        assertEquals("the reloaded receipt keeps its money", labelled.net(), restored.net());
        assertNull("an unlabelled receipt stays generic",
            FixtureReads.currentSession(loaded).transactions().get(1).actionLabel());
    }

    @Test
    public void malformedOversizedControlAndGenericLabelsNormalizeToNull()
    {
        assertNull("invalid punctuation is rejected",
            normalizedWire("\"Ice Burst!!!\"", false).actionLabel());
        assertNull("oversized labels are rejected",
            normalizedWire("\"" + repeat("Ice Burst ", 7) + "\"", false).actionLabel());
        assertNull("control characters are rejected",
            normalizedWire("\"Ice\\u0000Burst\"", false).actionLabel());
        assertNull("generic Cast text never becomes an exact label",
            normalizedWire("\"Cast\"", false).actionLabel());
        assertNull("Autocast text never becomes an exact label",
            normalizedWire("\"Autocast\"", false).actionLabel());

        Transaction sanitized = normalizedWire("\"<col=ff9040>Ice Burst</col>\"", false);
        assertNotNull(sanitized.actionLabel());
        assertEquals("markup is stripped, never displayed", "Ice Burst",
            sanitized.actionLabel().value);
        assertFalse(sanitized.actionLabel().value.contains("<"));

        Transaction nonCast = normalizedWire("\"Ice Burst\"", true);
        assertNull("a non-CAST receipt cannot keep a stale spell label",
            nonCast.actionLabel());
    }

    @Test
    public void labelMutationsNeverDriftFinancialFingerprint()
    {
        Engine engine = engine();
        engine.startCustom("Vorkath", T0);
        Transaction transaction = cast(null, T0 + 100L);
        engine.session().addTransaction(transaction, 100, false);
        String before = fingerprint(transaction);
        long netBefore = engine.metrics(T0 + 200L).net;

        transaction.setActionLabel(ActionLabel.of("Ice Burst"));
        assertEquals("setting a label never changes money", before, fingerprint(transaction));
        transaction.setActionLabel(ActionLabel.of("Smoke Barrage"));
        assertEquals("changing a label never changes money", before, fingerprint(transaction));
        transaction.setActionLabel(null);
        assertEquals("clearing a label never changes money", before, fingerprint(transaction));
        assertEquals(netBefore, engine.metrics(T0 + 200L).net);

        Transaction trade = trade();
        String tradeBefore = fingerprint(trade);
        trade.setActionLabel(ActionLabel.of("Ice Burst"));
        assertNull("a non-CAST receipt never reads a spell label", trade.actionLabel());
        assertEquals("GE-shaped receipts stay financially identical", tradeBefore, fingerprint(trade));
    }

    @Test
    public void ownerFenceKeepsBookedLabelsAndClearsOnlyPendingEvidence()
    {
        Engine engine = engine();
        engine.startCustom("Vorkath", T0);
        Transaction cast = cast("Ice Burst", T0 + 100L);
        engine.session().addTransaction(cast, 100, false);
        long net = engine.metrics(T0 + 200L).net;

        engine.clearActionEvidence();

        assertNotNull("booked labels survive the presentation owner fence",
            cast.actionLabel());
        assertEquals("Ice Burst", cast.actionLabel().value);
        assertEquals("the fence never touches money", net, engine.metrics(T0 + 200L).net);
    }

    @Test
    public void onlyTheCurrentSchemaIsRead()
    {
        assertEquals(110, SavedState.CURRENT_SCHEMA_VERSION);
        SavedState old = new SavedState();
        old.setSchemaVersion(107);
        assertFalse("an older schema is never read", old.isSupportedSchema());
        assertFalse((old.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        SavedState future = new SavedState();
        future.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION + 1);
        assertTrue((future.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        assertFalse("a newer schema is refused/read-only", future.isSupportedSchema());
        assertEquals("a restored profile saves as the current schema", 110,
            engine().savedState().schemaVersion);
    }

    @Test
    public void exactSpellGroupsSurviveRestartAndMergeLaterCasts()
    {
        long now = System.currentTimeMillis();
        Engine source = engine();
        source.startCustom("Vorkath", now);
        source.session().addTransaction(cast("Ice Burst", now + 1_000L), 100, false);
        source.session().addTransaction(cast("Ice Burst", now + 2_000L), 100, false);
        // Runes that name no spell (a staff covered them) keep the click; runes that do name one win.
        Transaction smoke = genericCast(now + 3_000L);
        smoke.setActionLabel(ActionLabel.of("Smoke Barrage"));
        source.session().addTransaction(smoke, 100, false);
        source.session().addTransaction(genericCast(now + 4_000L), 100, false);
        SavedState detached = source.savedState();
        assertEquals("detach keeps the bounded label", "Ice Burst",
            FixtureReads.currentSession(detached).transactions().get(0).actionLabel().value);

        Engine restored = engine();
        restored.restore(detached, now + 5_000L);
        Session session = restored.session();
        List<Transaction> restoredTransactions = session.transactions();
        assertEquals("Ice Burst", restoredTransactions.get(0).actionLabel().value);

        Semantics.Result before = SemanticProbe.capture(
            restoredTransactions, null, session.id(), null);
        Semantics.Group burst = groupNamed(before.groups, "Ice Burst");
        assertNotNull(burst);
        assertEquals(2, burst.count);
        String stableGroupId = burst.id;
        assertTrue("search terms survive restore", burst.terms.contains("Ice Burst"));

        session.addTransaction(cast("Ice Burst", now + 6_000L), 100, false);
        Semantics.Result after = SemanticProbe.capture(
            session.transactions(), null, session.id(), null);
        Semantics.Group merged = groupNamed(after.groups, "Ice Burst");
        assertNotNull(merged);
        assertEquals("a later exact cast merges into the restored group", 3, merged.count);
        assertEquals("semantic identity stays stable for search and deep links",
            stableGroupId, merged.id);
        assertNotNull("Smoke Barrage stays separate",
            groupNamed(after.groups, "Smoke Barrage"));
        Semantics.Group generic = groupNamed(after.groups, "Cast");
        assertNotNull("the old generic Cast stays generic", generic);
        assertNotEquals(merged.id, generic.id);
    }

    @Test
    public void repositoryRoundTripAndBackupRotationPreserveTheLabel() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Session session = new Session("General", 1_000L, SessionMode.GENERAL);
        Transaction labelled = cast("Ice Burst", 2_000L);
        session.addTransaction(labelled, 100, false);
        SavedState state = new SavedState(session, null, Collections.emptyList());
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        PersistenceProbe.save(repository, state);
        // Ordinary next save rotates the previous labelled generation into the backup file.
        session.addTransaction(cast("Smoke Barrage", 3_000L), 100, false);
        PersistenceProbe.save(repository, new SavedState(session, null, Collections.emptyList()));

        SavedState reloaded = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
        assertEquals(110, reloaded.schemaVersion);
        Transaction restored = FixtureReads.currentSession(reloaded).transactions().get(0);
        assertEquals(labelled.id(), restored.id());
        assertEquals(labelled.flows().size(), restored.flows().size());
        assertEquals(labelled.net(), restored.net());
        assertEquals("Ice Burst", restored.actionLabel().value);
        assertTrue("backup rotation does not strip the field",
            Files.readString(directory.resolve("sessions.backup.json")).contains("Ice Burst"));
    }

    @Test
    public void compactionDoesNotArchiveOrInferActionNames()
    {
        long old = T0 - 400L * 24L * 60L * 60L * 1_000L;
        Engine engine = engine();
        engine.startCustom("Old Grind", old);
        engine.session().addTransaction(cast("Ice Burst", old + 1_000L), 100, false);
        String sessionId = engine.session().id();
        engine.finishCustom(old + 2_000L);
        FixtureReads.compactOlderThan(engine, 1, T0);

        Session compacted = engine.historySession(sessionId);
        assertNotNull(compacted);
        assertTrue("the receipt detail is compacted away",
            compacted.transactions().isEmpty());
        assertTrue(compacted.compactedTransactionCount > 0);
        String json = new Gson().toJson(engine.savedState());
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
                public Retention receiptRetentionDays()
                {
                    return Retention.DAYS_365;
                }
            });
    }

    private static SavedState stateWith(Transaction transaction)
    {
        Session session = new Session("General", 1_000L, SessionMode.GENERAL);
        session.addTransaction(transaction, 100, false);
        return new SavedState(session, null, Collections.emptyList());
    }

    /** Serialize, inject one raw wire value and reload; every read revalidates the label. */
    private static Transaction normalizedWire(String rawJsonValue, boolean nonCast)
    {
        Transaction source = nonCast ? eat("Ice Burst") : cast("Ice Burst", 2_000L);
        String json = new Gson().toJson(stateWith(source))
            .replace("\"observedActionLabel\":\"Ice Burst\"", "\"observedActionLabel\":" + rawJsonValue);
        SavedState loaded = new Gson().fromJson(json, SavedState.class);
        return FixtureReads.currentSession(loaded).transactions().get(0);
    }

    private static Transaction cast(String label, long at)
    {
        Transaction transaction = new Transaction(at, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(560, "Death rune", -2L, 188, -376L, PriceSource.GRAND_EXCHANGE),
                new Flow(562, "Chaos rune", -4L, 106, -424L, PriceSource.GRAND_EXCHANGE),
                new Flow(555, "Water rune", -4L, 5, -20L, PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "Exact fixture", null);
        transaction.setAction(ActionKind.CAST);
        if (label != null)
        {
            transaction.setActionLabel(ActionLabel.of(label));
        }
        return transaction;
    }

    /** A Cast whose runes no spell pays (a Nature rune too), so it stays generic, never Ice Burst. */
    private static Transaction genericCast(long at)
    {
        Transaction transaction = new Transaction(at, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(560, "Death rune", -2L, 188, -376L, PriceSource.GRAND_EXCHANGE),
                new Flow(561, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "Exact fixture", null);
        transaction.setAction(ActionKind.CAST);
        return transaction;
    }

    private static Transaction eat(String label)
    {
        Transaction transaction = new Transaction(T0 + 100L,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(385, "Shark", -1L, 950, -950L,
                PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "Food", null);
        transaction.setAction(ActionKind.EAT);
        if (label != null)
        {
            transaction.setActionLabel(ActionLabel.of(label));
        }
        return transaction;
    }

    private static Transaction trade()
    {
        Transaction transaction = new Transaction(T0 + 100L, TransactionType.TRADE,
            Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(561, "Nature rune", -10L, 100, -1_000L,
                PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "GE sale", null);
        return transaction;
    }

    private static String fingerprint(Transaction transaction)
    {
        return transaction.net() + "|" + transaction.revenue() + "|" + transaction.costs()
            + "|" + transaction.counts() + "|" + transaction.type() + "|"
            + transaction.context() + "|" + transaction.correction() + "|"
            + transaction.action() + "|"
            + new Gson().toJson(transaction.flows());
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

    private static Semantics.Group groupNamed(
        List<Semantics.Group> groups, String name)
    {
        for (Semantics.Group group : groups)
        {
            if (name.equals(group.name))
            {
                return group;
            }
        }
        return null;
    }
}
