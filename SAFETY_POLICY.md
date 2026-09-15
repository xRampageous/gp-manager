# GP Manager Passive Safety Policy

## Product scope

GP Manager is a passive accounting ledger. It may read ordinary RuneLite events and item-container state, perform local arithmetic, display historical totals, and save or export those totals locally. Party tracking is an explicit opt-in exception that shares only current-session aggregate totals through RuneLite's built-in Party service.

## Permanently prohibited

- Automated or simulated mouse, keyboard, menu, chat, or game input.
- One-click or multi-action helpers, macroing, prayer or gear switching, pathing, target selection, or action scheduling.
- Packet manipulation, client-state mutation, or gameplay widget manipulation.
- Boss solvers, reactive combat guidance, tile markers tied to mechanics, gameplay risk warnings, escape guidance, opponent scouting, or live recommendations.
- Network transmission outside the built-in, opt-in RuneLite Party service; telemetry, crowdsourcing, player lookup services, remote APIs, credentials, or player-data exposure.
- Reflection, JNI/native code, runtime-downloaded code, or subprocess execution.
- Runtime dependencies outside the RuneLite-provided surface without an explicit future safety review.

## Allowed

- Reading inventory and equipment state.
- Reading whether a bank interface is visible.
- Reading RuneLite NPC-loot and player-loot events.
- Reading local-player death and recent interaction events to group completed PvM reclaim / PK accounting records and to add explanation-only evidence to the settled local-death row.
- Calculating a passive estimate of the local player's carried item value in a PvP-capable area, using measured inventory/equipment prices and supported keep-count rules. The estimate is optional, informational only, hides outside those areas, and cannot recommend or trigger an action.
- Reading RuneLite item prices through `ItemManager`.
- Calculating revenue, costs, profit/loss, rates, confidence, corrections, activities, and PK encounter totals.
- Rendering the plugin's own panel and compact accounting overlay.
- User-triggered local CSV export and local JSON persistence.
- Opt-in sharing of current-session aggregate revenue, costs, profit, rates, and transaction count with current RuneLite Party members through `PartyService`; snapshots are memory-only and contain no item identities or PK details.
- Developer-only offline simulations that call accounting classes directly and never launch or modify RuneLite.

## PK-specific restrictions

- Opponent names are not stored by default.
- No opponent stats, risk, equipment, prayers, location, or combat state are displayed.
- The local carried-value estimate is not a warning, escape cue, death-cost promise, exact item-priority calculation, or opponent-risk signal.
- No PK data is uploaded or shared, including opponent names, item identities, or encounter details.
- PK events are used only for post-event bookkeeping.
- The plugin never performs or recommends a gameplay action.

## Build gate

1. Run `./gradlew releaseCheck`, which includes `safetyAudit`, both offline simulations, packaging checks, compatibility audits, and tests.
2. Review every source change against this policy.
3. Keep Plugin Hub preparation deferred until the 1.0 release candidate.

The audit's `MouseEvent` / `KeyEvent` name check is gated, not absolute: the plugin's own Swing sidebar (`GpManagerPanel.java` and the `com.gpmanager.ui.bento` package) handles clicks and keys on its *own* components and is allowlisted for those two names only. That is the user operating the sidebar, not the plugin operating the game; every other prohibited pattern still applies to those files.

No developer can honestly guarantee literal zero account risk for third-party software. This policy keeps the plugin limited to passive accounting, narrowly scoped Party snapshots, and adds a build-time check against prohibited capability classes.


## Simulation isolation

Simulation code must remain under `src/simulation/java`, must not post RuneLite events or access the live client, and must remain absent from normal plugin output. The `simulationIsolationCheck` task enforces the packaging boundary.
