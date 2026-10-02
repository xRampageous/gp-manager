# GP Manager changelog

## 1.1.0 — 2026-10-02

- **No save stutter.** The periodic save while you play reuses the saved form of finished
  Grinds that have not changed, so a large history no longer hitches the game every 30 s.
- **A cleaner HUD+ header.** It shows your Grind's name, or "KC: 3" while a kill streak runs;
  never an NPC's name. Activity names (Combat, Woodcutting, a raid) stay on the sidebar's Live
  page, which no longer says "Hitpoints" either.
- **Tighter loot tray.** Its heading sits right on its rows, and rows are a little shorter.
- **Icons.** Every item and spell icon is cropped, fitted to one size and centred, on HUD+ and
  in the sidebar. A new **Item icons** setting turns them off.
- **Ledger and Grinds sit closer to their toolbar,** and the Losses table picks All, Supplies,
  Items or Charges from a dropdown beside its header.
- **The loot tray names the fight.** A kill streak's heading names its NPCs ("Guard",
  "Guard & Man", "Guard, Man +1") with its Net per kill. Going back to an NPC you just left
  joins both streaks into one.
- **Split shows a preview.** After you choose how many to keep, the receipt shows Keep, Others
  and the Net it leaves before you confirm.
- **Picked-up ammo comes back.** Picking up ammo you fired lowers Supplies instead of counting
  as loot. More than you fired still counts as loot.
- **Wilderness risk.** In dangerous areas the Live page shows what a death would lose
  (everything but your three most valuable items, none when skulled, one more with Protect
  Item) beside your skull and Protect Item. HUD+ drops its PvP line and stays short; its gem
  still turns PvP.
- **Smaller for the Plugin Hub's 200k review limit** (the review counted 1.0.3 at 202,333):
  - CSV export is removed; backups stay.
  - The HUD+ hover panel is removed; its figures are on Live, and Net per kill is on the tray.
  - The HUD+ End card, "vs your average" and "New best" moments, and the Grind detail's VS
    PREVIOUS RUN and PERSONAL BESTS are removed. A Grind keeps its outcome, biggest gain and
    cost and loot by source, and HUD+ still says "<Grind> ended" with its Net and time.
  - Death receipts no longer carry the kept/lost explanation text; death accounting is
    unchanged.
- **Blowpipe darts count.** Each shot spends its dart unless an Ava's device saves it (an
  assembler or Dizana's quiver saves 80%, the accumulator 72%, the attractor 60%), booked as
  Supplies like arrows once a Check or a load has shown the dart type; a later Check corrects
  the estimate. The blowpipe's Charges row is now its Zulrah's scales, and blood fury reads as
  one "Blood fury" row.
- **Fixes.** Charged-weapon casts are read from the current game API.

## 1.0.3 — 2026-10-02

- **Source size for the Plugin Hub limit.** Internal names are shortened again and
  lines keep a light indent, which brings the source under the Hub's 200k review
  limit. No change in behaviour.

## 1.0.2 — 2026-10-02

- **Source layout for the Plugin Hub size limit.** Lines no longer use indentation,
  which brings the source under the Hub's 200k review limit. Names stay readable;
  no change in behaviour.

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
