# Live acceptance matrix

Current candidate: **1.0.0**, three pages **Live, Ledger and Grinds**, save schema
**108**. Offline tests and simulations exercise accounting and UI models.
On **2026-10-01**, the owner reported approximately **90%** of live acceptance
tested and accepted this candidate for release. Individual row results were not
provided, so the entries below remain **pending documentation**; this does not
claim that the remaining checks have passed.
Use disposable/copied data for reset, damaged-save and multi-client scenarios.

| Area | Acceptance observation | Recorded live result |
| --- | --- | --- |
| Fresh install | Enable/disable and open all three pages; initial inventory creates no gain; gameplay auto-starts without a Resume Tracking button | Pending |
| Tracking | Status gem pauses/resumes the same run; manual pause persists across login; AFK pause uses configured active-time behavior | Pending |
| HUD | Untitled empty run uses one compact row; money wraps at 120px; all text sizes, long timer, hover and opacity are readable | Pending |
| Tray | This streak default; Whole session preference retained; same-name new run clears prior loot/moments; renamed run retains identity | Pending |
| Display filtering | Follow Ground Items default; hidden loot count appears only in HUD folio; GE trades excluded; all-hidden tray stays folded; gain filters leave Net and CSV unchanged; costs and unpriced gains stay visible | Pending |
| Grinds | Start/end, saved setup/targets, Start again, completed detail and All time agree with owned receipts; empty factory-reset state auto-starts on gameplay | Pending |
| Loot and supplies | Delayed pickup counts only measured inventory gain; food/potions/runes/ammo costs are observed; mixed NPC and PK gains keep separate sources and encounters | Pending |
| Transfers | Bank deposits/withdrawals, equipment swaps, pouch and supported container movements remain neutral; Withdraw-200 then quick bank close stays neutral; cancelled Withdraw-X cannot mask later gathering; stale bank-open hints cannot mask later consumption | Pending |
| GE | Partial fill/cancel/slot reuse, collection, offline/login replay and delayed callbacks reconcile with observed custody; tax and basis counted once | Pending |
| PK | Only confidently dangerous events count; two encounters with the same loot ID retain ownership; safe contexts stay neutral; logout/hop clears stale combat evidence | Pending |
| Loot keys/chests | Pending key survives restart/retention; measured chest contents settle once; shared-ID contents and additional loot do not close the wrong claim | Pending |
| PvM reclaim | Death wipe, grave/retrieval return, fee and new loot stay distinct; recent PvP from a prior login/profile does not change current PvM disposition | Pending |
| Charges | Fresh checks seed baselines; later measured decreases count, top-ups stay neutral; same-variant weapons/slot changes and restart cannot compare unrelated balances | Pending |
| Review | Unknown prices remain unknown; unconfirmed mixed changes wait for decision; Correct, Split and Undo reconcile every surface/export | Pending |
| Retention | Closed receipt detail compacts at the selected age or at receipt limits; retained summaries remain exact; CSV uses COMPACTED totals without invented item rows | Pending |
| Account isolation | Logout/hop/profile changes flush and rebind the correct RS profile; no previous-owner loot, combat hints or history leaks | Pending |
| Persistence conflict | A second client's newer revision is preserved; repeat autosave/reset refuses stale writes; warning explains backup and restart; restart loads external state | Pending |
| Backup/reset/recovery | Back up and copy folder path; backups/ keeps newest 10 per account; Restore backup brings back an older save (safety copy first); Clear old backups keeps the newest; reset only current profile after backup; damaged primary recovers validated backup without resurrecting reset data | Pending |
| Soak | Long live session stays responsive; page switching, repeated refresh, save/reload and logs show no unbounded activity | Pending |

The full local release gate is `releaseCheck`; preview images use deterministic fixtures and are
not screenshots of live gameplay.
