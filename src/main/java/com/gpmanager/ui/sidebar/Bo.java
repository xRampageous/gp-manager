package com.gpmanager;
import lombok.AllArgsConstructor;
/**
* Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull,
* Protect Item). Presentation only; nothing here touches accounting.
*/
@AllArgsConstructor
class Bo {
static final Bo NONE = new Bo(false, false, false);
final boolean pvpPossible;
final boolean skulled;
final boolean protectItem;
}
