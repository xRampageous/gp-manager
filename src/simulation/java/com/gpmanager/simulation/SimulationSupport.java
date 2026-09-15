package com.gpmanager.simulation;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.FlowValuator;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.persistence.CsvExportResult;
import com.gpmanager.persistence.CsvExporter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimulationSupport
{
    private static final Map<Integer, Integer> PRICES = new LinkedHashMap<>();
    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    static
    {
        price(995, "Coins", 1);
        price(526, "Bones", 31);
        price(555, "Water rune", 5);
        price(379, "Lobster", 250);
        price(385, "Shark", 900);
        price(2444, "Ranging potion(4)", 8_000);
        price(892, "Rune arrow", 60);
        price(561, "Nature rune", 180);
        price(1511, "Logs", 50);
        price(50, "Shortbow (u)", 120);
        price(1265, "Bronze pickaxe", 100);
        price(11941, "Loot key", 0);
        price(10001, "PK loot bundle", 780_000);
        price(10002, "Risked gear", 550_000);
        price(10003, "PK entry fee", 75_000);
        price(10004, "Carried PK loot", 500_000);
        price(10005, "Second PK loot", 320_000);
    }

    private SimulationSupport()
    {
    }

    private static void price(int itemId, String name, int value)
    {
        PRICES.put(itemId, value);
        NAMES.put(itemId, name);
    }

    static GpManagerEngine newEngine(SessionMode mode)
    {
        FlowValuator valuator = quantityDeltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : quantityDeltas.entrySet())
            {
                int itemId = entry.getKey();
                long quantity = entry.getValue();
                int unitPrice = PRICES.getOrDefault(itemId, 100);
                flows.add(new ItemFlow(
                    itemId,
                    NAMES.getOrDefault(itemId, "Item " + itemId),
                    quantity,
                    unitPrice,
                    quantity * unitPrice));
            }
            return flows;
        };

        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public SessionMode defaultSessionMode()
            {
                return mode;
            }

            @Override
            public boolean keepTransferAuditRows()
            {
                return true;
            }

            @Override
            public int stabilizationTicks()
            {
                return 2;
            }

            @Override
            public int pkSupplyWindowSeconds()
            {
                return 90;
            }
        };

        return new GpManagerEngine(valuator, new TransactionClassifier(), config);
    }

    static ContainerSnapshot snapshot(Object... itemQuantityPairs)
    {
        if (itemQuantityPairs.length % 2 != 0)
        {
            throw new IllegalArgumentException("snapshot requires itemId, quantity pairs");
        }
        Map<Integer, Long> quantities = new LinkedHashMap<>();
        for (int index = 0; index < itemQuantityPairs.length; index += 2)
        {
            int itemId = ((Number) itemQuantityPairs[index]).intValue();
            long quantity = ((Number) itemQuantityPairs[index + 1]).longValue();
            if (quantity > 0L)
            {
                quantities.put(itemId, quantity);
            }
        }
        return new ContainerSnapshot(quantities);
    }

    static ProfitTransaction settle(
        GpManagerEngine engine,
        ContainerSnapshot snapshot,
        long firstTick)
    {
        ProfitTransaction transaction = engine.processIfDirty(snapshot, firstTick);
        equal(null, transaction, "transaction must wait for first stable tick");
        transaction = engine.processIfDirty(snapshot, firstTick + 600L);
        equal(null, transaction, "transaction must wait for second stable tick");
        return engine.processIfDirty(snapshot, firstTick + 1_200L);
    }

    static ItemFlow gain(int itemId, long quantity)
    {
        return flow(itemId, quantity);
    }

    static ItemFlow cost(int itemId, long quantity)
    {
        return flow(itemId, -Math.abs(quantity));
    }

    static ItemFlow valuedFlow(int itemId, String name, long quantity, int unitPrice)
    {
        return new ItemFlow(itemId, name, quantity, unitPrice, quantity * unitPrice);
    }

    private static ItemFlow flow(int itemId, long quantity)
    {
        int unitPrice = PRICES.getOrDefault(itemId, 100);
        return new ItemFlow(
            itemId,
            NAMES.getOrDefault(itemId, "Item " + itemId),
            quantity,
            unitPrice,
            quantity * unitPrice);
    }

    static CsvExportResult export(ProfitSession session, Path output) throws IOException
    {
        Path exportDirectory = output.resolve("exports");
        return new CsvExporter().exportSession(session, exportDirectory, 15);
    }

    static void equal(long expected, long actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    static void equal(int expected, int actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    static void equal(Object expected, Object actual, String message)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    static final class Suite
    {
        private final String name;
        private final Path output;
        private final Instant startedAt = Instant.now();
        private final List<CaseResult> cases = new ArrayList<>();

        Suite(String name, Path output)
        {
            this.name = name;
            this.output = output;
        }

        void run(String caseName, CheckedRunnable runnable)
        {
            long start = System.nanoTime();
            try
            {
                runnable.run();
                cases.add(new CaseResult(caseName, true, "Passed", elapsedMillis(start)));
                System.out.println("[PASS] " + caseName);
            }
            catch (Throwable failure)
            {
                String detail = failure.getClass().getSimpleName() + ": "
                    + (failure.getMessage() == null ? "No message" : failure.getMessage());
                cases.add(new CaseResult(caseName, false, detail, elapsedMillis(start)));
                System.err.println("[FAIL] " + caseName + " - " + detail);
            }
        }

        void finish() throws IOException
        {
            Files.createDirectories(output);
            Path textReport = output.resolve("simulation-report.txt");
            Path csvReport = output.resolve("simulation-report.csv");
            int passed = 0;
            for (CaseResult result : cases)
            {
                if (result.passed)
                {
                    passed++;
                }
            }

            try (BufferedWriter writer = Files.newBufferedWriter(textReport, StandardCharsets.UTF_8))
            {
                writer.write("GP Manager Offline Simulation");
                writer.newLine();
                writer.write("Suite: " + name);
                writer.newLine();
                writer.write("Started: " + startedAt);
                writer.newLine();
                writer.write("Result: " + passed + "/" + cases.size() + " passed");
                writer.newLine();
                writer.newLine();
                for (CaseResult result : cases)
                {
                    writer.write((result.passed ? "PASS" : "FAIL") + " | "
                        + result.name + " | " + result.durationMillis + " ms | " + result.detail);
                    writer.newLine();
                }
            }

            try (BufferedWriter writer = Files.newBufferedWriter(csvReport, StandardCharsets.UTF_8))
            {
                writer.write("suite,case,status,duration_millis,detail");
                writer.newLine();
                for (CaseResult result : cases)
                {
                    writer.write(csv(name));
                    writer.write(',');
                    writer.write(csv(result.name));
                    writer.write(',');
                    writer.write(result.passed ? "PASS" : "FAIL");
                    writer.write(',');
                    writer.write(Long.toString(result.durationMillis));
                    writer.write(',');
                    writer.write(csv(result.detail));
                    writer.newLine();
                }
            }

            System.out.println("Report: " + textReport.toAbsolutePath());
            System.out.println("CSV report: " + csvReport.toAbsolutePath());
            if (passed != cases.size())
            {
                throw new IllegalStateException(name + " simulation failed: "
                    + (cases.size() - passed) + " case(s) failed");
            }
        }

        Path getOutput()
        {
            return output;
        }

        private static long elapsedMillis(long startedNanos)
        {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        }

        private static String csv(String value)
        {
            String safe = value == null ? "" : value;
            if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r"))
            {
                return "\"" + safe.replace("\"", "\"\"") + "\"";
            }
            return safe;
        }
    }

    @FunctionalInterface
    interface CheckedRunnable
    {
        void run() throws Exception;
    }

    private static final class CaseResult
    {
        private final String name;
        private final boolean passed;
        private final String detail;
        private final long durationMillis;

        private CaseResult(String name, boolean passed, String detail, long durationMillis)
        {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
            this.durationMillis = durationMillis;
        }
    }
}
