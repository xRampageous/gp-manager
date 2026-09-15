# Sidebar redesign — "Bento" (B08)

Status: **approved by the owner 2026-09-14**; implementation not started. Supersedes the sidebar section of [PRE_1_0_ARCHITECTURE.md](PRE_1_0_ARCHITECTURE.md) (B08). Renders live in [sidebar/](sidebar/); `sidebar/spec-sheet.html` is the source of the main sheet and renders at true panel width in any browser.

The sidebar is presentation only. It consumes read models and never changes type, valuation or counted state; every accounting rule in `README.md` "Tracking principles" applies unchanged.

---

## 0. Vocabulary (decided 2026-09-14, supersedes "runs" and "trips" below where they still appear in renders)

- **Overall** — everything, always. Not an owner you look at: a derived total (free play + every session), shown as *Overall today* under Live's figure and on the Sessions page, and as the totals in Insights. Nothing is double-booked; each receipt lives in exactly one session or in free play.
- **Free play** — play outside any session. It is the engine's durable owner (`Overall` / legacy `General`) relabelled; it is what Live shows when nothing is running.
- **Session** — a start-to-end stretch the player names (Vorkath, Wildy slayer, CoX) and ends; the engine's custom session. One level only: nothing lives inside a session. Sessions carry tags, notes, favourite, exclusion from averages, Compare, same-name average. Consecutive same-name sessions in a day **fold** on the Sessions page (`Vorkath ×8 · 3h 20m · +2.1M ▸`) — presentation, not accounting.
- **Activity** — what the detector says you are doing (Vorkath, Wilderness Agility Course, Agility). Names the session by default; never a time box.
- **Automatic boundaries** (Tools › Automatic boundaries, each **Off · Mark · New session**, default Off): *Slayer task* (stretch opens at the first Slayer XP with a task active, closes when the count reaches zero; assignment is a point), *Raid* (CoX / ToB by varbit, ToA by region; entry to exit), *Bank visit* (the close). *Mark* paints the Live ribbon; *New session* starts a session named after the boundary (`Slayer · Abyssal demons`, `Chambers of Xeric`) and ends it, or for a bank visit ends the live session and starts one with the same name. Automatic sessions carry the `auto` tag and a ⚙; an automatic boundary never ends a session the player started by hand. The engine's inner "runs" (`startRun`) are no longer surfaced.

## 1. Principles

1. **Glanceable, then deep.** Rows are icon · name · qty · change; everything else appears on expand or in a sheet.
2. **Scoped by default.** Live and Ledger open on the current session; older data is a deliberate step ("Older ›"), never a scroll.
3. **Aggregate before render; window what renders.** Item tiles, day/week/month groups, precomputed daily rollups; only visible rows are built. A 40k-receipt profile draws like a 40-receipt one.
4. **State, not chrome.** Situational tiles/notices exist only while their condition is true (neutral zone, reclaim pending, key held, unmeasured charges, in combat, party).
5. **Decisions are chores, not signals.** Pending reviews live in Tools › Review with a dot on the tab; Live never nags.
6. **One clock.** Active time is shown once, in the stat-block header, with the HUD+ gem states (live / idle / paused / waiting).
7. **Honest states.** Paused, idle, prices-pending (≈), profile switching, compacted history and "unexplained" wealth are all first-class, never hidden.
8. **Colour is never the only signal.** Signs, tags and text carry meaning; accent is selectable.
9. **Nothing destructive without two confirmations.** End custom confirms once.

---

## 2. Shell

| Row | Content |
| --- | --- |
| 1 · Rail | `Live · Ledger · Sessions · Insights · Tools` — five equal tabs, icon over 9 pt label. Amber dot on **Tools** while any decision is pending. |
| 2 · Page bar | **Live:** `❚❚ Pause tracking` (→ `▶ Resume`, green) · `Session ▾` (`Start ▾` in free play). **Others:** title · scope ▾ · view/search icons (Ledger adds `↗` pop-out; Sessions `★`; Insights period segment). |
| 3 · Context | `General | PvP` (+ `Wealth` on Insights) segment; activity name / Wilderness level on the right; small `⌕` search (also `/`). |

- **Width:** `PluginPanel(false)` → 242 px owned; custom **6 px overlay scrollbar** (rounded thumb, no arrows, hairline when idle, expands on hover) → ~236 px content. 225 px remains the minimum fixture. Horizontal scroll never.
- **Palette:** bg `#0a0a0b`, surface `#131315`, alt `#1a1a1d`, border `#26262b`, text `#f3f1ee`, muted `#a09c96`, dim `#605c58`, accent coral `#ff7a59` (alternates: mint `#35d6bd`, amber `#f2b134`, blue `#7cb4ff`), positive `#7bd88f`, negative `#ff6a6a`, warn `#f7c66b`, info `#7cb4ff`, quiet `#8a86ff`, PvP `#ff4d6d`, wealth tint `#122030`.
- **Type:** Segoe UI / Dialog; body 12, secondary 11, micro labels 9 uppercase tracked, hero 26 semibold, tabular numerals. Density is Compact, always (the Comfortable option was removed after the first live pass).
- **Numbers:** compact everywhere (`1.2k`, `1.76M`), exact on hover and in expanded state; signs always present.
- **Keyboard:** `/` search, `p` pause, `s` split, `g` goal, arrows + Enter expand, Esc closes sheets/palette.
- **Memory:** scroll position and expanded state per page for the session; tile layout persisted.
- **Search palette** (`⌕` or `/`): one overlay for items in scope (→ Item sheet), sessions and runs (→ Runs), actions with their keys (pause `p`, split `s`, goal `g`, end run, start custom, export, copy as image), and plugin settings by name (→ RuneLite config). Arrow keys + Enter; Esc closes. Results grouped, max 6 per group.
- **Toasts:** bottom of the page, above nothing (they never cover the page bar); at most 2 stacked; undo toasts carry a countdown; goal / milestone / party toasts dismiss on tap.
- **Empty states:** every list has a one-line sentence in place of a blank ("No receipts yet this run", "No sessions match", "Bank not seen yet — open it once").
- **Accessibility:** visible focus ring (info colour, 2 px) on every interactive element; full keyboard operation; accessible names on icon-only buttons; large-font settings reflow rather than clip.
- **Acceptance fixtures** (from PRE_1_0 §B08): 225 px content minimum and 236 px owned width; heights 300 / 450 / 600; OS scaling 100 / 125 / 150 / 200 %; fixed-classic, resizable-classic and modern layouts; no horizontal scrollbar, no clipped currency signs, every control reachable, billions / negative / unknown values, empty and huge histories, missing sprites, keyboard-only.

---

## 3. Live

### General
1. **Stat block** — header `Net · Free play` (or `Net · <session name>`, `⚙` when automatic) + `◔ h:mm:ss` clock. Second micro row carries **Supplies** and **Overall today** (`+412k · 3 sessions`). Hero net with a green **tick chip** (`▲ 1.2k`) on each settled change (fades ~3 s). Micro row: **Gains · Loss · GP/h** (GP/h carries `▲12%` vs your average for the same activity when ≥3 sessions exist). Net graph **off by default**; `Settings › Live › Net graph: Sparkline` draws the session sparkline behind the figure.
2. **Ribbon** — one bar for the session's time; bank visits, deaths, keys, task and raid boundaries as marks with tooltips. (The run line and run segments are retired with the vocabulary in §0.)
3. **Goal line** — `◎ 200k ▬▬▬▬▭ 62% · ~18m` with milestone tick. Tap → Goal editor: kind (Net / GP·h / Kills / Item count), value, scope (session / today / run), milestone toast %, on-reach (toast · HUD flash · keep going).
4. **Party** (only while sharing) — collapsed one line: avatars · count · combined net · rate; expanded: members with net, rate, share bar; stale members dimmed with age. Information only (§8).
5. **Notices** (one line each, only while true): Neutral zone · Reclaim pending (`›` details) · Key held · Charges (`Blowpipe 1,240 → 803 · −12.9k`; unmeasured says *"Check it to book charges"*).
6. **Encounter** row — `Vorkath · 41 · 58.7k/kill · ×12 streak` while an encounter exists.
7. **Recent** — last 5 receipts, `ledger ›`. Quiet (Ledger-only) rows never appear here.

**Notice priority** (top to bottom, only while true): switching profile / logged out · prices pending · neutral zone · in combat (PvP) · reclaim pending · key held · charges · party notable drop. Never more than 5; the rest collapse into "+N more".

**Session ▾ menu** (reads `Start ▾` in free play): Rename session · Goal… · ─ · Undo last change · Undo last correction · Restore last undo · ─ · Start a session… · End session → back to free play (confirms once) · ─ · Plugin settings. "Start a session" asks for a name (default: the detected activity).

**Goal editor**: item-count goals pick the item through the search palette; kills goals require an encounter; all goals are per profile and persist across sessions until removed.

States: **paused** (tag, ❚❚ clock, dimmed), **idle** (`◔ 30:12 · idle 3m`, dimmed clock), **prices pending** (`≈` values, amber notice, re-priced silently), **waiting** (first use / after profile switch), **switching profile / logged out**.

### PvP (mode)
Offered automatically on entering a PvP-capable area (Wilderness / PvP world varbits) unless `Appearance › PvP mode` is `never`; pinnable. Stat block: `PvP net · <owner>`; rows **Kills · Deaths · Loss** and **K/D (·streak) · Per kill · Per death**. Ribbon in PvP colour: deaths as red marks, loot-key pickups as amber ticks. Goal may be a kills goal. Tiles: **At risk** (value that would be lost, kept count, game **skull** and **Protect Item** sprites via `SpriteManager`, keep/lose summary) · **Loot keys** by victim with age and manifest value ("counted when claimed · lost on death closes the row") · **In combat** notice (opponent, timer) · **Fights** feed (kills with key state, deaths with what was kept).

---

## 4. Ledger

- Page bar: `Ledger · [This session ▾] · ▤ by item / ⏱ timeline · ⌕ · ↗`. Scopes: this run · this session · today · older ›. Sort: value / time / name. **Select** mode for bulk corrections.
- Context line: `General | PvP`; right side `38 receipts · 9 items`.
- **Sections** (collapsible, count + subtotal): **Gains** · **Supplies** (food, potions, measured charges, quiet runes/ammo) · **Losses** (death fees, GE tax, dropped/destroyed) · **Neutral** (transfers, decants, pouch fills; collapsed by default) · **Claims** (deferred keys at 0, loot keys pending) · **Market** (GE offer ledger, B01: fills at real prices, pending offers, tax, buys as cost).
- Rows: sprite · name · qty (dim, inside the name span so the name wins) · change. Tags inline: `quiet`, `claim`, `review`, `shared`.
- **Expand**: how received (pickups · activity), price · source · capture time, counted · type · confidence, receipts list, `Correct ▾` (automatic / gain / cost / transfer / ignore / **Split with…**; scope: this receipt · all N · selected), `Exclude`, `Hide`. Right-click = same actions.
- PvP mode sections: Kills · Deaths · Supplies · Claims.
- Footer: `1.92M − 171k = +1.75M · 3 hidden by filter · show`.
- **Undo toast** with countdown; undo stack depth 5.
- Filter-hidden footer `show` reveals the hidden rows inline (dimmed) for that view only; the Ground Items filter itself is configured in RuneLite (link from the Item sheet's `Hide`).
- Timeline view: time order, run boundaries as separators, section colours as tags.

### Item sheet (from any item anywhere)
All-time total, count/doses used, avg price, sessions; price now · source · capture time · **override** (set/clear) · fallback rule; where it goes (drank / decanted / dropped / bought); by activity; `Set price… · Exclude · Hide`; wiki link; pop-out.

---

## 5. Sessions

- Page bar `Sessions · ⚖ Compare · ★` (§13.3). **Current card**: `● CURRENT SESSION` (accent border, `⚙ Auto` chip when a boundary started it) or `● FREE PLAY · ALWAYS ON`; category icon · name · `Bossing · <activity>` (free play: `Overall today +102k`) · GP/h; joined grid **Duration · Total time · Profit/Loss** (Total time is Overall's all-time active play — every session and free play, including free play paused behind the running session); `End session` / `＋ Start a session`.
- **Start a session** asks for a name and a **category** (PvM · Bossing · Raids · Slayer · Skilling · PvP · Trading · Other, §13.2), pre-selected from the danger facts / detector / name; PvP starts the session in PK mode. The category is written as a tag (`bossing`, `raids`, `slayer`…) and read back from the tag or the engine's category. Automatic boundaries write Slayer / Raids; a bank restart keeps the category; `⟲ Again` reuses the last one with that name.
- **Previous sessions**: the most recent eight as **strips** — `[sprite] Vorkath  Yesterday 17:53 ······ +52.6k ›` (★ when a favourite) — that **expand on click** into the full card: `category · activity · rate`, when, the `duration │ key stat │ net` footer and `Ledger · ⟲ Again · ⋯`; fold strips read `Vorkath  ×2 · 1h 40m · Today 16:53 ······ +108k ▸` (PvP: kills; otherwise the most-gathered item `~1.6k logs`, else `N drops`; deaths; `summary only`). `All sessions ›` switches to every day grouped with the day total, older spans collapsed, and consecutive same-name sessions **folded** (`Vorkath ×2 · +108k ▸`) that open to the individual cards. Click a card for `Ledger · ⟲ Again · ⋯`.
- **Compare**: `⚖ Compare` (or picking any card while picking) turns the icons into pick marks; the footer reads `Pick two sessions to compare` → `Pick one more` → `Two picked · Compare ›`; `Cancel` leaves picking.
- ⋯: rename, category, tags, notes, favourite, include/exclude in averages, delete (asks twice).
- Older: collapsed lines per month / year with totals. Compacted sessions show `summary only`; Ledger disabled with that reason.
- **Compare** (sub-view, `‹` back, `↗` pop-out): two sessions side by side — Net, GP/h, Loot, Supplies, Loss, Kills, Deaths, Duration, Left on ground with deltas; **same-activity average**. Pop-out window adds Copy CSV and Copy as image.

---

## 6. Insights

- Page bar `Insights · [7d | 30d | All]`; context `General | PvP | Wealth`.
- **General:** answer tile (`Last 30 days you made +8.9M · 21h 36m · best hour…` + `▲12% vs the 30 days before`), **net per session** trend vs dashed average (excluded sessions not drawn), `Avg/h · Best run · Deaths`, top activities, top items, **By boss** (KC, avg loot/kill, supplies/kill with trend arrow, deaths, GP/h), when-you-earn heatmap (weekday × 4 h), major costs (incl. deaths by activity), milestones (drop · kill count · session net · GP/h at that moment). Footer: scope, sessions, exclusions, "prices at capture". Export view as CSV. "With party" filter.
- **PvP:** period answer, K/D with ratio, per-kill / per-death with medians, best kills, where you die (and where it costs most), supplies per fight.
- **Wealth:** total (bank snapshot age in header), change split **Earned · Market · (Unexplained)**, wealth over time (one point per bank visit), where it sits (bank / worn+inv / GE offers + collection box / pouch, keys, coffers / cash), top movers with reason. Never part of Net. Bank read only while open.

Definitions — *Earned* = counted net between snapshots; *Market* = Σ held quantity × price change for items present in both snapshots; *Unexplained* = remainder, shown only when non-zero, dimmed.

---

## 7. Tools

Built as the settings app of §13.4. A **search field** at the top filters every row on every page (group and sub-page names count as words); the overview is six cards — **Attention** (Review inbox › with the count and oldest age, Wealth › with the changes since last bank · today · 7 · 30 days), **Session** (Slayer task · Raid · Bank visit pickers, Alerts ›, Layout ›, Party ›), **Data** (Storage ›, Rules ›), **Preferences** (Appearance ›, Diagnostics ›), **Help** (How tracking works, Shortcuts, Report an issue — copies the diagnostics report) and **Danger zone** (Delete session…, Reset tracking…, Factory reset… — each asks twice and offers a recovery export first). Sub-pages share the page bar as `‹ Tools › Storage` and remember their scroll position.

- **Review**: every pending receipt as a card (sprite, why, `Decide ▾`), **`Decide all ▾`** (gain · cost · transfer · ignore, confirmed with the count; the engine's atomic batch — one correction record, one `Undo last` reverts it all; rows that cannot take the decision are counted, not forced), Applied with `Undo last`.
- **Alerts**: notable drop over ▾, Goal reached (toast once per session when the net goal is first met, `alertGoalReached`), Wealth milestone every ▾ (`wealthMilestoneGp`), End session after idle ▾ (off · 5–60 min, `sessionIdleAutoEndMinutes` → `engine.setSessionIdleAutoEnd`). Every toast — notable drop, goal reached, wealth milestone, reclaim expired, session auto-ended — comes from the engine's `AlertEvent` stream through one listener; the sidebar keeps no alert state of its own, and `AlertPolicy` is pushed from config on each refresh. Sessions record a `SessionEndReason` (manual · boundary · idle); boundary-driven ends that restart the same name stay silent, a conclusion (raid exit, task done, idle) announces the one-line summary with Copy.
- **Layout**: the optional Live tiles — Goal · Party · Notices (with the encounter row) · Recent — each with ▲ ▼ and a switch; order and hidden ids persist in the profile's `TileLayout`; the net card, stat strip and ribbon stay put.
- **Party**: share figures, party in HUD, members with their figures, Split ▾ and Copy party card.
- **Storage**: profile · size · receipts · compacted, Receipts kept ▾, next compaction, Compact older now, Export session CSV / History…, **Backup profile** (write `engine.exportProfile().toJson()` to the repository export folder using the pure dated/collision-safe filename builder), **Restore from backup** (choose a JSON file, show `engine.inspectBackup()` counts / identity / migrations / replacement scope and refusal before confirmation, then call `PersistenceCoordinator.restoreProfile()` and show whether the ordinary save committed), Copy data folder path, Copy as image, Restore last undo, Clear history…, Archive free play….
- **Rules**: a filter box, Excluded items (include), Price overrides (clear), **＋ Add an override…** by item search.
- **Appearance**: accent swatches, PvP layout ▾, Net graph, Exact figures, Reduced motion. **Diagnostics**: Plugin settings ›, Debug trace, Diagnostics report ›, Shortcuts ›, About (version · schema · RuneLite).

## 8. Party

Kept: 5 s member snapshots (revenue, costs, net, GP/h, rolling GP/h, tx count) with generation/revision; 15 s staleness; HUD option.

- **Live tile:** information only — members, net, rate, stale state. No controls, no split.
- **Tools › Party:** share figures on/off (off: you still see others, they see "not sharing"); what you share (net · rate · activity — `activityName` added to the message); notable-drop sharing threshold (optional `notableDrop` field; shown as a notice on others' Live); party in HUD; **auto custom session when a party forms** (ask / always / never) with a generated name; save member summary at end; member list (hide stale, remove left — keeps final); stale timeout; **Split** (even-split table: owes / is owed; basis counted net · gains only · loot only; **Copy split** card); link to RuneLite's party manager; copy party card.
- **Runs:** party session tile shows avatars, your net, combined figure and per-member finals **saved at party end** (`PartySummary` persisted on the session), and the split line. Insights "with party" filter.

---

## 9. Scale and retention

Scoped defaults; item tiles; day → week → month → year collapse; daily rollups for Insights; windowed rows with sprite cache and repaint throttling; receipts kept in full for a configurable window (default 90 days) then **compacted** to session/run summaries that still compare; nothing deleted silently; profile size visible.

---

## 10. Read models required

Existing: session/transaction/eligibility projections, corrections, encounters, PK ledger, loot-key and deferred-claim provenance, death lifecycle, neutral zones, measured charges, party tracker, Wealth locate, GE offer observations (B01 step 14), run statements and comparisons (B11 step 17), wealth snapshots and price-capture times (step 18).
New (small, presentation-side stores): tile layout, goals, applied-corrections log (if not already durable), `PartySummary` on session, Earned/Market/Unexplained derivation from wealth snapshots, milestones once durable encounter evidence exists.

---

## 11. Build order

Built on the `sidebar/rework` branch; config key `sidebarStyle` (`BENTO` default on the branch, `CLASSIC` fallback) until step 7. Engine passes from the other agent merge into `codex/ci-baseline` and are rebased under the sidebar branch at each milestone.

1. Design doc (this) + component kit `ui/bento/`: tokens, SlimScrollBar, Rail, PageBar, ModeSwitch, StatBlock, Ribbon, GoalLine, Notice, Tile, Section, Row/ItemRow (sprites), KV, Sparkline, Heatmap, Toast, Sheet, PopoutWindow — each painted, fixture-tested at 225/236 px, 300 px height, 13 pt.
2. Shell + Live (General) → first in-client screenshot.
3. Ledger + Item sheet.
4. Sessions + Compare + pop-out.
5. Insights (General, PvP, Wealth).
6. Tools + states + Live PvP + Party.
7. Remove Classic; delete `GpManagerPanel`; docs, CHANGELOG, live matrix.

Every step: clean `releaseCheck`, local commit, real-client screenshot; no push until told.

---

## 12. Renders

| File | Shows |
| --- | --- |
| [01-live-ledger-runs.png](sidebar/01-live-ledger-runs.png) | Live General, Live PvP, Ledger, Runs |
| [02-compare-insights-tools-states.png](sidebar/02-compare-insights-tools-states.png) | Compare, Insights, Tools, first-use, toasts |
| [03-goal-search-session-menu.png](sidebar/03-goal-search-session-menu.png) | Goal line and editor, search palette, Session ▾ menu |
| [04-insights-detail-ledger-correct.png](sidebar/04-insights-detail-ledger-correct.png) | Insights trend/top items, Ledger Correct ▾ and footer |
| [05-states-item-sheet-tools.png](sidebar/05-states-item-sheet-tools.png) | Paused / idle / prices-pending, Item sheet, Review history, exclusions, overrides, appearance |
| [06-wealth-market-auto-runs.png](sidebar/06-wealth-market-auto-runs.png) | Insights › Wealth, Tools wealth/alerts/boundaries/profiles, Ledger Market, auto-opened runs |
| [07-party.png](sidebar/07-party.png) | Party tile, Tools › Party, party session in Runs (split later moved to Tools) |
| [spec-sheet.html](sidebar/spec-sheet.html) | Source of 01/02; open in a browser to inspect at 1:1 |

Renders show illustrative values, not gameplay evidence. Where a render and this text disagree, the text wins (e.g. the split table is in Tools, not on Live; the Wealth split is labelled Earned / Market).

---

## 13. Visual pass 2 (decided 2026-09-14) — the card language

Seven owner-supplied mockups (mint accent, rounded cards, icon-led stat grids, answer-first tiles, delta chips) replace the coral renders above as the visual reference. Everything copies except sizing (RuneLite gives 242 px, the mocks are ~410) and the points marked *honest* below. Where §0–§12 and this section disagree, this section wins.

### 13.1 Theme and kit
- Accent **Mint** by default (Coral / Amber / Blue stay as options). Surfaces: card `#0f1113` on page `#0a0b0c`, border `#1e2226`, text `#e8ecef`, muted `#9aa3ab`, dim `#5c646b`, positive mint `#35d6a3`, negative `#ff6b6b`, warn amber `#f2b134`, info blue `#5aa9ff`.
- **Card**: 12 px radius, 1 px border, 10 px padding. **Section header**: 16 px icon + title, optional right text / `View all ›` / `›` chevron; the whole header is a button when it navigates.
- **Stat grid**: cells of icon · label · value · delta chip; 3-up, then 2-up; a cell is a card in itself (`Loot value 13.35M ▲18%`). **Delta chip**: `▲ +12%` / `▼ −23%` pill, mint or red, with a tooltip naming the comparison ("vs previous 30 days" / "vs your average").
- **Answer tile**: title, hero figure, delta line, one-line sub, sparkline right-aligned behind the figure.
- **Row**: icon (item sprite / activity icon) · name + subtitle · right value · optional chevron. **Activity icons** map activities to item sprites (Vorkath → Vorkath's head, Woodcutting → logs, Slayer → slayer helmet, Runecraft → runes, Raids → CoX/ToB/ToA keys, PvP → game skull sprite, Wealth → coins) with no fallback: where there is no sprite there is no icon. **Decorative glyphs are gone from every panel** (owner's call after the first live session — only real game art reads well beside it; an interface-art trial on section headers was turned down too). Item and activity sprites, the rail, page-bar buttons and control marks (★, ›) remain.
- **Toggle** switch control replaces on/off ghost buttons; **picker chip** (`90 days ▾`) for choices; **progress bar** (goal, wealth breakdown).
- Empty states: icon in a ring, one line, one dim line ("No losses recorded · Items lost on death will appear here").

### 13.2 Session categories
Start a session gets a category picker: **PvM · Bossing · Raids · Slayer · Skilling · PvP · Trading · Other**, pre-selected from the detector (Vorkath → Bossing, a course → Skilling, Wilderness → PvP), and set by automatic boundaries (slayer → Slayer, raid → Raids). The category is stored as `SessionCategory` where one exists (PvP → `PKING` and `SessionMode.PK`, Skilling, Trading, PvM) and as a session tag otherwise (`bossing`, `raids`, `slayer`). It drives the card icon, the Sessions subtitle (`Vorkath · Bossing · Yesterday 20:45`), the PvP layout, Insights' top-activities grouping and the same-name average.

### 13.3 Pages
- **Live · General**: `Net · General` + `● Live 01:12:36`; hero net with the tick chip; stat grid Gains · Loss · GP/h (icons; GP/h carries `▲ +18%` vs your same-activity average when ≥3 sessions); goal bar `◎ 200k · 62% · ETA 2h 18m`; notice cards with chevron (**Notable drop** from the notable-drop threshold → Ledger; reclaim / neutral zone / key held as today); **Encounter row** `Vorkath · 41 · 58.7k/kill · ×12 streak` from the reward presentation's live totals; **Recent (5)** with `All drops →` to the Ledger. Supplies · Overall today sit under the figure, and **Gains · Loss · GP/h are a strip inside the same card** (one compact card; the PvP layout does the same with Kills · Deaths · K/D · Streak, and Insights' answer cards carry Performance / Combat / Wealth groups the same way).
- **Live · PvP**: `Net PvP` hero with `▲ +12%` vs previous session, sub "loot value minus risked value"; grid Kills · Deaths · K/D · Streak; cards Loot value / Loss value with deltas vs your PvP average; Per kill / Per death; **Recent PvP events (5)**: `PKed <name>` / `Died to <name>` · place · age · value, chevron → Ledger. *Honest*: no opponent combat level (not recorded).
- **Sessions**: header `Sessions` + `⚖ Compare` + `★`; **current card** (● Current session, category icon, name, category · activity, net; Duration · Total time (Overall all-time active) · Profit/Loss; `End session`); free play uses the same card without the session border. **Previous sessions** list (`All sessions →` opens the grouped history): icon, name, category · activity, day + time, duration, key stat by category (kills / drops / items gathered / kills for PvP), net, ★ toggle. Fold rows keep `×N`. Footer `Pick two sessions to compare ›`.
- **Ledger**: as built, plus row chevrons to the item sheet, the Total card (figure; equation on hover). **Supplies and Loss are one Costs card** with two tabs (`Supplies · N | Loss · N`), each with its own empty state; the header carries the combined figure.
- **Insights · General**: answer tile (`Last 30 days · +8.9M · ▲ +42% vs previous · 21h 36m tracked · Total profit across all activities`); **Performance** card (Avg GP/h ▲, Best session ▲, Deaths ▼ — deltas vs previous window); **Top activities** with share bars; **Top items** (`View all ›` → Ledger Today/All by item); **Highlights**: Biggest drop, Best hour (the four-hour bucket with the highest net in the window), Longest session.
- **Insights · PvP**: `PvP performance` hero K/D with ▲ vs previous, "53 kills and 17 deaths"; **Combat summary** Kills · Deaths · Streak · Per kill · Per death; **Best kills** (name · place · value); **Top PK trips** — the finished PvP *sessions* in the window, best net first (day · duration · kills · deaths · commonest place), each also counted in the General total and Top activities, opening in the Ledger; **Where you fight** lists places (time in place · kills · deaths · net) from the engine's place summaries; **Risk & profit**: Loot value · Loss value · Net · Avg fight cost (supplies per fight).
- **Insights · Wealth**: `Tracked wealth` hero with age dot, `▲ +352M · +19.7%` vs 30 days; **Wealth breakdown** bars Bank · Equipped · GE · Other with shares; **Where it sits** rows; **Insights** lines (wealth up X (Y%) vs 30 days · steady growth over 7 days) from the timeline. *Honest*: no advice text ("consider diversifying").
- **Tools** — see 13.4.

### 13.4 Tools rework
Tools becomes a settings app, not a scroll of tiles: a **search field** at the top filters every row across groups; groups are cards with icon headers; rows use toggles, picker chips and `›` sub-pages; the rail's amber dot and a `Review · N` badge stay.

- **Attention**: Review inbox (count, oldest) `›` — sub-page with the full list, Decide ▾ per row, **Decide all ▾** (gain / cost / transfer / ignore) and applied history with undo; Wealth summary (since last bank · today · 30 days) `›` Insights › Wealth.
- **Session**: Automatic boundaries (Slayer · Raid · Bank, each Off / Mark / New session); Alerts `›` (notable drop ≥, goal reached → toast · HUD, supplies low on/off, wealth milestone every N, **end session after N min idle**); Layout `›` (Live tiles on/off and order ▲▼); Party `›` (share figures, party in HUD, members, Split with Copy split, Copy card).
- **Data**: Storage `›` (profile size, receipts, compacted; Receipts kept ▾; next compaction; Compact older now; Export session / history CSV; **Backup profile** writes the profile envelope to the export folder with a dated collision-safe name; **Restore from backup** inspects and previews a same-profile file before confirmation, then commits via the persistence coordinator; Copy data folder path; Restore last undo; Clear history…; Archive free play…); Rules `›` (Excluded items with search and *include*, Price overrides with *clear*, add an override by item search).
- **Preferences**: Appearance `›` (accent swatches, density, PvP layout, net graph, reduced motion); Diagnostics `›` (plugin settings, debug trace, copy report, shortcuts, about: version · schema · RuneLite build).
- **Help**: how tracking works, changelog, report an issue (copies the diagnostics report).
- **Danger zone**: delete session…, reset tracking…, factory reset… — each confirms twice and offers a recovery export first.
- Sub-pages share the shell's page bar (`‹ Tools › Storage`) and scroll memory.

### 13.5 Also in this pass
- Goal editor (built): kind **Net / GP·h / Kills** with the value, a `Session goal` dialog from the goal card or Live › Session ▾ › Goal…. The profile holds one session-scope `GoalDefinition` scored by the engine's `getGoalProgress` (kills = PvM encounters + PKs); a net goal is mirrored to the session's legacy target so the HUD keeps it; a legacy target with no definition still reads as a net goal. The card shows `◎ 15 kills · 12 / 15`, `◎ 1.5M/h · 1.2M/h`, or `◎ 200k · 62% · ETA 18m`, and "reached"; the Goal reached toast fires once per session. Item-count goals wait for the item picker.
- Global search palette (built): `⌕` on Sessions opens a page with `‹` and a field over **items in the Ledger's scope** (→ item sheet), **sessions** by name · category · activity · day (→ that session's Ledger) and **every Tools row** (→ Tools with the sub-page open); every word of the query must match; Enter opens the first hit, Esc goes back. Ledger's `⌕` stays the in-place row filter and Tools' field the settings search.
- Keyboard (built): every card that navigates is focusable; **Esc goes back** from the item sheet, Compare, the palette and Tools sub-pages; segmented switches take ← →.
- Follow-ups recorded, not built: opponent combat level on encounters (engine), item-count goals, HUD+ visual parity with the card language.
