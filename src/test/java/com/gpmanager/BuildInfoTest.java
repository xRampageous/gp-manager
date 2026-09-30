package com.gpmanager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Build identity is ordinary immutable metadata: parsed, displayed and fail-safe. */
public class BuildInfoTest
{
    @Test
    public void developmentMetadataParsesIntoTheTinyFooterMarker()
    {
        BuildInfo info = BuildInfo.load(stream("version=0.8.0\n"
            + "developmentVersion=0.8.0-dev\n"
            + "development=true\n"
            + "schema=105\n"
            + "builtAt=2026-09-23T01:24:37Z\n"
            + "revision=2929696\n"
            + "runeliteDependency=1.12.39\n"));

        assertTrue(info.development());
        assertEquals("0.8.0", info.version());
        assertEquals("0.8.0-dev", info.developmentVersion());
        assertEquals("105", info.schemaVersion());
        assertEquals("2929696", info.revision());
        assertEquals("DEV \u00b7 0.8.0 \u00b7 01:24Z", info.badgeText());
        assertTrue(info.tooltipText().contains("GP Manager Development Build"));
        assertTrue(info.tooltipText().contains("Version: 0.8.0-dev"));
        assertTrue(info.tooltipText().contains("Schema: 105"));
        assertTrue(info.tooltipText().contains("Built: 2026-09-23 01:24:37 UTC"));
        assertTrue(info.tooltipText().contains("RuneLite: 1.12.39"));
        assertTrue(info.tooltipText().contains("Revision: 2929696"));
    }

    @Test
    public void releaseMetadataOmitsTheDevelopmentMarker()
    {
        BuildInfo info = BuildInfo.load(stream("version=1.0.0\n"
            + "developmentVersion=1.0.0\n"
            + "development=false\n"
            + "schema=105\n"
            + "builtAt=2026-09-23T01:24:37Z\n"));

        assertFalse(info.development());
        assertEquals("", info.badgeText());
    }

    @Test
    public void missingMetadataFallsBackWithoutFailing()
    {
        BuildInfo info = BuildInfo.load((java.io.InputStream) null);

        assertTrue("a missing resource still identifies the running build", info.development());
        assertEquals("DEV BUILD", info.badgeText());
        assertTrue(info.tooltipText().contains("GP Manager Development Build"));
    }

    @Test
    public void corruptMetadataFailsSafelyAndNeverLeaksControlCharacters()
    {
        BuildInfo info = BuildInfo.load(stream("not=properties\n\u0000\u0001version=\u0007garbage\u0000\n"));

        // No usable version survives, so the marker degrades to the bare development form.
        assertEquals("DEV BUILD", info.badgeText());
        for (int i = 0; i < info.badgeText().length(); i++)
        {
            assertFalse(Character.isISOControl(info.badgeText().charAt(i)));
        }
    }

    @Test
    public void unparseableTimestampKeepsTheVersionAndDropsOnlyTheClock()
    {
        BuildInfo info = BuildInfo.load(stream("version=0.8.0\n"
            + "developmentVersion=0.8.0-dev\n"
            + "development=true\n"
            + "builtAt=not-a-timestamp\n"));

        assertEquals("DEV \u00b7 0.8.0", info.badgeText());
        assertTrue(info.tooltipText().contains("Built: not-a-timestamp"));
    }

    @Test
    public void injectedFixedIdentityDoesNotDependOnTheWallClock()
    {
        BuildInfo info = BuildInfo.of("0.8.0", "0.8.0-dev", "105",
            "2026-09-23T11:42:05Z", "abcdef1", "1.12.39", true);

        assertEquals("DEV \u00b7 0.8.0 \u00b7 11:42Z", info.badgeText());
    }

    private static ByteArrayInputStream stream(String text)
    {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
