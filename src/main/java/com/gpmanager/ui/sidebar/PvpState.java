package com.gpmanager;
import lombok.AllArgsConstructor;
/**
* Client-side PvP facts the plugin observes on its tick (Wilderness / PvP world, skull,
* Protect Item). Presentation only; nothing here touches accounting.
*/
@AllArgsConstructor
class PvpState {
static final PvpState NONE = new PvpState(false, false, false);
final boolean pvpPossible;
final boolean skulled;
final boolean protectItem;
}
