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
        Ad session = new Ad("General", 1_000L);
        Ac labelled = cast("Ice Burst", 2_000L);
        Ac generic = genericCast(3_000L);
        session.kf(labelled, 100);
        session.kf(generic, 100);
        SavedState state = new SavedState(session, null, false, Collections.emptyList());
        assertEquals(108, state.schemaVersion);
        String json = new Gson().toJson(state);
        assertTrue("the bounded label is a stable JSON field",
            json.contains("\"observedActionLabel\":\"Ice Burst\""));

        SavedState loaded = new Gson().fromJson(json, SavedState.class);
        loaded.aar();
        Ac restored = loaded.getActiveSession().getTransactions().get(0);
        assertNotNull(restored.uc());
        assertEquals("Ice Burst", restored.uc().value());
        assertEquals("the reloaded receipt keeps its money", labelled.getNet(), restored.getNet());
        assertNull("an unlabelled receipt stays generic",
            loaded.getActiveSession().getTransactions().get(1).uc());
    }

    @Test
    public void malformedOversizedControlAndGenericLabelsNormalizeToNull()
    {
        assertNull("invalid punctuation is rejected",
            normalizedWire("\"Ice Burst!!!\"", false).uc());
        assertNull("oversized labels are rejected",
            normalizedWire("\"" + repeat("Ice Burst ", 7) + "\"", false).uc());
        assertNull("control characters are rejected",
            normalizedWire("\"Ice\\u0000Burst\"", false).uc());
        assertNull("generic Cast text never becomes an exact label",
            normalizedWire("\"Cast\"", false).uc());
        assertNull("Autocast text never becomes an exact label",
            normalizedWire("\"Autocast\"", false).uc());

        Ac sanitized = normalizedWire("\"<col=ff9040>Ice Burst</col>\"", false);
        assertNotNull(sanitized.uc());
        assertEquals("markup is stripped, never displayed", "Ice Burst",
            sanitized.uc().value());
        assertFalse(sanitized.uc().value().contains("<"));

        Ac nonCast = normalizedWire("\"Ice Burst\"", true);
        assertNull("a non-CAST receipt cannot keep a stale spell label",
            nonCast.uc());
    }

    @Test
    public void labelMutationsNeverDriftFinancialFingerprint()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        Ac transaction = cast(null, T0 + 100L);
        engine.getActiveSession().kf(transaction, 100);
        String before = fingerprint(transaction);
        long netBefore = engine.getMetrics(T0 + 200L).net;

        transaction.ahu(Bb.of("Ice Burst"));
        assertEquals("setting a label never changes money", before, fingerprint(transaction));
        transaction.ahu(Bb.of("Smoke Barrage"));
        assertEquals("changing a label never changes money", before, fingerprint(transaction));
        transaction.ahu(null);
        assertEquals("clearing a label never changes money", before, fingerprint(transaction));
        assertEquals(netBefore, engine.getMetrics(T0 + 200L).net);

        Ac trade = trade();
        String tradeBefore = fingerprint(trade);
        trade.ahu(Bb.of("Ice Burst"));
        assertNull("a non-CAST receipt never reads a spell label", trade.uc());
        assertEquals("GE-shaped receipts stay financially identical", tradeBefore, fingerprint(trade));
    }

    @Test
    public void ownerFenceKeepsBookedLabelsAndClearsOnlyPendingEvidence()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        Ac cast = cast("Ice Burst", T0 + 100L);
        engine.getActiveSession().kf(cast, 100);
        long net = engine.getMetrics(T0 + 200L).net;

        engine.ov();

        assertNotNull("booked labels survive the presentation owner fence",
            cast.uc());
        assertEquals("Ice Burst", cast.uc().value());
        assertEquals("the fence never touches money", net, engine.getMetrics(T0 + 200L).net);
    }

    @Test
    public void onlyTheOneZeroSchemaIsRead()
    {
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
        SavedState old = new SavedState();
        old.setSchemaVersion(107);
        assertFalse("a pre-1.0 schema is never read", old.ye());
        assertFalse((old.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        SavedState future = new SavedState();
        future.setSchemaVersion(109);
        assertTrue((future.schemaVersion > SavedState.CURRENT_SCHEMA_VERSION));
        assertFalse("a newer schema is refused/read-only", future.ye());
        assertEquals("a restored profile saves as the current schema", 108,
            engine().qm().schemaVersion);
    }

    @Test
    public void exactSpellGroupsSurviveRestartAndMergeLaterCasts()
    {
        long now = System.currentTimeMillis();
        Am source = engine();
        source.ajl("Vorkath", Cx.GENERAL, now);
        source.getActiveSession().kf(cast("Ice Burst", now + 1_000L), 100);
        source.getActiveSession().kf(cast("Ice Burst", now + 2_000L), 100);
        // Runes that name no spell (a staff covered them) keep the click; runes that do name one win.
        Ac smoke = genericCast(now + 3_000L);
        smoke.ahu(Bb.of("Smoke Barrage"));
        source.getActiveSession().kf(smoke, 100);
        source.getActiveSession().kf(genericCast(now + 4_000L), 100);
        SavedState detached = source.qm();
        assertEquals("detach keeps the bounded label", "Ice Burst",
            detached.getActiveSession().getTransactions().get(0).uc().value());

        Am restored = engine();
        restored.restore(detached, now + 5_000L);
        Ad session = restored.getActiveSession();
        List<Ac> restoredTransactions = session.getTransactions();
        assertEquals("Ice Burst", restoredTransactions.get(0).uc().value());

        Br.Result before = Br.capture(
            restoredTransactions, null, session.getId(), null);
        Br.Group burst = groupNamed(before.groups, "Ice Burst");
        assertNotNull(burst);
        assertEquals(2, burst.receiptCount);
        String stableGroupId = burst.semanticGroupId;
        assertTrue("search terms survive restore", burst.searchTerms.contains("Ice Burst"));

        session.kf(cast("Ice Burst", now + 6_000L), 100);
        Br.Result after = Br.capture(
            session.getTransactions(), null, session.getId(), null);
        Br.Group merged = groupNamed(after.groups, "Ice Burst");
        assertNotNull(merged);
        assertEquals("a later exact cast merges into the restored group", 3, merged.receiptCount);
        assertEquals("semantic identity stays stable for search and deep links",
            stableGroupId, merged.semanticGroupId);
        assertNotNull("Smoke Barrage stays separate",
            groupNamed(after.groups, "Smoke Barrage"));
        Br.Group generic = groupNamed(after.groups, "Cast");
        assertNotNull("the old generic Cast stays generic", generic);
        assertNotEquals(merged.semanticGroupId, generic.semanticGroupId);
    }

    @Test
    public void repositoryRoundTripAndBackupRotationPreserveTheLabel() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Ad session = new Ad("General", 1_000L);
        Ac labelled = cast("Ice Burst", 2_000L);
        session.kf(labelled, 100);
        SavedState state = new SavedState(session, null, false, Collections.emptyList());
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(repository, state);
        // Ordinary next save rotates the previous labelled generation into the backup file.
        session.kf(cast("Smoke Barrage", 3_000L), 100);
        PersistenceProbe.save(repository, new SavedState(session, null, false, Collections.emptyList()));

        SavedState reloaded = new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load();
        assertEquals(108, reloaded.schemaVersion);
        Ac restored = reloaded.getActiveSession().getTransactions().get(0);
        assertEquals(labelled.getId(), restored.getId());
        assertEquals(labelled.getFlows().size(), restored.getFlows().size());
        assertEquals(labelled.getNet(), restored.getNet());
        assertEquals("Ice Burst", restored.uc().value());
        assertTrue("backup rotation does not strip the field",
            Files.readString(directory.resolve("sessions.backup.json")).contains("Ice Burst"));
    }

    @Test
    public void compactionDoesNotArchiveOrInferActionNames()
    {
        long old = T0 - 400L * 24L * 60L * 60L * 1_000L;
        Am engine = engine();
        engine.ajl("Old Grind", Cx.GENERAL, old);
        engine.getActiveSession().kf(cast("Ice Burst", old + 1_000L), 100);
        String sessionId = engine.getActiveSession().getId();
        engine.sx(old + 2_000L);
        engine.pg(1, T0);

        Ad compacted = engine.ua(sessionId);
        assertNotNull(compacted);
        assertTrue("the receipt detail is compacted away",
            compacted.getTransactions().isEmpty());
        assertTrue(compacted.compactedTransactionCount > 0);
        String json = new Gson().toJson(engine.qm());
        assertFalse("a compacted receipt takes its action name with it",
            json.contains("Ice Burst"));
        assertFalse("no sidecar action-label archive exists",
            json.contains("actionLabelHistory"));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
                @Override
                public Db receiptRetentionDays()
                {
                    return Db.DAYS_365;
                }
            });
    }

    private static SavedState stateWith(Ac transaction)
    {
        Ad session = new Ad("General", 1_000L);
        session.kf(transaction, 100);
        return new SavedState(session, null, false, Collections.emptyList());
    }

    /** Serialize, inject one raw wire value, reload and run the production normalization pass. */
    private static Ac normalizedWire(String rawJsonValue, boolean nonCast)
    {
        Ac source = nonCast ? eat("Ice Burst") : cast("Ice Burst", 2_000L);
        String json = new Gson().toJson(stateWith(source))
            .replace("\"observedActionLabel\":\"Ice Burst\"", "\"observedActionLabel\":" + rawJsonValue);
        SavedState loaded = new Gson().fromJson(json, SavedState.class);
        loaded.aar();
        return loaded.getActiveSession().getTransactions().get(0);
    }

    private static Ac cast(String label, long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Ab(560, "Death rune", -2L, 188, -376L, Av.GRAND_EXCHANGE),
                new Ab(562, "Chaos rune", -4L, 106, -424L, Av.GRAND_EXCHANGE),
                new Ab(555, "Water rune", -4L, 5, -20L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        if (label != null)
        {
            transaction.ahu(Bb.of(label));
        }
        return transaction;
    }

    /** A Cast whose runes no spell pays (a Nature rune too), so it stays generic, never Ice Burst. */
    private static Ac genericCast(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Ab(560, "Death rune", -2L, 188, -376L, Av.GRAND_EXCHANGE),
                new Ab(561, "Nature rune", -1L, 100, -100L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static Ac eat(String label)
    {
        Ac transaction = new Ac(T0 + 100L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(385, "Shark", -1L, 950, -950L,
                Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Food", null);
        transaction.setActionKind(Au.EAT);
        if (label != null)
        {
            transaction.ahu(Bb.of(label));
        }
        return transaction;
    }

    private static Ac trade()
    {
        Ac transaction = new Ac(T0 + 100L, null, Ai.TRADE,
            Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(561, "Nature rune", -10L, 100, -1_000L,
                Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "GE sale", null);
        return transaction;
    }

    private static String fingerprint(Ac transaction)
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

    private static Br.Group groupNamed(
        List<Br.Group> groups, String name)
    {
        for (Br.Group group : groups)
        {
            if (name.equals(group.primaryName))
            {
                return group;
            }
        }
        return null;
    }
}
