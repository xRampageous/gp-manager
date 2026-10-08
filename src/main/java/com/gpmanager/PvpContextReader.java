package com.gpmanager;
import lombok.RequiredArgsConstructor;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.VarbitID;
/**
* Samples the local player's PvP context (NONE / SAFE / DANGEROUS) on the game tick and on
* demand for death/loot disposition. Aj only; it never books item flows.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PvpContextReader {
final Client client;
final GpManagerConfig config;
Dh context = Dh.NONE;
/** The context sampled on the latest tick (or by {@link #sample}); presentation and disposition input only. */
Dh context() {
 return context;
}

/** Re-reads the client signals now (death/loot events may precede the next tick sample). */
Dh sample(Player /* localPlayer */a) {
 int /* regionId */b = a == null || a.getWorldLocation() == null
 ? 0 : a.getWorldLocation().getRegionID();
 context = Dh.classify(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1,
 client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1, client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) == 1,
 client.getVarbitValue(VarbitID.THIS_IS_A_PVP_ARENA_WORLD) == 1, b);
 return context;
}

/** Game tick: re-classify the PvP context; disposition input only. */
void onGameTick(Player /* localPlayer */a) {
 sample(a);
}
}
