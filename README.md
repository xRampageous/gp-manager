# GP Manager

Passive RuneLite profit tracking by **Rampageous**. Track estimated revenue and supply costs, see live GP drops, review uncertain changes, and keep a local session history.

**Version 1.0.0** — the first release candidate. It has not yet been submitted to Plugin Hub; the live acceptance sweep is deferred to the first players' reports.

## Features

- Compact information box and animated, signed GP drops.
- Inventory, optional equipment and rune-pouch accounting with transfer suppression and delayed loot correlation.
- Automatic activity labels, manual session controls, idle-aware timing, and GP/hour.
- Pricing provenance, manual price overrides, and an optional high-alchemy fallback.
- Current and archived transaction review, batch corrections with reasons, undo and restoration audit trails.
- Session search, notes, favorites, comparisons, activity breakdowns, and PK encounter accounting.
- Local CSV exports, last-good save recovery, and legacy data migration.
- Optional RuneLite Party session totals; disabled by default.

## Tracking principles

GP Manager stays passive and honest about what it can and cannot count:

- **Pickup-gated loot.** Ground loot is display-only until it enters your inventory. Unpicked piles never inflate net, rate, or targets.
- **Read-only Ground Items.** Highlight colours and visibility filters may reuse Ground Items settings for presentation. They do not rewrite Ground Items config and do not change accounting by themselves.
- **Banking is ownership-neutral.** Deposits and withdrawals are transfers, not profit or loss. Soft bank-open evidence (the widget was merely visible) expires the instant the bank UI closes; a confirmed bury/eat/drink/cast action carries its own independent evidence lifetime, so a burial that settles after the bank closes is always consumption, never a transfer. Each evidence source's lifetime is tracked separately by design — see `com.gpmanager.engine.evidence` in source.
- **One eligibility path.** Advanced accounting filters flow through a single `ContributionEligibility` contract (see `com.gpmanager.grounditems.ContributionEligibility`) shared by HUD+, Floating, Ledger, Insights, exports, and Party. Presentation filters (Ground Items visibility, minimum displayed loot) stay display-only and never touch this path; the Ground Items display filter also hides gain rows in Live item changes, Ledger, and filterable Insights rows while leaving costs visible unless accounting exclusions apply. Older name-only Insights summaries honor unconditional list entries and preserve rows whose missing price/quantity details cannot be evaluated. See [`docs/SETTINGS_MAPPING.md`](docs/SETTINGS_MAPPING.md) for the split; raw CSV/PK projections and historical recalculation gaps remain B03.
- **Event-driven active time.** Active-time summaries advance from game-tick ingestion and explicit timestamped activity/pause/session-end events, never from a metrics, history, Overall, or Insights read. Delayed timestamps cannot rewind the cursor. Legacy inflated day values are not rewritten where the saved session lacks pause intervals needed to recover exact daily time.
- **Cost categories follow the effective counted contribution.** Only consumption the engine evidenced (eat, drink, cast, fire, charges, processing inputs) is Supplies; an unexplained decrease is an item gone and reads as Loss, as do death loss, death/reclaim fees and GE tax; TRADE costs remain in costs under the Market portion of the non-supply total. Transfers, claims, ignored corrections and uncounted rows are neither. Compacted history keeps the split when its evidence is retained; older compacted totals expose it as unavailable. Classification never changes type, valuation, counted state or Net.
- **HUD+ titles follow evidence.** A fresh NPC/object/player target outranks a recent XP-confirmed process title, which outranks Banking, neutral-zone and first-use Waiting labels. Process titles last five game ticks; Prayer needs explicit Use-on-altar evidence followed by Prayer XP. A fresh target can prefix PAUSED/AFK/REC; menu clicks by themselves, chat, consumption and receipts never title the HUD. Presentation does not alter accounting. See [`docs/INTERACTION_CONTEXT.md`](docs/INTERACTION_CONTEXT.md).
- **Charge/container limits.** Toxic Blowpipe and supported standard/Swamp Trident variants now use session-local Check-to-Check component measurements; a Check/menu click or animation alone never books cost. Missing prices fail closed, loading components is ownership-neutral only when an exact item-on-item action settles, and other charge families remain catalogue-only. This offline support still needs current-client acceptance (B04/B05); see [charge accounting design](docs/design/CHARGE_ACCOUNTING.md).
- **Collection accounting.** Actual partial pickups each count once through inventory settlement. Pending chest presentation cannot credit bank transfers. PvM reclaim ownership uses a separate canonical inventory/equipment snapshot captured at death, intersected with settled death-wipe losses within a 100-tick cap; without that snapshot, losses remain ordinary counted changes. Strong sale, transfer, consume and drop evidence takes precedence. Only measured returned quantities are neutral, while source-backed loot keeps precedence. Reclaim chat cannot independently book a fee; only a separate observed carried-coin outflow can be labelled a reclaim fee. A coin loss overlapping an armed delayed death wipe remains an ordinary cost. Expired gravestones create an informational audit row and never a guessed item loss. GE Collect ignores item-only returns, but complete offer/partial-fill tax reconciliation remains unfinished.
- **Currency proxies.** Optional catalogue (`useCurrencyProxies`) values Tokkul/shards/etc. via traded counterparts with `CURRENCY_PROXY` provenance — manual overrides always win.
- **No Plugin Hub claim.** This build is development-only (see the version note above) and has not been submitted to, or published on, Plugin Hub.

## What the numbers mean

GP Manager estimates changes in the value of items you carry. It is not a bank wealth tracker or a complete cash ledger. Price priority is your manual override, optional currency proxy (when enabled), RuneLite's cached market price, optional high-alchemy fallback, then Unpriced. RuneLite checks its price cache on an approximately 30-minute schedule; newly valued flows retain the local capture time and `ItemPriceSource`, and history is not later revalued. Older and unpriced rows have no capture time. SavedState schema 12 added this timestamp without inventing historical times; schema 13 adds profile-owned goal definitions and tile layouts; schema 14 stores the cost split when supported evidence exists; schema 15 adds receipt-retention state; schema 17 adds profile-zone daily Insights rollups; schema 18 adds Wealth history; schema 19 adds session category overrides and per-category daily totals; schema 20 adds durable observed encounter summaries and PvM daily counts; schema 21 adds local-hour net/active-time buckets and coverage-aware current/previous Insights highlights; schema 22 adds bounded PK place-time history; schema 23 adds explicit Free play/named-session owner provenance and named-session-start coverage. Older rollups retain four-hour values while hourly and named-start data remain unavailable until supported evidence exists. The B08 sidebar still needs to display price source/time and split availability and consume the expanded Insights read models, including Overall totals and their coverage. Mixed changes without supporting context are uncertain and excluded by default. Review uncertain and unpriced rows before relying on a session total.

Currency proxies, Pending Rewards presentation, item Split… corrections and Wealth locate are implemented offline. Wealth read models also expose active GE sell offers and visible collection-box contents, using complete observations and failing closed on missing prices; they are Wealth-only and never enter Net. The B08 sidebar presentation and live checks remain open. Measured charge accounting is implemented for two weapon families but awaits live acceptance; GE offer accounting reconciliation, several loot-key/direct-bank cases and specialized container integration remain open. See the [supported-scenario matrix](docs/SUPPORTED_SCENARIOS.md) and current backlog.

The detailed ledger is bounded (2,000 rows by default). Older rows are summarized into revenue and costs so the session total and full-session GP/hour remain intact. Item, activity and PK breakdowns, review actions, and rolling GP/hour use the retained detail. Diagnostics exports include the summarized amounts. Rows discarded by older plugin versions cannot be recovered.

History is isolated by RuneLite RuneScape profile (account hash and world-type profile). Older root-level data stays unassigned until you explicitly claim it for the active profile. Concurrent writers use a file lock and revision checks rather than silently overwriting each other. History retention defaults to 100 sessions; export important sessions before they age out.

## First use

1. Launch the development client using the instructions below and enable **GP Manager**.
2. Open the GP Manager sidebar. Allow the login baseline to settle; opening inventory is not counted as profit.
3. The durable **General** tracker starts automatically after login. On the **Sessions** tab, use **Start custom** to name an optional custom session; General is suspended while that custom session is active and resumes when it is finished. While a custom session is running, Live also shows a finish banner.
4. Use **Review uncertain / unpriced** to inspect ambiguous changes and record correction reasons.
5. Export sessions you want to keep or investigate.

### Pause, Stop and activity

- **Pause** and **Stop** sit side by side in the header strip, above the tabs — visible from every tab, not just Live. In **Session tracking**, enable **Auto-resume paused session** to resume on gameplay activity. The option is off by default.
- **Stop** suspends accounting until you explicitly choose **Resume**, regardless of auto-resume. It does not finish or archive either owner, and stopped state survives reloads.
- **Resume** starts from a fresh inventory baseline, so changes made while paused are not retroactively counted. The short baseline warm-up may also exclude the triggering change.
- **Finish custom** (Sessions tab, or the Live banner while custom is active) archives only the active custom session and returns to its existing General tracker. Transactions always have one active accounting owner; sessions are never merged on that transition.
- **Profit target** is optional and owner-local. It is an absolute tracked net-profit goal, not bank value, revenue, or account wealth. Set, edit, or remove it from Live's **Edit** button or the header **⋮** menu — not Session tools. It never changes accounting.
- Idle pauses resume on activity as before. Login/stat refreshes and inventory updates do not trigger the new manual-pause auto-resume.
- Crash recovery pauses at the last recorded save time and requires explicit Resume. Older saves without a save timestamp cannot reconstruct the precise offline interval.

Use `panelPreview` to paint the actual sidebar (Live, Ledger, Sessions, Compare, Insights, Tools) to PNGs under `build/bento-preview` from a fixture engine. They omit fetched item sprites and are not live-client screenshots.

For party totals, join a RuneLite Party and have each participant enable **Enable party tracker**. Only temporary current-session summaries are shared through RuneLite's Party service.

## Build and run

Use **JDK 21** to run the included Gradle 9.6.1 wrapper. The plugin compiles to Java 11 bytecode. The first build downloads Gradle and RuneLite dependencies; the wrapper verifies the distribution checksum.

Windows, from this directory with `JAVA_HOME` pointing to JDK 21:

```powershell
.\gradlew.bat clean releaseCheck
.\gradlew.bat run
.\gradlew.bat releaseBundle
```

Linux/macOS:

```sh
sh gradlew clean releaseCheck
sh gradlew run
sh gradlew releaseBundle
```

`releaseCheck` runs unit tests, general and PK simulations, passive-safety and compatibility audits, and checks the actual plugin JAR. The release bundle appears in `build/distributions`. `shadowJar` is a development launcher containing RuneLite and tests, not the Plugin Hub artifact.

## Data and upgrades

Settings remain under `profitmanager`; Java classes now live under `com.gpmanager`. Data lives in `.runelite/gp-manager` (an existing `profit-manager` or `smart-profit-tracker` folder is copied in once):

- `sessions.json`: current saved state.
- `sessions.backup.json`: previous valid saved state, used if the primary is missing or damaged.
- `sessions.corrupt-*.json`: preserved damaged files for investigation.
- `exports/`: locally requested CSV exports.

Missing legacy settings and data are copied from `smartprofittracker` and `.runelite/smart-profit-tracker`; the legacy copies remain in place. Configuration schema 31 is current; schema 29 introduced separate display-only loot visibility, optional accounting exclusion and a universal minimum displayed loot value. Existing single-active-session files are migrated by assigning that exact session to General without merging, deleting, or reclassifying historical sessions; schema 8's General/custom suspension flag is promoted without changing session data. Back up the data directory before upgrading or downgrading. Older versions do not understand the new summarized totals.

The plugin makes no direct external HTTP requests and does not automate gameplay. See [safety and privacy](SAFETY_POLICY.md) and the [changelog](CHANGELOG.md).

## Manage data

Use the header **⋮** menu → **Manage data…** for completed-history clearing, restarting General, resetting tracking data, or factory reset. Each action explains its exact scope, leaves prior CSV exports untouched, and can make a recovery export first. Resets create a fresh stopped General and require explicit Resume after baseline priming. Factory reset additionally clears only the `profitmanager` configuration group; it leaves unrelated RuneLite settings alone.
