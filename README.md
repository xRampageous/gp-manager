# GP Manager

See what you make after costs. GP Manager tracks your loot, supplies, losses and Grand Exchange trades as you play. Keep everything in Free play, or start a Grind for an activity you want to follow.

Works on normal, Ironman and Hardcore Ironman accounts. Ultimate Ironman isn't supported.

## Getting started

1. Install **GP Manager** from RuneLite's Plugin Hub.
2. Open its icon on the right sidebar and start playing. Tracking starts automatically.
3. To track an activity separately, open **Grinds → Start Grind**.

Use **⚙ → Getting started** for the in-plugin guide. Settings are behind the wrench beside GP Manager in RuneLite's plugin list.

## Screenshots

Screenshots use illustrative sample data.

| Live | Grinds |
|:--:|:--:|
| ![Live](docs/screenshots/live.png) | ![Grinds](docs/screenshots/grinds.png) |
| Net, GP/h, supplies and recent receipts | Named activities, previous runs and All time |

| Raids | PvP |
|:--:|:--:|
| ![All Time Raids totals and saved loot](docs/screenshots/raids.png) | ![PvP stats and death losses](docs/screenshots/pvp.png) |
| Collected loot, paid fees and All raids totals | PvP death losses, separate from PvE and raid fees |

| Ledger detail |
|:--:|
| ![A loot key's ledger entry](docs/screenshots/ledger-key.png) |
| Items and booked values inside a receipt |

## What you can track

- **Live:** Net after costs, GP/h and recent receipts. Click GAINS, SUPPLY, LOSS or TRADE to inspect that session's entries. Click Net to set a target; ACTIVE opens the session controls. Restarting a session keeps the full run's totals recorded.
- **Ledger:** see why an entry counted and open its retained receipts. Uncertain entries wait in Review for your decision. **Correct → Exclude** removes an entry from your totals, with Undo available.
- **Grinds and saved sessions:** name an activity, set targets and compare finished runs. **⚙ → View recap → Save session** keeps a session copy in Grinds without counting its money twice. All time brings your runs together.
- **Free play:** tracks activity outside Grinds. Set an optional milestone, or use **⚙ → Reset free-play** to start again and keep the previous run in Grinds.
- **Raids:** entering CoX, ToB, ToA or The Fractured Archive unlocks the account's tab; you always open it manually. Browse saved loot from every raid in one collapsible table. The tab shows completed raids, failed runs and actual paid death fees. Abandoned attempts and deaths with lives remaining stay out of the list. Rewards count when collected or sent to the bank, once. RuneLite's Loot Tracker can provide an unclaimed reward preview.
- **PvP:** KILLS opens retained PK loot; DEATHS opens PvP death losses and looting-bag details. PvE death records stay in Ledger, and raid fees also appear in Raids. BEST is a read-only figure. Choose **Loot display → PvP hero view → Always** to see the stats anywhere. Estimated RISK appears only in dangerous PvP areas and excludes stored charges, including bracelet ether and Fractured Archive item contents.

## HUD+

An optional in-game overlay shows Net, GP/h, targets and a loot tray. Compact item rows can show an item's icon, value and quantity together.

| With a target | On a raid streak |
|:--:|:--:|
| ![HUD+ with a target](docs/screenshots/hud-grind.png) | ![HUD+ on a raid streak](docs/screenshots/hud-raid.png) |
| Progress towards your target and recent loot | Kill count, Net per kill and the streak's loot |

## What it counts

- **Supplies used:** food, potion doses, runes, ammunition, cannonballs and supported item charges.
- **Loot collected:** chest, key, casket and clue previews do not book their contents just by opening.
- **Grand Exchange:** observed collections, partly filled offers, tax and platinum payments above max cash.
- **Death and repair costs:** reclaimed gear stays neutral; observed fees and repairs count as costs. Barrows wear alone is not a loss.

Banking, storage and gear swaps stay neutral. Prices use RuneLite's G.E. prices, your overrides or an alch fallback; ironmen can choose high-alch values. Leagues, Deadman, beta and tournament worlds pause regular tracking.

When a change needs your decision, the Ledger explains it in Review.

## Your data

Data stays on your computer, separately for each account. Use **⚙** to back up your profile, find its location or **Delete Profile**. Restore a backup while RuneLite is closed.

Recent receipts are retained alongside run totals; older item details may be compacted. Very large histories can make saving slower, so keep a backup. GP Manager makes no network calls of its own and never plays the game for you.

## Feedback

Found a bug or have an idea? Use **⚙ → Report a bug**, open an [issue](https://github.com/xRampageous/gp-manager/issues), or ask in [Discussions](https://github.com/xRampageous/gp-manager/discussions). A screenshot of the relevant Ledger entry helps.
