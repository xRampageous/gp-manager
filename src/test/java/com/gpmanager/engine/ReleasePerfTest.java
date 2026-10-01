package com.gpmanager;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import org.junit.Assume;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Release pass 2026-09-28: timing budget on a heavy profile (a running Grind at the 2,000 detailed
 * receipts the default keeps, 300 finished Grinds). Dev-only; runs when GP_PERF is set:
 * {@code GP_PERF=1 ./gradlew test --tests 'com.gpmanager.ReleasePerfTest'}. Prints median/p99.
 */
@Category(PerformanceBudget.class)
public class ReleasePerfTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final Random RANDOM = new Random(20260928L);

    @Test
    public void heavyProfileStaysInsideTheFrameBudget() throws Exception
    {
        Assume.assumeTrue("set GP_PERF to run", System.getenv("GP_PERF") != null);
        Engine engine = PresentationLifecycleTest.engine();
        for (int grind = 0; grind < 300; grind++)
        {
            Session done = new Session("Grind " + grind, T0 - (300 - grind) * 3_600_000L);
            for (int i = 0; i < 60; i++)
            {
                done.addTransaction(receipt(done.startedAtEpochMillis + i * 1_000L), 2_000);
            }
            done.close(done.startedAtEpochMillis + 3_000_000L);
            engine.history.add(0, done);
        }
        engine.startCustomSession("Greater Nechryael", SessionMode.GENERAL, T0);
        for (int i = 0; i < 2_000; i++)
        {
            engine.getActiveSession().addTransaction(receipt(T0 + i * 1_800L), 2_000);
        }
        long now = T0 + 4_000_000L;

        List<Transaction> all = engine.getActiveSession().getTransactions();
        report("Bp.transaction x2000", () -> all.stream().mapToLong(t -> AccountingProjection.transaction(t).costs).sum());
        report("Bp.costSplit x2000", () -> all.stream().mapToLong(t -> AccountingProjection.costSplit(t).supplies).sum());
        report("spellName x2000", () -> all.stream().mapToLong(t -> t.spellName().length()).sum());
        report("Ledger contributions x2000", () -> all.stream().mapToLong(t -> Contribution.project(t).size()).sum());
        report("session metrics", () -> engine.getActiveSession().metrics(now).net);
        int[] flip = {0};
        report("Live capture (cold)", () -> LiveSnapshot.capture(engine, now,
            new LiveContext(false, null, flip[0]++ % 2, PvpState.NONE, "", false, "")).net);
        report("Live capture (warm)", () -> LiveSnapshot.capture(engine, now, LiveContext.NONE).net);
        report("Ledger capture", () -> LedgerData.capture(engine, now, LedgerData.Entry.current()).net);
        report("Grinds capture", () -> GrindsData.capture(engine, now, false, null).myGrinds.size());
        GpManagerConfig config = HudBuilderTest.config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now, LiveContext.NONE);
        report("HUD+ update + render", () ->
        {
            builder.update(snapshot, f -> true, null, now);
            return overlay.render(g).width;
        });
        Gson gson = new Gson();
        String once = gson.toJson(engine.createSavedState());
        SavedState copy = gson.fromJson(once, SavedState.class);
        copy.normalizeActionLabels();
        System.out.println("PERF save JSON bytes " + once.length()
            + ", identical to the old detached copy: " + once.equals(gson.toJson(copy)));
        report("save snapshot (engine lock)", () -> engine.createSavedState().schemaVersion);
        report("detach round trip", () -> gson.fromJson(gson.toJson(engine.createSavedState()), SavedState.class).schemaVersion);
        report("save snapshot + JSON", () -> gson.toJson(engine.createSavedState()).length());
        SessionRepository repository = new SessionRepository(gson,
            FilepathTestSupport.root(java.nio.file.Files.createTempDirectory("gp-perf")));
        PersistenceCoordinator coordinator = new PersistenceCoordinator(null, null, repository,
            new OrderedPersistenceWriter(repository, null), engine);
        report("save intent (client thread)", () -> coordinator.currentIntent().expectedBaseRevision);
    }

    private static Transaction receipt(long at)
    {
        List<Flow> flows;
        TransactionType type;
        ActionKind action = null;
        switch (RANDOM.nextInt(5))
        {
            case 0:
                type = TransactionType.LOOT;
                flows = Arrays.asList(new Flow(1_319, "Rune axe", 1L, 7_202, 7_202L),
                    new Flow(560, "Death rune", 46L, 187, 8_602L));
                break;
            case 1:
                type = TransactionType.CONSUMPTION;
                action = ActionKind.CAST;
                flows = Arrays.asList(new Flow(560, "Death rune", -4L, 187, -748L),
                    new Flow(565, "Blood rune", -2L, 341, -682L), new Flow(555, "Water rune", -6L, 5, -30L));
                break;
            case 2:
                type = TransactionType.CONSUMPTION;
                action = ActionKind.DRINK;
                flows = Arrays.asList(new Flow(2434, "Prayer potion(4)", -1L, 9_800, -9_800L),
                    new Flow(139, "Prayer potion(3)", 1L, 7_350, 7_350L));
                break;
            case 3:
                type = TransactionType.CONSUMPTION;
                action = ActionKind.FIRE;
                flows = Collections.singletonList(new Flow(808, "Steel dart", -2L, 4, -8L));
                break;
            default:
                type = TransactionType.GAIN;
                flows = Collections.singletonList(new Flow(526, "Bones", 1L, 37, 37L));
                break;
        }
        Transaction transaction = new Transaction(at, null, type,
            type == TransactionType.LOOT ? Context.LOOT : Context.GENERIC, "",
            "Greater Nechryael", true, flows, ClassificationConfidence.CONFIRMED, "perf", null);
        transaction.setActionKind(action);
        return transaction;
    }

    private static void report(String name, LongSupplier work)
    {
        for (int i = 0; i < 20; i++)
        {
            work.getAsLong();
        }
        long[] times = new long[100];
        for (int i = 0; i < times.length; i++)
        {
            long start = System.nanoTime();
            work.getAsLong();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        System.out.printf("PERF %-24s median %7.3f ms  p99 %7.3f ms%n", name, times[50] / 1e6, times[98] / 1e6);
    }
}
