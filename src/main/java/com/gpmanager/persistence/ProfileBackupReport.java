package com.gpmanager.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable dry-run or apply result for importing a profile backup. */
public final class ProfileBackupReport
{
    private final boolean accepted;
    private final boolean applied;
    private final int schemaVersion;
    private final String profileId;
    private final String profileTimeZoneId;
    private final List<String> migrationSteps;
    private final String replaces;
    private final String refusalReason;
    private final ProfileBackup.Counts counts;
    private final String backupDescription;
    private final boolean persistenceAttempted;
    private final boolean durablyCommitted;
    private final String persistenceDetail;

    public ProfileBackupReport(boolean accepted, boolean applied, int schemaVersion,
        String profileId, String profileTimeZoneId, List<String> migrationSteps,
        String replaces, String refusalReason, ProfileBackup.Counts counts,
        String backupDescription)
    {
        this(accepted, applied, schemaVersion, profileId, profileTimeZoneId, migrationSteps,
            replaces, refusalReason, counts, backupDescription, false, false, "");
    }

    private ProfileBackupReport(boolean accepted, boolean applied, int schemaVersion,
        String profileId, String profileTimeZoneId, List<String> migrationSteps,
        String replaces, String refusalReason, ProfileBackup.Counts counts,
        String backupDescription, boolean persistenceAttempted, boolean durablyCommitted,
        String persistenceDetail)
    {
        this.accepted = accepted;
        this.applied = applied;
        this.schemaVersion = schemaVersion;
        this.profileId = profileId == null ? "" : profileId;
        this.profileTimeZoneId = profileTimeZoneId == null ? "" : profileTimeZoneId;
        this.migrationSteps = migrationSteps == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(migrationSteps));
        this.replaces = replaces == null ? "" : replaces;
        this.refusalReason = refusalReason == null ? "" : refusalReason;
        this.counts = counts;
        this.backupDescription = backupDescription == null ? "" : backupDescription;
        this.persistenceAttempted = persistenceAttempted;
        this.durablyCommitted = durablyCommitted;
        this.persistenceDetail = persistenceDetail == null ? "" : persistenceDetail;
    }

    public ProfileBackupReport asApplied()
    {
        return new ProfileBackupReport(accepted, true, schemaVersion, profileId,
            profileTimeZoneId, migrationSteps, replaces, refusalReason, counts, backupDescription,
            persistenceAttempted, durablyCommitted, persistenceDetail);
    }

    public ProfileBackupReport withRefusal(String reason)
    {
        return new ProfileBackupReport(false, false, schemaVersion, profileId,
            profileTimeZoneId, migrationSteps, replaces, reason, counts, backupDescription,
            persistenceAttempted, durablyCommitted, persistenceDetail);
    }

    public ProfileBackupReport asDurablyCommitted()
    {
        return new ProfileBackupReport(accepted, applied, schemaVersion, profileId,
            profileTimeZoneId, migrationSteps, replaces, refusalReason, counts, backupDescription,
            true, true, "Saved to the active profile");
    }

    public ProfileBackupReport withPersistenceFailure(String detail)
    {
        return new ProfileBackupReport(accepted, applied, schemaVersion, profileId,
            profileTimeZoneId, migrationSteps, replaces, refusalReason, counts, backupDescription,
            true, false, detail);
    }

    public boolean isAccepted() { return accepted; }
    public boolean isApplied() { return applied; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getProfileId() { return profileId; }
    public String getProfileTimeZoneId() { return profileTimeZoneId; }
    public List<String> getMigrationSteps() { return migrationSteps; }
    public String getReplaces() { return replaces; }
    public String getRefusalReason() { return refusalReason; }
    public ProfileBackup.Counts getCounts() { return counts; }
    public String getBackupDescription() { return backupDescription; }
    public boolean isPersistenceAttempted() { return persistenceAttempted; }
    public boolean isDurablyCommitted() { return durablyCommitted; }
    public String getPersistenceDetail() { return persistenceDetail; }
}
