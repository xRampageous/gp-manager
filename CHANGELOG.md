# GP Manager changelog

## 1.5.2 - 2026-10-17

**New**
- Click the ACTIVE clock for a new session: Net, Recent, GP/h and the clock start at zero. Free play or your Grind keeps its whole total, shown under your Net ("Free play +420M"; click it to show the whole run on Live again), and the Ledger keeps every row.
- Setting a target starts a new session too. Everything it makes still counts toward Free play or your Grind, reached or not. ✓ Done clears the target and the session keeps counting; ↻ Again starts a new session with the same target.
- ⚙ Reset free-play: your Free play so far moves to the new FREE PLAY list on the Grinds tab (nothing is deleted) and a new Free play starts at zero. Restore any past Free play from that list; the current one takes its place, so you can swap back.
- ⚙ Delete Profile (formerly Factory reset) deletes this account's data: "Back up & delete" saves a safety copy first, or "Delete without backup".
- Free play milestone: set a Net milestone (100m to 5b) in Targets. It counts all of Free play; its progress shows under Grinds › FREE PLAY and when you hover the Free play total, and a small notice tells you when you pass it.

**Changed**
- Risk moved from the top line to under your Net, and long NPC, activity and target names shrink to fit before they're cut.
- The NPC or activity name on Live stays for the Activity label timeout (15 s by default) between kills instead of dropping after 5 s.
- Nothing is said twice: a time target shows one line ("2h 50m left of 3h 00m · 6%") with no projected Net, a reached target adds no next-hour guess, the Grinds tab no longer repeats a Grind's last Net and average, and a Ledger group with one entry opens straight on it.

## 1.5.1 - 2026-10-17

**New**
- Clicking the ACTIVE clock now restarts GP/h and the clock from that moment, in a Grind too; Net, Recent and the Ledger keep the whole run. It replaces Start fresh, so a factory reset is the only full reset.
- AFK pause is now on by default at 3 minutes: standing still that long pauses tracking so GP/h leaves AFK time out. Skilling still counts as play. Set it to 0 in the plugin settings to turn it off.

**Fixed**
- A Grand Exchange buy paid from your bank no longer sends its change (the coins back when it fills below your price) to Review; earlier rows like this leave Review and Recent on login.
- Wilderness Agility dispenser rewards that go straight into an open looting bag now count as loot.
- Old items still showing "?" (half pies from before 1.5.0) take their price when you log in.
- Risk sits beside the Grind button instead of taking a row of its own, and it now includes what your looting bag holds (always lost on a death).

## 1.5.0 - 2026-10-16

**New**
- Simpler Grinds: one Start Grind sheet lists your Grinds (what you're doing now first) or takes a new name with targets. Every Grind you start is remembered, and the targets you change carry over to its next run. My Grinds shows each one's average GP/h, and starting a Grind opens Live.
- A shorter Targets form with one-click picks (5m, one bond, 20m to 1b; 30m to 6h; Clear). A running Net target has a ✓ Done button.
- Start fresh: click the ACTIVE clock on Live to move Free play's run so far into Recent grinds and start again at zero. Nothing is deleted.
- A short "Getting started" guide in the ⚙ menu, and a factory reset that says plainly what it deletes.
- AFK pause (off by default): after the chosen minutes without playing, tracking pauses so GP/h leaves AFK time out. Playing or moving resumes it; opening tabs like Friends or Skills does not.
- Leagues, Deadman, beta, tournament and other separate worlds pause tracking, so they never skew your regular GP/h.
- Blast Furnace: the coffer's drain counts as a cost; deposits and withdrawals stay neutral.
- Hiding one dose of a potion from Recent hides all its doses.
- An "Always show items" setting keeps the items you list on the tray and Live even when Ground Items hides them or they are under the minimum value, and drops on Ground Items' highlighted list show in its highlight colour.
- Your history updates itself on login: worthless items show "—", old tablet breaks and alchs show as their spell, and coins picked up near a Grand Exchange sale leave Review. Rows you corrected are never changed.

**Fixed**
- High Level Alchemy (including "Really cast?") and broken magic tablets show as their spell, one row at its net; something picked up in the same moment books as its own pickup.
- Items with no Grand Exchange price use their alch value on every account, or show "—" when they have neither; items with both keep the GE price unless an ironman turns on alch values.
- Half pies, half pizzas and cake slices are priced as their share of the whole one.
- Puzzle boxes and clue keys count as clue items worth nothing until the trail pays out.
- Coins picked up while a finished sale waits to be collected no longer go to Review as "Grand Exchange".
- The Live RECENT total counts a Grand Exchange trade by its Result, so a purchase no longer pulls it down by what you paid.
- A click no longer names an action it had no part in, and a quick second click no longer steals the first one's label.
- A Net target's "play left at this rate" counts only the time since you set it.
- Coin rows show only their value, without a quantity.
- A pending sell offer reads "Selling" instead of a clipped "Offering".
- Reading the Grand Exchange or a trade window no longer counts as idle, so Free play keeps its activity name.
- After resizing the client, the Ledger scrolls straight away.

## 1.4.1 - 2026-10-15

**New**
- Start tracking on login: with Automatic tracking on, tracking starts or resumes at your first login of a RuneLite session instead of waiting for the first action. A manual pause or stop always holds.
- HUD+ aim gauge: a Net aim's bar end counts down what is left while the bar fills. A time target keeps a live countdown; its final seconds turn the bar red and blink (unless Reduced motion), with a configurable warning window. The run's name lives on the sidebar now, and a kill streak's "KC" rides the header.
- Net aims count what the run earns after the aim is set, not the run's total.
- One-row aim actions: pause, reset, again, done and edit ride the target card as icon buttons with tooltips. Again on a reached Net asks to keep the net or start fresh; an ended timer re-arms in place.
- The sailors' amulet counts its charges: loading law and water runes is neutral, each teleport books one charge (a tenth of a law rune plus a water rune) as Supplies, and a Check measures the true count.
- Loot keys show their chest's worth: while a key chest is open, the Ledger and the Live key tooltip show the exact value of the loot inside (the game's own display rounds to the nearest million). Taking the loot settles it as usual.
- Charge rows for crystal tools, infernal tools, echo boots and the slayer's staff (e): loading their components books one Supplies spend and Checks book nothing. The bone staff books each cast; its chaos runes refund on Uncharge.
- The sidebar's gear menu offers a Changelog link that opens the changelog on GitHub.
- Offers and Recent on the Live page fold away with a click on their chevrons; their headers show a summary (the open offers' worth, the recent rows' net).

**Fixed**
- Fractional charge carries (a scythe's vial of blood, a tome's page, a blowpipe's scale) survive a logout or hop instead of resetting.
- Typing in the Ledger's search no longer drops focus after every character.
- A table header keeps its title when the page arrows appear (a long title shortens instead of vanishing).
- Sales with no tracked purchase carry no marker at all: the row shows as a plain trade (the coins received, like a buy shows its spend) and no profit is claimed — the same as how the big flip plugins treat unpaired sales. An open offer never marks Trade either.

**Changed**
- The sidebar uses Inter for labels and JetBrains Mono for numbers.
- Free play's hero names the activity it detects ("Trade"); a named Grind keeps its name.
- HUD+ and Live polish: the target rides the progress bar, a started time target shows its countdown, a time target counts from when it was set, and a reached aim keeps a pace line.
- Charge rows show what they spent, and a charge row that spends several component types wears the item's own icon.

## 1.3.4 - 2026-10-13

This update started your history fresh one last time; the old save is kept beside the new one. From here on, updates preserve your data.

**New**
- Right-click a charge row in Recent to hide that charge family (or the whole item) from Recent; restore either in Loot display.
- Charge loads book their cost: crystal shards onto crystal armour or the crystal shield, rings of recoil onto the ring of suffering, sunfire splinters into the Tonalztics of Ralos, soaked or soiled pages onto the tomes, and revenant ether onto the bracelet of ethereum. Checks book what they measured.
- Minigame loaners (Castle Wars, Soul Wars, Barbarian Assault, the Nightmare Zone, the Tithe Farm) never book; their real rewards still count.
- Guardians of the Rift books only the runes you craft; the deposit pool is a neutral bank move and rewards show as loot.
- Imbuing swaps an item for its imbued form with no loss or gain, and the imbued piece keeps its plain item's value.

**Fixed**
- Cannonballs count as Supplies for the dwarf multicannon and every ship cannon; set-up, load and empty reconcile with the balls that actually fire.
- Shop buys and sells count as "Bought"/"Sold" trades; tokkul and other-currency shops read "Exchanged".
- A drop with a harvest in the same moment books both.
- Pendant of Ates teleports book their frozen tear from the menu or the Rub interface, and a refused teleport removes its charge.
- Charged jewellery RuneLite cannot price counts one dose from the family's full price; a last charge that leaves a plain item books as one dose.
- Instance fees count whether paid from Death's coffer or the bank.
- Scythe of Vitur and Blade of Saeldor Checks count what they measured, including from the worn tab.
- A bite during a bank withdrawal of the same food still books, and a queued bite reads "Ate".
- Seed packs and Bounty supply crates count each Take; spirit flakes, Molch pearls and tokkul show values; Mastering Mixology counts cleanly.
- Sailing and Tempoross book cleanly: salvage, port-task cargo, charting drinks, unlock keys and schematics, harpoonfish and boat bottles never book as losses.
- Guardians of the Rift runes count at the craft, and its rewards show as loot in the HUD+ tray.

**Changed**
- Settings read in use order; Recent lists charge rows like any other row; the HUD+ tray drops the "claimed"/"pending" words and defaults to Small text.

## 1.3.3 - 2026-10-11

This update started your history fresh; the old save is kept beside the new one.

**New**
- Picking up something you recently dropped — or ammo you fired — shrinks the original row instead of adding a gain.
- Dwarf multicannon: set-up and pick-up book nothing; only fired balls count.
- Zulrah's scales used by the toxic staff of the dead and the serpentine, tanzanite and magma helms count from Checks.
- An abyssal tentacle that runs out or is dissolved books its whip as a loss.
- Herb boxes count each herb when you take it.

**Fixed**
- Checks count loads since the last Check; recharges, uncharges and Configure-Charges restart only that item's Checks.
- Leftover drop and ammo records expire once pickup is impossible, so later sales show the right profit.
- Losses list newest first; opening a gain shows All; private instance bank shares count.
- GE edges: compacted receipts read as unavailable, untrusted offers stay untrusted, and alch or shop coins are never booked as a waiting sale.

**Changed**
- PvP keeps kills, deaths, streaks and best kill per Grind in a smaller save.

**Removed**
- Bank · Carried wealth; Decide all in Review; HUD+ moments; the backup menu items; AFK pause and End Grind after AFK; the Ledger Deaths table; the GE reference and difference lines.

## 1.3.2 - 2026-10-10
- New pixel-art icon: a coin sack marked GP.

## 1.3.1 - 2026-10-10
- "Report a bug" in the ⚙ menu opens GitHub's bug and suggestion forms.
- Uncharging Bryophyta's staff reads as a measured spend.
- Toggling RuneLite's Ground Items updates the loot filter immediately.
- Two backups in the same second keep the newer; short amounts cut off like the game (999.6k → 999k); less work every game tick.

## 1.3.0 - 2026-10-09

**New**
- Barrows gear and the Amulet of the damned wear down without Review; repairs count their fee.
- Bryophyta's staff: charging is neutral, each alch or superheat costs a nature rune, uncharging counts the runes lost.
- The chugging barrel books one row per chug; filling is neutral.
- NPC purchases count, in coins or alternate currencies.
- House servant trips are neutral; wages and sawmill fees count.
- Loot sacks, courier bags and Hallowed sacks count what they hold when opened.
- Imp-in-a-box, the master scroll book and black-mask imbuing are neutral.
- Ironmen can value items at high alch (setting, off by default).

**Fixed**
- A Grand Exchange sale collected in parts shows as one sale.
- Gear reclaimed from Death's Office is not loot; an Eye of Ayak charged with runes no longer costs tears.
- Saves survive a brief antivirus lock; amounts format like the game (1.00M, never rounded up).

**HUD+**
- Clue drops show again; trades read "Traded"; combined rows show an icon.

## 1.2.1 - 2026-10-09
- Grand Exchange offers collected straight to the bank settle (purchases exactly; sales with their tax, never overstated).
- Review entries that can now be booked from their items are rechecked once when your profile loads.

## 1.2.0 - 2026-10-09
- Loot that goes straight into a looting bag, herb sack, gem bag, seed box or log basket counts; so do catches into an open fish barrel and logs into a basket.
- Death reclaim and instance fees paid from the coffer or bank count.
- Raid and minigame chest Bank-all loot counts.
- Ammo fired from Dizana's quiver and ash sanctifier runes count.
- Combining or decanting potions books nothing.
- Lit and unlit light sources (and open and closed containers) are the same item.
- Reverting or dismantling an item keeps its worth; a crystal tool's revert counts its 120 shards.
- Tackle box, seed box, forestry kit and huntsman's kit transfers are neutral.
- Fast Grand Exchange buys settle without Review.
- Revenant weapons count one ether per attack, measured by Checks.
- Charging never books a loss; Kharedst's memoirs and blood essence count what they use.
- HUD+ tray: newest loot on top, counts never cut off, hidden loot stays quiet.
- Plus many reconciliation fixes from code audits and reviews (pending actions per account, bag credit limits, reset safety copies, big multi-price GE sales).

## 1.1.2 - 2026-10-02
- Plugin Hub listing tidy-ups; removed unused code.

## 1.1.1 - 2026-10-02
- No thread is interrupted when saving.
- One tray streak per skilling run; gathering starts with its first XP.
- One ore row per smelting run ("Iron ore 16/28").
- Fixes: deaths read "Lost"; production receipts name their skill; removing worn gear is not Construction.

## 1.1.0 - 2026-10-02
- No save stutter: unchanged finished Grinds are saved from their stored form.
- Cleaner HUD+ header: the Grind's name or "KC", never an NPC's name.
- Icons: every item and spell icon cropped, fitted and centred; a setting turns them off.
- The loot tray names the fight and joins streaks for the same NPC.
- Wilderness risk line on Live; HUD+ stays short.
- Split previews Keep, Others and the Net before you confirm.
- Picked-up ammo lowers Supplies instead of counting as loot.
- Blowpipe darts count per shot (Ava's devices save theirs), and the blowpipe's charges read as Zulrah's scales.
- Smaller for the Plugin Hub's size limit: CSV export, the HUD+ hover panel, end cards and "vs average" moments removed.

## 1.0.3 - 2026-10-02
- Source size trims for the Plugin Hub limit; no behaviour change.

## 1.0.2 - 2026-10-02
- Source layout trims for the Plugin Hub limit; no behaviour change.

## 1.0.1 - 2026-10-01
- One GP/h everywhere; the Rate window and "last 10m/15m" lines are gone.
- Charging stays neutral whichever way round you load, and uncharging survives the confirm prompt.
- Cancelled Grand Exchange offers leave Pending; bottomless compost bucket charges are no longer tracked.
- Smoother saving; re-enabling while logged in reads the game on the right thread; menu text matches any language.

## 1.0.0 - 2026-10-01
- Live, Ledger and Grinds: current tracking, a correctable ledger (Gains, Losses, Market, Deaths, Pending, Review) and saved activities with targets, favourites and recaps.
- HUD+: Net, GP/h, target progress and a loot tray in the game window.
- Observed loot and supplies with receipts; unknown prices stay unknown and history is never repriced.
- Grand Exchange accounting: partial fills, collections, cancellations and slot reuse reconcile; verified proceeds, tax and basis count once.
- PvP and death accounting with their own receipts; loot keys and chests settle from measured contents.
- Local profiles with atomic saves, backups, restore and recovery.
- Data stays local; GP Manager makes no network calls and performs no in-game automation.
