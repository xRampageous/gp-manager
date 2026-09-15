package com.gpmanager.model;

/** Immutable factual notification. Alerts are transient and are not saved with profile data. */
public final class AlertEvent
{
    private final long sequenceId;
    private final AlertKind kind;
    private final long atEpochMillis;
    private final String sessionId;
    private final String title;
    private final String detail;
    private final long value;
    private final int itemId;

    public AlertEvent(AlertKind kind, long atEpochMillis, String sessionId, String title,
        String detail, long value, int itemId)
    {
        this(0L, kind, atEpochMillis, sessionId, title, detail, value, itemId);
    }

    private AlertEvent(long sequenceId, AlertKind kind, long atEpochMillis, String sessionId,
        String title, String detail, long value, int itemId)
    {
        this.sequenceId = Math.max(0L, sequenceId);
        this.kind = kind;
        this.atEpochMillis = atEpochMillis;
        this.sessionId = sessionId == null || sessionId.trim().isEmpty() ? null : sessionId.trim();
        this.title = title == null ? "" : title.trim();
        this.detail = detail == null ? "" : detail.trim();
        this.value = value;
        this.itemId = itemId;
    }

    /** Returns an engine-assigned copy; caller-created events retain sequence id zero. */
    public AlertEvent withSequenceId(long id)
    {
        return new AlertEvent(id, kind, atEpochMillis, sessionId, title, detail, value, itemId);
    }

    public long getSequenceId() { return sequenceId; }
    public AlertKind getKind() { return kind; }
    public long getAtEpochMillis() { return atEpochMillis; }
    public String getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    /** GP for value alerts, current goal value, or configured idle minutes by event kind. */
    public long getValue() { return value; }
    /** Item id for item-specific events; -1 when the event has no item. */
    public int getItemId() { return itemId; }
}
