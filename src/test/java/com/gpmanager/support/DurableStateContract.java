package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Shared Checkpoint 2 contract checks: the zero-drift rule between two financial fingerprints and
 * the structured allow/deny audit of a serialized compact {@link SavedState} (charter §11/§13).
 */
public final class DurableStateContract
{
    private DurableStateContract()
    {
    }

    /** Serialized form with the two volatile stamps removed. */
    public static String stable(SavedState state)
    {
        JsonObject json = new Gson().toJsonTree(state).getAsJsonObject();
        json.remove("savedAtEpochMillis");
        json.remove("revision");
        return new Gson().toJson(json);
    }

    /**
     * Zero-drift rule (charter D.6 / Rev 3 §10): every financial value the old policy could
     * produce must be identical afterwards; a dimension that was unavailable before constrains
     * nothing, and a value may only become available, never disappear. Row/rollup detail
     * lines are not compared here — retained rows legitimately change under migrated corrections
     * and retention — the fixture tests assert those explicitly.
     */
    public static void assertNoFinancialDrift(String label, FinancialFingerprint before, FinancialFingerprint after)
    {
        Map<String, List<String>> expected = sessionBlocks(before.text());
        Map<String, List<String>> actual = sessionBlocks(after.text());
        assertEquals(label + ": session set", expected.keySet(), actual.keySet());
        for (Map.Entry<String, List<String>> entry : expected.entrySet())
        {
            List<String> theirs = actual.get(entry.getKey());
            for (String line : entry.getValue())
            {
                String trimmed = line.trim();
                boolean money = trimmed.startsWith("accounting ") || trimmed.startsWith("costSplit ")
                    || trimmed.startsWith("pvp ");
                if (!money) continue;
                if (trimmed.contains(" unavailable")) continue;
                assertTrue(label + ": session " + entry.getKey() + " lost or changed \"" + trimmed + "\"\n"
                    + "after:\n" + String.join("\n", theirs), theirs.contains(line));
            }
            String header = entry.getKey();
            assertEquals(label + ": owner/closed of " + header, headerLine(before.text(), header),
                headerLine(after.text(), header));
        }
    }

    private static Map<String, List<String>> sessionBlocks(String text)
    {
        Map<String, List<String>> blocks = new java.util.TreeMap<>();
        String current = null;
        for (String line : text.split("\n"))
        {
            if (line.startsWith("session "))
            {
                current = line.split(" ")[1];
                blocks.put(current, new ArrayList<>());
            }
            else if (line.startsWith("  ") && current != null)
            {
                blocks.get(current).add(line);
            }
            else current = null;
        }
        return blocks;
    }

    private static String headerLine(String text, String sessionId)
    {
        for (String line : text.split("\n"))
        {
            if (line.startsWith("session " + sessionId + " ")) return line;
        }
        return "";
    }

   private static List<String> linesStarting(String text, String prefix)
   {
       List<String> lines = new ArrayList<>();
       for (String line : text.split("\n")) if (line.startsWith(prefix)) lines.add(line);
       return lines;
   }

    /** Field names that must never appear anywhere in a compact durable payload (charter §11 / §13). */
    public static final Set<String> DENIED_KEYS = new HashSet<>(Arrays.asList(
        "partySummary", "members", "partyPeers", "peers",
        "pkLocationLedger", "locationLabel", "placeSummaries", "deathPlaces",
        "wealthSnapshotHistory", "wealthHistory", "snapshots", "timeline", "movers", "anchors",
        "geOfferProvenance", "geOfferObservation", "geObservations",
        "chargeLoadReviewProvenance", "chargeReads", "measuredChargeReads",
        "lootKeyProvenance", "deferredClaimProvenance", "manifestQuantities", "provenance", "evidence",
        "runs", "runHistory", "runRetainedAggregates", "activitySegments",
        "hudState", "trayState", "unknownJsonFields", "receiptRetentionDeferredUntilDayChange",
        "activeSession"));

    /** Top-level members a compact payload is allowed to carry. */
    public static final Set<String> ALLOWED_TOP_LEVEL = new HashSet<>(Arrays.asList(
        "schemaVersion", "savedAtEpochMillis", "revision", "ownerKey",
        "generalSession", "customSession", "generalSuspendedByCustom", "history",
        "tileLayout", "lastReceiptRetentionDayUtc", "profileTimeZoneId",
        "dailyRollups", "lifetimeArchive", "coinStores", "currentWealth", "pendingClaims",
        "pkHistory", "pendingDeathReclaim", "savedGrinds", "geCustody", "trackedBasis"));

    /** Structured allow/deny audit of one serialized compact state. */
    public static void assertDurableStateContract(JsonObject json)
    {
        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        for (String key : json.keySet())
        {
            assertTrue("unexpected top-level durable member: " + key, ALLOWED_TOP_LEVEL.contains(key));
        }
        List<String> path = new ArrayList<>();
        walk(json, path);
        if (json.has("pendingClaims"))
        {
            for (JsonElement claim : json.getAsJsonArray("pendingClaims"))
            {
                Set<String> keys = claim.getAsJsonObject().keySet();
                Set<String> allowed = new HashSet<>(Arrays.asList("claimId", "itemOrKeyId",
                    "quantity", "createdAtEpochMillis"));
                assertTrue("By carries only the charter fields: " + keys, allowed.containsAll(keys));
            }
        }
    }

    private static void walk(JsonElement element, List<String> path)
    {
        if (element == null) return;
        if (element.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
            {
                assertFalse("denied durable field " + entry.getKey() + " at " + path, DENIED_KEYS.contains(entry.getKey()));
                path.add(entry.getKey());
                walk(entry.getValue(), path);
                path.remove(path.size() - 1);
            }
        }
        else if (element.isJsonArray())
        {
            for (JsonElement child : element.getAsJsonArray()) walk(child, path);
        }
    }

}
