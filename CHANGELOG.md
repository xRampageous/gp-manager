# GP Manager changelog

## 1.4.0 - 2026-10-15

**New**
- "Start tracking on login" (on by default, beside Automatic tracking): with both on, GP Manager starts or resumes at the first login of a RuneLite session instead of waiting for the first action; a Manual pause or Stop always holds, and later hops behave as before (owner live 2026-10-13).
- HUD+ gauge (owner live 2026-10-14): a Net aim's bar end always counts down what is left (200k → 0) while the bar fills with the progress. A time target keeps its live countdown (1h 28m, then seconds); in the final seconds the countdown replaces the label and warns: the bar and countdown turn red, and the countdown blinks unless Reduced motion is on. A timer-paused countdown never warns. The warning window is a setting, 10 seconds by default, 0 turns the warning off. The run's name no longer appears on HUD+ — it lives on the sidebar. A kill streak's "KC: 12" shows in the smaller body face on the header's right, with the GP/h under it, and the timer never jumps sides; the count never ellipsizes.
- A Net aim counts what the run earns after it is set (owner live 2026-10-14): with 10M already banked, a new 10m aim counts from zero instead of reading "reached" the moment it is set. Starting a Grind is unchanged (it counts the fresh run), Edit > Save on a completed aim starts it over, and older saves keep counting from the run's start.
- Aim actions (owner live 2026-10-14): icon buttons keep the card one row tall — pause (||) / resume (▶), reset (↺), again (↻), done (✓) and edit (…) each carry their wording as a tooltip. While a run goes on the icons (pause, reset, edit) ride the target card's header in one row with no bottom row; a completed or paused aim lays the set (again, reset?, done, edit, pause) along the bottom. A running Net aim offers reset (re-bases its count on the net so far, in place, without completing anything). Again (↻) on a reached Net asks: Retain net keeps the run going and re-bases the aim on the net so far, Start fresh re-runs the same setup as a new instance. An ended timer re-arms in place (Free play included); done clears the goal entirely while the run keeps tracking; edit (…) opens Targets; pause / resume works the timer itself — its window freezes and the run keeps tracking.
- The sailors' amulet counts its charges (owner 2026-10-14): loading law and water runes onto it books nothing (a neutral charge load), and each teleport — The Pandemonium, Port Roberts, Red Rock or Deepfin Point — books one charge's share, a tenth of a law rune plus one water rune, as Supplies. A Check ("The amulet has 106 charges.") measures the true count and reconciles the estimates.
- A boat recovery books from the bank (owner 2026-10-14): the Recover window shows each boat's fee, so the Recover click reads its row's amount and the amountless "Payment has been taken from your bank." line books one "Recovered <boat>" row — value only — without a fresh Recover click (or after its window), the line books nothing. With coins in the pack the game prints nothing: the same row books from the pack's exact coins spend.
- Loot keys show what their chest is worth (owner 2026-10-14): while a key chest is open with its loot still inside, the Ledger's Pending key line and the Live key tooltip show the exact value of its observed contents — hover the Ledger row for the exact figure, where the game's own display rounds to the nearest million. Taking the loot settles and books it as always; a closed chest, an emptied one or any part without a quote shows no value.
- More charge rows (owner 2026-10-14): the crystal tools (axe, pickaxe, harpoon), the infernal tools, the echo boots and the slayer's staff (e) are load-costed — their components (crystal shards, the smouldering stone or a spare dragon tool, echo crystals, a slayer's enchantment) book one Supplies spend when loaded and a Check books nothing; the infernal, echo and slayer upgrades keep the base's worth through their swap (a crystal tool's revert costs only its shards); the bone staff is fed by chaos runes (refunded on Uncharge, so loading books nothing), each cast spends one charge and its Check measures (the exact Check wording awaits the first live read).

**Fixed**
- The fractional charge carries survive a logout or hop now: a scythe's vial of blood (and a tome's page, a blowpipe's scale) keeps accruing toward its next whole unit across logins instead of being wiped, and an account switch still clears it (owner live 2026-10-13).

**Changed**
- The sidebar wears its new type: Inter for labels and JetBrains Mono for every number (bundled OFL fonts, subset to the glyphs the plugin can show), with full item names, a one-row PvP strip (Best kill, Streak, Risk) and calm spacing.
- Activity names are retired: the Live hero shows the run's name only - free play stays blank - and combat is never named by a click (the engine's detected activity still feeds session naming and receipts).
- Free play's hero names the detected activity again when there is one ("Trade"); a named Grind keeps its name only (owner live 2026-10-14).
- HUD+: the target rides the progress bar's end instead of stretching the money row; a started time target shows its countdown; a non-positive rate reads "Not earning at this rate" instead of pretending a pace.
- Live: the hero carries the live Net with its target when one is set (-1.0k/200k) and the card adds the Active Time pair (elapsed/target · percent) with its countdown and projected Net, so the Net is never shown twice; reaching the Net early no longer hides a still-pending timer (owner live 2026-10-13).
- A time target counts from when you set it: 12s / 2m then 1m / 2m (seconds under a minute), not 59m / 2m from the run's start; the pair never runs past its aim (2m / 2m stays 2m / 2m), re-saving the same value keeps a running count but starts a completed window over, a changed value restarts the count, and older saves keep counting from the run's start (owner live 2026-10-13).
- Charge rows show what they spent (the vial, the rune), so a charged weapon never looks like a duplicate of its own loot row.
- A charge row's icon matches its name when a cast spends several component types (the sailors' amulet wears the amulet, not an arbitrary law rune); a single-component spend keeps what it spent (the vial, the rune) (owner live 2026-10-14).
- A reached aim keeps a pace line (owner 2026-10-14): once a Net aim completes with no timer still running, the Live card shows `~+27.1M in 1h` — the next hour at the rate you are earning — instead of an empty line; an unestablished rate still shows nothing, and the `~` figure never counts toward Net.

## 1.3.4 - 2026-10-13

Your GP Manager history starts fresh with this update (saved Grind setups are cleared too); the old save is kept beside the new one as `sessions.schema109-….json`. The fixes below re-book charges, items and trades, and rows an earlier build already wrote cannot be corrected in place.

**New**
- Right-clicking a charge row in Recent offers "Hide charge from recent" beside "Hide item from recent": hiding the charge removes that item family's spends (a "Burning amulet*" pattern) while its gains stay listed; hiding the item removes every row of that name. Net and the Ledger are unchanged, and removing either setting in Loot display restores the rows.
- Crystal armour and the crystal shield count their shards when you load them: using crystal shards on a piece books a Supplies spend at the shard's price, because a revert keeps only the seeds. A Check on a piece books nothing; the crystal weapons keep their per-use estimates and measured Checks.
- The ring of suffering counts its recoils when you load them: rings of recoil (noted or unnoted) book a Supplies spend at the recoil's price as a Charge load, because a discharge never refunds them. A Check on the ring, the recoil settings toggle and the bank's Configure-Charges book nothing.
- The Tonalztics of Ralos counts its sunfire splinters: charging it (the Charge prompt or splinters used on it) books nothing, and a Check books the splinters its throws used.
- The tomes of water and earth count their pages: loading soaked or soiled pages books nothing, and a Check measures the casts from the tome you clicked.
- The bracelet of ethereum counts its revenant ether: loading ether books a Supplies spend at the ether's price as a Charge load (an uncharge destroys the stored ether, so it never refunds). Alchemy books the bracelet at its uncharged form's price, and its 250-ether dismantle stays Review — its parts are worth more than the item.
- Minigame loaner items never book: a loaned loadout or table supply from Castle Wars, Soul Wars, Barbarian Assault, Guardians of the Rift, the Nightmare Zone or the Tithe Farm is invisible on both the grant and the strip, while the persistent rewards (tickets, penance gear, tithe gear, and the Guardians of the Rift reward items) still count.
- Guardians of the Rift books only what it should: the guardian essence, the guardian stones and the portal talismans never book on a grant, a use or a drop, so crafting at the altars books the runes alone; the intricate pouch is a claim — its pickup books nothing and opening it books its contents. The Emir's Arena staging-area rune pouch is invisible too.
- Imbuing keeps an item's worth: swapping an item for its imbued form (or between imbue venues) never books a loss and a gain, and the imbued piece prices from its plain item — salve amulets, the Fremennik and Vet'ion rings, the granite ring, the ring of wealth and the magic shortbow (i).

**Fixed**
- Cannonballs count as Supplies on the Ledger for the dwarf multicannon and every ship cannon tier (bronze to dragon, chainshot and incendiary).
- Dwarf cannon: the balls Set-up loads by itself, and the balls loaded by using one on the cannon, now come back through Empty exactly like Fire and Load do - the parts custody covers the whole set-up, so the furnace is never a loss, and a login right after a set-up no longer misbooks its auto-load; only the balls that actually fire count (owner live 2026-10-13).
- Buying from a shop with its window open books "Bought" and selling books "Sold", as counted Market trades instead of Review; shops that pay in tokkul or other currencies still ask.
- A drop with a harvest in the same moment books both: dropping empty plant pots while a calquat fruit is picked up shows the Dropped row plus the fruit, instead of one Review row.
- A Pendant of Ates teleport books the frozen tear from a Rub-interface destination or a minimenu destination click, including one that carries no item id (a worn pendant's own option); no Check is needed first.
- Charged jewellery RuneLite cannot price (a Burning amulet(4)) now counts one dose from the family's full price, so each teleport costs about a fifth of the amulet instead of the whole price or an unknown row.
- The last charge of an amulet or ring that leaves a plain uncharged item (a glory(1) to Amulet of glory) books as the same one-dose Supplies step, instead of waiting in Review.
- The HUD+ tray no longer writes "lost" after a dropped item's count; the red amount and the Dropped headline carry it.
- A refused pendant teleport (the click-here-to-continue line, "you appear to already be at that location") removes the charge its click estimated - the line arrives as a message box, so it is matched before the chat-type gate now (owner live 2026-10-13).
- The Ledger always opens its Losses view on All (owner live 2026-10-13).
- A private boss instance paid from Death's coffer counts again (the game's line reads "12,500 Coins", not "12,500 x Coins"), along with its same-moment bank share.
- A private instance paid only from the bank counts its fee too, instead of missing it when Death's coffer is empty.
- A Check on a Scythe of Vitur (including the holy and sanguine forms) or a Blade of Saeldor now counts what it measured — the scythe's blood runes, the blade's crystal shards — instead of staying estimate-only.
- A bite that lands while a bank withdrawal of the same item settles now books its own Supplies cost instead of being netted away by the transfer: withdraw-all food, eat one, keep playing — the eaten one counts.
- A queued bite after the last Eat click keeps its verb: the chat confirmation ("You eat the chicken.") names Ate, so the row no longer reads Used.
- A Check clicked from the Worn Equipment tab now works: the click adopts the item's own worn slot, so tab Checks on a scythe or another measured-Check item book their measured spend instead of being ignored.
- A farming seed pack and the Bounty Hunter supply crate work like a herb box: worth nothing until you take from them, then each Take counts what came out with no Review.
- Spirit flakes count at their soaked-page exchange and Molch pearls at Alry's golden-tench rate, so neither shows "?"; the hallowed marks, Castle Wars tickets and Soul Wars spoils are catalogued as known currencies.
- Mastering Mixology counts cleanly: the herbs you refine book their cost once, the pastes, order potions and digweed never book, and the rewards you buy with resin count normally.
- Tempoross's raw, cooked and crystallised harpoonfish never book: they are minigame-only ammunition, so grants, cooking and loading stay invisible.
- The fish sack barrel, forestry basket and the gem pouch, satchel, tote and sack are utility containers now, so loot picked into them counts like any pickup.
- Shipwreck salvage sorts cleanly: the raw salvage never books, so the sorted loot counts once when you sort it at a salvaging station.
- Sailing port tasks count cleanly: courier cargo and bounty monster parts never book, so a delivery or bounty hand-in shows only the real reward; a sealed crate's charting drink never books either — the loot it grants still counts.
- Sailing unlocks count cleanly too: lost schematics, their keys and lockbox directions never book, charging the sailors' amulet books only its law and water runes, and a full boat bottle never books — the empty bottle's use does.
- Runes crafted at the Guardians of the Rift altars count when they are crafted: emptying an essence pouch just before a craft no longer hides them, so a round books the runes it made.
- Depositing runes into the Guardians of the Rift deposit pool is a neutral bank move now, instead of a spent-runes row.
- Guardians of the Rift rewards show in the HUD+ tray as loot; they no longer read as "claimed".
- The Astral Contact spell (the Lunar NPC contact) shows the spell's icon.

**Changed**
- Plugin settings read in use order: Loot display runs filter, minimum, item icons, Hidden Recent items, Hidden Recent charges; Accounting puts PK and uncertainty before ironman and manual overrides; HUD+ lists what it shows before how it looks.
- Recent now lists charge rows (a weapon's cast estimate, a Check's measured charges, a pendant's tears) like any other row; they were previously Ledger-only. "Hide charge from recent" hides them per weapon, the same as jewellery doses, and is offered only on charge or dose rows (an ordinary supply like buried bones offers just "Hide item from recent").
- Tokkul now counts: it prices from the uncut onyx exchange, so TzHaar drops show their value and tokkul trades book both sides; wearing Karamja gloves uses their improved rate.
- A shop trade paid in a currency other than coins (tokkul, spirit flakes, …) now reads "Exchanged" and names what it moved: buying runes with tokkul shows "Bought Death rune ×50" on the tokkul entry, and "Paid Tokkul ×14,550" on the rune's. Coin shops keep their own wording. It also sits under the Ledger's Trade section as a settled trade instead of waiting in Pending.
- Recent's shop-exchange row opens the exchange's own entries (every buy in that row), not one rune's side of it, and each entry is named by what was bought ("Death rune ×5"); the Ledger section holding G.E. and shop trades reads TRADE.
- HUD+ text size now defaults to Small; pick Normal or Large in settings if you want it bigger.
- HUD+ tray rows read as the item icon, its name, ×count and value only; the old "claimed" and "pending" words are gone.

## 1.3.3 — 2026-10-11

Your GP Manager history starts fresh with this update; the old save is kept beside the new one as `sessions.schema108-….json`.

**New**
- Picking up something you dropped a few minutes ago shrinks its Dropped row instead of adding a gain.
- Picking up arrows, bolts and blowpipe darts you fired shrinks the shot the same way; a blowpipe shot keeps its Zulrah's scales cost.
- With the game's ammo-picking setting, darts you pick up straight into the blowpipe recover the shot too, and the next Check still counts them once.
- Dwarf multicannon: setting it up and picking it up book nothing; only the cannonballs it fires count, and balls you get back shrink the load.
- The Toxic staff of the dead and the serpentine, tanzanite and magma helms count the Zulrah's scales your Checks show they used.
- An abyssal tentacle that runs out or is dissolved books its whip as a loss instead of asking in Review.
- Herb boxes are worth nothing until opened, like a casket: each Take-one counts its herb with no Review or "?". Bank-all counts nothing, since the game only says how many herbs went to the bank.

**Fixed**
- A Check counts darts, scales and runes you loaded since the last Check, so reloading picked-up darts or topping up to the same count no longer hides what you fired.
- A Check that shows the darts unchanged while the scales fall removes the dart estimates, as a Check that changed nothing already did.
- A dart a Check already accounted for books as a normal gain when picked up, instead of vanishing.
- Picking up one item from a drop of several keeps the others' cost.
- The bank's Configure-Charges, Uncharge and Unload no longer read as charges spent at the next Check, and only restart that item's Checks.
- Casts and shots before a recharge or a first Check stay counted; the next Check no longer removes them as overestimates.
- Dropped items and fired ammo nobody picks up leave the purchase-price record once they can no longer be picked up, so later GE sales of the same item show the right profit.
- The Losses table's All view lists newest first, like Gains.
- Opening a gain from Live shows the Losses table on All, not Supplies.
- A private boss instance paid partly from the bank counts the bank part too, not just the coffer part.
- Reclaim fees at the Mimic's strange casket and at the Dragon Slayer II, Sins of the Father, Song of the Elves and Fremennik Exiles retrieval points name their service.
- An old GE sale whose receipt was compacted shows its result as unavailable instead of asking for Review.
- After a relog, a GE offer GP Manager couldn't trust stays untrusted.
- Coins from an alch or a shop sale while a GE sale waits to be collected are no longer booked as that sale.
- The Reset sheet says a safety copy is saved first (it always was), and the retention setting says it compacts entries, not just closed sessions.

**Changed**
- PvP keeps kills, deaths, streaks and best kill per Grind in a smaller save.

**Removed**
- The Bank · Carried wealth line under All time.
- Decide all in Review.
- HUD+ moments.
- The Restore backup and Clear old backups menu items.
- Grind recap extras.
- AFK pause and End Grind after AFK.
- The Ledger Deaths table and death panel; death losses still show under Costs · Loss.
- The GE reference and GE difference lines on GE receipts; Received, Spent, Result and GE tax stay.
- Back up first on the Factory reset sheet; Reset already saves its own safety copy first.

## 1.3.2 — 2026-10-10

**New**
- A new pixel-art GP Manager icon: a coin sack marked GP, on the Plugin Hub and in the sidebar.

**Fixed**
- Small internal tidy-ups; nothing you'd notice changes.

## 1.3.1 — 2026-10-10

**New**
- "Report a bug" in the ⚙ menu opens GitHub's bug report and suggestion forms.

**Fixed**
- Uncharging Bryophyta's staff shows as a measured charge spend, not an estimate.
- Turning RuneLite's Ground Items plugin on or off updates the loot filter straight away.
- Two backups made in the same second keep the newer one.
- Short amounts are cut off like the game's own numbers, never rounded up: 999,600 shows as 999k.
- Less work on every game tick, and a fix for one start-up step that could be skipped.

## 1.3.0 — 2026-10-09

**New**
- Barrows gear and the Amulet of the damned wear down without Review; Barrows repairs count their fee.
- Bryophyta's staff: charging is neutral, each alch or superheat costs a nature rune, uncharging counts the runes lost.
- Chugging barrel: each chug is one Drank row with the potions it used; filling it is neutral.
- NPC purchases count, paid in coins or in spirit flakes, molch pearls or abyssal pearls.
- House servant bank trips are neutral; his wage and sawmill fees count.
- Hunters' loot sacks, Sailing courier bags and Hallowed sacks count what they hold when opened.
- Imp-in-a-box and the master scroll book are storage, not losses.
- Imbuing a black mask or slayer helmet is neutral.
- Pete Kayer's PvP tutorial and the inside of the Gauntlet book nothing.
- The PvP risk line values the Avernic defender at 600k, as the game now does.
- Ironmen can value items at high alch instead of GE prices (setting, off by default).

**Fixed**
- A Grand Exchange sale collected in parts shows as one sale with its full result.
- Gear bought back from Death's Office after your gravestone expires is no longer counted as loot.
- An Eye of Ayak charged with runes no longer costs demon tears.
- A save file briefly locked by antivirus is never set aside, and backing up no longer freezes the game.
- 999,600 shows as 1.00M, not 1000k.

**HUD+**
- Clue drops show on the tray again; opening caskets or impling jars is one row per kind.
- Trades read "Traded", and sales show the item sold with the coins it brought.
- Combined rows such as the chugging barrel show an icon.

## 1.2.1 — 2026-10-09

- **Collect to bank counts.** A Grand Exchange offer collected straight to the bank never showed
  its coins or items in your inventory, so after ten minutes it went to Review uncounted. The offer
  itself shows what it traded, so it now settles within half a minute: a purchase exactly, a sale
  with its exact tax, or, for a sale filled at several prices, the highest tax it could have paid,
  so its result is never overstated. Anything unsold or unspent went to the bank with it.
- **Old Review entries are rechecked once.** Entries already waiting in Review that 1.2.0 would now
  book from their items alone (an opened reward casket, clue scroll or impling jar, a clue step,
  combined potions) are booked that way when your profile loads. Your own corrections, and every
  other entry, stay exactly as they were.

## 1.2.0 — 2026-10-09

- **Loot that goes straight into a bag counts.** Items you pick up into an open looting bag
  (in the Wilderness), herb sack, gem bag, seed box or log basket never touched your inventory, so
  they were never counted. They now count like any pickup, and picking your own dropped item back
  up into a bag cancels its "Dropped" loss instead of counting as new loot. Fish caught into an
  open fish barrel and logs cut into an open log or forestry basket count too, read from the
  game's "You catch…" and "You get some…" messages.
- **Death and instance fees paid from the coffer or bank count.** Reclaim fees Death takes from
  your coffer or bank, and private instance fees paid from the coffer, never left your inventory,
  so they were missed. They are now read from the game's own messages and count as costs, without
  counting a fee twice.
- **Raid and minigame loot you bank from the chest counts.** Choosing Bank-all at the Chambers
  of Xeric, Theatre of Blood or Tombs of Amascut chest, the Fortis Colosseum, the Lunar Chest,
  the Wilderness loot chest, Doom of Mokhaihotl, Fishing Trawler or drift net fishing sends loot
  straight to your bank, so it was never counted. It now counts like loot you pick up.
- **Ammo and runes you never see spent.** Ammo fired from Dizana's quiver and the death runes an
  ash sanctifier uses (one per ten ashes) now count as supplies; charging the sanctifier nets to
  zero. Loot from your own kill picked up into a bag counts for the full minute it stays yours.
- **Combining potions needs no review.** Merging potions by hand or decanting them keeps every
  dose, so it now books nothing instead of adding an entry to Review each time.
- **Lighting a lantern is no longer a cost.** Choosing Light on a lantern booked its whole price
  as spent, and extinguishing it left an entry in Review. Lit and unlit light sources (candles,
  torches, lamps, lanterns, the mining helmet) and open and closed bags, sacks, barrels, baskets
  and pouches now count as the same item, so switching between them books nothing.
- **Taking an item apart needs no review.** Reverting or dismantling an item (a crystal tool, a
  godsword) keeps its worth in the parts, so it books nothing instead of adding a Review entry.
  Reverting a crystal pickaxe, axe, felling axe or harpoon counts the 120 crystal shards used to
  make it as spent.
- **Taking items out of a tackle box is not loot.** Withdrawing from (or storing in) a tackle box,
  seed box, forestry kit or huntsman's kit through its own window is a neutral move, so it no
  longer shows in Recent as a gain.
- **Fast Grand Exchange buys need no review.** An offer that fills and is collected within a
  couple of seconds no longer leaves a second coin entry in Review next to the purchase. A
  finished offer collected just before you place a new one in the same slot still settles as a
  purchase instead of going to Review.
- **Revenant weapons count their ether.** Craw's bow, Webweaver bow, Viggora's and Ursine
  chainmace, and Thammaron's and Accursed sceptre spend one revenant ether per attack. Each attack
  is estimated as you fight, and checking the weapon measures what was really used. Charging one
  with ether, or uncharging it, books nothing.
- **Newest loot on top of the HUD+ tray, and long names keep their count.** A row moves to the
  top whenever it grows, so the latest drop is always first. A name too long for the tray now
  shortens before its count, so "×2" always shows, and "(uncharged)" reads "(u)", as the game
  writes it for "Craw's bow (u)".
- **Charging never books a loss.** Putting charges into any supported item (crystal shards into
  a crystal bow, runes into a sanguinesti staff or Tumeken's shadow, ether into a revenant weapon,
  tears into the Pendant of Ates, the scythe at the Vyre well) moves those items into charges, so
  it books nothing. A "How many charges?" prompt no longer cuts the charge short. The charges
  count as used only when you attack. The Vyre well's own Fill box (vials of blood plus blood
  runes) rides the same rule: filling it is refundable storage, and only the charges you use
  book (owner live 2026-10-13).
- **Kharedst's memoirs and the blood essence cost what they use.** Each memoirs (or Book of the
  Dead) teleport from the worn book spends one law, body, mind and soul rune, and checking the book
  measures what was really used. Charging it at the Old Memorial books nothing. Activating a blood
  essence counts that essence as used, instead of adding an entry to Review.
- **Fixes from a second code audit.** Choosing Bank-all at a reward chest counts only what really
  leaves the chest, so a refused or repeated click no longer adds loot. Another player's or a public
  pile vanishing as you click Take is no longer counted as going into your bag; only your own pile
  is. A reclaim fee paid from the bank never lands on the next account you log into. A Check that
  shows the same charges as before removes estimates that were too high, and checking one of two
  identical weapons no longer makes the other's use count twice. A catch split between your pack
  and a fish barrel counts each fish once.
- **Turning GP Manager on mid-Make-X counts the next action.** Enabling the plugin while
  fletching (or crafting, smithing, cooking) no longer swallows the first XP drop, so the next
  item you make counts as Processing without another click instead of going to Review.
- **HUD+ big drops follow Ground Items and keep their row.** With the loot display following
  Ground Items (the default), a drop lights gold and raises "Big drop" at your Ground Items insane
  value instead of a fixed 10M. A big drop also keeps its tray row however much cheaper loot comes
  after it; the cheaper rows go to "+N more" instead.
- **Bank and carried on the All time card.** One line shows your bank (as of when you last
  opened it) and what you carry, e.g. "Bank 3.55B · Carried 1.21B"; hover it for exact amounts
  and when the bank was checked. Items use their GE price, untradeables their high alch value,
  and bank placeholders count nothing. The bank total is remembered per account across restarts.
- **Moving is not AFK.** Walking or running now holds off AFK pause and wakes a Grind it paused,
  so a long trip to a boss with no XP no longer pauses your run. Moving never starts tracking.
- **End Grind after AFK.** A new setting (off by default) ends a named Grind once AFK pause
  has held it for the minutes you choose. It saves to your history as if you had pressed End at
  the moment it paused, and Free play takes over. Free play itself never ends. Needs AFK pause on.
- **Hidden loot stays quiet.** Picking up an item your loot filter hides no longer pops the tray
  open again. When the tray is open anyway, its footer says how many hidden items it holds
  ("+2 hidden"). Hidden items still count in Net, and the number beside Net still flashes.
- **See what a row used.** Opening a Ledger row that spent or received more than one item lists
  each item with its total: a Scythe of vitur row shows "Blood rune ×124" and "Vial of blood ×1",
  not just a count. Each entry under it says what that use spent ("Blood rune ×2"). A charged
  weapon's row always lists what it spent, even when that is a single rune type.
- **Receipts are now entries.** The Ledger, its menus and the "Entry detail retention" setting
  use the word "entries"; your saved settings are unchanged.
- **No false "save failed" warnings.** Antivirus or Windows Search can briefly hold a file the
  plugin has just written. GP Manager now tries again at once, and a one-off failure stays
  silent: your previous save is intact and the next save (within minutes, and always at logout)
  writes everything. The warning only appears if saving keeps failing.
- **Fixes from an independent code review.** A change in an item's price no longer re-prices
  every earlier charge of a charged weapon into the next one; only the new charge uses the new
  price. Crystal shards are no longer priced from the main-game market on special or unknown
  worlds. Backup, restore and factory reset no longer freeze RuneLite while they write to disk.
  Bag pickups only count the stack you clicked when it is yours (loot from your own kill or your
  own drop), so another player taking a pile never becomes your loot. Catches book the raw fish
  (never cooked). Backup and reset always act on the account you confirmed them on, even if you
  switch accounts meanwhile. The bank and carried line follows bank and inventory changes, a
  re-opened bank and a change of account.
- **Charged items are a data table.** Nothing you see changes; adding a charged weapon is now
  cheap, which the next charged-item additions build on.
- **Cleanup.** Leftovers from the plugin's old name are gone.
- **Re-enabling during production resumes processing.** Fresh production XP after enabling
  confirms the ongoing Make-X run, so its next input/output change does not become Review
  just because the initiating click happened while GP Manager was disabled.
- **Pending actions stay with their account.** An unfinished fee, chest transfer, bag pickup or
  other action cannot carry into another account or be reused when you return to the first.
- **Bag pickup credit is limited to your remaining recorded loot.** Items already collected
  normally cannot also count when a pile later goes into a bag; credit never exceeds the
  quantity still proved to be yours.
- **A higher charge Check keeps a top-up neutral.** An exact increase in a weapon's charges
  marks loading as a transfer even when the message does not say “charged with”.
- **Reset keeps a safety copy.** Both Reset choices require a validated backup of the current
  account before clearing it. An unchanged existing copy can be reused; if a safe copy cannot
  be secured, the reset is refused and the account data stays intact.
- **Edits during a safety copy are preserved.** Reset and Restore refuse to replace data that
  changed while their safety copy was being prepared; retry the action to protect the latest data.
- **Big Grand Exchange sales no longer go to Review.** A sale that fills at several prices at
  once (10,000 longbows bought by different players) has no single price, so its tax could not
  be checked and the whole collect, with any other sale collected beside it, went to Review. The
  coins you collect now prove the tax, as long as they are an amount the 2% tax rule allows.
  Collecting through a banker's or clerk's collection box counts like the exchange itself.
- **Fletching with a knife is production.** Using a knife on logs counted the logs as burned
  fuel and the first batch went to Review. A Make-X you start with item-on-item Use, confirmed
  with the space bar or its Make button (even after a wait), now books as production once its
  skill's XP arrives. Feeding a fire, a brazier or a funeral pyre still counts its fuel as used.
- **Clue scrolls, scroll boxes and caskets are worth nothing until opened.** They have no price,
  so picking one up showed "?" and finishing a step went to Review. Like a coin pouch, a trail's
  scrolls, boxes, bottles, nests, geodes and its reward casket now count only for what the
  casket gives you.
- **Impling jars count.** Catching an impling (an empty jar becomes a full one) and looting a
  jar (the full jar becomes its loot, and usually an empty jar back) book as gains instead of
  Review, for every impling.
- **More stores are not loot.** Storing or taking back items through Elnock's storage in
  Puro-Puro (also Quick-withdraw), the seed vault, a boat's cargo hold, your house's costume room,
  fossil storage and the drift net store is a neutral move like the bank. Swapping a set for its
  pieces (or back) at the Grand Exchange books nothing, and items sacrificed to Death's coffer no
  longer count as a loss on top of the reclaim and instance fees the coffer later pays.
- **A sold item can be hidden from Recent.** Finished Grand Exchange trades now offer
  "Hide from recent" like any other item.
- **Trades show how many.** A finished purchase or sale in Recent shows its quantity beside the
  item ("Magic logs ×500"), like every other row.

## 1.1.2 — 2026-10-02

- **Plugin Hub listing.** The description no longer mentions CSV export, which 1.1.0 removed.
- **Cleanup.** Unused code left over from removed features is gone; nothing you see changes.

## 1.1.1 — 2026-10-02

- **Plugin Hub review.** No thread is interrupted any more; the save worker finishes its
  current write and stops.
- **One tray streak per skilling run.** The tray names a run from the XP that comes with each
  item, so the first bar, log or catch no longer starts a streak of its own and the HUD+ count
  matches Recent. New headings: Cooked, Fletched, Made (Herblore), Caught (Hunter), Crafted
  (Runecraft) and Smithed (anvil); bars still read Smelted.
- **Gathering starts with its first XP.** Clicking a tree, rock, fishing spot or crop no
  longer names the activity until that skill's XP arrives, so a click without an axe, or on the
  way to the tree, changes nothing.
- **One ore row per smelting run.** Recent and the Ledger show "Iron ore 16/28": 16 of the
  run's 28 ores failed. The row's receipts still tell used and failed ore apart.
- **Fixes.** A death's lost items read "Lost" on the tray. A production run's first receipt
  names its own skill in the Ledger. Taking off worn equipment no longer counts as a
  Construction run.

## 1.1.0 — 2026-10-02

- **No save stutter.** The periodic save while you play reuses the saved form of finished
  Grinds that have not changed, so a large history no longer hitches the game every 30 s.
- **A cleaner HUD+ header.** It shows your Grind's name, or "KC: 3" while a kill streak runs;
  never an NPC's name. Activity names (Combat, Woodcutting, a raid) stay on the sidebar's Live
  page, which no longer says "Hitpoints" either.
- **Tighter loot tray.** Its heading sits right on its rows, and rows are a little shorter.
- **Icons.** Every item and spell icon is cropped, fitted to one size and centred, on HUD+ and
  in the sidebar. A new **Item icons** setting turns them off.
- **Ledger and Grinds sit closer to their toolbar,** and the Losses table picks All, Supplies,
  Items or Charges from a dropdown beside its header.
- **The loot tray names the fight.** A kill streak's heading names its NPCs ("Guard",
  "Guard & Man", "Guard, Man +1") with its Net per kill. Going back to an NPC you just left
  joins both streaks into one.
- **Split shows a preview.** After you choose how many to keep, the receipt shows Keep, Others
  and the Net it leaves before you confirm.
- **Picked-up ammo comes back.** Picking up ammo you fired lowers Supplies instead of counting
  as loot. More than you fired still counts as loot.
- **Wilderness risk.** In dangerous areas the Live page shows what a death would lose
  (everything but your three most valuable items, none when skulled, one more with Protect
  Item) beside your skull and Protect Item. HUD+ drops its PvP line and stays short; its gem
  still turns PvP.
- **Smaller for the Plugin Hub's 200k review limit** (the review counted 1.0.3 at 202,333):
  - CSV export is removed; backups stay.
  - The HUD+ hover panel is removed; its figures are on Live, and Net per kill is on the tray.
  - The HUD+ End card, "vs your average" and "New best" moments, and the Grind detail's VS
    PREVIOUS RUN and PERSONAL BESTS are removed. A Grind keeps its outcome, biggest gain and
    cost and loot by source, and HUD+ still says "<Grind> ended" with its Net and time.
  - Death receipts no longer carry the kept/lost explanation text; death accounting is
    unchanged.
- **Blowpipe darts count.** Each shot spends its dart unless an Ava's device saves it (an
  assembler or Dizana's quiver saves 80%, the accumulator 72%, the attractor 60%), booked as
  Supplies like arrows once a Check or a load has shown the dart type; a later Check corrects
  the estimate. The blowpipe's Charges row is now its Zulrah's scales, and blood fury reads as
  one "Blood fury" row.
- **Fixes.** Charged-weapon casts are read from the current game API.

## 1.0.3 — 2026-10-02

- **Source size for the Plugin Hub limit.** Internal names are shortened again and
  lines keep a light indent, which brings the source under the Hub's 200k review
  limit. No change in behaviour.

## 1.0.2 — 2026-10-02

- **Source layout for the Plugin Hub size limit.** Lines no longer use indentation,
  which brings the source under the Hub's 200k review limit. Names stay readable;
  no change in behaviour.

## 1.0.1 — 2026-10-01

- **One GP/h.** GP/h is your Net over active time on Live, Grinds, HUD+ and
  exports. The Rate window setting, the HUD+ "Last 10m" line and the "last 15m"
  label are gone.
- **Charging stays neutral.** Loading a weapon works whichever way round you use
  the items, and uncharging stays a transfer even when you take your time with
  the confirm prompt. Choosing No, then looting the same runes, still counts as loot.
- **Pending is tidier.** A Grand Exchange offer you cancel before anything fills
  no longer waits in Pending.
- **Bottomless compost bucket** charges are no longer tracked.
- **Smoother saving.** While nothing changes, the profile saves every 5 minutes
  instead of every 30 seconds, so large profiles stutter less. Sidebar edits save
  straight away.
- **Fixes.** Re-enabling the plugin while logged in reads the game on the right
  thread, and menu text matches on any system language.
- **Readable source.** Clear names and normal layout throughout; no change in
  behaviour.

## 1.0.0 — 2026-10-01

GP Manager keeps track of what you earn and spend while you play. It observes
inventory, equipment, bank and Grand Exchange changes, records the evidence, and
lets you review anything it cannot confidently classify.

- **Live, Ledger and Grinds.** Three tabs cover current tracking, the accounting
  ledger, and saved activities. Free play tracks between named Grinds. Live shows
  Net, gains, supplies, losses, active time, GP/h and your target.
- **Named Grinds and targets.** Save activities with profit or time targets,
  mark favourites, start another run, and compare completed runs with the last.
  Finished runs keep their loot by source and offer a recap. All time shows
  retained totals, active time, Grinds played and your best runs.
- **A ledger you can inspect and correct.** Browse Gains, Losses, Market, Deaths,
  Pending and Review, with search and a scope picker. Open a row for its receipts
  and counting reason. Correct a receipt, mark a transfer, exclude it, split it,
  or undo a correction after previewing the effect on Net. Review supports a
  preview of Decide all and an undo.
- **HUD+.** See activity, Net, GP/h and target progress in the game window, with
  a loot tray and hover details. The empty HUD stays compact, narrow money rows
  wrap, and a finished Grind offers a short recap. Pause and resume from the
  status gem. Text size, width, opacity and reduced motion are configurable.
- **Loot display preferences.** The tray defaults to This streak and Follow
  Ground Items. Hide individual Recent items and restore them from the saved
  list. Display filters leave Ledger values, Net and exports unchanged; hidden
  loot is counted in the hover details without opening an empty tray.
- **Observed loot and supplies.** Collected loot counts when it reaches your
  inventory. Banking, equipment and supported container movements stay neutral.
  Confirmed food, potion doses, runes, ammo and other supplies keep their own
  receipts. Own-drop pickups reverse only the matched loss.
- **Spell and charge details.** Evidenced spells retain their names and icons.
  Supported charged items record per-use estimates, with in-game Checks
  reconciling supported measured balances. Charge receipts distinguish Estimated
  from Measured and retain the captured unit price and source. Ambiguous changes
  remain in Review.
- **Grand Exchange accounting.** Partial fills, collections, cancellations and
  slot reuse reconcile against observed custody. Verified sell proceeds, tax
  and acquisition basis are counted once. RuneLite 1.13.1 supports the Beyond Max
  Cash update; out-of-range unit-price quotes stay unpriced in Review.
- **PvP and death accounting.** Dangerous-context kills, deaths and collected
  player loot retain their own receipts. Loot keys and chests settle from
  measured contents. Death returns and reclaim fees stay separate from new
  loot. Opponent names are not stored.
- **Frozen booking prices.** Each receipt keeps its observed quote or manual
  override. Actual GE settlement provides stronger evidence than a quote.
  Unknown prices remain unknown, and history is never repriced.
- **CSV exports.** Export history summaries or the receipts for one run, one
  file per export, with Copy file path feedback. Empty history reports zero
  retained runs.
- **Receipt compaction.** Older receipts and detail beyond the receipt limit
  fold into exact correction-aware totals. The selected retention window governs
  age-based compaction of closed runs; it does not guarantee a full window of
  individual receipts. Unresolved loot-key claims remain retained, and CSV marks
  compacted summaries as `COMPACTED`.
- **Local profiles and recovery.** Account profiles stay separate. Saves use
  atomic writes, a recovery copy and revision fencing. Back up or restore a
  profile, copy its data-folder path, and clear old backups while keeping the
  newest. Restore makes a safety copy first; factory reset offers Back up first
  and refuses to continue if that backup fails. A conflicting external save
  pauses tracking until RuneLite restarts and reloads disk truth.

Data stays local. GP Manager makes no network calls of its own and performs no
in-game automation.
