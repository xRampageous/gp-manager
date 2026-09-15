package com.gpmanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import net.runelite.api.SkullIcon;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.ui.HudPlusHeaderLabel;
import com.gpmanager.ui.PrayerAltarTitleEvidence;
import com.gpmanager.ui.ProcessSkillSignals;
import com.gpmanager.ui.ProcessTitleHold;
import com.gpmanager.ui.TrackingDisplaySnapshot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plugin-level failed-switch ingestion coverage via {@link GameplayIngestionFacade},
 * the same gate {@link GpManagerPlugin} uses for login, container, loot, and tick.
 *
 * <p>Coordinator identity tests alone do not prove plugin callbacks refuse the new
 * account's activity — these cases lock both the gate semantics and the wiring.
 */
public class GpManagerPluginIngestionTest
{
    @Test
    public void acceptedRewardEncounterBridgeStoresMetadataWithoutCreatingReceipt()
    {
        GpManagerEngine engine = new GpManagerEngine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() { });
        engine.startNewSession("Goblin", 1_000L);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setEncounterObserver(engine::recordObservedEncounter);

        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin", "stable-goblin-kill",
            Collections.singletonList(new RewardItem(526, "Bones", 2L, 70L, true,
                ItemPriceSource.GRAND_EXCHANGE)), 2_000L, true);

        assertEquals(1L, engine.getActiveSession().getEncounterTotals().getTotalEncounterCount());
        assertEquals(70L, engine.getActiveSession().getEncounterTotals().getTotalLootValue());
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
    }

    @Test
    public void refusedSwitchBlocksLoginContainerLootAndTickGates()
    {
        // After a refused identity switch the plugin sees identityReady=false while
        // still logged in — every accounting entry point must refuse.
        GameplayIngestionFacade held = new GameplayIngestionFacade(() -> true, () -> false);

        assertFalse("login init must refuse", held.canInitializeAfterLogin());
        assertFalse("container must refuse", held.canProcessItemContainer());
        assertFalse("loot must refuse", held.canProcessLoot());
        assertFalse("tick must refuse", held.canProcessGameTick());
        assertFalse(held.canIngestGameplay());
    }

    @Test
    public void matchedIdentityAllowsAllGameplayGates()
    {
        GameplayIngestionFacade ready = new GameplayIngestionFacade(() -> true, () -> true);
        assertTrue(ready.canInitializeAfterLogin());
        assertTrue(ready.canProcessItemContainer());
        assertTrue(ready.canProcessLoot());
        assertTrue(ready.canProcessGameTick());
    }

    @Test
    public void loggedOutRejectsIngestionEvenWhenIdentityLooksReady()
    {
        GameplayIngestionFacade facade = new GameplayIngestionFacade(() -> false, () -> true);
        assertFalse(facade.canInitializeAfterLogin());
        assertFalse(facade.canProcessItemContainer());
        assertFalse(facade.canProcessLoot());
        assertFalse(facade.canProcessGameTick());
    }

    @Test
    public void disabledPkTrackingDoesNotTreatEveryDeathAsPvmReclaim()
    {
        assertEquals(GpManagerPlugin.LocalDeathDisposition.UNCLASSIFIED,
            GpManagerPlugin.localDeathDisposition(false, 0, false, true));
        assertEquals(GpManagerPlugin.LocalDeathDisposition.UNCLASSIFIED,
            GpManagerPlugin.localDeathDisposition(false, 10, false, true));
        assertEquals("PvP evidence blocks reclaim even if PK accounting is disabled",
            GpManagerPlugin.LocalDeathDisposition.UNCLASSIFIED,
            GpManagerPlugin.localDeathDisposition(false, 10, true, true));
        assertEquals(GpManagerPlugin.LocalDeathDisposition.TRACK_PVM_RECLAIM,
            GpManagerPlugin.localDeathDisposition(false, 0, true, true));
        assertEquals("A safe-area death is PvM even after NPC context cleared",
            GpManagerPlugin.LocalDeathDisposition.TRACK_PVM_RECLAIM,
            GpManagerPlugin.localDeathDisposition(true, 0, false, false));
        assertEquals("A Wilderness death without target or player evidence stays ordinary",
            GpManagerPlugin.LocalDeathDisposition.UNCLASSIFIED,
            GpManagerPlugin.localDeathDisposition(true, 0, false, true));
        assertEquals(GpManagerPlugin.LocalDeathDisposition.TRACK_PK,
            GpManagerPlugin.localDeathDisposition(true, 10, false, true));
        assertEquals("PvP evidence takes precedence over a lingering NPC context",
            GpManagerPlugin.LocalDeathDisposition.TRACK_PK,
            GpManagerPlugin.localDeathDisposition(true, 10, true, true));
        assertEquals("Recent player combat remains PK evidence outside a PvP-capable area",
            GpManagerPlugin.LocalDeathDisposition.TRACK_PK,
            GpManagerPlugin.localDeathDisposition(true, 10, false, false));
        assertEquals(GpManagerPlugin.LocalDeathDisposition.TRACK_PVM_RECLAIM,
            GpManagerPlugin.localDeathDisposition(true, 0, true, true));
        assertEquals("No player or PvM signal leaves death loss ordinary",
            GpManagerPlugin.LocalDeathDisposition.UNCLASSIFIED,
            GpManagerPlugin.localDeathDisposition(true, 0, false, true));
    }

    @Test
    public void pluginCallbacksWireDistinctLoginContainerLootAndTickGates() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        assertTrue("plugin source must be readable for wiring audit", Files.isRegularFile(pluginSource));
        String source = readSource(pluginSource);

        assertTrue("login init must gate on canInitializeAfterLogin",
            source.contains("if (!ingestion.canInitializeAfterLogin())"));
        assertTrue("container callback must gate on canProcessItemContainer",
            source.contains("if (!ingestion.canProcessItemContainer())"));
        assertTrue("loot callbacks must gate on canProcessLoot",
            source.contains("if (!ingestion.canProcessLoot())"));
        assertTrue("tick callback must gate on canProcessGameTick",
            source.contains("if (!ingestion.canProcessGameTick())"));
        assertTrue("plugin must own a GameplayIngestionFacade field",
            source.contains("private GameplayIngestionFacade ingestion;"));
        assertTrue("chat reinforce must gate on canIngestGameplay",
            source.contains("public void onChatMessage(ChatMessage event)")
                && source.contains("isConsumptionChatMessage"));
        assertTrue("consume chat must reinforce intent",
            source.contains("engine.reinforceConsumptionIntent"));
        assertTrue("PvM death must open the ownership-neutral death window",
            source.contains("engine.markLocalPvmDeath(")
                && source.indexOf("engine.markLocalPvmDeath(") > source.indexOf("public void onActorDeath(ActorDeath event)"));
        assertTrue("retrieval-service clicks must arm the reclaim window before the bank branch",
            source.contains("BossRetrievalCatalogue.forMenu(option, target)")
                && source.indexOf("engine.noteDeathReclaimIntent(") < source.indexOf("if (joined.contains(\"bank\") || joined.contains(\"deposit\"))"));
        assertTrue("game tick must feed the neutral-zone tracker for Gauntlet-style instances",
            source.contains("applyNeutralZone(tile.getRegionID());")
                && source.contains("neutralZoneTracker.onRegion(regionId)"));
        assertTrue("rune pouch varbits must feed the same dirty path as inventory",
            source.contains("public void onVarbitChanged(VarbitChanged event)")
                && source.contains("ContainerSnapshotFactory.isRunePouchVarbit(event.getVarbitId())"));
        assertTrue("death disposition must distinguish unclassified deaths from PvM reclaim",
            source.contains("localDeathDisposition(")
                && source.contains("config.enablePkTracking(), recentPlayerCombatTicks, pvmContext, pvpPossible)")
                && source.contains("interactionContextTracker.hasPvmContext()")
                && source.contains("VarbitID.INSIDE_WILDERNESS")
                && source.contains("VarbitID.THIS_IS_A_PVP_OR_BH_WORLD")
                && source.contains("disposition == LocalDeathDisposition.UNCLASSIFIED")
                && source.contains("engine.markUnclassifiedLocalDeath(deathEvidence);"));
        int interactingStart = source.indexOf("public void onInteractingChanged(InteractingChanged event)");
        int actorDeathStart = source.indexOf("public void onActorDeath(ActorDeath event)", interactingStart);
        String interactingHandler = source.substring(interactingStart, actorDeathStart);
        int combatEvidence = interactingHandler.indexOf("recentPlayerCombatTicks = 20;");
        int pkAccounting = interactingHandler.indexOf("if (config.enablePkTracking())", combatEvidence);
        assertTrue("player-combat death evidence must be recorded before the PK-accounting-only branch",
            combatEvidence >= 0 && pkAccounting > combatEvidence);
        int gameTickStart = source.indexOf("public void onGameTick(GameTick event)");
        String gameTickHandler = source.substring(gameTickStart);
        assertTrue("active player interaction must refresh death-safety evidence even when PK tracking is disabled",
            gameTickHandler.contains("if (client.getLocalPlayer() != null\n"
                + "            && client.getLocalPlayer().getInteracting() instanceof Player)"));
        assertTrue("late process signals must yield to an active NPC in chat and XP paths",
            source.contains("private boolean suppressProcessSignalForNpcEngagement(String activity)")
                && source.contains("interactionContextTracker.hasNpcContext()")
                && source.contains("if (suppressProcessSignalForNpcEngagement(skill))"));
        assertTrue("tracked container toggles must warm a new baseline",
            source.contains("\"includeEquipment\".equals(event.getKey())")
                && source.contains("\"includeRunePouch\".equals(event.getKey())")
                && source.contains("engine.beginBaselinePriming();"));
    }

    @Test
    public void grandExchangeOfferEventsFeedSeededSlotLedger() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int handlerStart = source.indexOf("public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)");
        int handlerEnd = source.indexOf("public void onVarbitChanged(VarbitChanged event)", handlerStart);
        int seedStart = source.indexOf("private void beginGeOfferSeed()");
        int seedEnd = source.indexOf("private void advanceGeOfferSeed()", seedStart);
        assertTrue("the plugin must subscribe to real slot offer events",
            handlerStart >= 0 && handlerEnd > handlerStart);
        String handler = source.substring(handlerStart, handlerEnd);
        assertTrue(handler.contains("GeOfferLedger.Snapshot.fromOffer("));
        assertTrue(handler.contains("geOfferLedger.observe(snapshot)"));
        assertTrue("the login baseline must include RuneLite's current offer array",
            seedStart >= 0 && seedEnd > seedStart
                && source.substring(seedStart, source.indexOf("private void ", seedEnd + 1))
                    .contains("client.getGrandExchangeOffers()"));
        assertTrue("login/relogin must suppress offer replay before live transitions",
            source.contains("geOfferLedger.beginLoginSeed()")
                && source.contains("geOfferLedger.finishLoginSeed()")
                && source.contains("advanceGeOfferSeed();"));
        assertFalse("offer observation itself must not book from API spent/price fields",
            handler.contains("processIfDirty(")
                || handler.contains("recordGeOffer"));
        assertFalse("an offer event may arm the MARKET context but never another",
            handler.replaceAll("markContext\\(\\s*TrackingContext\\.MARKET", "").contains("markContext("));
    }

    @Test
    public void geOfferProvenanceIsAttachedAfterBookingWithoutChangingSettlement() throws Exception
    {
        String source = readSource(Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java"));
        int tickStart = source.indexOf("public void onGameTick(GameTick event)");
        int tickEnd = source.indexOf("private void syncActivityHold()", tickStart);
        int attachStart = source.indexOf("private void attachRecentGeOfferProvenance(");
        int attachEnd = source.indexOf("public void onVarbitChanged(VarbitChanged event)", attachStart);
        int eventStart = source.indexOf(
            "public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)");
        int eventEnd = source.indexOf("private void attachRecentGeOfferProvenance(", eventStart);
        assertTrue(tickStart >= 0 && tickEnd > tickStart);
        assertTrue(attachStart >= 0 && attachEnd > attachStart);
        assertTrue(eventStart >= 0 && eventEnd > eventStart);

        String tick = source.substring(tickStart, tickEnd);
        String attach = source.substring(attachStart, attachEnd);
        String event = source.substring(eventStart, eventEnd);
        assertTrue(tick.indexOf("engine.processIfDirty(")
            < tick.indexOf("attachRecentGeOfferProvenance(transaction, now)"));
        assertTrue(tick.indexOf("attachRecentGeOfferProvenance(transaction, now)")
            < tick.indexOf("offerFeedback(transaction, now)"));
        assertTrue(event.contains("transition.hasComparableProgress()")
            && event.contains("transition.getQuantityTradedDelta() > 0")
            && event.contains("pendingGeOfferProvenance.addLast("));
        assertTrue(attach.contains("transaction.getContext() != TrackingContext.MARKET")
            && attach.contains("transaction.getType() != TransactionType.TRADE")
            && attach.contains("GeOfferProvenanceMatcher.uniqueMatch("));
        assertTrue(attach.contains("observed.toProvenance()")
            && attach.contains("transaction.setGeOfferProvenance("));
        assertFalse("offer provenance must not be a flow or valuation producer",
            attach.contains("addFlow(")
                || attach.contains("setCounted(")
                || attach.contains("setType("));
    }

    @Test
    public void measuredChargeChatBooksOnlyExactPricedReadsWithoutActivitySignals() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int chatStart = source.indexOf("public void onChatMessage(ChatMessage event)");
        int menuStart = source.indexOf("public void onMenuOptionClicked(MenuOptionClicked event)", chatStart);
        int chargeStart = source.indexOf("private boolean observeMeasuredChargeCheck(", menuStart);
        int loadStart = source.indexOf("private void armChargeLoadTransfer(", chargeStart);
        int nextMethod = source.indexOf("private void loadChargeCalibration(", loadStart);
        assertTrue(chatStart >= 0 && menuStart > chatStart && chargeStart > menuStart
            && loadStart > chargeStart && nextMethod > loadStart);

        String chatHandler = source.substring(chatStart, menuStart);
        assertTrue("exact charge Check reads must short-circuit before unrelated chat intents",
            chatHandler.indexOf("observeMeasuredChargeCheck(message, System.currentTimeMillis())")
                < chatHandler.indexOf("message.contains(\"accepted trade\")"));
        int chargeGate = chatHandler.indexOf("if (observeMeasuredChargeCheck(message, System.currentTimeMillis())");
        int chargeGateEnd = chatHandler.indexOf("if (message.contains(\"accepted trade\")", chargeGate);
        assertTrue(chargeGate >= 0 && chargeGateEnd > chargeGate
            && chatHandler.substring(chargeGate, chargeGateEnd).contains("return;"));

        String chargeHandler = source.substring(chargeStart, loadStart);
        assertTrue(chargeHandler.contains("MeasuredChargeRead.parseCheckMessage(message)"));
        assertTrue(chargeHandler.contains("measuredChargeCheckIntent.consume(read, client.getTickCount())"));
        assertTrue(chargeHandler.contains("engine.observeMeasuredChargeRead(read, targetIdentity, now)"));
        assertTrue("load reconciliation must run before null charge-spend deltas return",
            chargeHandler.indexOf("engine.observeMeasuredChargeRead(read, targetIdentity, now)")
                < chargeHandler.indexOf("if (!read.isBookable()"));
        assertTrue(chargeHandler.contains("engine.resetMeasuredChargeReads()"));
        assertTrue(chargeHandler.contains("engine.bookChargeSpend(delta"));
        assertTrue(chargeHandler.contains("Math.multiplyExact(quantityDelta, (long) unitPrice)"));
        assertTrue(chargeHandler.contains("Math.addExact(totalCost, Math.negateExact(valueDelta))"));
        assertTrue(chargeHandler.contains("itemId == 995 ? 1 : itemManager.getItemPrice(canonicalId)"));
        assertTrue(chargeHandler.contains("itemId == 995\n                    ? com.gpmanager.model.ItemPriceSource.FACE_VALUE"));
        assertTrue(chargeHandler.contains(": com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE"));
        assertTrue("measured charge flows retain the price capture time",
            chargeHandler.contains("priceSource,\n                    now));"));
        assertFalse("a measured Check must not create HUD activity or a process title",
            chargeHandler.contains("markMeaningfulActivity")
                || chargeHandler.contains("observeActivity")
                || chargeHandler.contains("noteStickyProcessTitle")
                || chargeHandler.contains("ProcessSkillSignals"));

        assertFalse("weapon menu Check clicks must not calibrate charge booking",
            source.contains("LiveProducerBridge.tryCalibrateChargeFromMenu(option, target)"));
    }

    @Test
    public void chargeLoadTransferRequiresItemUseAndRecipeMatchedSource() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int menuStart = source.indexOf("public void onMenuOptionClicked(MenuOptionClicked event)");
        int armStart = source.indexOf("private void armChargeLoadTransfer(", menuStart);
        int nextMethod = source.indexOf("private void loadChargeCalibration(", armStart);
        assertTrue(menuStart >= 0 && armStart > menuStart && nextMethod > armStart);
        String menuHandler = source.substring(menuStart, source.indexOf("public void on", menuStart + 20));
        String chargeLoad = source.substring(armStart, nextMethod);

        assertTrue("menu action must arm only after checking the exact RuneLite item-use action",
            menuHandler.contains("armChargeLoadTransfer(event, target)")
                && menuHandler.contains("armMeasuredChargeCheck(event, option, target)")
                && chargeLoad.contains("event.getMenuAction() != MenuAction.ITEM_USE_ON_ITEM"));
        assertTrue(chargeLoad.contains("supportedVariantForItemName(target)"));
        assertTrue(chargeLoad.contains("event.getItemId()"));
        assertTrue(chargeLoad.contains("itemManager.canonicalize(sourceItemId)"));
        assertTrue(chargeLoad.contains("isSupportedLoadComponent("));
        assertTrue(chargeLoad.contains("engine.markChargeLoadTransfer(variant, canonicalId"));
        assertTrue("load intents and Check reads must share stable target identity",
            chargeLoad.contains("chargeWeaponTargetIdentity(event, variant, weaponItemId)"));
        assertTrue(source.contains("event.getWidgetId() <= 0 || event.getParam0() < 0"));
        Path enginePath = Paths.get("src/main/java/com/gpmanager/engine/GpManagerEngine.java");
        String engineSource = readSource(enginePath);
        assertTrue(engineSource.contains("chargeLoadTransferEvidence.arm(\n            variant, selectedItemId, selectedItemName, targetIdentity, ticks)"));
        assertFalse("the live engine wrapper must not supply an inferred load amount",
            engineSource.contains("chargeLoadTransferEvidence.armWithExactQuantity("));
        assertTrue("any superseding menu action must clear a pending narrow load intent",
            chargeLoad.contains("engine.clearChargeLoadTransfer()"));
        assertFalse("weapon load evidence must not arm a broad generic transfer context",
            chargeLoad.contains("markContext(TrackingContext.TRANSFER"));
    }

    @Test
    public void lootKeyLifecycleUsesDeathSnapshotAndLiveManifestEvidenceOnly() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int deathStart = source.indexOf("public void onActorDeath(ActorDeath event)");
        int tickStart = source.indexOf("public void onGameTick(GameTick event)");
        int manifestStart = source.indexOf("private void pollLootKeyManifest(long now)");
        int manifestEnd = source.indexOf("private boolean isNestedLootKeyContainerItem", manifestStart);
        assertTrue(deathStart >= 0 && tickStart > deathStart && manifestStart > tickStart && manifestEnd > manifestStart);

        String deathHandler = source.substring(deathStart, tickStart);
        assertTrue("local death snapshots held keys before any PvP/PvM disposition branch",
            deathHandler.contains("engine.beginLootKeyLocalDeathSettle")
                && deathHandler.contains("client.getItemContainer(InventoryID.INV)"));

        String gameTickHandler = source.substring(tickStart, manifestStart);
        assertTrue("live widget/container polling runs before inventory settlement",
            gameTickHandler.indexOf("pollLootKeyManifest(now);") >= 0
                && gameTickHandler.indexOf("pollLootKeyManifest(now);") < gameTickHandler.indexOf("engine.processIfDirty("));
        assertTrue("ordinary chest-open evidence expires on the game-tick lifecycle",
            gameTickHandler.contains("engine.tickDeferredChestClaimLifecycle();"));
        assertTrue("menu target arms ordinary chest provenance before a settled inventory delta",
            source.contains("engine.observeKeyChestInteraction(option, target);"));

        String manifestPoll = source.substring(manifestStart, manifestEnd);
        assertTrue(manifestPoll.contains("client.getWidget(InterfaceID.WILDY_LOOT_CHEST, 0)"));
        assertTrue(manifestPoll.contains("LootKeyLifecycle.keyContainerIdForIndex(index)"));
        assertTrue(manifestPoll.contains("LootKeyLifecycle.manifestFromContainer("));
        assertTrue(manifestPoll.contains("this::isNestedLootKeyContainerItem"));
        assertTrue(manifestPoll.contains("itemManager.getItemPrice(canonicalId)"));
        assertTrue(source.contains("name.contains(\"crate\") || name.contains(\"casket\")"));

        assertFalse("chat/menu/reward text cannot create or mutate key ownership provenance",
            source.contains("noteLootKeyReceived")
                || source.contains("noteLootChestOpened")
                || source.contains("onKeyDestroyed()"));
    }

    @Test
    public void localDeathEvidenceIsReadOnceAndPassedToExplanationPaths() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int deathStart = source.indexOf("public void onActorDeath(ActorDeath event)");
        int dispositionStart = source.indexOf("static LocalDeathDisposition localDeathDisposition(", deathStart);
        assertTrue(deathStart >= 0 && dispositionStart > deathStart);
        String deathHandler = source.substring(deathStart, dispositionStart);

        assertEquals(1, occurrences(deathHandler, "client.getWidget(InterfaceID.DEATHKEEP)"));
        assertEquals(1, occurrences(deathHandler, "client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM)"));
        assertEquals(1, occurrences(deathHandler, "localPlayer.getSkullIcon()"));
        assertTrue(deathHandler.contains("LocalDeathEvidence.capture("));
        assertTrue(deathHandler.contains("snapshotFactory.captureDeathHeldItems(config.includeEquipment())"));
        assertTrue(deathHandler.contains("engine.markPkDeath(\"Player death\", System.currentTimeMillis(), deathEvidence)"));
        assertTrue(deathHandler.contains("engine.markUnclassifiedLocalDeath(deathEvidence)"));
        assertTrue(deathHandler.contains("deathEvidence,\n            deathHeldItems == null ? null : deathHeldItems.getQuantities())"));
        assertTrue(readSource(Paths.get("src/main/java/com/gpmanager/engine/GpManagerEngine.java"))
            .contains("getDeathReclaimStatus()"));
        assertTrue("death evidence is captured before disposition and remains explanation-only",
            deathHandler.indexOf("LocalDeathEvidence.capture(") < deathHandler.indexOf("localDeathDisposition("));
        assertTrue("held-stack accounting evidence stays separate from LocalDeathEvidence",
            deathHandler.indexOf("captureDeathHeldItems") > deathHandler.indexOf("LocalDeathEvidence.capture("));
    }

    @Test
    public void pkLocationLabelsArePresentationOnlyAndUseCurrentRegionAndPvpSignals() throws Exception
    {
        assertEquals("The Gauntlet", GpManagerPlugin.playerLocationLabel(7512, true, true));
        assertEquals("Corrupted Gauntlet", GpManagerPlugin.playerLocationLabel(7768, false, false));
        assertEquals("Wilderness", GpManagerPlugin.playerLocationLabel(1234, true, false));
        assertEquals("PvP world", GpManagerPlugin.playerLocationLabel(1234, false, true));
        assertEquals(null, GpManagerPlugin.playerLocationLabel(1234, false, false));

        String source = readSource(Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java"));
        assertTrue(source.contains("engine.updatePlayerWorldLocation(tile.getX(), tile.getY(), tile.getPlane(), label)"));
        assertTrue(source.contains("syncPlayerWorldLocation(client.getLocalPlayer());"));
        assertTrue(source.contains("syncPlayerWorldLocation(localPlayer);"));
        assertTrue(source.contains("engine.clearPlayerWorldLocation();"));
    }

    @Test
    public void wildernessRiskKeepCountUsesOnlySupportedSkullRules()
    {
        assertEquals(3, GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.NONE, false));
        assertEquals(4, GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.NONE, true));
        assertEquals(0, GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.SKULL, false));
        assertEquals(1, GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.SKULL, true));
        assertEquals("high-risk worlds disable kept items and Protect Item", 0,
            GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.SKULL_HIGH_RISK, true));
        assertEquals("unmapped special modes remain unknown", -1,
            GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.SKULL_DEADMAN, false));
        assertEquals(-1, GpManagerPlugin.wildernessRiskKeepCount(SkullIcon.LOOT_KEYS_TWO, false));
    }

    @Test
    public void wildernessRiskUsesCarriedInventoryAndEquipmentOnlyForDisplay() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int tickStart = source.indexOf("public void onGameTick(GameTick event)");
        int tickEnd = source.indexOf("private void syncActivityHold()", tickStart);
        assertTrue(tickStart >= 0 && tickEnd > tickStart);
        assertTrue(source.substring(tickStart, tickEnd).contains("updateWildernessRisk(localPlayer);"));

        int riskStart = source.indexOf("private void updateWildernessRisk(Player localPlayer)");
        int keepCountStart = source.indexOf("static int wildernessRiskKeepCount(", riskStart);
        assertTrue(riskStart >= 0 && keepCountStart > riskStart);
        String riskUpdate = source.substring(riskStart, keepCountStart);
        assertTrue(riskUpdate.contains("client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)"));
        assertTrue(riskUpdate.contains("client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD)"));
        assertTrue(riskUpdate.contains("wildernessRiskItems(InventoryID.INV)"));
        assertTrue(riskUpdate.contains("wildernessRiskItems(InventoryID.WORN)"));
        assertTrue(riskUpdate.contains("WildernessRiskCalculator.calculate("));
        assertTrue(riskUpdate.contains("trackingDisplayModel.setWildernessRisk(true, risk)"));
        assertTrue(riskUpdate.contains("trackingDisplayModel.setWildernessRisk(pvpPossible, null)"));
        int itemStart = source.indexOf("private List<WildernessRiskCalculator.ItemStack> wildernessRiskItems(", keepCountStart);
        int itemEnd = source.indexOf("private void applyNeutralZone(", itemStart);
        assertTrue(itemStart > keepCountStart && itemEnd > itemStart);
        String carriedRead = source.substring(itemStart, itemEnd);
        assertTrue(carriedRead.contains("itemManager.getItemPrice(canonicalId)"));
        assertTrue(carriedRead.contains("geUnitPrice"));
        assertFalse("risk is a display metric and never books item flows", carriedRead.contains("engine."));
    }

    @Test
    public void wildernessRiskFailsClosedForHiddenContainerContents()
    {
        assertTrue(GpManagerPlugin.hasUnmeasuredRiskContents("Rune pouch", true));
        assertFalse(GpManagerPlugin.hasUnmeasuredRiskContents("Rune pouch", false));
        assertTrue(GpManagerPlugin.hasUnmeasuredRiskContents("Looting bag", false));
        assertTrue(GpManagerPlugin.hasUnmeasuredRiskContents("Seed box", false));
        assertFalse(GpManagerPlugin.hasUnmeasuredRiskContents("Abyssal whip", false));
        assertTrue(GpManagerPlugin.hasUnmeasuredRiskContents(null, false));
    }

    private static int occurrences(String source, String needle)
    {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0)
        {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    public void processTitleExpiresOnItsFiveTickXpHoldAndClearsDetectedActivity() throws Exception
    {
        assertEquals(5, GpManagerPlugin.PROCESS_TITLE_TICKS);
        ProcessTitleHold hold = new ProcessTitleHold();
        hold.confirm("Prayer", GpManagerPlugin.PROCESS_TITLE_TICKS);
        assertEquals("Prayer", renderProcessTitle(hold.getTitle()));
        for (int i = 0; i < GpManagerPlugin.PROCESS_TITLE_TICKS - 1; i++)
        {
            assertFalse("the process title remains during its short XP hold", hold.advance());
            assertEquals("Prayer", renderProcessTitle(hold.getTitle()));
        }
        assertEquals("title remains through the fourth tick", 1, hold.getTicksRemaining());
        assertTrue("the fifth tick expires the process title", hold.advance());
        assertEquals("", renderProcessTitle(hold.getTitle()));
        assertFalse(hold.advance());

        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int expiryStart = source.indexOf("private void advanceProcessTitleTicks()");
        int expiryEnd = source.indexOf("private void syncActivitySession()", expiryStart);
        assertTrue(expiryStart >= 0 && expiryEnd > expiryStart);
        String expiryMethod = source.substring(expiryStart, expiryEnd);
        assertTrue("zero ticks must clear the separate confirmed presentation title",
            expiryMethod.contains("processTitleHold.advance()")
                && expiryMethod.contains("publishConfirmedProcessTitle();"));
        assertFalse("title expiry must not rewrite session/accounting activity",
            expiryMethod.contains("detectedActivity")
                || expiryMethod.contains("activityCandidate")
                || expiryMethod.contains("engine.setDetectedActivity("));
        int titleSetterStart = source.indexOf("private void setConfirmedProcessTitle(");
        int titleSetterEnd = source.indexOf("private void clearConfirmedProcessTitle(", titleSetterStart);
        assertTrue(titleSetterStart >= 0 && titleSetterEnd > titleSetterStart);
        assertTrue("plugin title state must reach the display model",
            source.substring(titleSetterStart, titleSetterEnd)
                .contains("processTitleHold.confirm(title, PROCESS_TITLE_TICKS)")
                && source.contains("trackingDisplayModel.setConfirmedProcessTitle(processTitleHold.getTitle())"));
        int statStart = source.indexOf("public void onStatChanged(StatChanged event)");
        int statEnd = source.indexOf("@Subscribe", statStart + 1);
        assertTrue(statStart >= 0 && statEnd > statStart);
        String statHandler = source.substring(statStart, statEnd);
        assertTrue("only a positive XP delta reaches process title selection",
            statHandler.contains("event.getXp() <= previous")
                && statHandler.contains("observeActivityPreferringProcessStick(skill.getName())")
                && statHandler.contains("prayerAltarTitleEvidence.consumeForPrayerXp(now)"));
        int applyStart = source.indexOf("private void applyLiveProcessSkill(");
        int applyEnd = source.indexOf("private void setConfirmedProcessTitle(", applyStart);
        assertTrue(applyStart >= 0 && applyEnd > applyStart);
        assertTrue("XP-confirmed process skill owns the presentation title setter",
            source.substring(applyStart, applyEnd).contains("setConfirmedProcessTitle(skill);"));
        assertTrue("the expiry countdown must run from GameTick",
            source.contains("advanceProcessTitleTicks();"));
    }

    @Test
    public void prayerTitleNeedsAnExplicitAltarUseBeforePositiveXp() throws Exception
    {
        PrayerAltarTitleEvidence evidence = new PrayerAltarTitleEvidence();
        long now = 10_000L;
        assertFalse("plain bury has no altar-use title evidence", evidence.consumeForPrayerXp(now));

        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int menuStart = source.indexOf("public void onMenuOptionClicked(MenuOptionClicked event)");
        int statStart = source.indexOf("public void onStatChanged(StatChanged event)", menuStart);
        int statEnd = source.indexOf("@Subscribe", statStart + 1);
        assertTrue(menuStart >= 0 && statStart > menuStart && statEnd > statStart);
        String menuHandler = source.substring(menuStart, statStart);
        String statHandler = source.substring(statStart, statEnd);
        assertTrue("each later menu action clears an old altar candidate",
            menuHandler.contains("prayerAltarTitleEvidence.clear();"));
        int altarPair = menuHandler.indexOf("ProcessSkillSignals.isPrayerAltarUsePair(option, target)");
        int arm = menuHandler.indexOf("prayerAltarTitleEvidence.arm(", altarPair);
        assertTrue("only explicit Use-on-altar evidence arms a title candidate", altarPair >= 0 && arm > altarPair);
        assertTrue("Use-on-altar is the supported explicit signal",
            ProcessSkillSignals.isPrayerAltarUsePair("Use", "Bones -> Gilded altar"));
        assertFalse("plain Bury is not altar evidence",
            ProcessSkillSignals.isPrayerAltarUsePair("Bury", "Bones -> Gilded altar"));
        assertTrue("positive Prayer XP consumes the short-lived altar candidate",
            statHandler.contains("prayerAltarTitleEvidence.consumeForPrayerXp(now)")
                && statHandler.contains("interactionContextTracker.hasFreshInteractionContext()"));
        assertTrue("the same XP remains available to confirm the Buried/Offered receipt",
            statHandler.contains("rewardPresentationModel.confirmProcessSpendXp("));
        int clearStart = source.indexOf("private void clearConfirmedProcessTitle()");
        int clearEnd = source.indexOf("private void armLossOnlyProcessSpend(", clearStart);
        int sessionStart = source.indexOf("private void syncActivitySession()");
        int sessionEnd = source.indexOf("private boolean isBankOpen()", sessionStart);
        int deathStart = source.indexOf("private void clearHudPlusOnLocalDeath()");
        int deathEnd = source.indexOf("private boolean suppressProcessSignalForNpcEngagement(", deathStart);
        assertTrue("death and owner changes clear pending altar evidence with the title",
            clearStart >= 0 && clearEnd > clearStart
                && source.substring(clearStart, clearEnd).contains("prayerAltarTitleEvidence.clear();")
                && sessionStart >= 0 && sessionEnd > sessionStart
                && source.substring(sessionStart, sessionEnd).contains("clearConfirmedProcessTitle();")
                && deathStart >= 0 && deathEnd > deathStart
                && source.substring(deathStart, deathEnd).contains("clearConfirmedProcessTitle();"));

        if (ProcessSkillSignals.isPrayerAltarUsePair("Use", "Bones -> Gilded altar"))
        {
            evidence.arm(now, 4_000L);
        }
        assertTrue(evidence.isArmed(now));
        assertFalse("menu click alone does not create a title", new ProcessTitleHold().isActive());
        assertTrue("the next positive Prayer XP consumes the candidate", evidence.consumeForPrayerXp(now + 1L));
        assertFalse("altar evidence is one-shot", evidence.consumeForPrayerXp(now + 2L));

        evidence.arm(now, 4_000L);
        evidence.clear();
        assertFalse("superseding menu action cancels the candidate", evidence.consumeForPrayerXp(now + 1L));
        evidence.arm(now, 4_000L);
        assertFalse("an expired altar candidate cannot authorize later Prayer XP",
            evidence.consumeForPrayerXp(now + 4_000L));
    }

    private static String renderProcessTitle(String title)
    {
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "General", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withConfirmedProcessTitle(title);
        return HudPlusHeaderLabel.resolve(snapshot);
    }

    @Test
    public void processSpendMenuPathArmsReceiptWithoutPromotingTitle() throws Exception
    {
        Path pluginSource = Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java");
        String source = readSource(pluginSource);
        int armStart = source.indexOf("private void armLossOnlyProcessSpend(");
        int armEnd = source.indexOf("private void observeNonProcessMenuActivity(", armStart);
        assertTrue(armStart >= 0 && armEnd > armStart);
        String spendMethods = source.substring(armStart, armEnd);
        assertFalse("menu actions may arm receipts but cannot promote titles",
            spendMethods.contains("observeActivity("));
        assertFalse("menu actions may arm receipts but cannot promote titles",
            spendMethods.contains("applyLiveProcessSkill("));

        int chatStart = source.indexOf("public void onChatMessage(ChatMessage event)");
        int nextHandler = source.indexOf("@Subscribe", chatStart + 1);
        assertTrue(chatStart >= 0 && nextHandler > chatStart);
        String chatHandler = source.substring(chatStart, nextHandler);
        assertFalse("chat text must not promote a live title", chatHandler.contains("observeActivity("));
        assertFalse("chat text must not promote a live title", chatHandler.contains("applyLiveProcessSkill("));
    }

    @Test
    public void wealthReadModelsUseCompleteGeSeedAndVisibleCollectionWidgetsOnly() throws Exception
    {
        String source = readSource(Paths.get("src/main/java/com/gpmanager/GpManagerPlugin.java"));
        int seedStart = source.indexOf("private void advanceGeOfferSeed()");
        int wealthStart = source.indexOf("private void refreshWealthLocationsSnapshot(");
        int collectionStart = source.indexOf("private WealthLocationSnapshot readCollectionBoxWealth(");
        int nextAfterCollection = source.indexOf("private String wealthItemName(", collectionStart);
        int tickStart = source.indexOf("public void onGameTick(GameTick event)");
        int tickEnd = source.indexOf("private void syncActivityHold()", tickStart);
        assertTrue(seedStart >= 0 && wealthStart > seedStart && collectionStart > wealthStart
            && nextAfterCollection > collectionStart && tickStart >= 0 && tickEnd > tickStart);

        String seed = source.substring(seedStart, wealthStart);
        assertTrue("login seeding must wait for all three or eight non-null GE slots",
            seed.contains("WealthLocationsFactory.isSupportedGeSlotCount(offers.length)")
                && seed.contains("if (offer == null)")
                && seed.contains("geOfferSeedTicks = 1;")
                && seed.contains("geOfferLedger.finishLoginSeed();"));
        String wealth = source.substring(wealthStart, collectionStart);
        assertTrue(wealth.contains("client.getGrandExchangeOffers()"));
        assertTrue(wealth.contains("geOfferLedger.isLoginSeedInProgress()"));
        assertTrue("bank item valuation is refreshed only after a container update, not every client tick",
            wealth.contains("if (wealthBankReadPending)")
                && wealth.contains("wealthBankLocationSnapshot = WealthLocationsFactory.container("));
        assertTrue("complete holdings with unknown prices stay in history but fail comparisons closed",
            wealth.contains("bank.getStatus() == WealthLocationSnapshot.Status.UNPRICED"));
        assertTrue("the main bank is captured only after its contents stabilize",
            wealth.contains("InventoryID.BANK") && wealth.contains("bankWealthCaptureGate.observe(hash)"));
        int bankEventStart = source.indexOf("public void onItemContainerChanged(ItemContainerChanged event)");
        int bankEventEnd = source.indexOf("public void onGrandExchangeOfferChanged(", bankEventStart);
        assertTrue(bankEventStart >= 0 && bankEventEnd > bankEventStart);
        String bankEvent = source.substring(bankEventStart, bankEventEnd);
        assertTrue("cached reads cannot authorize wealth capture before a visible-bank container event",
            bankEvent.contains("isMainBankUiOpen()")
                && bankEvent.contains("bankWealthCaptureGate.markBankContentsChanged()"));
        assertTrue("durable history is separate from transaction ingestion",
            wealth.contains("engine.recordWealthSnapshot(durable, now)")
                && wealth.contains("historyCaptureSnapshot(wealthLocationsSnapshot)"));
        assertTrue("held items use the receipt valuation selector",
            wealth.contains("itemValuationService.quoteForWealth(itemId, capturedAtEpochMillis)"));
        String collection = source.substring(collectionStart, nextAfterCollection);
        assertTrue(collection.contains("client.getWidget(InterfaceID.GeCollect.FRAME)"));
        assertTrue(collection.contains("if (!visible)"));
        assertTrue(collection.contains("InterfaceID.GeCollect.COLLECT_0 + slot"));
        assertTrue(collection.contains("WealthLocationsFactory.collectionBox(true, allSlotsPresent"));
        String tick = source.substring(tickStart, tickEnd);
        assertTrue("client/widget state is sampled on the client tick", tick.contains("refreshWealthLocationsSnapshot(now)"));

        String factory = readSource(Paths.get("src/main/java/com/gpmanager/engine/WealthLocationsFactory.java"));
        String locationModel = readSource(Paths.get("src/main/java/com/gpmanager/model/WealthLocationSnapshot.java"));
        assertTrue(factory.contains("GrandExchangeOfferState.SELLING"));
        assertTrue(factory.contains("offer.getTotalQuantity() - offer.getQuantityTraded()"));
        assertTrue(locationModel.contains("public boolean isExcludedFromNet() { return true; }"));
    }

    /**
     * Source audits must not depend on checkout line endings: the GitHub Windows
     * runner checks out with autocrlf (CRLF) while local and Linux trees are LF.
     */
    private static String readSource(Path path) throws java.io.IOException
    {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
