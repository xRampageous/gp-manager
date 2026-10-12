package com.gpmanager;

import static com.gpmanager.GameData.msg;

import lombok.*;

/**
 * Stable tracking ownership identity.
 *
 * <p>Primary key is the RuneLite RS profile key when available (account hash plus world-type
 * profile). Account hash alone is retained for diagnostics and for matching profiles. Display names
 * are never used as durable identity.
 *
 * <p><b>Account isolation</b> — each RS profile key owns its own General tracker, history, targets,
 * and analytics files. Switching accounts or world-type profiles must never attach one history to
 * another.
 *
 * <p><b>Settings-profile sharing</b> — RuneLite configuration profiles (plugin enablement/settings
 * presets) share the same account data when the same RS profile is logged in. They do not split or
 * duplicate accounting history.
 */
@EqualsAndHashCode(of = "rsProfileKey")
class TrackingIdentity {
    static final long ACCOUNT_HASH_INVALID = -1L;
    String rsProfileKey;
    long accountHash;

    TrackingIdentity(String rsProfileKey, long accountHash) {
        if (Phrases.blank(rsProfileKey)) throw new IllegalArgumentException("rsProfileKey");
        this.rsProfileKey = rsProfileKey.trim();
        this.accountHash = accountHash;
    }

    /** Filesystem-safe directory name derived from the RS profile key. */
    String folderName() {
        var out = new StringBuilder(rsProfileKey.length());
        for (int i = 0; i < rsProfileKey.length(); i++) {
            char c = rsProfileKey.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        String name = out.toString();
        return name.isEmpty() ? "profile" : name;
    }

    public String toString() {
        return msg("trackingidentity-rsprofilekey") + (accountHash != ACCOUNT_HASH_INVALID) + "}";
    }
}

/**
 * Immutable destination and fencing for one persistence attempt.
 *
 * <p>Queued and retry writes must carry this intent so a later account switch cannot redirect the
 * payload. {@code expectedBaseRevision} is the on-disk revision observed when the snapshot was
 * taken; under the write lock the repository refuses the save unless disk still matches.
 *
 * <p>A non-null {@code json} is the payload, serialized once under the engine lock; {@code state}
 * is then only its header (revision, savedAt), already final and never serialized.
 */
class WriteIntent {
    TrackingIdentity identity;
    long scopeGeneration;
    long expectedBaseRevision;
    SavedState state;
    String json;

    WriteIntent(
            TrackingIdentity identity,
            long scopeGeneration,
            long expectedBaseRevision,
            SavedState state) {
        this(identity, scopeGeneration, expectedBaseRevision, state, null);
    }

    WriteIntent(
            TrackingIdentity identity,
            long scopeGeneration,
            long expectedBaseRevision,
            SavedState state,
            String json) {
        if (state == null) throw new IllegalArgumentException("state");
        this.identity = identity;
        this.scopeGeneration = scopeGeneration;
        this.expectedBaseRevision = SafeMath.nonNeg(expectedBaseRevision);
        this.state = state;
        this.json = json;
    }
}
