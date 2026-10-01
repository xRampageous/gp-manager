package com.gpmanager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Immutable, machine-comparable financial state of one engine at one instant.
 *
 * <p>Every dimension that can be unavailable is fingerprinted as the value <em>and</em> its
 * availability; an unavailable dimension is never rendered as zero. Two fingerprints are
 * compared exactly, integer for integer, so a migration or extraction that moves one gp fails.
 */
public final class FinancialFingerprint
{
    private final String text;

    private FinancialFingerprint(String text)
    {
        this.text = text;
    }

    public String text()
    {
        return text;
    }

    @Override
    public String toString()
    {
        return text;
    }

    /** Exact comparison; the assertion message shows the first differing line. */
    public static void assertFinanciallyIdentical(FinancialFingerprint expected, FinancialFingerprint actual)
    {
        if (expected.text.equals(actual.text))
        {
            return;
        }
        String[] left = expected.text.split("\n");
        String[] right = actual.text.split("\n");
        int limit = Math.min(left.length, right.length);
        for (int index = 0; index < limit; index++)
        {
            if (!left[index].equals(right[index]))
            {
                assertEquals("financial fingerprint drifted at line " + (index + 1)
                    + "\nexpected: " + left[index] + "\nactual:   " + right[index], expected.text, actual.text);
            }
        }
        assertEquals("financial fingerprint length drifted", expected.text, actual.text);
    }

    /**
     * Compares accounting, PvP and archive facts exactly. Non-accounting coin-store, category
     * and PvM analytics lines are excluded from the financial comparison.
     */
    public static FinancialFingerprint of(String text)
    {
        StringBuilder kept = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n"))
        {
            if (line.startsWith("session ") && line.contains(" category="))
            {
                line = line.substring(0, line.indexOf(" category="));
            }
            String trimmed = line.trim();
            if (!line.startsWith("coinStore")
                && !trimmed.startsWith("pvm "))
            {
                kept.append(line).append('\n');
            }
        }
        return new FinancialFingerprint(kept.toString().trim() + "\n");
    }

    /** Captures the engine's effective correction-aware money, PvP metrics and archives at {@code now}. */
    public static FinancialFingerprint fingerprint(Engine engine, long now)
    {
        StringBuilder out = new StringBuilder();
        Map<String, Session> sessions = new LinkedHashMap<>();
        Session general = engine.getGeneralSession();
        Session active = engine.getActiveSession();
        if (general != null) sessions.put(general.getId(), general);
        if (active != null) sessions.put(active.getId(), active);
        for (Session session : engine.getHistory())
        {
            if (session != null) sessions.put(session.getId(), session);
        }
        List<Session> ordered = new ArrayList<>(sessions.values());
        ordered.sort(Comparator.comparing(Session::getId));
        out.append("sessions=").append(ordered.size()).append('\n');
        for (Session session : ordered)
        {
            boolean isActive = active != null && active.getId().equals(session.getId());
            SessionMetrics metrics = session.metrics(now);
            PkMetrics pk = session.pkMetrics();
            out.append("session ").append(session.getId())
                .append(" owner=").append(session.getOwnerKind())
                .append(" closed=").append(session.isClosed())
                .append(" active=").append(isActive)
                .append('\n');
            if (metrics == null)
            {
                out.append("  metrics unavailable\n");
            }
            else
            {
                out.append("  accounting revenue=").append(metrics.revenue)
                    .append(" costs=").append(metrics.costs)
                    .append(" net=").append(metrics.net)
                    .append('\n');
                out.append("  costSplit ").append(availability(metrics.costSplitAvailable));
                if (metrics.costSplitAvailable)
                {
                    out.append(" supplies=").append(metrics.suppliesCosts)
                        .append(" loss=").append(metrics.otherCosts);
                }
                out.append('\n');
                out.append("  transactions count=").append(SessionCounts.counted(session))
                    .append(" transfers=").append(SessionCounts.transfers(session))
                    .append('\n');
            }
            if (pk == null)
            {
                out.append("  pvp unavailable\n");
            }
            else
            {
                out.append("  pvp kills=").append(pk.kills)
                    .append(" deaths=").append(pk.deaths)
                    .append(" net=").append(pk.net)
                    .append(" bestKill=").append(pk.bestKill)
                    .append(" worstDeath=").append(pk.largestDeathLoss)
                    .append(" killNet=").append(pk.totalKillNet)
                    .append(" deathLoss=").append(pk.totalDeathLoss)
                    .append(" streak=").append(pk.currentStreak)
                    .append('\n');
            }
            out.append("  retainedRows=").append(session.getTransactions().size())
                .append(" compactedRows=").append(session.compactedTransactionCount)
                .append('\n');
            List<Transaction> rows = new ArrayList<>(session.getTransactions());
            rows.sort(Comparator.comparing(Transaction::getId));
            for (Transaction row : rows)
            {
                out.append("  row ").append(row.getId())
                    .append(" type=").append(row.getType())
                    .append(" counted=").append(row.isCounted())
                    .append(" correction=").append(row.getCorrection())
                    .append(" confidence=").append(row.getConfidence())
                    .append(" revenue=").append(row.getRevenue())
                    .append(" costs=").append(row.getCosts())
                    .append(" net=").append(row.getNet())
                    .append(" encounter=").append(row.getEncounterId())
                    .append('\n');
                for (Flow flow : row.getFlows())
                {
                    out.append("    flow item=").append(flow.itemId)
                        .append(" qty=").append(flow.quantityDelta)
                        .append(" unit=").append(flow.unitPrice)
                        .append(" value=").append(flow.valueDelta)
                        .append(" source=").append(flow.getPriceSource())
                        .append('\n');
                }
            }
            List<PkEncounter> encounters = new ArrayList<>(session.getPkEncounters());
            encounters.sort(Comparator.comparing(PkEncounter::getId));
            for (PkEncounter encounter : encounters)
            {
                out.append("  encounter ").append(encounter.getId())
                    .append(" type=").append(encounter.getType())
                    .append(" at=").append(encounter.timestampEpochMillis)
                    .append(" net=").append(encounter.getFinancialNetGp())
                    .append(" loss=").append(encounter.getFinancialLossGp())
                    .append('\n');
            }
        }

        return new FinancialFingerprint(out.toString());
    }

    private static String availability(boolean available)
    {
        return available ? "available" : "unavailable";
    }

    static String lower(Object value)
    {
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }
}
