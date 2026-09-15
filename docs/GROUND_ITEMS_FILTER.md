# Ground Items loot presentation filter

GP Manager can reuse active RuneLite Ground Items rules to hide **gain rows** from reward presentation, floating feedback, Live item changes, Ledger, and filterable Insights rows. This is display-only: collected gains still contribute to accounting totals and exports. Costs remain visible unless the separate advanced accounting-exclusion setting explicitly excludes them.

## Settings

| Setting | Values | Default |
| --- | --- | --- |
| Loot item filter | All items · Follow Ground Items · Highlighted list only | Follow Ground Items |
| Reuse Ground Items colours | On / Off | Off |

## How config is read

Supported APIs only (no reflection into Ground Items internals):

- `ConfigManager.getConfig(GroundItemsConfig.class)` for lists, thresholds, colours, `showHighlightedOnly`, `dontHideUntradeables`, value mode
- `ConfigManager.getConfiguration("grounditems", "highlight_<itemId>", Color.class)` for per-item colours
- `PluginManager.isPluginEnabled(GroundItemsPlugin)` for plugin on/off

Semantics verified against the installed RuneLite Ground Items sources (`GroundItemsConfig`, `GroundItemsPlugin` highlight/hide precedence, `ItemList` / `ItemThreshold`, overlay visibility).

## Rule precedence (reproduced)

1. Exact highlight name (+ quantity) beats exact hide
2. Exact hide beats wildcard highlight
3. Wildcard highlight beats wildcard hide
4. Quantity operators: `name>N` / `name<N` (default entry matches quantity &gt; 0)
5. Wildcards via public `WildcardMatcher`
6. Hide under value: both GE and HA stack values must be under the threshold; explicit highlight prevents hide
7. `dontHideUntradeables`: untradeables with GE 0 are not value-hidden when enabled
8. Value-tier colours (insane → low) when not list-hidden
9. Follow mode also honours Ground Items `showHighlightedOnly`

**Highlighted list only** keeps stacks that match the highlighted list (exact/wildcard/quantity). Value-tier colouring alone is not enough.

When Ground Items is **disabled**, Follow / Highlighted list only pass through (show all). Presentation refreshes on Ground Items config changes, GP Manager filter changes, and RuneLite profile changes **without replaying** rewards.

## Contextual rules not reproduced

These depend on a live ground-item instance / scene state and are intentionally out of scope:

- Ownership filter (self / group / takeable) and ironman ownership bits on `TileItem`
- Hotkey “hide all” and ALT double-tap hide state
- Scene distance / plane / world-view culling
- Despawn / private→public timers and lootbeam presence
- Whether the stack is still on the tile versus already looted
- Menu recolour / deprioritize (menu-only)

## Partial and all-filtered rewards

- **Partial:** tray / floating rows show visible *remaining* stacks only; confirmed pickups leave Ground Loot; source name stays from the complete observation; selected item and loot total are visible-only; collection footer uses the complete observation; HUD+ may show `+N hidden items` under icon · qty · price rows **only in Always Expanded** (Auto-collapse never shows that footer) and hover lists Hidden stacks
- **All-filtered:** Auto-collapse does **not** open the tray. Always Expanded shows **Hidden items (N)**; hover lists Hidden stacks and notes they still count; complete observation remains so toggling the filter reveals it without replay
- **Intentional pickups:** Floor pickups of open NPC Ground Loot confirm overlapping stacks and present as **Received** (confirmed quantities only while PARTIAL; full stacks when COLLECTED). Unconfirmed NPC drops stay **Ground Loot**. Adapter keys (`npc:…`) soft-confirm on LOOT/PK_LOOT inventory gains without requiring a stable engine encounter id. Player dumps use **Dropped**.
- Settled HUD+ always paints the **filtered** projection — never the complete observation (avoids junk reappearing after reveal)

## Accounting

Display-hidden gains stay in revenue, Net, and exports, but their gain rows are omitted from Ledger, Live item changes, and filterable Insights rows. Ground Items visibility never hides costs. Only the separate advanced accounting filter may exclude costs or gains from revenue/costs/Net. Raw source records remain available for correction and audit.

The minimum displayed loot value also hides gain rows on the reward tray, floating drops, Live item changes, and Ledger; it does not change accounting. Unknown-price rows remain visible for review because their value cannot be compared to the threshold.
