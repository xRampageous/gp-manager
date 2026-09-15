package com.gpmanager.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional bounded in-memory debug trace. Disabled by default. Never mutates
 * accounting or uploads data. When enabled, also mirrors lines to the local
 * RuneLite client log for live Drink/Pick/Bury diagnosis.
 */
@Singleton
public class DebugTrace
{
    private static final Logger log = LoggerFactory.getLogger(DebugTrace.class);

    public static final int CAPACITY = 200;

    private final Deque<String> events = new ArrayDeque<>(CAPACITY);
    private volatile boolean enabled;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
        if (!enabled)
        {
            clear();
        }
    }

    public void record(String category, String detail)
    {
        if (!enabled)
        {
            return;
        }
        String line = System.currentTimeMillis() + " [" + safe(category) + "] " + safe(detail);
        synchronized (events)
        {
            while (events.size() >= CAPACITY)
            {
                events.removeFirst();
            }
            events.addLast(line);
        }
        log.debug("{}", line);
    }

    public List<String> snapshot()
    {
        synchronized (events)
        {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    public void clear()
    {
        synchronized (events)
        {
            events.clear();
        }
    }

    private static String safe(String text)
    {
        if (text == null)
        {
            return "";
        }
        // Keep traces free of path/credential-looking payloads by truncation only;
        // callers must not pass secrets.
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
}
