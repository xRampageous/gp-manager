package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A Session saved by the code before Phase N (one PvP money class, projection extends profile base)
 * loads with the same PvP metrics and writes the same PvP field names back.
 */
public class PvpSaveCompatibilityTest
{
    /** PkMetrics and per-encounter net/cost as the pre-N code computed them for this save. */
    private static final String BEFORE =
        "2|1|1|5700|2300|3400|4700|2000|5400|2000|300|2000|true|2700.0|2000.0|4700/300|-2000/2000|700/0";

    private static JsonObject fixture() throws Exception
    {
        try (Reader reader = new InputStreamReader(
            PvpSaveCompatibilityTest.class.getResourceAsStream("/com/gpmanager/model/pvp-session-before-phase-n.json"), StandardCharsets.UTF_8))
        {
            return new Gson().fromJson(reader, JsonObject.class);
        }
    }

    @Test
    public void preNSaveLoadsWithTheSamePvpMetrics() throws Exception
    {
        Session session = new Gson().fromJson(fixture(), Session.class);
        PkMetrics m = session.pkMetrics();
        StringBuilder out = new StringBuilder();
        out.append(m.kills).append('|').append(m.deaths).append('|').append(m.currentStreak)
            .append('|').append(m.revenue).append('|').append(m.costs).append('|').append(m.net)
            .append('|').append(m.bestKill).append('|').append(m.largestDeathLoss)
            .append('|').append(m.totalKillNet).append('|').append(m.totalDeathLoss)
            .append('|').append(m.suppliesCosts).append('|').append(m.otherCosts)
            .append('|').append(m.costSplitAvailable).append('|').append(m.medianKillNetGp)
            .append('|').append(m.medianDeathLossGp);
        for (PkEncounter encounter : session.getPkEncounters())
        {
            out.append('|').append(encounter.getFinancialNetGp()).append('/').append(encounter.total().getCostsGp());
        }
        assertEquals(BEFORE, out.toString());
    }

    @Test
    public void pvpFieldNamesAreWrittenBackUnchanged() throws Exception
    {
        JsonObject before = fixture();
        JsonObject after = new Gson().toJsonTree(new Gson().fromJson(before, Session.class)).getAsJsonObject();
        assertEquals(keys(before.getAsJsonObject("pkProjection")), keys(after.getAsJsonObject("pkProjection")));
        assertEquals(keys(before.getAsJsonArray("pkAttributions").get(0).getAsJsonObject()),
            keys(after.getAsJsonArray("pkAttributions").get(0).getAsJsonObject()));
        assertEquals(keys(before.getAsJsonArray("pkEncounters").get(2).getAsJsonObject()),
            keys(after.getAsJsonArray("pkEncounters").get(2).getAsJsonObject()));
        assertEquals(before.get("pkAttributions"), after.get("pkAttributions"));
        assertEquals(before.get("pkProjection"), after.get("pkProjection"));
        assertEquals(before.get("pkEncounters"), after.get("pkEncounters"));
    }

    private static Set<String> keys(JsonObject object)
    {
        return new TreeSet<>(object.keySet());
    }
}
