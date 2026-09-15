package com.gpmanager.ui;

/** Tick-bounded lifetime for the most recent XP-confirmed HUD+ process title. */
public final class ProcessTitleHold
{
    private String title = "";
    private int ticksRemaining;

    public void confirm(String title, int ticks)
    {
        String normalized = title == null ? "" : title.trim();
        this.title = normalized;
        this.ticksRemaining = normalized.isEmpty() ? 0 : Math.max(0, ticks);
        if (this.ticksRemaining == 0)
        {
            this.title = "";
        }
    }

    /** @return true exactly when this tick expires a previously live title. */
    public boolean advance()
    {
        if (ticksRemaining <= 0)
        {
            return false;
        }
        ticksRemaining--;
        if (ticksRemaining == 0)
        {
            title = "";
            return true;
        }
        return false;
    }

    public void clear()
    {
        title = "";
        ticksRemaining = 0;
    }

    public boolean isActive()
    {
        return ticksRemaining > 0 && !title.isEmpty();
    }

    public String getTitle()
    {
        return title;
    }

    public int getTicksRemaining()
    {
        return ticksRemaining;
    }
}
