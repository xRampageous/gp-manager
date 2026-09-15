package com.gpmanager.persistence;

import java.nio.file.Path;

public class CsvExportResult
{
    private final Path detailPath;
    private final Path summaryPath;
    private final Path diagnosticsPath;
    private final Path activitiesPath;
    private final Path pkEncountersPath;
    private final Path auditPath;

    public CsvExportResult(
        Path detailPath,
        Path summaryPath,
        Path diagnosticsPath,
        Path activitiesPath)
    {
        this(detailPath, summaryPath, diagnosticsPath, activitiesPath, null);
    }

    public CsvExportResult(
        Path detailPath,
        Path summaryPath,
        Path diagnosticsPath,
        Path activitiesPath,
        Path pkEncountersPath)
    {
        this(detailPath, summaryPath, diagnosticsPath, activitiesPath, pkEncountersPath, null);
    }

    public CsvExportResult(
        Path detailPath,
        Path summaryPath,
        Path diagnosticsPath,
        Path activitiesPath,
        Path pkEncountersPath,
        Path auditPath)
    {
        this.detailPath = detailPath;
        this.summaryPath = summaryPath;
        this.diagnosticsPath = diagnosticsPath;
        this.activitiesPath = activitiesPath;
        this.pkEncountersPath = pkEncountersPath;
        this.auditPath = auditPath;
    }

    public Path getDetailPath() { return detailPath; }
    public Path getSummaryPath() { return summaryPath; }
    public Path getDiagnosticsPath() { return diagnosticsPath; }
    public Path getActivitiesPath() { return activitiesPath; }
    public Path getPkEncountersPath() { return pkEncountersPath; }
    public Path getAuditPath() { return auditPath; }
}
