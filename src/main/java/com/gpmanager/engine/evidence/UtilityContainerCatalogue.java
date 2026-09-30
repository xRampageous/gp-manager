package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import java.util.regex.Pattern;
/**
* Special bags/sacks/barrels/kits/pouches. Store/retrieve is ownership-neutral
* ({@link Kind}); contents valuation follows snapshots — never invent gain/loss.
*/
class UtilityContainerCatalogue {
enum Kind {
STORE,
WITHDRAW,
EMPTY_TO_BANK,
EMPTY_TO_INVENTORY,
CALIBRATE_CHECK
}
/** Special container names, most specific first: the first one a name contains wins. */
static final List<String> NAMES = Ak.names("d5");
static final Pattern ESSENCE_POUCH = Ak.pattern("azb");
/** The container family used in the custody note ({@code herb_sack}), or null for other items. */
static String sv(String itemName) {
String lower = Ag.norm(itemName);
if (lower != null && ESSENCE_POUCH.matcher(lower).find()) {
return "essence_pouch";
}
for (String name : NAMES) {
if (lower != null && lower.contains(name)) {
return name.replace("'", "").replace(' ', '_').replace('-', '_');
}
}
return null;
}
/**
* Classify a menu option on a known container. Deposit-box Empty → bank is
* {@link Kind#EMPTY_TO_BANK} (ownership-neutral).
*/
static Kind oq(String option, boolean depositBoxOpen) {
String op = Ag.norm(option) == null ? "" : Ag.norm(option);
return "check".equals(op) ? Kind.CALIBRATE_CHECK
: op.startsWith("empty") ? depositBoxOpen ? Kind.EMPTY_TO_BANK
: Kind.EMPTY_TO_INVENTORY
: op.startsWith("withdraw") || op.startsWith("remove") ? Kind.WITHDRAW
: Kind.STORE;
}
/** True when evidence must stay ownership-neutral (never invent Net). */
static boolean xg(Kind kind) {
return kind != null && kind != Kind.CALIBRATE_CHECK;
}
/**
* True when the menu option is a container custody action this catalogue can classify.
* Store/withdraw/empty/fill are reversible custody movement, never revenue or cost.
*/
static boolean wp(String option) {
String op = Ag.norm(option);
return op != null && (op.matches(msg("bh"))
|| op.startsWith("empty") || op.startsWith("withdraw") || op.startsWith("remove"));
}
}
