> Current qualification (2026-09-13): measured Check-to-Check charge accounting for two families is implemented offline but still needs live acceptance; GE/claim/container paths retain other implementation gaps. See B01–B04 and review evidence. Reclaim chat does not book costs; ordinary observed coin losses do.

# Activity / Used-lost coverage matrix (1.0)

Current implementation summary: configuration schema **31** (see `SETTINGS_MAPPING.md`). Offline AE/AC epic coverage (tray states, GE tax, measured charges, Pending Rewards, splits, wealth locate) is locked by subject suites below. The dated notes preserve earlier fix sequence; live certification remains separate.

Offline evidence only. **Not** live acceptance. Do not read “recognised” as “accounting verified”.

Status key: **verified** (automated offline path), **partial** (some signals), **unsupported** (no reliable client signal), **awaiting live**.

Resolved and pinned default client dependency for this review: RuneLite **1.12.38**. An explicit `-PruneliteVersion=...` override remains available for compatibility checks.

### HUD+ activity transitions (schema 29+)

Always Expanded accumulates confirmed skilling/process receipts across skills (multi-skill tag **Session**). Auto-collapse remains activity-scoped. Soft-stick / Make-X / Tend-to / death clear unchanged from schema 27. Schema **29** seeds `hudPlusShowItemIcons` / `hudPlusShowTrayTags` (default on).

Single signal table: `ProcessSkillSignals` (menu / Make-X questions / chat failsafes / open-intent spend families). Inspired by Action Progress question strings (**BSD-2 reference only** — no runtime plugin dependency).

| Rule | Behaviour |
| --- | --- |
| Same skill under hold / Always Expanded | Accumulate on one tray |
| Different skill | **Auto-collapse:** replace tray (no Mixed-bleed across Cooking↔Firemaking, etc.). **Always Expanded:** accumulate into multi-skill **Session** bag |
| Process-spend intent | Wins over stale session activity (light logs stamped “Cooking” still → Firemaking **Burned:**) |
| Open intent | Matches spend **family** only (FM→logs; Prayer→bones/ashes) — not any loss |
| Soft-stick clear | Live process skill clears NPC + mismatched process scenery; gather (Willow/rocks) kept until Idle/retarget |
| Make-X / Tend-to / Use tinderbox↔logs | Retitle via `applyLiveProcessSkill`; conflicting spend intent cleared on skill switch |
| Chat failsafe | Cook/light/smelt/smith/fletch/craft/herb/build/alch/bury/… retitle before XP |
| Death | Local death hard-clears HUD+ soft-stick / tray / activity |
| Peer sources | Ground Loot ≠ Received ≠ Mixed ≠ Claimed/Pending ≠ Deposit/Traded |

Offline: `ProcessSkillSignalsTest`, Cooking↔FM light-log + open-intent tests, Fletching↔Smithing / Herblore↔Cooking / Smelting↔Crafting replace, peer isolation tests, death clear. **Live soak** after reload still required for Make-X questions and Tend-to on a real client.

**Live coalesced drink/pick/bury (2026-09-09):** Screenshot path — open consume intent + one stabilization window mixing dose leftover, potato gains, and bone losses settled **UNCERTAIN** (not counted). Fix: open intent matches any cost; dose-pair allows companion gains; chat reinforce Drink/Bury. Offline: live-sequence + drink+potato + chat wiring tests. **Rebuild/reload required**; live re-check pending.

**Supply spend + harvest gains (2026-09-09):** Open consume intent when menu item id is `-1`; inventory-slot id fallback (Supplies Tracker pattern); dose/partial leftover matching; Cast/Release options; intent TTL refresh on dirty; bank open clears stale consume intents. Potato-style harvest GAIN verified offline. Offline: extended `ConsumptionBurialEngineTest`, `InventoryConsumptionOptionTest`. Live: drink/bury/cast/potato still pending until reload of coalesced-window fix.
**Accounting/session-HUD gate (2026-09-09):** prior 250 tests green. Own-drop recovery + HUD tray layout added (`OwnDropRecoveryEngineTest`, `HudPlusTrayLayoutTest`); schema **15**. Live bury/own-drop still pending.

**Bank/bury latch fix (2026-09-09):** hard deposit evidence survives bank-close idle tick; soft UI-open still clears so burial after close is CONSUMPTION; bury menu item-id fallback + canonicalize. Offline: `ConsumptionBurialEngineTest` 13, `TrayBatchingBoundaryTest / BankBoundaryBurialTest (formerly CurrentStateReviewProbeTest)` 8, `HiddenItemExclusionConsistencyTest` 4. Live bury/bank still pending.

**HUD+ tray + consume intents (2026-09-09):** FLOATING Drop feedback no longer hides HUD+ observed/unconfirmed tray (OFF still hides). Eat/Drink/Empty/Break join Bury/Scatter for consumption intent; dose leftover still CONSUMPTION when intent matches; bank deposit without intent stays TRANSFER. Offline: `DedicatedHudRendererTest` FLOATING/OFF, `InventoryConsumptionOptionTest`, extended `ConsumptionBurialEngineTest`. Live pending.

**Factory reset / activity ingest (2026-09-09, refreshed 2026-09-11):** Factory reset = fresh install: wipe `profitmanager`, typed `setDefaultConfiguration`, schema stamp; legacy `smartprofittracker` is backup-only after that. Auto-start on → running General with trusted baseline. Offline: `FactoryResetActivityCoverageTest`, migration `shouldImportLegacyOnlyBeforeSchemaStamp`.

| Category | Activity detect | Gains | Costs / Used-lost | Source attribution | Offline evidence | Live |
| --- | --- | --- | --- | --- | --- | --- |
| NPC / boss loot | Partial | Yes (adapters + batching) | Supplies when classified | Yes (NPC / optional LT) | Reward + engine tests; HUD+ tray under FLOATING | Awaiting live |
| Bone bury | Generic inventory + bury intent | - | **Verified** offline (open intent + coalesced Drink/Pick/Bury window + bank-close + hard TRANSFER beat) | Bury/Scatter intent + chat reinforce; inventory-confirmed | `ConsumptionBurialEngineTest`, boundary probes | Rebuild/reload; awaiting live re-check |
| Bone altar / ash scatter | Partial | — | Same consumption path when priced stacks leave inventory | Scatter intent | Engine path; no dedicated altar fixture | Awaiting live |
| Banking / deposit-all / withdraw | Yes (bank open + hard container/menu) | None (ownership-neutral) | None — baseline only; TRANSFER hidden from ordinary Ledger | Soft UI-open + hard container/menu; consume intent cleared on bank open | Closed-boundary + pending-deposit + hard-before-inventory + bank-clears-intent | Awaiting live |
| Equipment / container moves | Partial | — | Transfer when evidenced; else uncertain | Weak | Snapshot / transfer tests | Awaiting live |
| World-ground drop vs bank | Distinct | Drop cost if priced leave inv; **matched own-drop pickup reverses loss** (not revenue). Drop intent TTL matches consume window; Destroy is irreversible consume-style (no recovery). HUD **Dropped** flash replaces skilling tag on dump; Destroy → **Destroyed**. | Bank retains ownership — not a cost | Drop intent + location; Destroy → consume intent (destroy stamp) | `OwnDropRecoveryEngineTest` | Awaiting live |
| PK loot / death / fees | Yes | Yes | Yes | Yes | `pkSimulation` | Awaiting live |
| Production / processing | **Verified** offline (transform menus arm PRODUCTION → PROCESSING) | Outputs + inputs on one settle | Inputs counted when PRODUCTION; loss-only FM/Prayer stay CONSUMPTION | Skill / station | `RewardPresentationModelTest` paired Mixed + Burned/Offered; classifier PRODUCTION | Rebuild/reload; awaiting live |
| Crafting / smithing / fletching / cooking / herblore / construction / runecraft | Transform menu + XP reinforce | Paired −in +out | PRODUCTION window + HUD **Mixed** + countdown till correlation TTL | Exact skill | Offline matrix below | Awaiting live |
| Firemaking light | light / Tend-to / Use tinderbox↔logs + FM XP + Make-X burn question | — | **Burned:** after XP (or intent-first under stale activity); timeout → Used | Firemaking | `firemakingWaitsForXpBeforeBurnedTag`; Cooking→light intent-first tests | Awaiting live |
| Prayer bury / offer | bury/scatter/offer/Use→altar/Sinister + Prayer XP; HUD+ Prayer title requires explicit Use→altar followed by Prayer XP | — | **Buried:** / **Scattered:** / **Offered:** after XP (XP-before-invent latched — no Used→Buried double flash); potion drink under Prayer stays **Used** | Prayer | bury + offer + scatter + drinkUsed + XP-before-invent + OSRS chat tests | Awaiting live |
| Food / potions | Menu Eat/Drink (+ Empty/Break/Release) + chat | Dose leftovers | **Verified** offline (named id + **open intent** dose leftover + **coalesced harvest companion** → CONSUMPTION) | Slot/item-id + intent + chat; Supplies Tracker-style confirm | `ConsumptionBurialEngineTest`, `InventoryConsumptionOptionTest` | Rebuild/reload; awaiting live re-check |
| Magic / ammo / runes | Cast menu + inventory/equipment loss | — | **Verified** offline (cast intent beats hard TRANSFER on rune stack loss); ammo when equipment in snapshot | Cast intent; pure cost | `ConsumptionBurialEngineTest` rune case | Awaiting live |
| Farming / harvest / gather | Pick/Harvest (+ Chop/Mine/Fish hints) | **Verified** offline potato/wheat/log GAIN from actual inventory collection, including first auto-start gather and coalesced consumption | Seed/compost when consumed | Menu activity hint + XP; exact object in HUD, Skilling in sidebar/Insights | `ConsumptionBurialEngineTest`, `BankVisibilityRegressionTest`, `CollectedLootVisibilityTest` | Rebuild/reload; awaiting live re-check |
| Charged gear / degradation | Partial | — | **Measured Check-to-Check offline** for Toxic Blowpipe and supported seas/swamp Tridents; exact component deltas only, missing GE prices fail the whole delta; item-on-item load losses are neutral only for the selected recipe component | Per-session exact Check baseline; item-on-item evidence for loading | `MeasuredChargeReadTrackerTest`, `ChargeLoadTransferEvidenceTest`, `ChargeSpendBookingTest`, plugin wiring audit | Awaiting live Check/message and inventory-settle verification; other families and charge/uncharge dialogs remain unsupported |
| Utility containers (bags/sacks/pouches) | Partial | Store/retrieve → **Stored** (TRANSFER) | Contents not Lost; forestry kit / plank sack / essence pouch / herb sack gated | Container adapters | `AccountingDepthTest` utility + deposit-box | Awaiting live |
| Neutral storage (vault / leprechaun / coffers / hopper / GIM / raid bag / LMS wipe) | Partial | Ownership-neutral TRANSFER / baseline refresh | Never Net | Storage adapters | `AccountingDepthTest` lmsRaidGimNeutral + transferNeverCounts | Awaiting live |
| Death / recovery / fees | Partial | Recovered flash + reclaim | Death Lost + fee tiers / Death's Office / boss IRS | PK + death adapters | `AccountingDepthTest` deathReclaimFeeTiers; pkSimulation | Awaiting live |
| Raids / chests / Pending Rewards | Partial | Observation → **Pending Rewards**; bank/invent claim → Claimed/Received | Never Net until claim | Exact source in HUD; Raid in Insights | Reward + `AccountingDepthTest` rewardChests | Awaiting live |
| Loot key (Wilderness PK) | Partial | Key held deferred; chest → Pending Rewards → Claimed once | Key consume TRANSFER; 5-cap overflow → Ground Loot | PK | `LootKeyLifecycleTest`, `AccountingDepthTest` | Awaiting live |
| Clues / activity crates | Limited | Casket/crate → Pending when reward UI fires; birdhouse/herbiboar/Foundry/BH paths offline | Clue dig cost pairs with claim | Misc / Skilling | `AccountingDepthTest` clue + crates | Awaiting live |
| Agility (no item cost) | Limited | Rare gains | No expected cost | XP | Labels only | Awaiting live |
| Trading / GE | Partial | Inventory TRADE deltas + Collect tax XOR; player trade → **Traded** flash | GE sell tax 2% (cap/exempt) when `applyGeSellTax`; invent shortfall XOR explicit row; no tax on buy/shop/player trade | Classifier + `GeSellTaxBooking` + ledger why | GeSellTax* + trade flash tests | Awaiting live |
| Item splits (any loot) | Yes (Review) | — | Keep qty stays in Net; given-away via correction (`Split share`) — not Used/eat; Party untouched | Ledger Split… / Review | `ItemSplitAccountingTest`, ledger why tests | Awaiting live |
| Wealth locate (PoH / STASH / DWMS-class) | Observe-only | — | Locate UI only — never invents Net | Live Wealth accordion | `WealthLocateModelTest` | Awaiting live |

### Process skill HUD matrix (offline)

One process presenter. Shape decides the tray tag; PRODUCTION arms only for transforms.

| Skill | Settle shape | Menu / confirm | HUD tag | Accounting |
| --- | --- | --- | --- | --- |
| Fletching | Paired | fletch / cut / string + XP reinforce | **Mixed** (−log +shafts) | PRODUCTION → PROCESSING |
| Crafting | Paired | craft / cut / spin / stations + XP | **Mixed** (−hide +chaps) | PRODUCTION → PROCESSING |
| Smithing / Smelting | Paired | smith / smelt / anvil / furnace | **Mixed** | PRODUCTION → PROCESSING |
| Cooking | Paired when both | cook / range + XP | **Mixed** or Cooked: | PRODUCTION → PROCESSING |
| Herblore | Paired or Cleaned: | mix / combine / clean | **Mixed** or Cleaned: | PRODUCTION → PROCESSING |
| Construction | Paired when both | build | **Mixed** or Built: | PRODUCTION → PROCESSING |
| Runecraft | Paired when mixed | craft / altar | **Mixed** (−ess +runes) | PRODUCTION → PROCESSING |
| Firemaking | Loss-only | light + FM XP | **Burned:** | CONSUMPTION (no PRODUCTION) |
| Prayer | Loss-only | bury / scatter / offer / Use→altar / Sinister Offering + Prayer XP | **Buried:** / **Scattered:** / **Offered:** (mode from menu) | CONSUMPTION (no PRODUCTION). Tray latches Prayer XP when it arrives before invent settle (avoids Used timeout then Buried upgrade). OSRS: PoH “gods are (very) pleased…” reinforces consume; Chaos “Dark Lord spares…” = title only (50% bone save — no cost without invent loss). |
| Magic alch | Loss-only or Mixed if coins | cast / alch + Magic XP | Alched: or **Mixed** | CONSUMPTION / PROCESSING if PRODUCTION |
| Eat / drink | Loss-only | Eat / Drink | **Used** | CONSUMPTION |

HUD+ shows a whole-second **countdown** on process cards until the PRODUCTION correlation window (or process-spend XP wait) ends — same dwell clock as Auto-collapse. Under Auto-collapse, gather/process cards also stay open under **activity-hold** until Character Idle releases hold, then Tray dwell folds.

**Used / lost policy:** counted consumption and production inputs come from stable inventory evidence, rather than a bare click, XP event, or bank ownership move. Matched Eat/Bury/Scatter/Drink/Empty/Break/Cast/Release intent plus confirmed removal books consumption once, including dose leftovers and mixed harvest windows. Confirmed Drop plus removal books consumption; a matching nearby pickup reverses that loss as a transfer at the original valuation. Bank transfer evidence stays ownership neutral and stale cached bank contents are never treated as an open bank. Gathered items book only when they actually enter the inventory. The Ground Items display filter hides matching gain rows from reward displays, Live item changes, Ledger, and filterable Insights, but collected gains still contribute to revenue, Net, and exports. Costs remain visible unless the separate advanced accounting filter explicitly excludes them. The universal minimum displayed loot value also hides gain rows without creating profit. Unknown prices retain their quantity and appear in Review unless the user enables Ignore unpriced items. HUD+ observed/unconfirmed trays paint for Floating and Integrated feedback; Off hides them.

**AE/AC invariants (offline-locked):** `TRANSFER` never counts (bank, deposit-box, GIM, raid bag, LMS wipe, STASH/PoH, loot-key consume, coffers/hopper/vault/leprechaun). Pending Rewards / Ground Loot never inflate Net until claim/pickup. One counted revenue per encounter (LT+invent; loot-key once). Weapon charge spend requires a complete negative component delta between compatible measured Check reads and exact component GE prices (coins use fixed face value); unsupported/missing evidence books zero. Utility-container calibration remains separate. GE tax XOR invent shortfall; no tax on player trade/shop buy. Item splits share the correction undo stack and are not Party settlement. Tray tags never mutate the ledger. See `GpManagerEngine`, `ChargeSpendBooking` and `AccountingDepthTest`.

**Known limits:** same-tick acquire+consume is recovered when a named consume intent stored invent qty at arm; compacted retained totals lack item detail for perfect retroactive filtering; measured charge baselines are session-local and restart from the first Check, while other catalogue families and charge/uncharge dialogue reads stay unsupported; missing prices fail a whole measured delta closed. Utility-container Check state persists per RuneScape profile via ConfigManager; Wealth accordion is locate-only (no SavedState wealth schema / no bank-wealth Net). Loot Tracker `amount` drives encounter multiplicity when present.

**Gaps / awaiting live:** Full per-skill live matrix on a real client; measured charge Check→`bookChargeSpend` traces for Blowpipe/Tridents, repeated use, refills, pauses/restarts and component settlement; charge/uncharge dialog reads remain unimplemented. Also pending: loot-key/chest/LMS/raid-bag/GIM end-to-end certification; GE Collect tax XOR live accept; Wealth observe producers (plugin hooks ship — need client exercise). **Item changes** hide gain rows under the Ground Items display filter and optional minimum value while keeping accounting totals intact; costs remain visible unless the advanced accounting filter excludes them. **Late future only:** DMM / Leagues rule forks.

### Metrics alias (HUD vs panel)

HUD+ `Total:` ≡ Live panel `Net` ≡ Insights `Total profit` — same `revenue − costs` formula (`SessionMetrics.getNet()`). Live Net tooltip notes the HUD Total alias. Truly different: `GP/hr`, Revenue/Costs, Folio Net, Overall Total, Party Combined net.

### Wiki methods — Partial / unsupported (OSRS)

Not missing a second profit engine. Fixtures below need dedicated signals before claiming full Prayer/skilling coverage:

| OSRS method | Wiki | Plugin today | Notes |
| --- | --- | --- | --- |
| PoH gilded altar | [Gilded altar](https://oldschool.runescape.wiki/w/Gilded_altar) | Offer / Use→altar + “gods are (very) pleased…” | **This pass** |
| Wilderness Chaos Altar | [Chaos Temple](https://oldschool.runescape.wiki/w/Chaos_Temple_(church)) | Offer / Use→altar; Dark Lord save = no fake cost | **This pass** |
| Sinister Offering | [Sinister Offering](https://oldschool.runescape.wiki/w/Sinister_Offering) | Cast arms Offered mode | **This pass** |
| Ectofuntus | [Ectofuntus](https://oldschool.runescape.wiki/w/Ectofuntus) | No fixture | Backlog |
| Libation bowl / blessed shards | [Libation bowl](https://oldschool.runescape.wiki/w/Libation_bowl) | Open family only | Backlog |
| Sacred Bone Burner | [Sacred Bone Burner](https://oldschool.runescape.wiki/w/Sacred_Bone_Burner) | No fixture | Backlog |
| Bonecrusher | Bones / crush | No dedicated signal | Backlog / unsupported if silent |
| Wintertodt Reward Cart | [Reward Cart](https://oldschool.runescape.wiki/w/Reward_Cart) | Crates Limited | Backlog |
| Tempoross / GOTR / Foundry / birdhouse | respective wiki | Limited crate paths | Backlog |
| GE partial fills / shop entry fees | — | Missing offer-book reconciliation | Profit backlog |
| Sailing cannon ammo | [Cannonball](https://oldschool.runescape.wiki/w/Cannonball) | No Sailing path | Late backlog |
