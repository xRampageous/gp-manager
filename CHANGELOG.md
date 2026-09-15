## 1.0.4 — 2026-09-16

- A teleport (or any region load) no longer re-primes the inventory baseline, so the tablet that just broke is booked. Fresh install: the Live button reads **Start** (or **Resume** when stopped) instead of "Waiting". HUD+ item icons can be turned off (Info box › Item icons). Toasts dismiss themselves after 5 s (8 s with an action).

## 1.0.3 — 2026-09-15

- README rewritten for players, with screenshots; shorter Plugin Hub description. No code change.

## 1.0.2 — 2026-09-15

- Plugin Hub review fix, complete: every JSON use outside the injected repository (profile backup, profile-size estimate, staged profile restore) goes through one `JsonCodec` bound to the client's injected Gson at start-up (customised through `newBuilder()`); the built JAR contains no Gson constructor. No behaviour change.

## 1.0.1 — 2026-09-15

- First attempt at the Gson rule (profile backup only); superseded by 1.0.2.

## 1.0.0 — 2026-09-15

GP Manager's first release: passive, inventory-based profit tracking with a Bento sidebar (Live · Ledger · Sessions · Insights · Tools), session categories and automatic boundaries, a Supplies / Loss / Market split that only counts what the engine evidenced, PvP accounting, wealth history with coin stores, records, session merge and compare, alerts, idle auto-end, profile backup / restore with rotated saves, and a Grand Exchange reconciliation path that stays observation-only until a live sale confirms the sell-side reading (Tools › Rules). Data lives in `.runelite/gp-manager`; older folders migrate in once. Everything below was the development review that led here, newest first.

**Defaults chosen without a confirming live sale (the owner cannot use the GE on the test account):** *Book trades from offers* is **off** and *Sell coins are after tax* is **on** — the safe pair: inventory-settled booking as before, provenance attached where an offer matches. The first player to sell an item worth 50 gp or more can settle it from Tools › Diagnostics › Last GE offers.

- **After the first live pass.** Panels carry no decorative icons (an interface-art trial was turned down; item and activity sprites, the rail and page-bar buttons stay); Insights opens on **All** (All · 30d · 7d) with a full-width General / PvP / Wealth tab strip, Highlights folded into Records on All and a folding Top Items list; density is Compact, always. The sidebar's own Supplies/Loss receipt walk is gone — the split is the model's alone, and where it cannot vouch (legacy compacted data) all costs read as Loss.
- **Pass 10 consumed by the sidebar.** Records come from `engine.getRecords` (PvP bests — best kill, longest kill streak, best K/D — join Insights › PvP › All); a session's ⋯ menu gains **Merge with previous <name>** with an Undo toast (`mergeHistorySessions` / `undoLastMerge`); Compare shows the engine's **Biggest item differences**; Insights › Wealth gains a **Coffers** card listing each coin store (fresh / stale / not read yet / unused) with **Mark unused**, which is what makes Other and the complete total available; Tools › Rules gains a **Grand Exchange** card (Book trades from offers · Sell coins are after tax); Tools › Diagnostics shows **Data health** (repaired days, newer-build fields kept, unavailable day-dimensions) and a **Recovered save** notice when the load fell back to a backup.
- **First live pass (AusVikings).** Supplies are only what the engine evidenced as used (eat / drink / cast / fire / charges / processing inputs); an unexplained decrease is a **Loss** — a Bronze dagger vanishing is an item gone, not a supply (`CostKind`, Ledger). Wealth: a location with some unpriced (untradeable) holdings keeps its priced total and says how many are left out, instead of the whole bank reading unpriced. The Grand Exchange counts as open from its top-level interface or its inventory side panel, so placing an offer settles as a market movement. The data folder is now `.runelite/gp-manager`; an existing `profit-manager` (or `smart-profit-tracker`) folder is copied in once.
- **Records; why a session ended.** Insights › General › All gains a **Records** card — best session, best GP/h (≥ 30 min), best day, best week (profile zone), play streak (current · best) and the wealth high — each opening the session it names. The sidebar computes them from retained summaries, the daily rollups and the wealth history until the engine's records model (pass 10 step 40) replaces the computation. An expanded session card now says how it closed when that is worth a word (`ended idle`, `ended by boundary`, `ended at logout`), read from the enriched `SessionSummary` (pass 10 step 39).
- **Step 39 — one session-strip summary and model cost split.** `SessionSummary` now carries category, close reason, owner provenance, auto-session marker, favourite/excluded flags, highlight and the model-owned Supplies/Other split, so Sessions can render a history strip from `getHistorySummary(id, now)` without reading a mutable `ProfitSession`. The split remains fail-closed for legacy compacted data whose attribution was not persisted; new compacted data retains it.
- **Step 40 — engine-owned all-time records.** Added `RecordsSnapshot` and `GpManagerEngine.getRecords(now)` for retained session best net/rate/length, covered day/week records, daily streaks, and wealth highs. Excluded and Free play sessions are omitted; every record carries independent coverage and unavailable status.
- **Step 41 — pairwise session comparison.** Added detached `SessionComparison` and `GpManagerEngine.compareSessions(leftId, rightId, now)` with independent availability for duration, active time, net, GP/h, gains, Supplies/Other and PvP kill/death figures. Session merging remains gated on the durable byte-for-byte undo representation.
- **Step 43 — persistence hardening.** The repository keeps the last three good saves (`sessions.backup.json.1..3`), falls back on load to the newest one that parses and says which (`SaveStatus.recoveredFrom`), and never lets a reset's rotated copies resurrect cleared data. `SaveStatus` reports bytes, worst duration, pending writes and the last failure. Fields written by a newer build survive load → save unchanged on the state, sessions and receipts (`UnknownFieldPreservation`). A 200-seed degradation fuzz restores random states stamped schema 14/18/20/22 with random optional fields removed and checks the invariants; a slow-disk test asserts saves never stall engine reads. Schema stays 23.
- **Step 44 — asserted performance budgets.** The long-history fixture is now a `PerformanceBudget` category with configurable `gp.perf.multiplier` assertions for first/repeat analytics reads, `getRecords`, `compareSessions`, saved-state creation and the serialized-size cap. The default local budgets are 400/200 ms for reads and 1 s for state creation.
- **Step 45 — GE booking mode gate.** Added `GeBookingMode.PROVENANCE_ONLY` (default) and `OBSERVED`, with synchronized engine getter/setter. Existing offer observations remain non-booking until live completed-offer acceptance explicitly selects observed mode.
- **Step 46 — measured charge follow-ups.** Added offline regressions for repeated spend/refill cycles, duplicate same-variant target isolation, pause/lifecycle and profile-restore baseline resets, Review retention, source-attribution ambiguity, and the no-double-count load-then-use path. Unresolved loads remain Review and never book automatically.
- **Step 47 — engine API freeze.** Added `docs/design/ENGINE_API.md` as the sidebar contract, exposed fail-closed `DataHealthSnapshot`/`getDataHealth(now)`, and moved alert listener callbacks outside the engine monitor. A grep-verified sweep kept `getTrackingInsights*` because the sidebar and diagnostics still consume it; no dead entry point was removed.
- **Steps 46-47 completed.** Measured charge spends are attributed to the session's activity (the weapon moves into the note) so Top activities never grow a "Toxic blowpipe" row, and `MeasuredChargeReadTracker` keeps a baseline per weapon identity so two blowpipes are tracked side by side; traces added for duplicate same-variant weapons and activity attribution. `docs/design/ENGINE_API.md` now lists merge/undo, item deltas and the same-name average, coin-store gaps/unused/derived coffers, the rollup cache, observed GE booking and `geSellSpentIsNet`, the rebuilt-days health count and the charge rules; `getTrackingInsights*` stays until a milestone read model exists.
- **Step 45 completed — Grand Exchange reconciliation (B01) behind a switch.** `GeObservedSettlement` derives what one comparable offer transition settles from the slot deltas alone (item side at its reference price, coin side from the raw `getSpent()` difference; sell-side tax booked as its own row only when the coins are taken as gross). In `GeBookingMode.OBSERVED` the engine books that as a counted MARKET trade with its provenance the moment the fill is observed (`bookObservedGeSettlement`), and every GE inventory movement — placing, collecting to inventory, a bank collection, a cancel refund — becomes an ownership-neutral transfer, so nothing is booked twice and a collection into the bank is no longer lost. The ledger now remembers the slots it saw before a logout and, after the login replay, reports the progress made while away (`takeResumedProgress`) so a fill that completed offline settles once; a slot this process never saw is only a baseline. `PROVENANCE_ONLY` (default) is unchanged and proven independent of observations by a property test. Config: `geBookingObserved` (off until the live completed-offer comparison confirms the sell-side reading) and `geSellSpentIsNet` (default on). Sequences covered offline: buy full, buy partial, sell full (net and gross coins), sell partial, cancel with coin refund, cancel with item return, collect to bank plus the later withdrawal, collect to inventory, relog with the offer open, relog after completion, an unseen slot, two offers for one item, the login replay.
- **Step 44 completed.** The profile day rollups are cached on a mutation generation (`rollupGeneration`, bumped by every invalidating mutation and by live active-time deltas), so repeat reads of Insights / Records / Overall never rebuild from sessions and idle ticks never rebuild at all; the long-history perf test now runs a 6,000-tick idle storm asserting zero rebuilds and a time budget, and Insights (30d) on the 2,000-session fixture dropped from ~48 ms to ~9 ms first / ~7 ms repeat. Legacy inflated active time is repaired on restore: a closed session whose day summaries claim more than it could have played is clamped (each day scaled by one factor, buckets and per-activity shares with it), the days are marked `activeTimeRebuilt`, their rollups report active-time coverage *partial*, the inflated persisted rollups for those sessions are dropped in favour of the rebuild, and `DataHealthSnapshot.getRebuiltDays()` counts them.
- **Steps 41–42 completed.** `mergeHistorySessions(ids, now)` merges consecutive closed sessions into one that stands for all of them (first identity, last close, receipts / runs / encounters / corrections in order, retained aggregates summed, day summaries absorbed by day key, the gap between parts counted as paused so active time is the exact sum; PK place ledgers absorbed); day rollups rebuild so Overall is unchanged; refusals name why (`TOO_FEW`, `NOT_FOUND`, `ACTIVE`, `NOT_CONSECUTIVE`, `MIXED_OWNER`); `undoLastMerge()` puts the untouched originals back until the client restarts. `compareSessions` now carries the top five item deltas (`ProfitSession.getItemNets`, unavailable when compaction lost the split) and the left session's same-activity average. Coin stores persist across restarts (additive `SavedState.coinStores`, schema stays 23), carry human titles, can be **marked unused** (`markCoinStoreUnused`) so a store the owner never has cannot hold Wealth › Other unavailable, and `getCoinStoreGaps(now)` names what is still missing; once every store is fresh or unused the engine derives the contract's `coffers` source from their sum so complete totals and shares become available.
- **Step 42 — coffer wealth observations.** Added `CoinStore` observations for NMZ coffer, Blast Furnace coffer, and servant moneybag. Fresh observations are captured as Other wealth sources for seven days, never affect Net, and disappear from later snapshots after expiry or explicit clearing.

- **Alerts come from one stream.** The sidebar subscribes once to the engine's alert feed (pass 9 step 37) and shows one toast per kind — notable drop (sprite + Copy), goal reached, wealth milestone (→ Insights › Wealth), death reclaim expired, and session auto-ended (the same one-line summary + Copy a manual end shows, noting `idle Nm`). The sidebar's own goal-reached and notable-drop polling is gone, so nothing fires twice. Tools › Alerts gains **End session after idle** (off · 5 · 10 · 15 · 20 · 30 · 60 min, `sessionIdleAutoEndMinutes`), and a session's history records why it closed (manual, boundary, idle). Idle pauses are dated at the first idle tick, not when the timeout fired.

- **Step 38 — synthetic long-history engine budget.** Added an offline fixture of 2,000 closed sessions / settlements across 400 UTC days, with 20 retained PK encounters, 40 synthetic wealth snapshots and 1,550 compacted receipts at 90-day retention. It measures retained-history totals, Insights/PvP windows, history plus 50 summaries, wealth trend and in-memory SavedState creation; the serialized fixture is 9,144,514 bytes against a 67,108,864-byte test cap. These measurements are informational: first-call/repeat timings do not reset JVM or caches, and SavedState timing excludes Gson serialization and disk I/O. This is not a RuneLite client soak or actual bank-visit measurement. This measurement-only step makes no production performance change.
- **Step 37 — engine alert stream and idle auto-end.** The engine exposes a bounded, transient alert feed and listener API for counted `LOOT` / `PK_LOOT` drops, observed goal crossings, complete wealth milestones, reclaim expiry and custom-session idle auto-end. Market proceeds cannot masquerade as loot drops. Each event has a stable in-process sequence id; listeners are isolated from accounting failures. Goal alerts fire on an observed transition to reached once per goal per owner session, including tick-driven GP/hour goals; wealth alerts require complete observed snapshots and can fire again after a decrease and upward recross. Profile replacement clears old transient alerts/baselines while keeping sequence ids monotonic. Optional idle auto-end is off by default, applies only to custom sessions and closes at the idle-start time. Additive nullable `SessionEndReason` persists without a SavedState schema bump. The engine-only Step 37 commit omitted client wiring. Owner commit `a79717b` subsequently wired the Bento alert listener/policy, added the `sessionIdleAutoEndMinutes` control, and set MANUAL/BOUNDARY end reasons at session close. Supplies Low remains removed from the sidebar. Owner commit `0d4acc5` then dated idle pauses at the measured idle start (`advanceIdleDetection` stamps the first idle tick and calls `pauseForIdle(now, idleStartedAt)`), so an idle auto-end closes the session when the player actually stopped.
- **Step 36 — engine-backed Review inbox and atomic bulk decisions.** `getReviewInbox(long now)` returns detached active-session rows with reason counts, item/value details, valid decisions and oldest age. `decideAll(ReviewDecision, Predicate<ReviewRow>, long now)` rechecks the shared `ReviewEligibility.needsOwnerDecision` predicate under the engine lock and records one additive `CorrectionRecord` batch; one undo restores the complete decision. `getAppliedDecisions(int limit)` supplies recent correction records, including the undo linkage for reverted decisions. Raw audit CSV now emits every batch member and retains the applied/undone state. SavedState remains schema 23. The sidebar must consume this snapshot for its decision badge and batch actions; Ledger's extra pricing/provenance review hints are presentation-only and are not owner decisions.
- **Step 35 — profile backup and validated restore engine APIs.** `GpManagerEngine.exportProfile()` emits a detached SavedState envelope with SavedState schema, profile/timezone identity, counts, export time and SHA-256 integrity; `inspectBackup()` verifies the envelope and runs the normal restore/migration path on an isolated engine; `restoreProfile()` installs the prepared result under the engine lock. `PersistenceCoordinator.restoreProfile()` drains the current profile's pending write and commits the import through the ordinary revision-fenced save path, reporting in-memory application separately from durable completion. The dated filename builder is pure; the existing shared exports folder is beside CSV exports. SavedState remains schema 23. Tools file selection, confirmation, disk write/collision handling and live save/restart acceptance remain B08/B05 work.
- **Step 34 — active time advances only on engine clock events.** PK encounter activity changes now use their recorded event timestamp instead of `System.currentTimeMillis()`, preventing synthetic or delayed PK history from writing days of fictional active time. The per-game-tick ingestion path advances live owners; `metrics`, history summaries, Overall totals, Insights windows and session-run reads no longer mutate session-day summaries. Out-of-order event times do not rewind the cursor, and closing a paused session preserves its pause instead of resuming it as a side effect. SavedState remains schema 23. Existing corrupted daily active-time rows cannot be reconstructed exactly because persisted sessions do not retain pause intervals; they are left unchanged and documented for owner review.
- **B08 sidebar shipped (Bento).** The Classic Swing panel (`GpManagerPanel` and its private helpers) is removed; `com.gpmanager.ui.bento` is the sidebar. Pages: **Live** (stat block with Net · Gains · Loss · GP/h and Supplies · Overall today, ribbon with bank/death/key/task/raid marks, goal line, information-only Party tile, notices in priority, Recent), **Ledger** (Gains / Market / Supplies / Losses / Neutral / Claims, Correct ▾ / Exclude / Item sheet, This session / Today scopes, review filter, search), **Sessions** (Overall today, free play status, sessions by day with consecutive same-name folding, ⋯ menu, ⟲ Again, two-pick Compare with the same-name average and a pop-out window with Copy CSV / Copy as image), **Insights** (7d / 30d / All × General / PvP / Wealth: answer tile with the previous-window comparison, net-per-session trend against the dashed average, Avg/h · Best · Deaths, top activities and items, when-you-earn heatmap, major costs, milestones; PvP K/D, per-kill and per-death medians, best kills, costliest deaths, supplies per fight; Wealth tracked total and locations) and **Tools** (Review inbox with Decide ▾ and undo, Wealth · Locate, Party sharing / HUD / even Split / card, Layout, Data & storage, Automatic boundaries, Excluded items, Price overrides, Milestones & alerts, Appearance, Settings & diagnostics, Help, Danger zone with double confirmation and recovery export).
- **Vocabulary.** *Overall* is everything, always — a derived total, never an owner you look at. *Free play* is play outside any session (the engine's durable owner relabelled) and is never counted as a session. A *session* is a start-to-end stretch you name and end; nothing lives inside it, and the engine's inner runs are no longer surfaced. *Activity* is what the detector sees. See [SIDEBAR_BENTO.md §0](docs/design/SIDEBAR_BENTO.md).
- **Automatic boundaries** (Sidebar config `boundarySlayer` / `boundaryRaid` / `boundaryBank`, each Off · Mark · New session, default Off). A slayer stretch opens at the first Slayer XP with a task active and closes when the count reaches zero; raids open on entry and close on exit (CoX / ToB by varbit, ToA by region); a bank visit is the close. *Mark* paints the Live ribbon; *New session* starts a session named after the boundary, tags it `auto`, never ends a hand-started session, and for a bank visit restarts whatever is live.
- **Supplies vs Loss everywhere** the sidebar shows costs: Supplies are consumables (food, potions, runes, ammo, charges); Loss is deaths, tax, fees and drops. The sidebar derives the split from receipts (`CostSplit`) with the same rule Step 22 moved into the model; switching to the model's split for compacted sessions is a follow-up.
- **PvP layout policy** `pvpLayoutMode` (Auto / Always / Never) drives both the sidebar's Live layout (Kills · Deaths · K/D, per kill / per death / risk, skull and Protect Item sprites, "In danger" notice) and a HUD+ line under the hero; Never keeps the General layout for Wilderness PvM.
- **Agility is an activity.** Agility XP in a known course region titles that course (Wilderness Agility Course, the rooftops, Brimhaven, Ape Atoll, Werewolf, Penguin, Prifddinas, Shayzien, the Pyramid, Barbarian, Gnome); elsewhere plain Agility titles too. Agility and ticket dispensers keep the course title; rewards book as ordinary gains.
- **Session categories and the Sessions card layout (§13.2–13.3).** Start a session asks for a name and a category (PvM · Bossing · Raids · Slayer · Skilling · PvP · Trading · Other) pre-selected from what the detector sees; PvP starts in PK mode; automatic boundaries write Slayer / Raids. The Sessions page is a current card (● Current session / ● Free play; Duration · Total time · Profit/Loss; End / Start) over Previous sessions as cards with the category icon, name, category · activity, when · duration · key stat, net and a ★ toggle; `All sessions ›` shows every day grouped with folds; `⚖ Compare` picks two. The ⋯ menu gains Category…. Labels and buttons fall back to the logical face for glyphs the UI font lacks, and the net card carries no standing tint — it flashes green or red only when net changes.
- **Session strips; PK trips are sessions.** Previous sessions are one-line strips (`Vorkath  Yesterday 17:53 ····· +52.6k ›`) that expand on click into the full card with the footer and actions; folds are strips too. Insights › PvP's **Top PK trips** are the finished PvP sessions in the window (day · duration · kills · deaths · place, best net first, opening in the Ledger — each also counted in General and Top activities); places moved to **Where you fight**.
- **Decide all is atomic.** Tools › Review › Decide all ▾ now calls the engine's batch (pass 9 step 36): one correction record, one Undo last reverts the whole batch; rows that cannot take the decision are reported, not forced.
- **For the PKer, and the last of the review ideas.** Insights › PvP's card is the **PvP total** — loot minus deaths and fight supplies, the PvP share of the General total — with a ▲/▼ vs the prior window; K/D lives in the combat card and **Costliest deaths** joins Best kills and Top PK trips. Live › PvP shows **risk** in the context row beside the skull. The hero caption (`TOTAL`) now leads the figure on one line, and Live adds `+38k since bank` after it when the session has banked. Session cards carry the rate on their category line. A notable drop raises a toast with the item's sprite and Copy; a new local day raises `Yesterday +412k · 4 sessions → Insights`. Insights › General's comparison is a chip beside the figure. Tools › Storage gains **Open export folder**.
- **No dead space; captions.** Page stacks put a gap only between visible cards (`GapStack`), so the goal sits right under the net card and notices right under the goal — or under the net card when no goal is set. The hero's label moved from the header to a small caption under the figure (**TOTAL**, K/D, WEALTH); the header now names the owner (Free play / the session) and, in PvP, `PvP · skulled`. The Ledger's Costs tabs read `Loss · N | Supplies · N` and open on the tab that has something to show. Ending a session raises a toast with its one-line summary and a **Copy** action.
- **Review recommendations built.** Live's page bar shows `Today +102k` (Overall today) at its right edge and the pause button reads Pause / Resume; the Ledger fuses Supplies and Loss into one **Costs** card with two tabs; a session's ⋯ menu gains **Compare with…**, **Compare with previous <name>** and **Copy summary** (a one-line Discord-ready summary); Tools › Appearance gains **Exact figures** (`sidebarExactFigures`: −6,544 instead of −6.5k on the net cards) and Layout a **Reset to default**; **Esc** steps back from any sub-page. **Backup profile** writes the engine's validated envelope (`exportProfile`, dated, collision-safe) and **Restore backup…** runs the engine's dry run before restoring, with a recovery export first; Clear history and every Danger-zone action write that recovery export too.
- **Hero cards carry figures only.** No sentences under the hero anywhere (what they said now sits in the card's tooltip); Live › General's strip is the whole equation — Gains · Supplies · Loss · GP/h; Live › PvP's net card carries Gains · Loss · Per kill (GP/h stays on General); the combat statistics live on Insights › PvP (Loot · Loss · Per kill in the K/D card, Kills · Deaths · K/D · Streak beneath); Insights › General's strip is Tracked · Avg/h · Best · Deaths. Icons stay where they carry meaning — item sprites, activity sprites, the rail, section and group headers, notices — and are gone from hero strips, stat cells and the session footers.
- **Compact cards; supplies-low notice removed.** Live › General is one card (net, Supplies · Overall today, then Gains · Loss · GP/h as a strip); Live › PvP's net card carries the money — Loot · Loss · Per kill (loss per death rides on the tooltip) — with one Kills · Deaths · K/D · Streak card beneath (Insights › PvP mirrors it); Insights' answer cards carry Performance (Avg GP/h · Best run · Deaths with deltas), Combat (Kills · Deaths · Streak) and the Wealth groups inside; Risk & profit is a 3×2 card. Strip cells size to their text and drop icons before they would clip. The **Supplies running low** notice, its inventory read and `alertSuppliesLow` are gone — other plugins already do that job.
- **Goal kinds and the search palette (§13.5).** The session goal is now a profile `GoalDefinition` of kind Net / GP·h / Kills scored by the engine (`getGoalProgress`), edited from the goal card; net goals stay mirrored to the session target for the HUD; the card reads `◎ 15 kills · 12 / 15` or `◎ 200k · 62% · ETA 18m`. Sessions gains a `⌕` that opens the search palette over items in the Ledger's scope, sessions and every Tools row.
- **Tools rebuilt as a settings app (§13.4).** A search field over every row, six grouped cards with boxed icons, and sub-pages (`‹ Tools › Storage` with scroll memory): Review (cards, Decide ▾, **Decide all ▾**, Applied + Undo last), Alerts (notable drop, goal reached, supplies low, wealth milestone), Layout (Live tiles on/off **and ▲▼ order**, persisted in the profile), Party, Storage (+ **Backup profile** as dated JSON, Copy data folder path), Rules (filter, include, clear, **add an override by item search**), Appearance (accent swatches, density, PvP layout, net graph, reduced motion), Diagnostics (+ About). New config keys `alertGoalReached`, `alertSuppliesLow`; a goal-reached toast fires once per session.
- **Pass-8 read models wired into the sidebar.** Session categories are the engine's persisted `SessionCategory` (Bossing / Raids / Slayer / Other added); the picker, ⋯ › Category…, bank restarts and ⟲ Again write it, and a pre-pass-8 tag still reads back. The Sessions key stat is the engine's `SessionHighlight` (PvP kills · kills · gathered item · drops) with the receipts rule as the legacy fallback; the current card's Total time and Overall today come from `getOverallTotals` / `getOverallToday` (rollups first, never receipts) where their coverage vouches. Insights › PvP reads the rollup-backed `PkWindow` (kills, deaths, best streak, medians, fight cost, best kills, previous-window K/D) and `getPkPlaceSummaries` for Top PK trips with time in place; Live › PvP's "vs previous session" chip uses `getPreviousPkSessionSummary`. Insights › Wealth reads `getWealthBreakdown` (Bank · Equipped · GE · Other, remainder flagged), `getWealthTrend` (gaps hold the last capture, never drop to zero) and the new 7-day anchor.
- Sidebar config section: accent, density (Comfortable default), PvP layout, net graph, and the three boundary modes. `panelPreview` now paints the real Bento sidebar to PNGs under `build/bento-preview`.
- Removed dead code found during the merge: `EncounterRevenueGate` (never produced data), `BankOpenEvidence`, `ConsumptionIntentEvidence`, `EvidenceKind`, and the Cursor-era handoff / probe scratch under `docs/`.

- The release-facing schema documentation originally identified configuration schema 31 and SavedState schema 18; Pass 8 advances SavedState through schema 23 and updates the README chronology. Historical schema-29 loot-setting notes remain labelled as historical.
- **Step 28 — first-class session categories.** Sessions now persist explicit category overrides, migrate the sidebar's recognized category tags without removing them, and keep PKing tied to PK mode. Daily Insights rollups retain per-category net and active time at SavedState schema 19; category averages reuse the existing availability, exclusion, median, and time-weighted-rate contract, failing closed when legacy coverage is incomplete.
- **Step 29 — durable session highlights and encounter goals.** The engine accepts already-deduplicated NPC encounter observations separately from receipts and persists bounded per-source counts, best streak, last-seen time, and explicitly available loot-value totals. Session highlights follow PK → observed source kills → counted gathered quantity → counted gained receipts and expose each raw stat with its own coverage; legacy encounter coverage stays unavailable. Session and today goal progress reads durable session/day aggregates. PvM daily kill totals stay exact when source identities overflow the bounded list, while the top source is marked partial; timezone rebases and the 400-day item-history cap invalidate only the affected evidence rather than reporting false zeros or truncated lifetime counts. SavedState schema 20. RewardPresentationModel exposes an observer bridge for the client ingestion call site; GpManagerPlugin wiring and live acceptance remain open.
- **Step 30 — Insights window performance and highlights.** SavedState schema 21 adds 24 profile-local hourly net/active-time buckets while retaining legacy four-hour values; old rollups keep their four-hour data and expose hourly coverage as unavailable rather than spreading totals. Current and previous windows now expose best/longest sessions, biggest measured drop, time-weighted average GP/hour, best local hour, top-activity positive-net share/session counts, and capped item quantities with truncation flags. Extrema and rates fail closed when retained evidence, filtering, rollup provenance, or timezone coverage cannot support them; excluded sessions affect average GP/hour only. Step 30 initially refreshed a live session's daily active-time slice at query time; Step 34 moves advancement to engine tick and timestamped lifecycle paths so the model reads stay side-effect-free. The model APIs are ready for B08 consumption; no UI or plugin wiring changed.
- **Step 31 — PvP read models and location time.** SavedState schema 22 adds a bounded per-session location ledger (200 merged intervals plus per-label totals) while preserving legacy unknown location history as unavailable. `getPkPlaceSummaries(days, now)` groups correction-aware encounter counts/net/supplies and active time by observed place; labelled and unlabelled time remain distinct, and trimmed-source, partial-PVP, legacy-zone and filter states fail closed. `getPkWindow(days, now)` reuses the canonical current/previous `InsightsWindowSnapshot` windows and adds best kills, costliest deaths, best streak, loot, attached-supply cost and per-event medians only when retained-source evidence is complete. Live `PkMetrics` now preserves a signed win/loss streak and exact medians including half-GP values. The existing location-update call site supplies labels; no UI, plugin or config wiring changed.
- **Step 32 — Wealth breakdown and trend read models.** `WealthAnchor.SEVEN_DAYS`, `getWealthBreakdown(now)`, `getWealthTrend(days, now)`, and `getWealthChangeFacts(now)` expose profile-local daily wealth facts, source-grouped Bank/Equipped/Grand Exchange/Other values, 7/30-day snapshot deltas, and bank share without affecting accounting Net. Missing or unpriced sources stay unavailable; trend gaps are explicit and never forward-filled. Full source totals may remain available alongside a flagged top-100 remainder, while identity-dependent comparisons remain unavailable. No SavedState schema, plugin, config, UI, or rendering changed. Current captures do not emit coffers, so Other and derived complete totals/shares remain unavailable until a coffer source is added.
- **Step 33 — Overall totals read models.** SavedState schema 23 persists `SessionOwnerKind` (`FREE_PLAY`, `NAMED_SESSION`, `UNKNOWN`) and an independently covered named-session-start daily count; legacy owners remain unknown rather than inferred from names, modes, or tags. `getOverallTotals(now)` and `getOverallToday(now)` reconcile each local `(date, zone)` once, preferring rollups over overlapping retained summaries, and include paused Free play owners without treating them as named sessions. Step 33 initially refreshed active time across profile-local midnight on query; Step 34 moves that accrual to engine tick and timestamped lifecycle paths so reads stay pure. The immutable snapshot reports retained-history Net, active time, named starts, tracked days, first date/zone, and per-value coverage. It reads day summaries/rollups only, never receipt lists; clock-only accrual patches affected cached day rows and cached all-time totals, while a newly current date is added once. Out-of-order day queries rebuild only the cached day projection; a 400-day summary eviction invalidates it. Accounting, correction, owner, restore, filter, and history mutations still invalidate the derived cache. These are retained day-key aggregates. No UI or plugin wiring changed.
- Step 27 hardens PvM gravestone reclaim: only a canonical inventory/equipment snapshot captured at death can authorize a measured ownership-neutral wipe; a missing snapshot fails closed. Split losses consume matching quantities from a 100-tick whitelist, while later explicit market/transfer/consumption/drop evidence supersedes stale death context. Only measured outstanding items return as uncounted transfers, even without a reclaim click; source-backed loot keeps precedence and unmatched gains/costs remain ordinary. Coin loss overlapping an armed, delayed death-wipe batch remains an ordinary measured cost and is not labelled a fee; a separate observed reclaim outflow can be booked as a fee. Reclaim status exposes awaiting/armed/item-count/age, Grave/Gravestone Loot/Check/Reclaim re-arms for at least 200 ticks, and a 1,500-tick informational expiry waits for any changed snapshot to settle, including a final return seen before its dirty callback. No UI files or SavedState schema changed.
- Step 26 adds model-only follow-ups: Stop→Resume reuses the last run only when it has zero active duration, no assigned receipt and no retained run aggregate; a nullable, presentation-only location label is persisted on PK kill/death encounters; and the engine exposes case-insensitive same-activity session count, median net and time-weighted GP/hour from retained daily summaries. Excluded sessions/ids are skipped and missing, filtered, truncated or rollup-fallback coverage fails closed. No UI files changed; SavedState remains schema 18.
- Step 22 moves Supplies-versus-Loss classification into the model read paths: effective consumable/action costs are Supplies; death losses, reclaim/death fees and GE tax are Loss; TRADE costs remain in the non-supply cost total as Market. Session, run, PK and daily Insights expose the split; session/run/item/day compaction retains it, while pre-split compacted data reports the split unavailable instead of guessing. SavedState schema 14 also wires profile goals and tile layouts through `GpManagerEngine` save/restore. No sidebar files changed.
- Step 23 adds a 30/90/180/365-day or Forever detail-retention setting (90-day default), compaction of eligible closed sessions on current-schema restore and after UTC day changes, an explicit `compactOlderThan(days, now)` engine API, retention status and approximate saved-JSON size read models. Compaction keeps session metadata and accounting/run/item aggregates; old-schema profiles wait for the first UTC day change. The default history cap is 2,000 sessions and existing explicit limits are preserved. SavedState schema 15 stores the UTC sweep date and migration deferral marker; no UI controls were added.
- Step 24 adds profile-local `DailyRollup` read models for accounting, Supplies/Loss, session/run starts, activity net/time, capped top-20 gains/costs, local four-hour buckets and PK encounter summaries. `TrackingDaySummary` updates incrementally as receipts settle/correct/undo and as session clocks advance; profile rollups are saved in SavedState schema 17, restored as fallback for days no longer represented by retained sessions, and reconciled with contributing-session ids without double-counting. If a rebuilt slice lacks a previously contributing session, it remains a partial subset; explicit history deletion/reset drops the cached aggregate so deleted data cannot return. Legacy-zone window checks account for the maximum two-day civil-date label difference. Active accounting filters redact flow-derived values in the rollup APIs, while session trends use the exact eligible projection; raw saved rollups remain available for recalculation. PK transaction summaries now survive correction, undo and receipt compaction. Existing `getTrackingInsights` is deprecated in favor of the coverage-aware profile-zone APIs. No Swing/UI files changed; sidebar consumption remains B08.
- Step 25 adds profile-local, read-only Wealth history in SavedState schema 18. The main bank is eligible only after a BANK container-change event while its interface is visible, then two adjacent matching content reads; bank holdings are revalued on container events rather than every GameTick, and one snapshot is captured per open visit. Each snapshot includes inventory, worn equipment, readable rune pouch, complete seeded GE offers, and a visible collection box. Each item uses the receipt price selector and wealth remains outside Net. History keeps every visit for 30 days, one per UTC day through day 365, then one per UTC week, capped at 2,048 snapshots and 100 named holdings per location plus an exposed remainder bucket. Comparisons and top movers fail closed when either snapshot has a remainder because item-level Market movement cannot be established. Engine APIs expose a timeline, latest snapshot age, last-bank/today/30-day Earned–Market–Unexplained comparisons, and aggregate Earned plus measured item Market movers. Today and 30-day anchors choose the latest retained read at or before local midnight or the 30-day cutoff; an older baseline can extend the compared interval, so consumers should display the capture time and treat a missing baseline as unavailable. Counted Net in partial days is apportioned uniformly within the existing local four-hour rollup buckets; missing overlapping-session rollups, unavailable coverage and filters fail closed. The wealth milestone threshold is a stored future-notification hook only. No sidebar/UI files changed.
- Sidebar read models added without Swing/UI changes: `PartySummary` persists a final plain-data snapshot on `ProfitSession`; `CorrectionRecord` retains applied history with durable undo linkage (and own-drop recoveries are undoable); `WealthChangeBreakdown` derives Earned / Market / Unexplained from comparable wealth snapshots, with Unexplained reconciling observed wealth change after counted Net and Market repricing; profile `SavedState` stores goal definitions and per-page tile layouts at schema 13. `GoalProgress` is a pure derived value and is recalculated from scope totals rather than persisted stale. `PartyProfitMessage` adds backward-compatible activity and optional notable-drop fields. Runtime party-end capture and sidebar rendering remain B06/B08 work.
- Step 18 adds immutable Wealth snapshots for active GE sell offers (remaining quantity at each offer price) and collection-box contents read only while the widget is visible and all required slots are readable (cached RuneLite market price, coins at face value). The login baseline fails closed until the full 3-slot F2P or 8-slot members offer array is observed; incomplete or unpriced locations do not show a partial total. These observations are Wealth-only and never affect Net.
- Step 18 added local price-capture timestamps beside `ItemPriceSource` on newly valued accounting flows; splits, reclaim/zone partitions, measured charge costs, corrections and daily Insights retain them. Legacy/unpriced rows keep timestamp zero. SavedState schema 12 added the optional field without fabricating historical times; Step 21 advances the current schema to 13 for profile goals and tile layouts. Source/time display is pending with B08.
- Insights carries a configurable notable-drop threshold and an explicit unavailable milestone status. `EncounterRevenueGate` has no production encounter/count/time data, so no kill-count or event-time GP/hr milestone rows are generated until durable evidence exists.
- B11 run history now persists session-owned run boundaries and exposes correction-aware statements and two-run comparisons. Explicit start/stop, stop/resume, pause/recovery, delayed-claim provenance, legacy whole-session migration and compaction are represented in model read models; automatic bank/death boundaries remain unspecified, the sidebar UI remains in B08, and Left on ground stays unavailable without durable stack evidence.
- Comparable GE offer progress can now be attached to a same-item/direction MARKET receipt as presentation-only provenance (slot, traded-quantity delta, raw `getSpent()` delta and observed state). It does not create flows or change valuation, transaction type, counted state or tax; the existing menu heuristic and `GeSellTaxBooking` remain the accounting path pending live proceeds verification.
- Accounting eligibility now flows through one correction-aware projection for session totals, detail and transaction CSVs, activities, PK projections and comparisons. Filtered reports carry scope/status rows; unsupported raw audit counts are named raw, and legacy compacted detail that cannot be recalculated reports unavailable. These projections change reporting only, not stored transactions or their valuation.
- HUD+ title precedence is evidence-graded: fresh interaction target, then recent XP-confirmed process, then place/session state, then blank title with the state gem. Fresh targets remain visible with a PAUSED/AFK/REC prefix; recent process titles last five game ticks and outrank Banking/neutral-zone/Waiting, even while the character-idle gem is shown. Prayer title promotion additionally requires explicit Use-on-altar evidence followed by Prayer XP; ordinary bury/scatter/cast, eating, drinking, decanting, menu clicks, chat and receipts never title by themselves. Presentation leaves accounting unchanged.
- Explicit eating stays **Ate** even when stale or delayed Firemaking evidence arrives; process XP can upgrade a generic use card only when every lost item matches that skill's spend family.
- Same-NPC kill counts update during the fight (`Goblin` → `Goblin ×2` → `Goblin ×3`) instead of waiting for idle or a tray transition.
- HUD+ drops the generic **Tracking** title. **Waiting** is reserved for first use, generic live tracking has an accent gem without redundant text, and actual character **Idle** has its own muted gem. A brief signed net-change chip appears beside the session total.
- Long HUD+ / InfoBox activity names now use a presentation-only abbreviation table and width-aware fallback; Detailed InfoBox duplicate detection uses the full semantic title before compaction, while hover and Ledger retain the full source name.
- HUD+ auto-size measures the painted compact title ("GE Clerk"), not the full semantic name, so the shell no longer stretches to fit "Grand Exchange Clerk" while drawing the short form.
- HUD names drop any trailing parenthetical qualifier the game appends ("Hofuthand (weapons and armor)" reads "Hofuthand"; likewise Banker/shop disambiguators) — a generic rule, not a table row, so it covers names never seen before. Raid mode qualifiers are the exception and are shortened instead (ToB (HM), ToA (Expert)). When a name still exceeds the header width the HUD now trims whole trailing words ("Elite Void…") before cutting inside a word. Ledger, Insights and hover keep the full name.
- The static key/chest catalogue now sits in the model layer, so Insights and other consumers share it without introducing a model-to-engine dependency; accounting and claim behavior are unchanged.
- Evidence-backed action wording (B10): settled rows carry a presentation-only `ActionKind` from the matched menu verb plus flow shape, so HUD+, receipts, Live and Ledger read Drank / Ate / Decanted / Mixed / Cooked / Burnt / Supplies used instead of blanket Processing/Mixed. Stale or coalesced evidence falls back to "Supplies used"; accounting type, valuation and quantities are unchanged.
- One action-to-surface policy: rune and ammunition spends, plus explicitly evidenced routine ammunition recovery, are Ledger-only — no HUD+ tray card, HUD+ capsule stack, latest-change notice, floating drop or Live timeline row — while still counting toward net and all aggregates. Generic ammo gains keep their normal source-based presentation.
- Rune pouch joins the ownership baseline (new **Include rune pouch** setting, on by default): filling the pouch nets to zero and casting from it is a visible cost. Looting bag / seed box stay on menu evidence because the client only syncs those containers when the bag is opened.
- Ground Items hidden gains now stay hidden in Ledger, Live item changes, and filterable Insights. Display filtering never hides costs; accounting exclusions remain the separate user-controlled way to remove them.
- HUD+ header no longer repeats the tray verb; after first use its generic live title slot is empty. "Waiting" remains for first use, and actual character Idle remains a separate muted state. A fresh NPC target outranks any process title.
- Paired process cards use the skill's own verb (Cooked / Burnt / Fletched / Crafted…); "Mixed" is reserved for Herblore and unknown processes. Tray tags drop the trailing colon everywhere so HUD+ and the trip beacon agree.
- Storage items added to the ownership-neutral container catalogue: bolt pouch, tackle box, reagent pouch, huntsman's kit, meat and fur pouches.
- Gauntlet and Corrupted Gauntlet instances are ownership-neutral zones: entry losses are recorded and only matching restored quantities are excluded on exit, so lobby rewards still count even beside the gear restore.
- GE sell tax exempt list now carries the full wiki exemption list (bonds, low-level ammo and runes, low-level food, energy potions, common teleports, basic tools) instead of a bond-only stub.
- PvM death lifecycle: an unsafe PvM death books the gear wipe as an ownership-neutral transfer. A retrieval interaction only arms after a witnessed local PvM death; matched loot events stay counted even when their item ids overlap the death wipe, and hard bank transfers cannot become reclaim fees. Observed carried-coin losses at a service are labelled with the published fee; bank / Death's Coffer payments and expired gravestones are not inferred.
- PvM death eligibility now uses the pinned Wilderness/PvP-world varbits as well as short-lived player-combat and NPC context: safe-area PvM deaths remain ownership-neutral after the target context clears; in PvP-capable areas an NPC context is still required when no player-combat evidence exists.
- Pre-pot device Fill/Empty now uses menu-evidence ownership transfers; its bank-only syncing container is intentionally not added to inventory snapshots.
- Credible ordinary/loot key pickups create non-counted audit rows; bank/transfer key movements do not create kill provenance. Claim receipts require chest-container removal plus residual settled INV gains after source-backed loot matching and final classification. Deferred key tokens never book as gain or cost, nested crates/caskets stay out until opened, and confirmed local-death key losses never book manifest value.
- A sourced key/chest catalogue now distinguishes tradeable keys (retain GE opportunity cost) from untradeable deferred claims. Brimstone, Enhanced crystal, Moon, and all 25 Shades keys value at zero while held and bypass high-alchemy/manual-price fallback; claim contents remain the only revenue.
- Ordinary untradeable key receipts create non-counted Ledger-only claim rows. A measured key loss plus settled INV contents closes the row; a chest click only disambiguates the chest and cannot stand in for key-loss evidence. Click-plus-gain while the key remains held stays a generic gain and leaves the audit row pending. Below-minimum rewards still close the row when key loss is measured, without bypassing the transaction threshold. Named chest activity reaches Insights and uses `Chest loot`; Crystal key use retains its GE cost. Death loss closes pending ordinary-key rows without a cost.
- Chest titles use their named object (`Brimstone chest`); a bare `Chest` falls back to `Opening chest`. Contents receipts use `Chest loot`, keeping the header and tray tag distinct.
- HUD+ now separates tracked gameplay activity from title-worthy object interaction: traversal verbs and route objects such as Wilderness ditch, ladders, portals and boats stay untitled, while resources, stations, chest rewards and supported minigame entries remain eligible.
- Local death evidence (Deathkeep contents, Protect Item and skull state) now annotates only the settled negative death row; it cannot change accounting. HUD+ adds a configurable PvP-only Risk estimate from measured inventory/equipment GE stacks. Unknown prices, unsupported skull modes, and carried containers whose contents are hidden show `?` instead of an understated value.
- Charge accounting measures Check-to-Check component decreases for Toxic Blowpipe and supported seas/swamp Tridents. A fresh Check target supplies identity only; same-target reads are required, and target changes seed a new baseline. Scales/darts remain independent across dart-type changes; Uncharge/Unload resets the baseline; costs require complete GE-priced components (coins use fixed face value). Check clicks alone, animations and probability estimates never book spend; other families and charge/uncharge dialog reads remain unsupported pending B04/B05.
- Ambiguous charge-load component losses now move into non-counted Ledger Review rows with their original flows. A same-target measured Check can confirm exact Blowpipe scales or Trident recipe quantities and resolve them as neutral transfers; partial/unmeasured components remain in Review. The default confirmation window is 10 minutes, and unresolved components block overlapping charge-use costs. No Check, stale window, changed target, or multiple matching candidate rows never becomes an automatic cost. Item-id-only source attribution within one settle still needs live validation.
- B11 design inputs now require a two-run compare view (loot, supplies, net, GP/hour with deltas) and an informational per-run Left on ground value from Ground Loot items never picked up. These remain design-only, not implemented.
- Non-bank transfers keep their own note and explanation (minigame wipe, neutral zone, death) instead of being relabelled "Bank transfer … a bank or deposit interface was active".
- Full-scope domain research recorded internally (potions, food, ammo, runes, GE tax, death fees, containers, RuneLite API surface, comparable plugins, Plugin Hub rules) with a ranked gap list; GE tax and death-fee models were verified against the wiki.
- Tests: 792 in 105 suites on the Step 4 clean local gate, with zero failures, errors or skips. Review-era test files folded into subject suites; one exact duplicate and one subset duplicate removed; three one-test files merged.
- September 13 review: fixed partial PK pickup suppression, stale pending-chest profit attribution, partial chest receipts, chat-only/duplicate reclaim costs and coinless GE Collect tax. Follow-up review also hardened death/reclaim matching, action evidence, and late process signals during NPC combat.
- Corrected crystal shard/dust enhanced-weapon-seed proxy ratios (1,500/15,000 per seed); existing stored valuations remain unchanged.
- Routed Wealth locate updates through Swing's EDT; wrapped first-use guidance, removed redundant rate units and stacked enlarged sidebar metrics.
- Added eight settlement/callback/valuation regressions, refreshed the full gate/previews and consolidated current roadmap/backlog evidence. Specialized integrations and live acceptance remain pending.
- Removed observed Gradle execution-time project-access warnings and included bootstrap scripts in source bundles.

- Moved net profit to the right and rate to the left in HUD+ and the compact legacy HUD at the user's request.
- Fixed RuneLite rejecting plugin startup because a parsed-value helper was mistakenly declared as an unannotated configuration method.
- Fixed normal gameplay lifecycle checks repeatedly clearing the inventory baseline, and preserved a trusted pre-action baseline when the first gather auto-starts General.
- Fixed retained bank contents being mistaken for an open bank, restoring potato, wheat, logs, food, and other post-bank gains/costs.
- Split display-only loot visibility from explicit accounting exclusions and added a universal per-stack minimum displayed loot value.
- Kept unpriced quantities reviewable by default, classified their direction from quantity, and preserved the explicit ignore option.
- Added exact object/NPC/player HUD context with Skilling, PvM, PvP, Raid, Trading, and Misc sidebar categories.
- Reconnected Custom HUD and Insights settings, repaired Review filtering, clarified RuneLite price caching/provenance, and pinned the default RuneLite dependency to 1.12.38.
- Standardized fields, fractional font sizes, scrollbars, ledger rows, and the narrow/status header; removed two confirmed-dead UI helpers and migrated deprecated RuneLite IDs.
- Compact responsive sidebar, content-sized empty History/activity views, native item rows, and standard RuneLite info-box fonts.
- Separate Stop control and opt-in auto-resume for Pause; Stop survives reload and blocks gameplay resume.
- XP refresh filtering, paused PK encounter guards and recovery-time exclusion.
- Last-good save backup, durable summarized session totals, safer repeated exports and spreadsheet-safe text.
- Current verification gate and offline UI preview harness. This build is not a public release or release candidate.
- Renamed the Java package to `com.gpmanager` and the `ProfitManager*` / `ProfitTrackerEngine` classes to `GpManager*`, matching the public GP Manager name.
- Kept the `profitmanager` config group, `.runelite/profit-manager` data directory, `smartprofittracker` / `smart-profit-tracker` legacy bridges, `profit_manager_icon.png` resource, and party message types unchanged so existing settings and history still load.

# GP Manager changelog

## Unreleased — party profit tracker

- Added an opt-in RuneLite Party integration that shares replaceable current-session GP snapshots and shows combined party revenue, costs, profit, GP/h, and member rows.
- Kept party snapshots in memory only; changing or leaving a party clears the shared view, and no item-level or PK data is transmitted.

## Unreleased — v0.5 accuracy tranche

- Added item-flow pricing provenance for manual overrides, RuneLite market prices, unpriced values, and legacy flows.
- Added an opt-in high-alchemy fallback for items without a market price.
- Added a compact session-health summary for uncertain transactions and unknown-price flows.
- Added a recent-ledger review filter for uncertain transactions and unpriced/legacy flows.
- Added multi-select review actions and one-click selection of all visible rows needing review.
- Extended the review workflow to archived-session ledgers with historical batch corrections.
- Added correction reasons to transaction state, correction history, tooltips, and both CSV exports so review decisions remain auditable after restart.
- Added compact current-session and archived-session correction-history panels showing the latest transitions and review notes.
- Added undo audit records and merged removals into the review timeline so accidental deletions are visible after restart.
- Added `undo_count` to diagnostic exports for session-level audit summaries.
- Added confirmed restoration for the latest eligible undo while retaining its removal and recovery state in the timeline.
- Bounded persisted undo snapshots to 32 entries and made restoration honor the session transaction cap.
- Added legacy-session and malformed-undo recovery fixtures so pre-audit saves remain loadable and safe.
- Added a dedicated audit CSV containing correction, undo, and restoration events for archival review.
- Added pricing summaries to transaction tooltips and `price_source` / `pricing_summary` fields to detailed CSV exports.
- Replaced the live-drop high-value `ALOT` cap with readable K/M/B abbreviations.
- Kept the live-drop implementation passive; native client script/widget injection remains outside the project safety boundary.

## v0.4.4.5 — GP Manager identity and independent info-box typography

- Renamed the public plugin, sidebar tooltip, project artifact, dialogs, and active documentation to **GP Manager**.
- Retained the established `com.profitmanager` package, `profitmanager` config group, `.runelite/profit-manager` data directory, and icon path to protect existing settings and history.
- Split the information-box typography control into **Header text size** and **Subtext size**.
- Migrated the new header scale from each user's previous unified info-box text scale, avoiding an unexpected visual change on upgrade.
- Added schema-v5 migration coverage, independent typography tests, branding checks, and `v0445Check`.

## v0.4.4.4 — Complete project rename and full release ZIP

- Renamed the root project to `profit-manager`.
- Renamed Java packages to `com.profitmanager`.
- Renamed plugin, configuration, panel, overlay, migration, and test classes to `ProfitManager*`.
- Renamed the icon resource to `profit_manager_icon.png`.
- Renamed the config group to `profitmanager`.
- Renamed the local persistence directory to `.runelite/profit-manager`.
- Added one-time compatibility migration from the legacy config group and data directory.
- Updated the Gradle bootstrap to 9.6.1.
- Consolidated historical patch documentation into this changelog.
- Added the complete cumulative project ZIP and `v0444Check`.

## v0.4.4.3 — Profit Manager public identity

- Renamed all user-facing plugin surfaces from Smart Profit Tracker to Profit Manager.
- Updated RuneLite metadata, sidebar tooltip, dialog titles, documentation, and logs.

## v0.4.4.2 — Info-box text scale

- Added 75–160% scaling for the info-box title and rows through one shared setting.
- Preserved compact spacing and live-drop scale independence.
- Added migration and scale regression coverage.

## v0.4.4.1 — Cumulative compatibility repair

- Restored dense info-box presentation where earlier patch files were incompletely merged.
- Restored activity-only heading and unified GP-drop scale files.
- Replaced brittle source checks with semantic compatibility checks.

## v0.4.4 — Stabilization and configuration cleanup

- Consolidated settings into predictable sections.
- Preserved legacy keys through one-time configuration migration.
- Sanitized malformed enum and numeric values.
- Stopped closed or unselected panel tabs from rebuilding heavy history views every tick.

## v0.4.3.9 — Dense info box and unified live-drop scale

- Reduced info-box padding and row spacing.
- Hid irrelevant zero-value rows in Detailed mode.
- Added one shared scale for live-drop text, icons, gaps, overlap, spacing, and movement distance.
- Simplified the normal settings surface while retaining advanced overrides.

## v0.4.3.8 — Classic info box and drop presets

- Added compact RuneLite/Slayer-style info-box presentation.
- Made the active activity the default heading without an `Activity:` prefix.
- Added the dedicated **Icon + GP value only** live-drop preset.
- Increased default live-drop hang time from 2.5 to 2.9 seconds.

## v0.4.3.7 — Info-box and personal-record polish

- Stabilized activity labels so Auto mode did not unnecessarily fall back to General.
- Added info-box views, themes, colours, widths, and visible-row controls.
- Rebuilt Personal Records as compact full-width rows without nested scrolling.

## v0.4.3.6 — Live-drop animation presets

- Added Static, Fade, Classic Rise, Rise + Fade, Slide In, Pop In, and Gentle Float.
- Added direction, distance, easing, icon motion, and merge reanimation controls.
- Kept Static as the default.

## v0.4.3.5 — Dynamic live-drop settings

- Added smart individual-item rows and condensed direction summaries.
- Added matching item icons, icon strips, item-name and quantity modes, and combine policies.
- Added content, icon, and appearance configuration sections.

## v0.4.3.4 — GP-drop and UI polish

- Added RuneScape/Expanded-XP-style text options.
- Added optional coin and item sprites through RuneLite ItemManager.
- Kept GP values transaction-ledger driven rather than XP derived.

## v0.4.3.3 — Readability and history simplification

- Increased panel text sizes.
- Simplified Insights.
- Redesigned History around list-first progressive disclosure.
- Removed pagination and unnecessary always-visible filters.

## v0.4.3.2 — Full-width panel recovery

- Rebuilt the sidebar around the full RuneLite panel footprint.
- Added viewport-width tracking and a single slim scrollbar.
- Removed overlapping, duplicated, and nested full-page scrolling behaviour.

## v0.4.3.1 — Pause ownership hotfix

- Separated manual, idle, and lifecycle pause ownership.
- Prevented logout or world-hop handling from incorrectly resuming a manually paused session.

## v0.4.3 — Responsive live UX

- Added ledger-driven live GP drops.
- Added automatic activity segments and idle-aware timing.
- Added responsive full-width Overview, Insights, and History views.
- Added dynamic profit and loss presentation.

## v0.4.2 — UI hierarchy polish

- Improved spacing, section priority, and card organization.
- Reduced visual clutter while preserving core statistics.

## v0.4.1 — Compact RuneLite/OSRS interface

- Added RuneLite item sprites and compact item rows.
- Reworked the panel toward an OSRS-inspired, RuneLite-native layout.

## v0.4.0 — Session intelligence and modern panel

- Added recent and lifetime averages, records, trends, activity breakdowns, and comparisons.
- Added Overview, Insights, and History navigation.
- Added session notes, favourites, tags, inclusion controls, and richer exports.

## v0.3.3 — Activity history and comparison

- Added historical activity summaries and two-session comparisons.
- Added corrections, undo, filters, and session management.

## v0.3.2 — PK metrics and session intelligence

- Added profit per kill, loss per death, net per encounter, streaks, and records.
- Added PK-aware session summaries and history metrics.

## v0.3.1 — Offline simulations

- Added separate general and PK simulation source sets.
- Excluded simulator code from the normal plugin JAR.
- Added simulation safety and packaging checks.

## v0.3 — Accuracy lock and PK ledger

- Added classification confidence, explanations, corrections, audit history, and undo.
- Added passive player-loot and death encounter accounting.
- Added supply, fee, kill, death, streak, and encounter metrics.

## v0.2.1 — Passive safety lock

- Added the passive-only safety policy and build audit.
- Prohibited automation, input injection, networking, reflection, subprocesses, and live combat guidance.

## Phase 1.2 — Accuracy preview

- Added improved transaction classification, item rules, pricing overrides, and initial insights.

## Phase 1.1 — Accounting foundation patch

- Added baseline protection, transfer exclusion, stabilization, supplies, NPC loot, pause-safe rates, JSON persistence, and CSV export.

## Phase 1 — Initial plugin foundation

- Added RuneLite project scaffolding, inventory and equipment snapshots, revenue/cost/profit accounting, panel UI, session lifecycle, and local persistence.
