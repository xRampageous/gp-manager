package com.gpmanager.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.ReceiptRetentionPeriod;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.persistence.ProfileBackup;
import com.gpmanager.persistence.ProfileBackupReport;
import com.gpmanager.persistence.SavedState;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileBackupEngineTest
{
    static
    {
        com.gpmanager.persistence.ProfileBackup.bindGson(new com.google.gson.Gson());
    }

    private static final String PROFILE = "profile-key-123";
    private static final long NOW = 1_800_000_000_000L;
    private static final Gson GSON = new Gson();

    private GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.FOREVER;
                }
            });
    }

    @Test
    public void currentProfileBackupRoundTripsStateCountsAndDescription()
    {
        GpManagerEngine source = engine();
        source.restoreForProfile(PROFILE, new SavedState(), 1L);
        source.ensureSession(100L);
        source.getGeneralSession().addTransaction(receipt(200L, "Overall loot"), 100);
        source.startCustomSession("Boss run", SessionMode.AUTO, 300L);
        source.getActiveSession().addTransaction(receipt(350L, "Boss loot"), 100);
        source.finishCustomSession(400L);
        source.getGeneralSession().close(500L);

        ProfileBackup backup = source.exportProfile();
        GpManagerEngine target = engine();
        target.restoreForProfile(PROFILE, new SavedState(), 1L);
        ProfileBackupReport inspected = target.inspectBackup(backup.toJson());
        assertTrue(inspected.getRefusalReason(), inspected.isAccepted());
        assertFalse(inspected.isApplied());
        assertTrue(inspected.getMigrationSteps().isEmpty());

        ProfileBackupReport applied = target.restoreProfile(backup.toJson(), NOW);
        assertTrue(applied.getRefusalReason(), applied.isAccepted());
        assertTrue(applied.isApplied());
        assertEquals(2L, applied.getCounts().getSessions());
        assertEquals(2L, applied.getCounts().getReceipts());
        assertTrue(applied.getBackupDescription().contains("2 sessions"));
        assertTrue(backup.describe().contains("2 receipts"));
        assertTrue(backup.describe().contains("B"));

        GpManagerEngine expectedEngine = engine();
        expectedEngine.restoreForProfile(PROFILE, backup.getState(), NOW);
        SavedState expected = expectedEngine.createSavedState();
        SavedState restored = target.createSavedState();
        expected.setSavedAtEpochMillis(0L);
        restored.setSavedAtEpochMillis(0L);
        assertEquals(GSON.toJson(expected), GSON.toJson(restored));
        assertEquals(PROFILE, restored.getOwnerKey());
        assertEquals(1, target.getGeneralSession().getTransactions().size());
        assertEquals("Boss run", target.getHistory().get(0).getName());
        assertEquals("gp-manager-profile-2026-09-15.json",
            ProfileBackup.fileNameFor(LocalDate.of(2026, 9, 15)));
        assertEquals("gp-manager-profile-2026-09-15-2.json",
            ProfileBackup.fileNameFor(LocalDate.of(2026, 9, 15), 1));
    }

    @Test
    public void schemaEighteenBackupUsesNormalMigrationAndDefersRetention()
    {
        GpManagerEngine source = engine();
        source.restoreForProfile(PROFILE, new SavedState(), 1L);
        source.ensureSession(100L);
        source.getGeneralSession().close(200L);
        SavedState legacy = source.createSavedState();
        legacy.setOwnerKey(PROFILE);
        legacy.setSchemaVersion(18);
        ProfileBackup backup = ProfileBackup.create(legacy, PROFILE, NOW);

        GpManagerEngine target = engine();
        target.restoreForProfile(PROFILE, new SavedState(), 1L);
        ProfileBackupReport report = target.restoreProfile(backup.toJson(), NOW);

        assertTrue(report.getRefusalReason(), report.isAccepted());
        assertTrue(report.isApplied());
        assertEquals(18, report.getSchemaVersion());
        assertTrue(report.getMigrationSteps().contains(
            "Preserve legacy owner uncertainty and defer receipt retention"));
        assertTrue(target.createSavedState().isReceiptRetentionDeferredUntilDayChange());
    }

    @Test
    public void invalidBackupsAreRefusedWithoutChangingTheLiveProfile()
    {
        GpManagerEngine source = engine();
        source.restoreForProfile(PROFILE, new SavedState(), 1L);
        source.ensureSession(100L);
        source.getGeneralSession().addTransaction(receipt(150L, "Source"), 100);
        ProfileBackup valid = source.exportProfile();

        GpManagerEngine target = engine();
        target.restoreForProfile(PROFILE, new SavedState(), 1L);
        target.ensureSession(900L);
        ProfitSession original = target.getGeneralSession();
        String originalId = original.getId();
        int originalRows = original.getTransactions().size();

        ProfileBackup foreign = ProfileBackup.create(valid.getState(), "another-profile", NOW);
        ProfileBackupReport foreignReport = target.restoreProfile(foreign.toJson(), NOW);
        assertFalse(foreignReport.isAccepted());
        assertTrue(foreignReport.getRefusalReason().contains("different"));

        JsonObject tamperedTree = new JsonParser().parse(valid.toJson()).getAsJsonObject();
        tamperedTree.addProperty("exportedAtEpochMillis",
            tamperedTree.get("exportedAtEpochMillis").getAsLong() + 1L);
        ProfileBackupReport corruptReport = target.restoreProfile(GSON.toJson(tamperedTree), NOW);
        assertFalse(corruptReport.isAccepted());
        assertTrue(corruptReport.getRefusalReason().contains("hash"));

        SavedState futureState = valid.getState();
        futureState.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION + 1);
        ProfileBackup future = ProfileBackup.create(futureState, PROFILE, NOW);
        ProfileBackupReport futureReport = target.restoreProfile(future.toJson(), NOW);
        assertFalse(futureReport.isAccepted());
        assertTrue(futureReport.getRefusalReason().contains("newer"));

        ProfileBackupReport malformed = target.restoreProfile("{ definitely not json", NOW);
        assertFalse(malformed.isAccepted());

        assertSame(original, target.getGeneralSession());
        assertEquals(originalId, target.getGeneralSession().getId());
        assertEquals(originalRows, target.getGeneralSession().getTransactions().size());
        assertFalse(target.getGeneralSession().isPaused());
    }

    @Test
    public void exportRequiresAProfileIdentityAndHashCoversBackupMetadata()
    {
        GpManagerEngine unbound = engine();
        try
        {
            unbound.exportProfile();
            fail("unbound export must be refused");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("identity"));
        }

        GpManagerEngine source = engine();
        source.restoreForProfile(PROFILE, new SavedState(), 1L);
        source.ensureSession(100L);
        ProfileBackup backup = source.exportProfile();
        JsonObject edited = new JsonParser().parse(backup.toJson()).getAsJsonObject();
        edited.addProperty("exportedAtEpochMillis",
            edited.get("exportedAtEpochMillis").getAsLong() + 1L);
        try
        {
            ProfileBackup.parseAndVerify(GSON.toJson(edited));
            fail("metadata change must invalidate the hash");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("hash"));
        }
    }

    @Test
    public void mismatchedProfileRestoreIsRejectedBeforeChangingTheEngine()
    {
        GpManagerEngine engine = engine();
        engine.restoreForProfile(PROFILE, new SavedState(), 1L);
        engine.ensureSession(100L);
        ProfitSession original = engine.getGeneralSession();
        SavedState foreign = new SavedState();
        foreign.setOwnerKey("another-profile");

        try
        {
            engine.restoreForProfile(PROFILE, foreign, NOW);
            fail("foreign state must be rejected");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("owner"));
        }
        assertSame(original, engine.getGeneralSession());
        assertFalse(engine.getGeneralSession().isPaused());
    }

    @Test
    public void failedProfileMigrationLeavesExistingOwnerAndStateInPlace()
    {
        final boolean[] failRetentionRead = {false};
        GpManagerConfig guardedConfig = new GpManagerConfig()
        {
            @Override
            public ReceiptRetentionPeriod receiptRetentionDays()
            {
                if (failRetentionRead[0]) throw new IllegalStateException("simulated migration failure");
                return ReceiptRetentionPeriod.FOREVER;
            }
        };
        GpManagerEngine engine = new GpManagerEngine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), guardedConfig);
        engine.restoreForProfile(PROFILE, new SavedState(), 1L);
        engine.ensureSession(100L);
        engine.getGeneralSession().rename("Existing profile");
        ProfitSession original = engine.getGeneralSession();
        SavedState aliased = engine.createSavedState();
        failRetentionRead[0] = true;

        try
        {
            engine.restoreForProfile(PROFILE, aliased, NOW);
            fail("migration failure must leave current profile untouched");
        }
        catch (IllegalStateException expected)
        {
            assertEquals("simulated migration failure", expected.getMessage());
        }
        assertSame(original, engine.getGeneralSession());
        assertEquals("Existing profile", engine.getGeneralSession().getName());
        assertFalse("failed preflight must not pause the aliased live session",
            engine.getGeneralSession().isPaused());
        assertEquals(PROFILE, engine.exportProfile().getProfileId());
    }

    private static ProfitTransaction receipt(long at, String note)
    {
        return new ProfitTransaction(at, TransactionType.LOOT, TrackingContext.LOOT,
            note, true, Collections.singletonList(new ItemFlow(995, "Coins", 10L, 1, 10L)));
    }
}
