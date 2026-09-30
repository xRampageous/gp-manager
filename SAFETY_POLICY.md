# GP Manager Passive Safety Policy

## Product scope

GP Manager is a passive accounting ledger. It may read ordinary RuneLite events and item-container state, perform local arithmetic, display historical totals, and save or export those totals locally.

## Permanently prohibited

- Automated or simulated mouse, keyboard, menu, chat, or game input.
- One-click or multi-action helpers, macroing, prayer or gear switching, pathing, target selection, or action scheduling.
- Packet manipulation, client-state mutation, or gameplay widget manipulation.
- Boss solvers, reactive combat guidance, tile markers tied to mechanics, gameplay risk warnings, escape guidance, opponent scouting, or live recommendations.
- Any network transmission of its own; telemetry, crowdsourcing, player lookup services, remote APIs, credentials, or player-data exposure.
- Reflection (`java.lang.reflect`, `Class.forName`, `setAccessible`), JNI/native code, runtime-downloaded code, Java serialization, subprocess execution (`ProcessBuilder`, `Runtime.exec`) and OS launchers (`Desktop.getDesktop().open` / `.browse`). Wiki links may only use RuneLite's `LinkBrowser`; the export folder path is copied, never opened.
- Constructing Gson in production (`new Gson()` / `new GsonBuilder()`): all JSON goes through the client's injected Gson bound once at start-up (`JsonCodec.bind`).
- `Class.getResource` for bundled files; bundled resources are read with `getResourceAsStream` only.
- Runtime dependencies outside the RuneLite-provided surface without an explicit future safety review.

## Allowed

- Reading inventory and equipment state.
- Reading whether a bank interface is visible.
- Reading RuneLite NPC-loot and player-loot events.
- Reading local-player death and recent interaction events to group completed PvM reclaim / PK accounting records and to add explanation-only evidence to the settled local-death row.
- Reading RuneLite item prices through `ItemManager`.
- Calculating revenue, costs, profit/loss, rates, confidence, corrections, activities, and PK encounter totals.
- Rendering the plugin's own panel and compact accounting overlay.
- User-triggered local CSV export and local JSON persistence.
- Developer-only offline simulations that call accounting classes directly and never launch or modify RuneLite.

## PK-specific restrictions

- Opponent names are never stored.
- No opponent stats, risk, equipment, prayers, location, or combat state are displayed.
- No PK data is uploaded or shared, including opponent names, item identities, or encounter details.
- PK events are used only for post-event bookkeeping.
- The plugin never performs or recommends a gameplay action.

## Build gate

1. Run `./gradlew releaseCheck`, which includes `safetyAudit`, both offline simulations, packaging checks, compatibility audits, and tests.
2. Review every source change against this policy.
3. Run `./gradlew releaseMetadataCheck publicSnapshotCheck` before a Plugin Hub submission: descriptor and `runelite-plugin.properties` must agree, the root `icon.png` must be at most 48x72, and only the public allowlist may be packaged.

The audit allows `MouseEvent` only in the plugin's own Swing component source directory (`src/main/java/com/gpmanager/ui/kit/`). Java declarations share `com.gpmanager`. These handlers respond to the user operating sidebar components; every other prohibited pattern still applies. `KeyEvent` has no allowlist exception.

This policy keeps the plugin limited to passive local accounting and adds a build-time check against prohibited capability classes. The current product has no Party transport or carried-value risk estimator.


## Simulation isolation

Simulation code must remain under `src/simulation/java`, must not post RuneLite events or access the live client, and must remain absent from normal plugin output. The `simulationIsolationCheck` task enforces the packaging boundary.
