package com.gpmanager;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import javax.annotation.Nullable;

/**
 * Immutable build-time identity for development builds (dev launcher and panel preview only; the
 * plugin itself has no build-identity code). Build metadata only: never financial,
 * never owner-scoped and never persisted. The Gradle build writes the bundled resource; runtime
 * reads ordinary immutable data (no process execution, no networking, no reflection).
 *
 * <p>Missing or corrupt metadata degrades to a bare {@code DEV BUILD} marker instead of failing
 * plugin startup.</p>
 */
public final class BuildInfo
{
    /** Build-generated resource, written by Gradle into the plugin resources. */
    static final String RESOURCE = "/gp-manager-build.properties";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm'Z'")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC);
    private static final int MAX_FIELD = 48;

    private final String version;
    private final String developmentVersion;
    private final String schemaVersion;
    private final String builtAtUtc;
    private final String revision;
    private final String runeliteDependency;
    private final boolean development;

    private BuildInfo(String version, String developmentVersion, String schemaVersion,
        String builtAtUtc, String revision, String runeliteDependency, boolean development)
    {
        this.version = safe(version);
        this.developmentVersion = safe(developmentVersion);
        this.schemaVersion = safe(schemaVersion);
        this.builtAtUtc = safe(builtAtUtc);
        this.revision = safe(revision);
        this.runeliteDependency = safe(runeliteDependency);
        this.development = development;
    }

    /** Reads the bundled build metadata; any failure yields the harmless fallback. */
    public static BuildInfo load()
    {
        return load(BuildInfo.class.getResourceAsStream(RESOURCE));
    }

    /** Explicit-stream load for tests and previews; the stream is closed here. */
    public static BuildInfo load(@Nullable InputStream stream)
    {
        if (stream == null)
        {
            return fallback();
        }
        Properties properties = new Properties();
        try (InputStream in = stream)
        {
            properties.load(in);
        }
        catch (Exception ex)
        {
            return fallback();
        }
        String version = properties.getProperty("version");
        String developmentVersion = properties.getProperty("developmentVersion");
        if (safe(version).isEmpty() && safe(developmentVersion).isEmpty())
        {
            return fallback();
        }
        return new BuildInfo(
            version,
            developmentVersion,
            properties.getProperty("schema"),
            properties.getProperty("builtAt"),
            properties.getProperty("revision"),
            properties.getProperty("runeliteDependency"),
            "true".equalsIgnoreCase(safe(properties.getProperty("development"))));
    }

    /** Injection seam for previews/tests: an explicit, fixed identity. */
    public static BuildInfo of(@Nullable String version, @Nullable String developmentVersion,
        @Nullable String schemaVersion, @Nullable String builtAtUtc, @Nullable String revision,
        @Nullable String runeliteDependency, boolean development)
    {
        return new BuildInfo(version, developmentVersion, schemaVersion, builtAtUtc, revision,
            runeliteDependency, development);
    }

    /** Safe fallback when optional metadata is missing or unreadable. */
    public static BuildInfo fallback()
    {
        return new BuildInfo("", "", "", "", "", "", true);
    }

    public boolean development()
    {
        return development;
    }

    public String version()
    {
        return version;
    }

    public String developmentVersion()
    {
        return developmentVersion;
    }

    public String schemaVersion()
    {
        return schemaVersion;
    }

    public String revision()
    {
        return revision;
    }

    /**
     * The tiny footer marker: {@code DEV · 0.8.0 · 01:24Z}. Release builds return an empty
     * string, so the footer collapses instead of showing a stale development marker.
     */
    public String badgeText()
    {
        if (!development)
        {
            return "";
        }
        if (version.isEmpty())
        {
            return "DEV BUILD";
        }
        String clock = clockUtc();
        return clock.isEmpty() ? "DEV \u00b7 " + version : "DEV \u00b7 " + version + " \u00b7 " + clock;
    }

    /** Full identity for the footer tooltip and accessibility description. */
    public String tooltipText()
    {
        StringBuilder text = new StringBuilder(development
            ? "GP Manager Development Build" : "GP Manager Release Build");
        String shownVersion = developmentVersion.isEmpty() ? version : developmentVersion;
        if (!shownVersion.isEmpty())
        {
            text.append("\nVersion: ").append(shownVersion);
        }
        if (!schemaVersion.isEmpty())
        {
            text.append("\nSchema: ").append(schemaVersion);
        }
        String stamp = stampUtc();
        if (!stamp.isEmpty())
        {
            text.append("\nBuilt: ").append(stamp);
        }
        if (!runeliteDependency.isEmpty())
        {
            text.append("\nRuneLite: ").append(runeliteDependency);
        }
        if (!revision.isEmpty())
        {
            text.append("\nRevision: ").append(revision);
        }
        return text.toString();
    }

    /** Compact UTC clock of the build, or empty when no usable timestamp is bundled. */
    public String clockUtc()
    {
        Instant instant = parsedBuiltAt();
        return instant == null ? "" : CLOCK.format(instant);
    }

    /** Full UTC timestamp of the build, or the raw bundled text when unparseable. */
    public String stampUtc()
    {
        Instant instant = parsedBuiltAt();
        return instant == null ? builtAtUtc : STAMP.format(instant);
    }

    @Nullable
    private Instant parsedBuiltAt()
    {
        if (builtAtUtc.isEmpty())
        {
            return null;
        }
        try
        {
            return Instant.parse(builtAtUtc);
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static String safe(@Nullable String value)
    {
        if (value == null)
        {
            return "";
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_FIELD));
        for (int i = 0; i < value.length() && clean.length() < MAX_FIELD; i++)
        {
            char c = value.charAt(i);
            if (!Character.isISOControl(c))
            {
                clean.append(c);
            }
        }
        return clean.toString().trim();
    }
}
