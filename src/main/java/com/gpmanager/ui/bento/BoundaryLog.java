package com.gpmanager.ui.bento;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Marks an automatic boundary left on the ribbon when it is set to <i>Mark</i> (slayer task
 * assigned / done, raid entered / left, bank visit). Held per active engine session and
 * cleared when the owner changes. Presentation only; never persisted.
 */
public final class BoundaryLog
{
    public static final class Entry
    {
        public final Ribbon.MarkKind kind;
        public final String label;
        public final long at;

        Entry(Ribbon.MarkKind kind, String label, long at)
        {
            this.kind = kind;
            this.label = label;
            this.at = at;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    @Nullable
    private String sessionId;

    public synchronized void note(@Nullable String activeSessionId, Ribbon.MarkKind kind, String label, long at)
    {
        if (activeSessionId == null)
        {
            return;
        }
        if (!activeSessionId.equals(sessionId))
        {
            entries.clear();
            sessionId = activeSessionId;
        }
        entries.add(new Entry(kind, label == null ? "" : label, at));
        if (entries.size() > 200)
        {
            entries.remove(0);
        }
    }

    public synchronized List<Entry> entries(@Nullable String activeSessionId)
    {
        if (activeSessionId == null || !activeSessionId.equals(sessionId))
        {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
