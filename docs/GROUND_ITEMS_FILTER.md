# Ground Items loot presentation filter

GP Manager applies display preferences to **gain rows** in HUD+, Live lists and
Ledger. Every collected gain still contributes to accounting totals and exports.
Costs remain visible. This adapter does not book money or reprice receipts.

| Setting | Default | Behavior |
| --- | --- | --- |
| Display loot filter | **Follow Ground Items** | Follow enabled Ground Items' explicit highlighted/hidden lists and Show highlighted only preference |
| All items | Optional mode | Ignore Ground Items lists |
| Highlighted list only | Optional mode | Show matching highlights unless a more specific hidden rule wins |
| Minimum displayed loot value | 0 | Apply a separate minimum unit GE value to gain presentation; unpriced gains remain visible |

When Ground Items is disabled or its configuration cannot be read, list filtering
shows all gains. GP Manager reads preferences using RuneLite's configuration and
plugin APIs; it does not reflect into Ground Items internals.

The adapter handles explicit names, wildcard names and quantity conditions. Exact
name rules take precedence over wildcard rules; at the same specificity highlighted
rules take precedence. Matching uses the observed flow quantity. Ground Items'
price thresholds, value-tier colors and other rendering rules are not reproduced;
ambiguous price/value rules stay visible. No color-reuse option exists.

Filtered loot adds a small **Hidden loot: #** count to the HUD hover folio, even
after the tray folds. It never opens an empty tray. GE buys/sells, losses and
recovered items are excluded from that loot count. Switching display preferences cannot
change historical values, Review decisions or exported totals. The defaults are
covered through the real RuneLite configuration proxy, including preservation of
an explicitly saved Whole session tray choice.

## Hide from Recent

Right-click a completed item row in Live Recent and choose **Hide from recent**.
This saves its displayed name in **Hidden Recent items** in GP Manager settings.
Remove the name there to show it again. The same explicit-name, wildcard and
quantity syntax is supported. This preference affects Recent only: it does not
hide Ledger or HUD rows, alter Ground Items settings, change Net, or remove
receipts from exports. Open GE offers remain discoverable.

A confirmed drop stays visible as **Dropped**, including when the item's gains
are hidden by Ground Items. Recovering that own drop reverses its matched loss;
extra picked-up quantities retain their own accounting.
