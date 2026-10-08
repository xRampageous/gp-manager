package com.gpmanager;
import lombok.RequiredArgsConstructor;
import javax.inject.*;
import net.runelite.api.*;
/**
* Client-thread adapter for character idle (no animation, no target for the idle delay).
* Presentation-only: it never pauses the engine or touches GP/hr; session idle pause is the
* engine's own idle timeout.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CharacterIdleTracker {
final Client client;
final CharacterIdleModel model;
void clear() {
 model.clear();
}

/** Soft-busy across Make-X / process XP gaps. */
void abn() {
 model.abn(Ae.now());
}

/** @return true once when the character newly becomes idle */
boolean onGameTick() {
 Player /* local */a = client.getLocalPlayer();
 boolean /* animating */b = a != null && a.getAnimation() != -1;
 boolean /* interacting */c = a != null && a.getInteracting() != null;
 return model.tick(b, c, Ae.now());
}
}
