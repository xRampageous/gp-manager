package com.gpmanager.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Immutable per-kind enablement policy for the engine's transient alert stream. */
public final class AlertPolicy
{
    private final Map<AlertKind, Boolean> enabled;

    /** Defaults every current and future alert kind to enabled. */
    public AlertPolicy()
    {
        this(null, true);
    }

    /** Unspecified kinds use {@code defaultEnabled}; explicit entries override it. */
    public AlertPolicy(Map<AlertKind, Boolean> enabled)
    {
        this(enabled, true);
    }

    private AlertPolicy(Map<AlertKind, Boolean> enabled, boolean defaultEnabled)
    {
        EnumMap<AlertKind, Boolean> copy = new EnumMap<>(AlertKind.class);
        for (AlertKind kind : AlertKind.values()) copy.put(kind, defaultEnabled);
        if (enabled != null)
        {
            for (Map.Entry<AlertKind, Boolean> entry : enabled.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null)
                {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.enabled = Collections.unmodifiableMap(copy);
    }

    public static AlertPolicy allEnabled()
    {
        return new AlertPolicy();
    }

    public static AlertPolicy allDisabled()
    {
        EnumMap<AlertKind, Boolean> values = new EnumMap<>(AlertKind.class);
        for (AlertKind kind : AlertKind.values()) values.put(kind, false);
        return new AlertPolicy(values);
    }

    public boolean isEnabled(AlertKind kind)
    {
        return kind != null && Boolean.TRUE.equals(enabled.get(kind));
    }

    public AlertPolicy withEnabled(AlertKind kind, boolean value)
    {
        if (kind == null) return this;
        EnumMap<AlertKind, Boolean> copy = new EnumMap<>(AlertKind.class);
        copy.putAll(enabled);
        copy.put(kind, value);
        return new AlertPolicy(copy);
    }

    public Map<AlertKind, Boolean> getEnabledKinds()
    {
        return enabled;
    }
}
