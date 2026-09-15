package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.WealthSnapshotHistory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portable, integrity-checked envelope around the exact {@link SavedState}
 * persistence payload. The state remains the source of truth; the envelope
 * only adds export metadata, identity, a summary and a corruption-detection
 * hash.
 */
public final class ProfileBackup
{
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = UnknownFieldPreservation.wrap(new Gson());
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private int formatVersion;
    private int schemaVersion;
    private long exportedAtEpochMillis;
    private String profileId;
    private String profileTimeZoneId;
    private SavedState state;
    private Counts counts;
    private String sha256;

    private ProfileBackup()
    {
    }

    public static ProfileBackup create(SavedState source, String profileId, long exportedAtEpochMillis)
    {
        if (source == null) throw new IllegalArgumentException("state");
        String owner = profileId == null ? "" : profileId.trim();
        if (owner.isEmpty()) throw new IllegalArgumentException("profileId");
        SavedState detached = GSON.fromJson(GSON.toJson(source), SavedState.class);
        detached.setOwnerKey(owner);

        ProfileBackup backup = new ProfileBackup();
        backup.formatVersion = CURRENT_FORMAT_VERSION;
        backup.schemaVersion = detached.getSchemaVersion();
        backup.exportedAtEpochMillis = Math.max(0L, exportedAtEpochMillis);
        backup.profileId = owner;
        backup.profileTimeZoneId = detached.getProfileTimeZoneId();
        backup.state = detached;
        backup.counts = Counts.from(detached);
        backup.sha256 = backup.calculateHash();
        return backup;
    }

    /** Parses the envelope and rejects missing, inconsistent, or tampered data. */
    public static ProfileBackup parseAndVerify(String json)
    {
        if (json == null || json.trim().isEmpty())
            throw new IllegalArgumentException("Backup is empty");

        JsonObject root;
        try
        {
            root = new JsonParser().parse(json).getAsJsonObject();
        }
        catch (RuntimeException ex)
        {
            throw new IllegalArgumentException("Backup JSON is malformed", ex);
        }

        require(root, "formatVersion");
        require(root, "schemaVersion");
        require(root, "exportedAtEpochMillis");
        require(root, "profileId");
        require(root, "profileTimeZoneId");
        require(root, "state");
        require(root, "counts");
        require(root, "sha256");
        if (!root.get("counts").isJsonObject())
            throw new IllegalArgumentException("Backup summary counts are invalid");
        JsonObject countObject = root.getAsJsonObject("counts");
        require(countObject, "sessions");
        require(countObject, "receipts");
        require(countObject, "compacted");
        require(countObject, "wealthCaptures");
        require(countObject, "rollupDays");
        if (!root.get("state").isJsonObject()
            || !root.getAsJsonObject("state").has("schemaVersion"))
            throw new IllegalArgumentException("Backup state schema is missing");

        ProfileBackup backup;
        try
        {
            backup = GSON.fromJson(root, ProfileBackup.class);
        }
        catch (RuntimeException ex)
        {
            throw new IllegalArgumentException("Backup contents are invalid", ex);
        }
        if (backup == null || backup.state == null || backup.counts == null)
            throw new IllegalArgumentException("Backup contents are incomplete");
        if (backup.formatVersion != CURRENT_FORMAT_VERSION)
            throw new IllegalArgumentException("Unsupported backup format " + backup.formatVersion);
        if (backup.schemaVersion <= 0 || backup.state.getSchemaVersion() != backup.schemaVersion)
            throw new IllegalArgumentException("Backup schema metadata does not match its saved state");
        if (backup.profileId == null || backup.profileId.trim().isEmpty())
            throw new IllegalArgumentException("Backup profile identity is missing");
        if (backup.exportedAtEpochMillis <= 0L)
            throw new IllegalArgumentException("Backup export time is invalid");
        String stateOwner = backup.state.getOwnerKey();
        if (stateOwner != null && !stateOwner.trim().isEmpty()
            && !stateOwner.equals(backup.profileId))
            throw new IllegalArgumentException("Backup owner does not match its profile identity");
        if (!same(backup.profileTimeZoneId, backup.state.getProfileTimeZoneId()))
            throw new IllegalArgumentException("Backup timezone metadata does not match its saved state");

        String suppliedHash = backup.sha256 == null ? "" : backup.sha256.trim().toLowerCase();
        JsonObject withoutHash = root.deepCopy();
        withoutHash.remove("sha256");
        String expectedHash = sha256(GSON.toJson(withoutHash));
        if (!constantTimeEquals(suppliedHash, expectedHash))
            throw new IllegalArgumentException("Backup content hash does not match");
        if (!backup.counts.matches(Counts.from(backup.state)))
            throw new IllegalArgumentException("Backup summary counts do not match its saved state");
        return backup;
    }

    private static void require(JsonObject object, String name)
    {
        if (!object.has(name) || object.get(name).isJsonNull())
            throw new IllegalArgumentException("Backup field is missing: " + name);
    }

    private String calculateHash()
    {
        JsonObject object = GSON.toJsonTree(this).getAsJsonObject();
        object.remove("sha256");
        return sha256(GSON.toJson(object));
    }

    private static String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                hex.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(b & 0x0f, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException impossible)
        {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String left, String right)
    {
        if (left == null || right == null || left.length() != right.length()) return false;
        int difference = 0;
        for (int i = 0; i < left.length(); i++) difference |= left.charAt(i) ^ right.charAt(i);
        return difference == 0;
    }

    private static boolean same(String left, String right)
    {
        return left == null ? right == null : left.equals(right);
    }

    public String toJson()
    {
        return GSON.toJson(this);
    }

    /** Human-readable confirmation summary. Size is the final UTF-8 JSON size. */
    public String describe()
    {
        long bytes = toJson().getBytes(StandardCharsets.UTF_8).length;
        return counts.sessions + " sessions · " + counts.receipts + " receipts · "
            + counts.compacted + " compacted · " + counts.wealthCaptures
            + " wealth captures · " + counts.rollupDays + " rollup days · " + formatBytes(bytes);
    }

    public static String fileNameFor(LocalDate date)
    {
        return fileNameFor(date, 0);
    }

    /** Pure collision-safe suffix variant for a caller that found a date name already present. */
    public static String fileNameFor(LocalDate date, int collisionIndex)
    {
        if (date == null) throw new IllegalArgumentException("date");
        if (collisionIndex < 0) throw new IllegalArgumentException("collisionIndex");
        String suffix = collisionIndex == 0 ? "" : "-" + (collisionIndex + 1);
        return "gp-manager-profile-" + FILE_DATE.format(date) + suffix + ".json";
    }

    private static String formatBytes(long bytes)
    {
        if (bytes < 1024L) return bytes + " B";
        return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0d);
    }

    public int getFormatVersion() { return formatVersion; }
    public int getSchemaVersion() { return schemaVersion; }
    public long getExportedAtEpochMillis() { return exportedAtEpochMillis; }
    public String getProfileId() { return profileId; }
    public String getProfileTimeZoneId() { return profileTimeZoneId; }
    public Counts getCounts() { return counts; }
    public String getSha256() { return sha256; }
    public SavedState getState()
    {
        return state == null ? null : GSON.fromJson(GSON.toJson(state), SavedState.class);
    }

    public static final class Counts
    {
        private long sessions;
        private long receipts;
        private long compacted;
        private long wealthCaptures;
        private long rollupDays;

        private Counts()
        {
        }

        private static Counts from(SavedState state)
        {
            Counts result = new Counts();
            Map<String, ProfitSession> sessions = new LinkedHashMap<>();
            add(sessions, state.getActiveSession());
            add(sessions, state.getGeneralSession());
            add(sessions, state.getCustomSession());
            for (ProfitSession session : state.getHistory()) add(sessions, session);
            result.sessions = sessions.size();
            for (ProfitSession session : sessions.values())
            {
                result.receipts += session.getTransactions().size();
                result.compacted += session.getCompactedTransactionCount();
            }
            WealthSnapshotHistory wealth = state.getWealthSnapshotHistory();
            List<WealthSnapshotHistory.Snapshot> snapshots = wealth == null
                ? new ArrayList<>() : wealth.getSnapshots();
            result.wealthCaptures = snapshots.size();
            List<DailyRollup> rollups = state.getDailyRollups();
            result.rollupDays = rollups == null ? 0L : rollups.size();
            return result;
        }

        private static void add(Map<String, ProfitSession> sessions, ProfitSession session)
        {
            if (session == null) return;
            String id = session.getId();
            String key = id == null || id.trim().isEmpty()
                ? "anonymous-" + System.identityHashCode(session) : id;
            sessions.putIfAbsent(key, session);
        }

        private boolean matches(Counts other)
        {
            return other != null && sessions == other.sessions && receipts == other.receipts
                && compacted == other.compacted && wealthCaptures == other.wealthCaptures
                && rollupDays == other.rollupDays;
        }

        public long getSessions() { return sessions; }
        public long getReceipts() { return receipts; }
        public long getCompacted() { return compacted; }
        public long getWealthCaptures() { return wealthCaptures; }
        public long getRollupDays() { return rollupDays; }
    }
}
