package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable read model for the run statements belonging to one session. */
public final class RunHistorySnapshot
{
    private final String sessionId;
    private final String sessionName;
    private final List<RunStatementSnapshot> statements;
    private final int unassignedReceiptCount;
    private final long unassignedReceiptValueGp;
    private final String unassignedReceiptStatus;

    public RunHistorySnapshot(
        String sessionId,
        String sessionName,
        List<RunStatementSnapshot> statements,
        int unassignedReceiptCount,
        long unassignedReceiptValueGp,
        String unassignedReceiptStatus)
    {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.sessionName = sessionName == null ? "" : sessionName;
        List<RunStatementSnapshot> copy = new ArrayList<>();
        if (statements != null)
        {
            for (RunStatementSnapshot statement : statements)
            {
                if (statement != null)
                {
                    copy.add(statement);
                }
            }
        }
        this.statements = Collections.unmodifiableList(copy);
        this.unassignedReceiptCount = Math.max(0, unassignedReceiptCount);
        this.unassignedReceiptValueGp = unassignedReceiptValueGp;
        this.unassignedReceiptStatus = unassignedReceiptStatus == null
            ? "UNAVAILABLE" : unassignedReceiptStatus;
    }

    public String getSessionId() { return sessionId; }
    public String getSessionName() { return sessionName; }
    public List<RunStatementSnapshot> getStatements() { return statements; }
    public int getUnassignedReceiptCount() { return unassignedReceiptCount; }
    public long getUnassignedReceiptValueGp() { return unassignedReceiptValueGp; }
    public String getUnassignedReceiptStatus() { return unassignedReceiptStatus; }
}
