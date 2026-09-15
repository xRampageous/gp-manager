package com.gpmanager.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only DWMS-style locate map for the Live Wealth accordion.
 * Never invents Net from parked storage — status is observe-only.
 */
public final class WealthLocateModel
{
    public enum Status
    {
        LIVE("Live baseline"),
        OBSERVED("Observed"),
        NOT_OBSERVED("Not observed this session"),
        NEUTRAL("Ownership-neutral park");

        private final String label;

        Status(String label)
        {
            this.label = label;
        }

        public String getLabel()
        {
            return label;
        }
    }

    public static final class Slot
    {
        private final String id;
        private final String title;
        private Status status;
        private String detail;

        private Slot(String id, String title, Status status, String detail)
        {
            this.id = id;
            this.title = title;
            this.status = status == null ? Status.NOT_OBSERVED : status;
            this.detail = detail == null ? "" : detail;
        }

        public String getId()
        {
            return id;
        }

        public String getTitle()
        {
            return title;
        }

        public Status getStatus()
        {
            return status;
        }

        public String getDetail()
        {
            return detail;
        }

        public String displayLine()
        {
            StringBuilder sb = new StringBuilder(title).append(" — ").append(status.getLabel());
            if (detail != null && !detail.isEmpty())
            {
                sb.append(" · ").append(detail);
            }
            return sb.toString();
        }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    public WealthLocateModel()
    {
        put("invent", "Inventory / equipment", Status.LIVE, "session baseline");
        put("bank", "Bank", Status.NEUTRAL, "transfers only");
        put("gim", "GIM shared storage", Status.NOT_OBSERVED, "");
        put("poh", "PoH costume room", Status.NOT_OBSERVED, "");
        put("stash", "Clue STASH units", Status.NOT_OBSERVED, "");
        put("raid_bags", "Raid bags / special containers", Status.NOT_OBSERVED, "");
        put("coffers", "Coffers / seed vault / Tool Leprechaun", Status.NEUTRAL, "no Net until spend/claim");
    }

    private void put(String id, String title, Status status, String detail)
    {
        slots.put(id, new Slot(id, title, status, detail));
    }

    /**
     * Marks a storage surface as observed when client evidence exists.
     * Does not value contents or touch Net.
     */
    public void observe(String slotId, String detail)
    {
        Slot slot = slots.get(slotId);
        if (slot == null)
        {
            return;
        }
        if (slot.status == Status.NEUTRAL || slot.status == Status.LIVE)
        {
            if (detail != null && !detail.trim().isEmpty())
            {
                slot.detail = detail.trim();
            }
            return;
        }
        slot.status = Status.OBSERVED;
        if (detail != null && !detail.trim().isEmpty())
        {
            slot.detail = detail.trim();
        }
    }

    /** Clears OBSERVED rows back to NOT_OBSERVED (session reset / logout). */
    public void clearObservations()
    {
        for (Slot slot : slots.values())
        {
            if (slot.status == Status.OBSERVED)
            {
                slot.status = Status.NOT_OBSERVED;
                slot.detail = "";
            }
        }
    }

    public List<Slot> slots()
    {
        return Collections.unmodifiableList(new ArrayList<>(slots.values()));
    }
}
