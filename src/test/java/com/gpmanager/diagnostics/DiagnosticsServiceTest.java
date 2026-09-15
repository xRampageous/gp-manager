package com.gpmanager.diagnostics;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.persistence.SessionRepository;
import java.nio.file.Files;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticsServiceTest
{
    @Test
    public void reportIncludesFingerprintAndOmitsSecrets() throws Exception
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> java.util.Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        DiagnosticsService service = new DiagnosticsService(
            engine,
            new SessionRepository(new Gson(), Files.createTempDirectory("gp-diag")),
            new PartyProfitTracker(null, null),
            new DebugTrace(),
            null,
            new GpManagerConfig() {},
            new com.gpmanager.ui.LatestDropModel());

        String report = service.buildReport(null);
        assertTrue(report.contains("Build fingerprint:"));
        assertTrue(report.contains("Schema:"));
        assertTrue(report.contains("Ordinary clean restart/resume is not file corruption"));
        assertFalse(report.contains("Party passphrase="));
        assertFalse(report.contains("ownerKey="));
        assertFalse(report.contains("C:\\\\Users"));
    }
}
