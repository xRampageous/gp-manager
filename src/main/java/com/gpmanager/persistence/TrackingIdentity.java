package com.gpmanager.persistence;

import java.util.Objects;

/**
 * Stable tracking ownership identity.
 *
 * <p>Primary key is the RuneLite RS profile key when available (account hash plus
 * world-type profile). Account hash alone is retained for diagnostics and for
 * matching profiles. Display names are never used as durable identity.
 *
 * <p><b>Account isolation</b> — each RS profile key owns its own General tracker,
 * history, targets, and analytics files. Switching accounts or world-type profiles
 * must never attach one history to another.
 *
 * <p><b>Settings-profile sharing</b> — RuneLite configuration profiles (plugin
 * enablement/settings presets) share the same account data when the same RS
 * profile is logged in. They do not split or duplicate accounting history.
 */
public final class TrackingIdentity
{
    public static final long ACCOUNT_HASH_INVALID = -1L;

    private final String rsProfileKey;
    private final long accountHash;

    public TrackingIdentity(String rsProfileKey, long accountHash)
    {
        if (rsProfileKey == null || rsProfileKey.trim().isEmpty())
        {
            throw new IllegalArgumentException("rsProfileKey");
        }
        this.rsProfileKey = rsProfileKey.trim();
        this.accountHash = accountHash;
    }

    public static TrackingIdentity ofRsProfileKey(String rsProfileKey)
    {
        return new TrackingIdentity(rsProfileKey, ACCOUNT_HASH_INVALID);
    }

    public String getRsProfileKey()
    {
        return rsProfileKey;
    }

    public long getAccountHash()
    {
        return accountHash;
    }

    /** Filesystem-safe directory name derived from the RS profile key. */
    public String storageFolderName()
    {
        StringBuilder out = new StringBuilder(rsProfileKey.length());
        for (int i = 0; i < rsProfileKey.length(); i++)
        {
            char c = rsProfileKey.charAt(i);
            if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.')
            {
                out.append(c);
            }
            else
            {
                out.append('_');
            }
        }
        String name = out.toString();
        return name.isEmpty() ? "profile" : name;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof TrackingIdentity))
        {
            return false;
        }
        TrackingIdentity that = (TrackingIdentity) other;
        return Objects.equals(rsProfileKey, that.rsProfileKey);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(rsProfileKey);
    }

    @Override
    public String toString()
    {
        return "TrackingIdentity{rsProfileKey=***, accountHashSet="
            + (accountHash != ACCOUNT_HASH_INVALID) + "}";
    }
}
