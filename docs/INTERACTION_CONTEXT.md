# Immediate interaction display context

Implemented in the shared tracking display without changing reward identities, accounting activity or transaction values.

## Behavior

### HUD+ title precedence (B10)

HUD+ resolves title evidence independently of transaction classification and value:

1. **Fresh interaction target** — NPC, object or player, including the existing interaction grace. This always wins over a process title, including while PAUSED, AFK or REC; the state token prefixes the target (for example, `PAUSED · Goblin`). Once the target expires, a non-live status returns to its status-only label.
2. **Process skill** — a specific skill title is eligible only after a real skill-XP increase observed in `onStatChanged`; it is displayed only when no rank-1 target is present. A menu click, chat line, inventory loss or receipt can provide evidence for a receipt or accounting classification, but never promotes this title. Prayer is stricter: only an explicit Use-on-altar menu pair followed by positive Prayer XP, with no fresh target, can promote Prayer. Plain bury/scatter, cast/chat evidence and XP without that altar pair remain receipt-only. A process title lasts five client game ticks after its last qualifying XP event, then yields.
3. **Place/session state** — Banking, a neutral zone, then Waiting for a session before first use.
4. **No specific activity** — leave the title blank and use the generic live/idle gem state.

The ladder is ordered: a recent XP title outranks place state, including Banking. Character Idle changes the gem and tray hold independently; it does not cancel a process title before the five-tick evidence hold expires. Without a fresh target, PAUSED, AFK and REC remain status-only and do not expose process/place titles.

Consumption is always a receipt, never a title. In particular, eating or drinking during an encounter cannot replace its target title; burying during an NPC encounter leaves the NPC title in place while the tray can say Buried. An open loss-only process-spend intent may label settled losses as that process only when every loss belongs to its spend family; mixed or incompatible losses stay generic. These are presentation-resolution rules and do not change a row's accounting type, valuation, counted state or totals.

### HUD name compaction

`HudNames` applies the exact activity-name abbreviations and generic rewrites for HUD+ and InfoBox labels. It compacts the target/process name before `Session ·`, status, kill-count and Streak labels are composed. Renderers then use their actual available pixel width and `FontMetrics`; a long Fossil Island Wyvern name keeps its specific variant when it fits. Detailed/Custom InfoBox duplicate-row detection uses the full semantic title before compaction, so `GE Clerk` does not gain a second `Activity: Grand Exchange Clerk` row. Prefixes, batch counts and streak suffixes remain separate from the name.

This is display-only: full interaction names remain on transactions, Ledger, Insights, Sessions, exports and explanations. Auto-resize measures the full semantic header, and a compacted/truncated HUD+ title retains the full title for hover. Transfer notes and receipt wording do not feed the header; only the target/process ladder can title it.

- A local-player NPC interaction updates the current display name from the actual NPC immediately. It works with PK tracking disabled. Normal tick observation is a fallback and refreshes transformed NPC names.
- NPC context survives a short interruption for five seconds, then expires. Repeated empty ticks do not extend the grace period. Another target replaces it immediately.
- Supported intentional object actions (including Chop, Mine, Fish, Harvest, Open and Search) resolve the object definition rather than trusting menu display text. They show `name (selected)` initially. A subsequent new animation corroborates activity and removes the selected marker. Unfulfilled selection expires after five seconds; a different intentional action cancels it. Bank chest selections are excluded.
- These object hints are presentation evidence, not proof of rewards or consumption. An animation is not enough to book profit or a kill. Arbitrary activity/source coverage is not implied by the supported object-action list.
- The existing automatic activity detection option controls this feature. Manual Pause remains paused. Stopped/absent sessions and identity holds do not expose transient context. Session ownership and logout/profile lifecycle prevent prior target context leaking into a different owner.
- HUD+, legacy HUD, Infobox details and the Live source heading use the shared context. HUD+ preserves same-source reward counts. If a new target differs from retained loot, a `From <source>` caption identifies that loot instead of relabeling it as the new target's reward.
- HUD / Live / Infobox never show Insights category buckets (Skilling / PvM / Raids / PKing / Trading) as the current-activity title; without a specific skill, NPC, object, or reward source the HUD+ title slot stays empty and its accent gem carries generic Live. First use alone keeps the **Waiting** title. Insights keeps the category buckets and click-to-expand sources.
- The NPC display grace is independent of kill-streak inactivity and loot animation timers. Target changes do not increment kills, refresh kill-streak time, change profit or manufacture collected loot.

### Movement actions are not activity titles

`HudPlusProcessLabels.isTrackedObjectOption` keeps its existing gameplay meaning for auto-start and idle/activity marking. HUD+ object selection uses the separate `isTitledObjectOption(action, objectName)` predicate after resolving the actual object definition. This lets movement continue to satisfy those existing activity signals without naming a route as the current activity.

The title predicate rejects traversal actions: `climb*`, `jump*`, `cross`, `walk-across`, `squeeze-through/past`, `enter`, `exit`, `pass`, `go-through`, `travel`, `board`, `ride`, and `teleport`. `Open` or `Close` on a door/gate is also movement; `Use` on a fairy ring, spirit tree or portal remains untitled.

It rejects traversal scenery by name: ditch (including Wilderness ditch), door, gate, large door, ladder, stairs/staircase, trapdoor, rope, tunnel, crevice, hole, cave entrance/exit, stepping stone, log balance, portal, fairy ring, spirit tree, obelisk, lever, gangplank, boat, canoe, magic carpet, charter ship, mushtree, portal nexus, jewellery box, mounted glory, fence and stile. Bank objects remain excluded. A traversal name always wins even if the action otherwise looks like an activity.

Resource targets, production stations, chests/coffers/caskets, reward objects and supported minigame activity entries can title when the action engages that object. `Chop down` + `Oak tree` and `Open` + `Brimstone chest` title; `Barrows stairs` does not. Consumption and receipts still cannot create or replace a title.

## Implementation

- `InteractionContextTracker`: client-thread event adapter, invoked from existing plugin interaction/menu/tick and lifecycle handlers. Existing PK bookkeeping remains separate.
- `ui/InteractionContextModel`: bounded, session-owned transient snapshot with a revision and expiry. Stores strings/IDs, not client Actor/Object references. Reads by renderers require no client API access.
- `ui/TrackingDisplayModel` and `TrackingDisplaySnapshot`: carry immutable display context, with same-timestamp cache invalidation when the context revision changes. Accounting activity and reward provenance remain independent fields.
- `ui/HudPlusHeaderLabel`, `DedicatedHudRenderer`, both legacy HUD layouts, Infobox and Live panel consume the context. The panel's existing refresh scheduling keeps Swing work on the EDT.

## Verification

`InteractionContextTrackerTest` (10 cases) covers immediate NPC names with PK disabled, unrelated actors, target switching, grace expiry, tick fallback/transformation, object selection/confirmation/cancellation, Examine and bank exclusions, pre-existing animation, pause, identity hold and disabled detection.

`InteractionContextDisplayTest` (4 cases) covers same-timestamp freshness, unchanged session values/counts, preserved old-source loot/reveal state, paused naming, selected object labels, and production-width rendering.

Historical preview and gate artifacts were under `build/` and are removed by clean builds. Current durable gate totals and the later width-aware InfoBox regression are covered by the test suite; the traversal and display regressions are listed above by test class. This document describes current presentation policy, not live client acceptance.

Live acceptance remains required on the identified build: attack/auto-retaliation timing, object actions and cancellation, long names/transforms, source switching with late loot, and pause/profile transitions. Offline tests do not measure actual client latency. This pass does not claim to close unrelated accounting/filtering review findings.
