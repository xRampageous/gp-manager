# Activity evidence and display coverage

Current candidate: 1.0.0. Activity labels explain observed work; they do not create
money. Accounting uses measured changes and source/context evidence, independently
of display labels. There is no configuration-schema migration mechanism.

| Evidence | Activity/display use | Accounting boundary |
| --- | --- | --- |
| Fresh NPC interaction | Current NPC title with short release grace; curated raids/minigames retain their activity name | An interaction alone cannot book loot or supplies |
| NPC loot events | Source identity and observed reward presentation | Only matched collected inventory gains count; separate sources keep their receipts |
| Skill XP and gather hints | Passive skill/activity inference | XP, menu clicks and animations never provide item quantities or prices |
| Confirmed item actions | Consumption or production explanation | Stable removal/context supplies the quantity; ambiguous mixed changes remain Review |
| GE observations/Collect | Trading label and custody explanation | Fills, frozen basis, tax and observed collection determine settlement; ambiguous offer subsets stay Review |
| Player loot/local death | PKing or Death reclaim grouping in supported contexts | Safe/unknown contexts fail closed; stale prior-login combat evidence is cleared |
| Key/chest evidence | Pending claim and chest source | Exact key/content evidence settles a deferred claim once |
| Charge Check | Target identity and measurement correlation | Exact same-target decreases book; first read seeds a baseline, top-ups are neutral |

HUD+ and Live share `ActivityLabel`: fresh supported NPC target, session activity,
named Grind, then blank quiet Free play. Generic placeholders, scenery clicks and
opponent names do not title the HUD. Opponent identities are never stored.

HUD item rows default to This streak and Follow Ground Items. Display filtering,
row coalescing, abbreviations and hover details cannot alter transaction type,
booked value, counted state or totals. Unknown evidence remains unavailable.

Offline coverage is summarized in [supported scenarios](SUPPORTED_SCENARIOS.md).
Actual callback order, UI widths, Check delivery and long-session observations
remain in [the live matrix](LIVE_ACCEPTANCE_MATRIX.md).
