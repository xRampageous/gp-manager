# GP Manager

See what you actually make while you play. GP Manager tracks your gains, supplies, losses and Grand Exchange trades for every trip and keeps a full ledger of where your GP went. You just play.

## Screenshots

| Live | Raid loot | PvP |
|:--:|:--:|:--:|
| ![Live](docs/screenshots/live.png) | ![A looted Theatre of Blood](docs/screenshots/raid.png) | ![PvP](docs/screenshots/pvp.png) |
| Net, GP/h and your latest drops, with a target if you set one | A looted raid — the scythe is one entry | Kills, the victim's loot key, and what a death would risk |

| Grinds | Loot key claim |
|:--:|:--:|
| ![Grinds](docs/screenshots/grinds.png) | ![A loot key's ledger entry](docs/screenshots/ledger-key.png) |
| Your Grinds with average GP/h, targets and All time | A key opens into one entry — the looted setup listed beneath it |

| HUD+ at a target | HUD+ on a raid streak |
|:--:|:--:|
| ![HUD+ with a target](docs/screenshots/hud-grind.png) | ![HUD+ on a raid streak](docs/screenshots/hud-raid.png) |
| Net, GP/h, a target bar and the streak's loot | KC, Net per kill and the drop that made it |

## Features

- **Live** – your Net, GP/h, gains, supplies and losses as you play, plus your latest drops. Click the ACTIVE clock to reset Active Time and recalculate GP/h from displayed Net after one active minute, start a fresh session, or Restore time to resume the frozen previous clock (the original run clock when no backup exists). Keep previous clock saves that frozen time before restarting. Saving or continuing returns to the full run clock. Click Net to set a GP or time target, which starts a new session too. Free play or your Grind keeps its whole total, and the Ledger keeps everything.
- **Free play** – what you make outside Grinds, with an optional milestone (say 1B) and a small HUD message while it is reached. **⚙ › Reset free-play** starts it over and keeps the old run under Grinds › FREE PLAY, where you can restore it.
- **Ledger** – every gain, loss, trade and death, with the reason it counted. The tab keeps the whole-run breakdown; Live's Gains, Supply, Loss and Trade open that displayed session instead; click the active category again to return to Recent. Search stays in Ledger. Recent keeps its total and shows 15 rows by default; change Recent rows under Loot display, up to 30. Open a row to see its receipts. Correct > Exclude removes an editable booking from totals with an undoable audit record; it applies to that whole booking. Anything it wasn't sure about waits in Review for you.
- **Grinds** – start one by name (Vorkath, Zulrah, a skilling spot) and it's remembered with its targets, best run and average GP/h, so you can compare runs, including loot by the monster that dropped it. Each Grind is booked on its own; between Grinds it tracks Free play.
- **Saved sessions** – click the Free play/Grind line under Live's Net and choose Save session to keep a separate, read-only snapshot in Grinds. Continue without saving keeps the usual flow; Cancel stays put. Saved copies keep their booked values and clock without adding to All time totals; the existing history limit bounds their count. Copy summary is available on saved entries; the gear menu also offers Copy Live summary.
- **All time** – your total Net and GP/h and your best Grinds.
- **HUD+** – an optional box in the game window with your Net, GP/h and a loot tray for the current kill streak. Enable **Compact item rows** in HUD+ settings for an icon followed by shortened value and quantity, such as **200M ×1**. Optional **Full values below 10M** shows smaller amounts in full when they fit.
- **PvP** – kills, deaths and player loot in dangerous areas, plus what a death there would cost you.

## Getting started

1. Install **GP Manager** from the Plugin Hub.
2. Open it from its icon in the RuneLite sidebar.
3. Play. Tracking starts on its own; to name what you're doing, press **Start Grind** on the Grinds tab.
4. New to it? **⚙ › Getting started** has a short guide.

Settings open from the wrench next to GP Manager in RuneLite's plugin list.

## What it counts

- **Supplies** you use: food, potions, runes, ammo, cannonballs and weapon charges (blowpipe, tridents, the Toxic staff of the dead, Bryophyta's staff, the Eye of Ayak and more). Picking up what you dropped or fired cancels it out.
- **Loot** once it reaches your inventory; keys, chests, caskets and clue rewards when you open them.
- **Grand Exchange** trades at the real tax. Banking, storage and gear swaps are neutral moves, not gains or losses.
- **Deaths**, reclaim fees and repair costs. Barrows gear wearing down is not a loss.
- **Prices** come from RuneLite's Grand Exchange prices or your own overrides; items with no Grand Exchange price count at their alch value, and ironmen can value items at high alch instead.
- **Separate worlds** – Leagues, Deadman, beta and tournament worlds pause tracking, so they never skew your regular GP/h.

If it isn't sure what happened, it doesn't guess: the entry waits in Review with a reason, and you decide.

## Your data

Everything stays on your computer, in one profile per account. The **⚙** menu backs up your profile, shows where it's kept and has **Delete Profile** (with or without a backup first); to restore a backup, copy it over the profile file while RuneLite is closed. GP Manager makes no network calls of its own and never plays the game for you.

## Feedback

GP Manager is in beta, so I could use some help testing the flows I can't reach myself — the pinned [live-test checklist](https://github.com/xRampageous/gp-manager/issues/1) has them. Found a bug or have an idea? Use **⚙ › Report a bug**, open an [issue](https://github.com/xRampageous/gp-manager/issues), or ask in [Discussions](https://github.com/xRampageous/gp-manager/discussions). A screenshot of the Ledger entry helps a lot.
