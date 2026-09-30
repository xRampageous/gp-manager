# Settings and runtime surfaces

Current candidate: 1.0.0, fresh saved-state schema 108. RuneLite settings use the
`gpmanager` group. The sidebar has three pages: **Live, Ledger and Grinds**.
Open plugin configuration through RuneLite's plugin-list wrench.

| Section | Settings and defaults |
| --- | --- |
| Tracking | Automatic tracking on; automatic activity detection on; AFK pause off, timeout 120 seconds; rolling Rate window 15 minutes; reduced motion off |
| Accounting | Equipment and rune pouch included; uncertain mixed changes uncounted; passive PK accounting on |
| HUD+ and Live | Hidden Recent items empty (remove a saved name to restore it); Follow Ground Items; minimum displayed unit GE value 0; HUD shown, hidden when not tracking; Normal text; maximum width 260; opacity 75%; Compact timer; Auto-fold tray, five seconds, four rows; rows kept for **This streak**; Hover detail panel |
| Grinds and history | Save history on; closed-run age compaction defaults to 90 days, with 30/180/365-day alternatives; receipt limits can compact detail sooner |
| Pricing and Market | Manual `itemId=gp` overrides, empty by default |

Saved preferences override defaults. In particular, an existing Whole session tray
choice is preserved. Internal settlement and retention limits are hidden settings,
not additional user-facing sections. There is no configuration migration system.

## Surface ownership

- **Live** displays current money, activity, a Net target and recent changes. Its
  status gem pauses or resumes tracking; there is no Resume Tracking button.
- **Ledger** opens gain, cost, market, death and Review receipts. Its Correct menu
  changes counted treatment, splits receipts and undoes corrections through the engine.
- **Grinds** starts and ends named Grinds, edits targets and saved setups, shows
  completed runs and All time, and owns CSV exports, profile backups, copying the
  data-folder path and factory reset. Restore backup makes a safety copy first;
  factory reset offers Back up first. Clear old backups keeps the newest backup.
- **HUD+** displays the same accounting through a compact overlay. An empty,
  untitled run fits a single row. Monetary segments wrap at narrow widths; hidden
  loot remains counted, with a small Hidden loot count in the hover folio.

Display filters never change revenue, costs, Net or CSV totals. See
[Ground Items filtering](GROUND_ITEMS_FILTER.md). Save-format compatibility is described in
[account isolation](ACCOUNT_ISOLATION.md).
