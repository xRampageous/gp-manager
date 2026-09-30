package com.gpmanager;
import java.util.*;
import lombok.*;
/**
* Wiki-sourced key/chest pairs used for deferred-claim valuation and provenance.
* Chest names and locations follow the OSRS Wiki pages for the
* <a href="https://oldschool.runescape.wiki/w/Crystal_chest">Crystal chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Elf_crystal_chest">Elven Crystal Chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Larran%27s_small_chest">Larran's small chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Larran%27s_big_chest">Larran's big chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Ogre_Coffin">Ogre coffin</a>,
* <a href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Sinister_chest">Sinister chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Grubby_chest">Grubby chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Brimstone_chest">Brimstone chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Moons_rewards">Moon chest</a>, and
* <a href="https://oldschool.runescape.wiki/w/Category%3AShades_of_Mort%27ton_%28minigame%29">Shades
* of Mort'ton chest/key variants</a> (for example,
* <a href="https://oldschool.runescape.wiki/w/Steel_Chest_%28brown%29">Steel Chest (brown)</a>).
* Key names/tradeability follow the item pages for
* <a href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>,
* <a href="https://oldschool.runescape.wiki/w/Enhanced_crystal_key">Enhanced crystal key</a>,
* <a href="https://oldschool.runescape.wiki/w/Larran%27s_key">Larran's key</a>,
* <a href="https://oldschool.runescape.wiki/w/Ogre_coffin_key">Ogre coffin key</a>,
* <a href="https://oldschool.runescape.wiki/w/Muddy_key">Muddy key</a>,
* <a href="https://oldschool.runescape.wiki/w/Sinister_key">Sinister key</a>,
* <a href="https://oldschool.runescape.wiki/w/Grubby_key">Grubby key</a>,
* <a href="https://oldschool.runescape.wiki/w/Brimstone_key">Brimstone key</a>,
* <a href="https://oldschool.runescape.wiki/w/Moon_key">Moon key</a>, and
* <a href="https://oldschool.runescape.wiki/w/Shade_key">Shade key variants</a> and the
* <a href="https://oldschool.runescape.wiki/w/Bronze_key_crimson">Bronze key crimson</a>,
* <a href="https://oldschool.runescape.wiki/w/Steel_key_brown">Steel key brown</a>,
* <a href="https://oldschool.runescape.wiki/w/Black_key_black">Black key black</a>,
* <a href="https://oldschool.runescape.wiki/w/Silver_key_red">Silver key red</a>, and
* <a href="https://oldschool.runescape.wiki/w/Gold_key_purple">Gold key purple</a> pages.
* Tradeable keys keep their market opportunity cost. Untradeable keys represent an
* unclaimed reward and are deliberately held at zero until contents enter inventory.
* The Wiki explicitly documents consumption for Crystal, Muddy, Sinister and Grubby
* keys (<a href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>,
* <a href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_sinister_chests">Sinister chest</a>,
* <a href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_grubby_chests">Grubby chest</a>).
* The Enhanced crystal, Larran, Ogre coffin, Brimstone, Moon and Shade pages describe
* using their keys but do not explicitly state that chest use consumes them. Every
* claim match therefore still requires a measured inventory key loss; a click only
* disambiguates a chest, and live acceptance must confirm consumption for those families.
*/
class KeyChestCatalogue {
static final List<Entry> ENTRIES = new ArrayList<>();
static {
for (String[] row : Ak.rows("d11")) {
ENTRIES.add(new Entry(Integer.parseInt(row[0]), row[1], Boolean.parseBoolean(row[2])));
}
}
/** Catalogue entries for a key item; Larran's key has both chest variants. */
static List<Entry> rs(int keyItemId) {
var entries = new ArrayList<Entry>();
for (Entry entry : ENTRIES) {
if (entry.keyItemId == keyItemId) {
entries.add(entry);
}
}
return entries;
}
/** First known chest association for an item, or {@code null} when not catalogued. */
static Entry rq(int keyItemId) {
List<Entry> entries = rs(keyItemId);
return entries.isEmpty() ? null : entries.get(0);
}
/** Match an observed object target that contains one exact catalogue chest label. */
static Entry rp(String target) {
if (Ag.blank(target)) {
return null;
}
String asw = normalize(target);
Entry best = null;
for (Entry entry : ENTRIES) {
String name = normalize(entry.getChestName());
if (asw.contains(name)
&& (best == null || name.length() > normalize(best.getChestName()).length())) {
best = entry;
}
}
return best;
}
/** Arm a short-lived chest label for measured key-loss disambiguation only. */
static Entry rv(String option, String target) {
String action = option == null ? "" : normalize(option);
if (!(action.equals("open") || action.startsWith("open ")
|| action.equals("unlock") || action.startsWith("unlock ")
|| action.equals("search") || action.startsWith("search "))) {
return null;
}
return rp(target);
}
static boolean wc(int keyItemId) {
return rq(keyItemId) != null;
}
/** Deferred claims have no held value and are excluded from high-alchemy fallback. */
static boolean wg(int keyItemId) {
Entry entry = rq(keyItemId);
return entry != null && !entry.isTradeable();
}
static List<Entry> entries() {
return Collections.unmodifiableList(ENTRIES);
}
static String normalize(String value) {
return value.trim().replace('\u2019', '\'').toLowerCase(Locale.ROOT);
}
/** Immutable key-to-chest information for accounting and presentation. */
@AllArgsConstructor
static class Entry {
@Getter
final int keyItemId;
@Getter
final String chestName;
@Getter
final boolean tradeable;
}
}
