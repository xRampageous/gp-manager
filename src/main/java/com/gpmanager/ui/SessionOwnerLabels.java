package com.gpmanager.ui;

import javax.annotation.Nullable;

/**
 * User-facing durable-owner vocabulary. Internal field {@code generalSession}
 * stays; displayed durable name is {@link #DURABLE_OWNER_NAME}.
 */
public final class SessionOwnerLabels
{
	public static final String DURABLE_OWNER_NAME = "Overall";
	/** Legacy durable display name — migrate on load; still accepted for badges. */
	public static final String LEGACY_DURABLE_OWNER_NAME = "General";

	private SessionOwnerLabels()
	{
	}

	public static boolean isDurableOwnerName(@Nullable String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return true;
		}
		String trimmed = name.trim();
		return DURABLE_OWNER_NAME.equalsIgnoreCase(trimmed)
			|| LEGACY_DURABLE_OWNER_NAME.equalsIgnoreCase(trimmed);
	}

	/** Empty / legacy General → Overall for new durable trackers and migration. */
	public static String durableDisplayName(@Nullable String name)
	{
		if (name == null || name.trim().isEmpty()
			|| LEGACY_DURABLE_OWNER_NAME.equalsIgnoreCase(name.trim()))
		{
			return DURABLE_OWNER_NAME;
		}
		return name.trim();
	}

	/**
	 * Folio title: {@code Overall} when the durable owner is active;
	 * {@code Current · {name}} while a custom session owns the tray.
	 */
	public static String folioTitle(@Nullable String sessionName, boolean customSessionActive)
	{
		if (!customSessionActive || isDurableOwnerName(sessionName))
		{
			return DURABLE_OWNER_NAME;
		}
		String trimmed = sessionName == null ? "" : sessionName.trim();
		return trimmed.isEmpty() ? "Current" : "Current · " + trimmed;
	}

	public static String emptyFolioTip(String folioTitle)
	{
		String title = folioTitle == null || folioTitle.isEmpty() ? DURABLE_OWNER_NAME : folioTitle;
		return "No items in " + title + " yet";
	}
}
