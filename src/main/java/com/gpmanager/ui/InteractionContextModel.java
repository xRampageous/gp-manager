package com.gpmanager.ui;

import com.gpmanager.model.InsightsActivityCategory;
import javax.inject.Singleton;

/** Transient display context only. Never holds client actors or changes accounting. */
@Singleton
public final class InteractionContextModel
{
    public static final long GRACE_MILLIS = 5_000L;
    /** Soft stick after grace: skilling XP / animation gaps may re-arm briefly. */
    public static final long SOFT_STICK_MILLIS = 8_000L;
    private View current = View.EMPTY;
    private long revision;
    /**
     * After Idle / bank / process clear: do not re-arm NPC titles from
     * {@code Player#getInteracting()} tick fallback until a fresh
     * {@code InteractingChanged} (or explicit {@link #npc}/{@link #player}).
     * Death clear leaves this false so post-respawn tick can restore a live fight.
     */
    private boolean npcTickRearmBlocked;
    /** Last non-NPC scenery cleared by Idle/bank — soft-restore on leave-Idle + animation. */
    private String lastObjectOwner = "";
    private String lastObjectKey = "";
    private String lastObjectName = "";
    private String lastObjectGerund = "";
    private InsightsActivityCategory lastObjectCategory = InsightsActivityCategory.OTHER;
    private long lastObjectClearedAtEpochMillis;

    public static final class View
    {
        public static final View EMPTY = new View(
            "", "", "", "", false, false, 0L, InsightsActivityCategory.OTHER);
        private final String owner;
        private final String key;
        private final String name;
        private final String actionGerund;
        private final boolean npc;
        private final boolean selected;
        private final long expiresAt;
        private final InsightsActivityCategory category;

        private View(
            String owner,
            String key,
            String name,
            String actionGerund,
            boolean npc,
            boolean selected,
            long expiresAt,
            InsightsActivityCategory category)
        {
            this.owner = owner;
            this.key = key;
            this.name = name;
            this.actionGerund = actionGerund == null ? "" : actionGerund;
            this.npc = npc;
            this.selected = selected;
            this.expiresAt = expiresAt;
            this.category = category;
        }

        public String getName() { return name; }
        /** Panel / tooltips may keep the selected suffix; HUD+ paints the bare name. */
        public String getLabel() { return selected && !name.isEmpty() ? name + " (selected)" : name; }

        /**
         * HUD+ title: NPC/player bare name; gather scenery object name; process
         * stations/actions as gerunds (Smelting, Crafting, …).
         */
        public String getHudPlusName()
        {
            if (npc || name.isEmpty())
            {
                return name;
            }
            return HudPlusProcessLabels.resolveHudPlusObjectName(name, actionGerund);
        }

        public String getActionGerund() { return actionGerund; }
        public boolean isSelected() { return selected; }
        public boolean isPresent() { return !name.isEmpty(); }
        public InsightsActivityCategory getCategory() { return category; }
    }

    public synchronized View snapshot(String owner, long now)
    {
        return owner != null && !owner.isEmpty() && owner.equals(current.owner)
            && now < current.expiresAt ? current : View.EMPTY;
    }

    /** Whether this owner still has a live or grace-retained NPC context. */
    public synchronized boolean hasNpcContext(String owner, long now)
    {
        View view = snapshot(owner, now);
        return view.npc && view.category != InsightsActivityCategory.PK;
    }

    /** Whether the visible actor context is an NPC classified as PvM. */
    public synchronized boolean hasPvmContext(String owner, long now)
    {
        View view = snapshot(owner, now);
        return view.npc && view.category == InsightsActivityCategory.PVM;
    }

    public synchronized long revision() { return revision; }

    public synchronized void npc(String owner, int index, int id, String name)
    {
        npc(owner, index, id, name, true);
    }

    /** NPC title plus the API's positive combat-level signal for death classification. */
    public synchronized void npc(String owner, int index, int id, String name, boolean combatNpc)
    {
        npcTickRearmBlocked = false;
        InsightsActivityCategory category = name != null
            && name.toLowerCase(java.util.Locale.ROOT).contains("fishing spot")
            ? InsightsActivityCategory.SKILLING
            : (combatNpc ? InsightsActivityCategory.PVM : InsightsActivityCategory.OTHER);
        update(owner, "npc:" + index + ':' + id, name, "", true, false, Long.MAX_VALUE, category);
    }

    public synchronized void player(String owner, int index, String name)
    {
        npcTickRearmBlocked = false;
        update(owner, "player:" + index, name, "", true, false, Long.MAX_VALUE, InsightsActivityCategory.PK);
    }

    /** True when tick fallback must not re-apply a stale NPC/player target. */
    public synchronized boolean isNpcTickRearmBlocked()
    {
        return npcTickRearmBlocked;
    }

    public synchronized void selectObject(String owner, String key, String name, long now)
    {
        selectObject(owner, key, name, now, InsightsActivityCategory.SKILLING, "");
    }

    public synchronized void selectObject(String owner, String key, String name, long now,
        InsightsActivityCategory category)
    {
        selectObject(owner, key, name, now, category, "");
    }

    public synchronized void selectObject(
        String owner,
        String key,
        String name,
        long now,
        InsightsActivityCategory category,
        String actionGerund)
    {
        update(owner, "object:" + key, name, actionGerund, false, true, now + GRACE_MILLIS, category);
    }

    public synchronized void confirmObject(String owner, long now)
    {
        if (snapshot(owner, now).isPresent() && !current.npc)
        {
            update(owner, current.key, current.name, current.actionGerund, false, false,
                now + GRACE_MILLIS, current.category);
        }
    }

    /**
     * Extends gather/process object grace while animating or skilling XP so
     * inter-swing gaps do not drop the HUD title (Willow tree stays, not logs).
     * Soft-stick allows a short re-arm after grace expires while the name is still
     * the last known object. No-op for NPCs or empty context.
     */
    public synchronized boolean refreshObjectGrace(long now)
    {
        if (current.name.isEmpty() || current.npc)
        {
            return false;
        }
        if (now >= current.expiresAt + SOFT_STICK_MILLIS)
        {
            return false;
        }
        update(current.owner, current.key, current.name, current.actionGerund, false,
            current.selected, now + GRACE_MILLIS, current.category);
        return true;
    }

    /**
     * Re-arm sticky scenery for matching XP without displacing a fresh target:
     * <ul>
     *   <li>NPC/player targets always outrank process XP until interaction grace ends</li>
     *   <li>Matching process scenery may extend its own grace</li>
     *   <li>Conflicting/gather scenery stays until Idle/retarget and is not extended</li>
     * </ul>
     */
    public synchronized boolean refreshObjectGraceForSkill(String skillName, long now)
    {
        if (current.name.isEmpty())
        {
            return false;
        }
        String skill = skillName == null ? "" : skillName.trim();
        if (!skill.isEmpty() && HudPlusProcessLabels.isProcessTitle(skill))
        {
            // A fresh interaction remains rank 1; XP can only refresh a matching
            // object title, never erase an NPC/player or conflicting object target.
            if (current.npc)
            {
                return false;
            }
            String hudTitle = HudPlusProcessLabels.resolveHudPlusObjectName(
                current.name, current.actionGerund);
            if (!HudPlusProcessLabels.isProcessTitle(hudTitle))
            {
                return false;
            }
            if (!skill.equalsIgnoreCase(hudTitle))
            {
                return false;
            }
        }
        if (current.npc)
        {
            return false;
        }
        return refreshObjectGrace(now);
    }

    /** True when a non-NPC object context is still within grace. */
    public synchronized boolean hasStickyObject(long now)
    {
        return !current.name.isEmpty() && !current.npc && now < current.expiresAt;
    }

    public synchronized void releaseNpc(String owner, long now)
    {
        if (snapshot(owner, now).isPresent() && current.npc && current.expiresAt == Long.MAX_VALUE)
        {
            update(owner, current.key, current.name, "", true, false, now + GRACE_MILLIS, current.category);
        }
    }

    public synchronized void cancelObject(String owner)
    {
        if (owner != null && owner.equals(current.owner) && !current.npc) clear();
    }

    public synchronized void clear()
    {
        rememberObjectBeforeClear();
        current = View.EMPTY;
        revision++;
    }

    /**
     * Wipe context and block getInteracting tick re-arm until a real engage
     * event. Use for Character Idle / bank open — not local death (respawn may
     * still be fighting the same target without a new InteractingChanged).
     */
    public synchronized void clearAndBlockNpcTickRearm()
    {
        rememberObjectBeforeClear();
        current = View.EMPTY;
        revision++;
        npcTickRearmBlocked = true;
    }

    /**
     * After Character Idle ends while animating: restore last scenery within
     * soft-stick so Willow tree returns without a re-click.
     */
    public synchronized boolean softRestoreLastObject(String owner, long now)
    {
        if (owner == null || owner.isEmpty() || lastObjectName.isEmpty())
        {
            return false;
        }
        if (!owner.equals(lastObjectOwner))
        {
            return false;
        }
        if (lastObjectClearedAtEpochMillis <= 0L
            || now - lastObjectClearedAtEpochMillis > SOFT_STICK_MILLIS)
        {
            return false;
        }
        if (!current.name.isEmpty())
        {
            return false;
        }
        update(
            lastObjectOwner,
            lastObjectKey,
            lastObjectName,
            lastObjectGerund,
            false,
            true,
            now + GRACE_MILLIS,
            lastObjectCategory);
        return !current.name.isEmpty();
    }

    private void rememberObjectBeforeClear()
    {
        if (current.name.isEmpty() || current.npc)
        {
            return;
        }
        lastObjectOwner = current.owner;
        lastObjectKey = current.key;
        lastObjectName = current.name;
        lastObjectGerund = current.actionGerund;
        lastObjectCategory = current.category;
        lastObjectClearedAtEpochMillis = System.currentTimeMillis();
    }

    private void update(
        String owner,
        String key,
        String name,
        String actionGerund,
        boolean npc,
        boolean selected,
        long expiresAt,
        InsightsActivityCategory category)
    {
        String clean = name == null ? "" : name.replaceAll("<[^>]*>", "").trim();
        String gerund = actionGerund == null ? "" : actionGerund.trim();
        if (owner == null || owner.isEmpty() || clean.isEmpty() || "null".equalsIgnoreCase(clean))
        {
            clear();
            return;
        }
        if (!owner.equals(current.owner) || !key.equals(current.key) || !clean.equals(current.name)
            || !gerund.equals(current.actionGerund)
            || npc != current.npc || selected != current.selected || expiresAt != current.expiresAt
            || category != current.category)
        {
            current = new View(owner, key, clean, gerund, npc, selected, expiresAt, category);
            revision++;
        }
    }
}
