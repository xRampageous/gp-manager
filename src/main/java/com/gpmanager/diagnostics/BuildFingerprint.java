package com.gpmanager.diagnostics;

import java.io.InputStream;
import java.util.Properties;

/**
 * Exact build fingerprint beyond the marketing version string.
 */
public final class BuildFingerprint
{
    private static final String RESOURCE = "/gp-manager-build.properties";
    private static final BuildFingerprint INSTANCE = load();

    private final String version;
    private final String fingerprint;
    private final String runeLiteDependency;
    private final String builtAt;

    private BuildFingerprint(String version, String fingerprint, String runeLiteDependency, String builtAt)
    {
        this.version = version;
        this.fingerprint = fingerprint;
        this.runeLiteDependency = runeLiteDependency;
        this.builtAt = builtAt;
    }

    private static BuildFingerprint load()
    {
        Properties properties = new Properties();
        try (InputStream input = BuildFingerprint.class.getResourceAsStream(RESOURCE))
        {
            if (input != null)
            {
                properties.load(input);
            }
        }
        catch (Exception ignored)
        {
            // Fall through to defaults.
        }
        String version = properties.getProperty("version", "1.0.0-SNAPSHOT");
        String runeLite = properties.getProperty("runeliteDependency", "unknown");
        String builtAt = properties.getProperty("builtAt", "unknown");
        String fingerprint = properties.getProperty("fingerprint",
            version + "+rl-" + runeLite + "+built-" + builtAt);
        return new BuildFingerprint(version, fingerprint, runeLite, builtAt);
    }

    public static BuildFingerprint get()
    {
        return INSTANCE;
    }

    public String getVersion()
    {
        return version;
    }

    public String getFingerprint()
    {
        return fingerprint;
    }

    public String getRuneLiteDependency()
    {
        return runeLiteDependency;
    }

    public String getBuiltAt()
    {
        return builtAt;
    }
}
