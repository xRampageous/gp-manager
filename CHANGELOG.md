# GP Manager changelog

## 1.0.1 — 2026-10-01

- **One GP/h.** GP/h is your Net over active time on Live, Grinds, HUD+ and
  exports. The Rate window setting, the HUD+ "Last 10m" line and the "last 15m"
  label are gone.
- **Charging stays neutral.** Loading a weapon works whichever way round you use
  the items, and uncharging stays a transfer even when you take your time with
  the confirm prompt. Choosing No, then looting the same runes, still counts as loot.
- **Pending is tidier.** A Grand Exchange offer you cancel before anything fills
  no longer waits in Pending.
- **Bottomless compost bucket** charges are no longer tracked.
- **Smoother saving.** While nothing changes, the profile saves every 5 minutes
  instead of every 30 seconds, so large profiles stutter less. Sidebar edits save
  straight away.
- **Fixes.** Re-enabling the plugin while logged in reads the game on the right
  thread, and menu text matches on any system language.
- **Readable source.** Clear names and normal layout throughout; no change in
  behaviour.

## 1.0.0 — 2026-10-01

GP Manager keeps track of what you earn and spend while you play. It observes
inventory, equipment, bank and Grand Exchange changes, records the evidence, and
lets you review anything it cannot confidently classify.

- **Live, Ledger and Grinds.** Three tabs cover current tracking, the accounting
  ledger, and saved activities. Free play tracks between named Grinds. Live shows
  Net, gains, supplies, losses, active time, GP/h and your target.
- **Named Grinds and targets.** Save activities with profit or time targets,
  mark favourites, start another run, and compare completed runs with the last.
  Finished runs keep their loot by source and offer a recap. All time shows
  retained totals, active time, Grinds played and your best runs.
- **A ledger you can inspect and correct.** Browse Gains, Losses, Market, Deaths,
  Pending and Review, with search and a scope picker. Open a row for its receipts
  and counting reason. Correct a receipt, mark a transfer, exclude it, split it,
  or undo a correction after previewing the effect on Net. Review supports a
  preview of Decide all and an undo.
- **HUD+.** See activity, Net, GP/h and target progress in the game window, with
  a loot tray and hover details. The empty HUD stays compact, narrow money rows
  wrap, and a finished Grind offers a short recap. Pause and resume from the
  status gem. Text size, width, opacity and reduced motion are configurable.
- **Loot display preferences.** The tray defaults to This streak and Follow
  Ground Items. Hide individual Recent items and restore them from the saved
  list. Display filters leave Ledger values, Net and exports unchanged; hidden
  loot is counted in the hover details without opening an empty tray.
- **Observed loot and supplies.** Collected loot counts when it reaches your
  inventory. Banking, equipment and supported container movements stay neutral.
  Confirmed food, potion doses, runes, ammo and other supplies keep their own
  receipts. Own-drop pickups reverse only the matched loss.
- **Spell and charge details.** Evidenced spells retain their names and icons.
  Supported charged items record per-use estimates, with in-game Checks
  reconciling supported measured balances. Charge receipts distinguish Estimated
  from Measured and retain the captured unit price and source. Ambiguous changes
  remain in Review.
- **Grand Exchange accounting.** Partial fills, collections, cancellations and
  slot reuse reconcile against observed custody. Verified sell proceeds, tax
  and acquisition basis are counted once. RuneLite 1.13.1 supports the Beyond Max
  Cash update; out-of-range unit-price quotes stay unpriced in Review.
- **PvP and death accounting.** Dangerous-context kills, deaths and collected
  player loot retain their own receipts. Loot keys and chests settle from
  measured contents. Death returns and reclaim fees stay separate from new
  loot. Opponent names are not stored.
- **Frozen booking prices.** Each receipt keeps its observed quote or manual
  override. Actual GE settlement provides stronger evidence than a quote.
  Unknown prices remain unknown, and history is never repriced.
- **CSV exports.** Export history summaries or the receipts for one run, one
  file per export, with Copy file path feedback. Empty history reports zero
  retained runs.
- **Receipt compaction.** Older receipts and detail beyond the receipt limit
  fold into exact correction-aware totals. The selected retention window governs
  age-based compaction of closed runs; it does not guarantee a full window of
  individual receipts. Unresolved loot-key claims remain retained, and CSV marks
  compacted summaries as `COMPACTED`.
- **Local profiles and recovery.** Account profiles stay separate. Saves use
  atomic writes, a recovery copy and revision fencing. Back up or restore a
  profile, copy its data-folder path, and clear old backups while keeping the
  newest. Restore makes a safety copy first; factory reset offers Back up first
  and refuses to continue if that backup fails. A conflicting external save
  pauses tracking until RuneLite restarts and reloads disk truth.

Data stays local. GP Manager makes no network calls of its own and performs no
in-game automation.
