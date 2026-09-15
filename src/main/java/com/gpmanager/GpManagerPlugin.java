package com.gpmanager;

import com.google.inject.Provides;
import com.gpmanager.diagnostics.DebugTrace;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.ContainerSnapshotFactory;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.GeOfferLedger;
import com.gpmanager.engine.GeOfferProvenanceMatcher;
import com.gpmanager.engine.WealthLocationsFactory;
import com.gpmanager.engine.BankWealthCaptureGate;
import com.gpmanager.engine.ItemValuationService;
import com.gpmanager.engine.LocalDeathEvidence;
import com.gpmanager.engine.evidence.MeasuredChargeCheckIntent;
import com.gpmanager.engine.evidence.UtilityContainerCatalogue;
import com.gpmanager.party.PartyProfitMessage;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.model.InsightsActivityBreakdown;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.persistence.PersistenceCoordinator;
import com.gpmanager.persistence.SessionRepository;
import com.gpmanager.grounditems.GroundItemsConfigSnapshot;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.ui.HudPlusProcessLabels;
import com.gpmanager.ui.HudTrayState;
import com.gpmanager.ui.ProcessTitleHold;
import com.gpmanager.ui.PrayerAltarTitleEvidence;
import com.gpmanager.ui.ProcessSkillSignals;
import com.gpmanager.ui.LatestChangeModel;
import com.gpmanager.ui.LatestDropModel;
import com.gpmanager.ui.TrackingDisplayModel;
import com.gpmanager.ui.WildernessRiskCalculator;
import com.gpmanager.reward.ActionPresentation;
import com.gpmanager.reward.RewardObservationFactory;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.reward.SessionItemLedger;
import com.gpmanager.ui.CharacterIdleModel;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.SkullIcon;
import net.runelite.api.Skill;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.InfoBoxMenuClicked;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.grounditems.GroundItemsPlugin;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.api.widgets.Widget;

@PluginDescriptor(
    name = "GP Manager",
    description = "Lightweight passive profit tracking for gains, costs, live GP changes, activities, parties, and session history",
    tags = {"profit", "gp", "loot", "costs", "supplies", "sessions", "party"}
)
public class GpManagerPlugin extends Plugin
{
    enum LocalDeathDisposition
    {
        TRACK_PK,
        TRACK_PVM_RECLAIM,
        UNCLASSIFIED
    }

    private static final Logger log = LoggerFactory.getLogger(GpManagerPlugin.class);
    private static final int SAVE_INTERVAL_TICKS = 50;
    private static final int MAX_PENDING_GE_OFFER_PROVENANCE = 32;
    /** A confirmed process title stays visible for five ticks after its last XP. */
    static final int PROCESS_TITLE_TICKS = 5;

    @Inject
    private Client client;

    @Inject
    private com.google.gson.Gson gson;

    @Inject
    private GpManagerConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private net.runelite.client.eventbus.EventBus eventBus;

    @Inject
    private ContainerSnapshotFactory snapshotFactory;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ItemValuationService itemValuationService;

    @Inject
    private GpManagerEngine engine;

    @Inject
    private SessionRepository repository;

    @Inject
    private PersistenceCoordinator persistence;

    @Inject
    private GpManagerOverlay overlay;

    @Inject
    private GpManagerGpDropOverlay gpDropOverlay;

    @Inject
    private PartyProfitTracker partyProfitTracker;

    @Inject
    private com.gpmanager.ui.bento.BentoPanel panel;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigurationPanelOpener configurationPanelOpener;

    @Inject
    private LatestChangeModel latestChangeModel;

    @Inject
    private LatestDropModel latestDropModel;

    @Inject
    private RewardPresentationModel rewardPresentationModel;

    @Inject
    private SessionItemLedger sessionItemLedger;

    @Inject
    private CharacterIdleModel characterIdleModel;

    @Inject
    private GameplayIngestionFacade ingestion;

    @Inject
    private TrackingDisplayModel trackingDisplayModel;

    @Inject
    private InteractionContextTracker interactionContextTracker;
    @Inject
    private CharacterIdleTracker characterIdleTracker;

    @Inject
    private DebugTrace debugTrace;

    @Inject
    private LootPresentationFilterService lootPresentationFilter;

    private NavigationButton navigationButton;
    private GpManagerTrackingInfoBox trackingInfoBox;
    private int ticksSinceSave;
    private int recentPlayerCombatTicks;
    private int idleTicks;
    /** Wall clock of the first idle tick; the pause and any idle auto-end are dated here. */
    private long idleStartedAt;
    private String activityCandidate = "General";
    private int activityEvidence;
    private int activityHoldTicks;
    /** Rank-2 title lifetime, refreshed only by a positive process-skill XP drop. */
    private final ProcessTitleHold processTitleHold = new ProcessTitleHold();
    /** One-shot altar evidence; a Prayer menu intent alone cannot promote a title. */
    private final PrayerAltarTitleEvidence prayerAltarTitleEvidence = new PrayerAltarTitleEvidence();
    private String detectedActivity = "General";
    private String activitySessionId;
    private final Map<Skill, Integer> lastSkillExperience = new java.util.EnumMap<>(Skill.class);
    private final SessionBoundaryTracker sessionBoundaries = new SessionBoundaryTracker(this::onSessionBoundary);
    /** Soft gate so transform XP only reinforces PRODUCTION after a transform menu. */
    private long productionWindowExpiresAtEpochMillis;
    /** Pending process-selection hint, applied as a title only after matching XP. */
    private String stickyProcessTitle;
    private boolean softStickProduction;
    private final com.gpmanager.engine.NeutralZoneTracker neutralZoneTracker =
        new com.gpmanager.engine.NeutralZoneTracker();
    /** One-shot menu identity for the exact measured Check chat response. */
    private final MeasuredChargeCheckIntent measuredChargeCheckIntent =
        new MeasuredChargeCheckIntent();
    /** Real GE slot observations; login replay is seeded without booking. */
    private final GeOfferLedger geOfferLedger = new GeOfferLedger();
    private final Deque<GeOfferProvenanceMatcher.Observation> pendingGeOfferProvenance = new ArrayDeque<>();
    private int geOfferSeedTicks;
    /** Immutable client-thread read model; safe for a future sidebar consumer to read. */
    private volatile WealthLocationsSnapshot wealthLocationsSnapshot =
        new WealthLocationsSnapshot(0L, Collections.emptyList());
    private final BankWealthCaptureGate bankWealthCaptureGate = new BankWealthCaptureGate();
    private boolean wealthBankVisitOpen;
    private boolean wealthBankReadPending;
    private boolean wealthBankCapturePending;
    @Nullable
    private WealthLocationSnapshot wealthBankLocationSnapshot;
    /** The last complete bank read, kept after the bank closes so Where it sits keeps a bank figure. */
    private Map<Integer, Long> lastBankQuantities;
    private long lastBankReadAtEpochMillis;
    private WealthLocationSnapshot lastBankRepriced;
    private long lastBankRepricedAtEpochMillis;
    /** Rising-edge: clear NPC soft-stick once when bank opens, not every bank tick. */
    private boolean bankClearedInteractionThisOpen;
    /** ConfigManager key — comma-separated calibrated charge family ids (per RS profile). */
    static final String CHARGE_CALIBRATED_KEY = "chargeCalibratedFamilies";

    @Override
    protected void startUp()
    {
        com.gpmanager.persistence.JsonCodec.bind(gson);
        GpManagerConfigMigration.apply(configManager);
        loadChargeCalibration();

        lootPresentationFilter.refresh();
        persistence.start();
        // Always sync identity for account isolation; persistHistory only
        // controls durable load/save, not whether accounts may mix.
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            beginGeOfferSeed();
            persistence.syncIdentityFromClient(config.persistHistory());
        }
        else if (config.persistHistory() && !repository.isAccountAware())
        {
            engine.restore(repository.load());
            rebindSessionCapsule();
        }

        overlayManager.add(overlay);
        overlayManager.add(gpDropOverlay);
        panel.bindConfigurationOpener(this::openPluginConfiguration);
        panel.bindState(neutralZoneTracker::isInside,
            () -> persistence != null && persistence.isIdentitySwitchHeld());
        panel.bindWealth(this::getWealthLocationsSnapshot);
        panel.bindEncounter(() -> rewardPresentationModel == null ? null : rewardPresentationModel.sessionEncounter(System.currentTimeMillis()));
        panel.bindParty(() -> partyProfitTracker == null ? null : partyProfitTracker.getSummary(config.enablePartyTracking()));
        trackingInfoBox = new GpManagerTrackingInfoBox(
            engine, config, infoBoxManager, itemManager, latestDropModel, trackingDisplayModel, this);
        trackingInfoBox.sync();
        partyProfitTracker.registerMessage();
        navigationButton = NavigationButton.builder()
            .tooltip("GP Manager")
            .icon(loadIcon())
            .priority(5)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);

        engine.setDebugTrace(debugTrace);
        debugTrace.setEnabled(config.enableDebugTrace());

        if (client.getGameState() == GameState.LOGGED_IN && ingestion.canInitializeAfterLogin())
        {
            initializeLoggedInState();
        }

        panel.refresh();
        debugTrace.record("lifecycle", "plugin started");
        log.debug("GP Manager started");
    }

    @Override
    protected void shutDown()
    {
        measuredChargeCheckIntent.clear();
        // The durable General tracker and any custom session are saved in
        // place. Shutdown must not turn either into history or merge owners.
        engine.prepareForShutdown(System.currentTimeMillis());
        persistence.shutdown(config.persistHistory());
        lastSkillExperience.clear();

        if (trackingInfoBox != null)
        {
            trackingInfoBox.unregister();
            trackingInfoBox = null;
        }
        latestChangeModel.clear();
        latestDropModel.clear();
        rewardPresentationModel.clear();
        sessionItemLedger.clear();
        interactionContextTracker.clear();
        trackingDisplayModel.setNeutralZoneActive(false);
        clearConfirmedProcessTitle();
        overlayManager.remove(gpDropOverlay);
        overlayManager.remove(overlay);
        partyProfitTracker.unregisterMessage();
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }

        debugTrace.record("lifecycle", "plugin stopped");
        log.debug("GP Manager stopped");
    }

    private GameState lastGameState;

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState previous = lastGameState;
        lastGameState = event.getGameState();
        // LOGGED_IN -> LOADING -> LOGGED_IN is a region change (teleport, instance, stairs), not a
        // login. Re-priming the baseline there swallowed whatever changed on the way -- a teleport
        // tablet breaking never booked. Only the location-bound trackers reset on a load.
        if (event.getGameState() == GameState.LOADING)
        {
            interactionContextTracker.clear();
            neutralZoneTracker.reset();
            trackingDisplayModel.setNeutralZoneActive(false);
            clearConfirmedProcessTitle();
            return;
        }
        if (event.getGameState() == GameState.LOGGED_IN && previous == GameState.LOADING)
        {
            syncActivitySession();
            panel.refresh();
            return;
        }
        if (event.getGameState() != GameState.LOGGED_IN)
        {
            geOfferLedger.beginLoginSeed();
            pendingGeOfferProvenance.clear();
            geOfferSeedTicks = 0;
            wealthLocationsSnapshot = new WealthLocationsSnapshot(
                System.currentTimeMillis(), Collections.emptyList());
            bankWealthCaptureGate.endVisit();
            wealthBankVisitOpen = false;
            wealthBankReadPending = false;
            wealthBankCapturePending = false;
            wealthBankLocationSnapshot = null;
            measuredChargeCheckIntent.clear();
            engine.resetMeasuredChargeReads();
            engine.clearChargeLoadTransfer();
            engine.clearPlayerWorldLocation();
            clearConfirmedProcessTitle();
            interactionContextTracker.clear();
            neutralZoneTracker.reset();
            trackingDisplayModel.setNeutralZoneActive(false);
            characterIdleTracker.clear();
        }
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            beginGeOfferSeed();
            boolean ready = persistence.syncIdentityFromClient(config.persistHistory());
            if (ready && ingestion.canInitializeAfterLogin())
            {
                initializeLoggedInState();
            }
            else
            {
                debugTrace.record("identity",
                    persistence.identityBlockReason() == null
                        ? "login held for identity"
                        : persistence.identityBlockReason());
                panel.refresh();
            }
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN
            || event.getGameState() == GameState.HOPPING)
        {
            lastSkillExperience.clear();
            if (persistence.isTrackingReady())
            {
                engine.pauseForLifecycle(System.currentTimeMillis());
                persist();
            }
            if (event.getGameState() == GameState.LOGIN_SCREEN)
            {
                persistence.onLogout(config.persistHistory());
                if (panel != null)
                {
                    panel.clearWealthObservations();
                }
            }
        }
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        beginGeOfferSeed();
        measuredChargeCheckIntent.clear();
        prayerAltarTitleEvidence.clear();
        engine.resetMeasuredChargeReads();
        engine.clearChargeLoadTransfer();
        interactionContextTracker.clear();
        // Drop prior profile's in-memory calibration before loading this profile's set.
        com.gpmanager.engine.evidence.ChargeAccountingGate.clearAllCalibrated();
        loadChargeCalibration();
        boolean ready = persistence.syncIdentityFromClient(config.persistHistory());
        if (ready)
        {
            latestChangeModel.clear();
            latestDropModel.clear();
            rewardPresentationModel.clear();
        sessionItemLedger.clear();
            initializeLoggedInState();
        }
        else
        {
            debugTrace.record("identity",
                persistence.identityBlockReason() == null
                    ? "profile switch held"
                    : persistence.identityBlockReason());
        }
        panel.refresh();
    }

    @Subscribe
    public void onAccountHashChanged(AccountHashChanged event)
    {
        beginGeOfferSeed();
        measuredChargeCheckIntent.clear();
        prayerAltarTitleEvidence.clear();
        engine.resetMeasuredChargeReads();
        engine.clearChargeLoadTransfer();
        interactionContextTracker.clear();
        long hash = client.getAccountHash();
        if (hash == -1L)
        {
            latestChangeModel.clear();
            latestDropModel.clear();
            rewardPresentationModel.clear();
        sessionItemLedger.clear();
            persistence.onAccountHashInvalidated(config.persistHistory());
        }
        else
        {
            boolean ready = persistence.syncIdentityFromClient(config.persistHistory());
            if (ready)
            {
                latestChangeModel.clear();
                latestDropModel.clear();
                rewardPresentationModel.clear();
        sessionItemLedger.clear();
                initializeLoggedInState();
            }
            else
            {
                debugTrace.record("identity",
                    persistence.identityBlockReason() == null
                        ? "account switch held"
                        : persistence.identityBlockReason());
            }
        }
        panel.refresh();
    }

    @Subscribe
    public void onPartyChanged(PartyChanged event)
    {
        partyProfitTracker.onPartyChanged(event);
        panel.refresh();
    }

    @Subscribe
    public void onUserJoin(UserJoin event)
    {
        partyProfitTracker.onUserJoin(event);
    }

    @Subscribe
    public void onUserPart(UserPart event)
    {
        partyProfitTracker.onUserPart(event);
        panel.refresh();
    }

    @Subscribe
    public void onPartyProfitMessage(PartyProfitMessage message)
    {
        partyProfitTracker.onMessage(message, config.enablePartyTracking());
        panel.refresh();
    }

    private void initializeLoggedInState()
    {
        if (!ingestion.canInitializeAfterLogin())
        {
            panel.refresh();
            return;
        }
        // Login restores identity and baseline. Resume LIFECYCLE only (hop/relog).
        // Idle and Recovery stay until activity or explicit Resume. Manual / Stop stay held.
        if (engine.getActiveSession() != null)
        {
            engine.beginBaselinePriming();
        }
        long now = System.currentTimeMillis();
        engine.resumeAfterLifecycle(now);
        idleTicks = 0;
        syncActivitySession();
        rebindSessionCapsule();
        panel.refresh();
    }

    /** Bind HUD+ Session Capsule to the active ProfitSession (Overall or Custom). */
    private void rebindSessionCapsule()
    {
        if (sessionItemLedger != null)
        {
            sessionItemLedger.bindToSession(engine.getActiveSession());
        }
        syncOwnerScopeNow();
    }

    /** Same-turn tray owner rebind so custom start/finish does not linger one tick. */
    private void syncOwnerScopeNow()
    {
        if (rewardPresentationModel == null)
        {
            return;
        }
        String scope = persistence != null && persistence.isTrackingReady()
            ? String.valueOf(System.identityHashCode(engine.getActiveSession()))
            : "held";
        rewardPresentationModel.setOwnerScope(scope);
    }

    private void beginGeOfferSeed()
    {
        geOfferLedger.beginLoginSeed();
        pendingGeOfferProvenance.clear();
        // Hide the prior profile's observations until a fresh complete seed arrives.
        wealthLocationsSnapshot = new WealthLocationsSnapshot(
            System.currentTimeMillis(), Collections.emptyList());
        // Give RuneLite time to deliver the documented EMPTY-slot replay and
        // the server-backed active offers before transitions become observable.
        geOfferSeedTicks = 3;
    }

    private void advanceGeOfferSeed()
    {
        if (geOfferSeedTicks <= 0 || --geOfferSeedTicks > 0)
        {
            return;
        }

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        boolean complete = offers != null
            && WealthLocationsFactory.isSupportedGeSlotCount(offers.length);
        if (complete)
        {
            for (int slot = 0; slot < offers.length; slot++)
            {
                GrandExchangeOffer offer = offers[slot];
                if (offer == null)
                {
                    complete = false;
                    break;
                }
                geOfferLedger.observe(GeOfferLedger.Snapshot.fromOffer(slot, offer));
            }
        }
        if (complete)
        {
            geOfferLedger.finishLoginSeed();
            // Offers that progressed while the client was away settle now (pass 10 step 45).
            for (GeOfferLedger.Transition resumed : geOfferLedger.takeResumedProgress())
            {
                handleGeOfferTransition(resumed);
            }
        }
        else
        {
            // Do not treat a partial/null slot array as an empty-account baseline.
            // Retry until all eight current slots are observed.
            geOfferSeedTicks = 1;
        }
    }

    /** Current immutable read model; client and widget reads happen only from GameTick. */
    public WealthLocationsSnapshot getWealthLocationsSnapshot()
    {
        return wealthLocationsSnapshot;
    }

    private void refreshWealthLocationsSnapshot(long now)
    {
        List<WealthLocationSnapshot> locations = new ArrayList<>();
        boolean mainBankOpen = isMainBankUiOpen();
        if (mainBankOpen && !wealthBankVisitOpen)
        {
            beginBankWealthVisit();
        }
        else if (!mainBankOpen && wealthBankVisitOpen)
        {
            wealthBankVisitOpen = false;
            wealthBankReadPending = false;
            wealthBankCapturePending = false;
            wealthBankLocationSnapshot = null;
            bankWealthCaptureGate.endVisit();
        }

        if (client != null)
        {
            locations.add(WealthLocationsFactory.container("inventory", "Inventory",
                client.getItemContainer(InventoryID.INV),
                itemManager == null ? null : itemManager::canonicalize,
                itemId -> wealthUnitQuote(itemId, now), now));
            locations.add(WealthLocationsFactory.container("worn", "Worn equipment",
                client.getItemContainer(InventoryID.WORN),
                itemManager == null ? null : itemManager::canonicalize,
                itemId -> wealthUnitQuote(itemId, now), now));
            if (snapshotFactory != null && snapshotFactory.isRunePouchContentsReadable())
            {
                locations.add(WealthLocationsFactory.quantities("rune_pouch", "Rune pouch",
                    snapshotFactory.runePouchContents(),
                    itemId -> wealthUnitQuote(itemId, now), now));
            }
            if (config.wealthBankReadEnabled())
            {
                if (mainBankOpen)
                {
                    if (wealthBankReadPending)
                    {
                        wealthBankLocationSnapshot = WealthLocationsFactory.container(
                            "bank", "Bank", client.getItemContainer(InventoryID.BANK),
                            itemManager == null ? null : itemManager::canonicalize,
                            itemId -> wealthUnitQuote(itemId, now), now);
                        wealthBankReadPending = false;
                        if (wealthBankLocationSnapshot.isAvailable()
                            || wealthBankLocationSnapshot.getStatus() == WealthLocationSnapshot.Status.UNPRICED)
                        {
                            lastBankQuantities = bankQuantities(client.getItemContainer(InventoryID.BANK));
                            lastBankReadAtEpochMillis = now;
                            lastBankRepriced = null;
                        }
                    }
                    locations.add(wealthBankLocationSnapshot == null
                        ? new WealthLocationSnapshot("bank", "Bank",
                            WealthLocationSnapshot.Status.INITIALIZING, 0L, now,
                            Collections.emptyList(), "Waiting for a visible-bank container update")
                        : wealthBankLocationSnapshot);
                }
                else if (lastBankQuantities != null)
                {
                    // The bank cannot be read while closed; what it held at the last visit is still the
                    // best figure there is, so keep it and reprice it at today's prices once a minute.
                    if (lastBankRepriced == null || now - lastBankRepricedAtEpochMillis > 60_000L)
                    {
                        WealthLocationSnapshot repriced = WealthLocationsFactory.quantities("bank", "Bank",
                            lastBankQuantities, itemId -> wealthUnitQuote(itemId, now), lastBankReadAtEpochMillis);
                        lastBankRepriced = new WealthLocationSnapshot("bank", "Bank", repriced.getStatus(),
                            repriced.getValueGp(), lastBankReadAtEpochMillis, repriced.getItems(),
                            "As of the last bank visit, repriced now \u00b7 " + repriced.getDetail());
                        lastBankRepricedAtEpochMillis = now;
                    }
                    locations.add(lastBankRepriced);
                }
                else
                {
                    locations.add(new WealthLocationSnapshot("bank", "Bank",
                        WealthLocationSnapshot.Status.CLOSED, 0L, now, Collections.emptyList(),
                        "Open the main bank once to capture a snapshot"));
                }
            }
        }
        if (config.showGrandExchangeOfferWealth())
        {
            Map<Integer, GeOfferLedger.Snapshot> offersBySlot = new HashMap<>();
            GrandExchangeOffer[] offers = client == null ? null : client.getGrandExchangeOffers();
            if (offers != null)
            {
                for (int slot = 0; slot < offers.length; slot++)
                {
                    if (offers[slot] != null)
                    {
                        offersBySlot.put(slot, GeOfferLedger.Snapshot.fromOffer(slot, offers[slot]));
                    }
                }
            }
            locations.add(WealthLocationsFactory.grandExchangeOffers(
                offersBySlot, !geOfferLedger.isLoginSeedInProgress(), this::wealthItemName, now));
        }
        if (config.showGrandExchangeCollectionWealth())
        {
            locations.add(readCollectionBoxWealth(now));
        }
        wealthLocationsSnapshot = new WealthLocationsSnapshot(now, locations);

        if (mainBankOpen && wealthBankCapturePending && config.wealthHistoryEnabled()
            && config.wealthBankReadEnabled())
        {
            WealthLocationSnapshot bank = wealthLocationsSnapshot.getLocation("bank");
            String hash = bankContentHash(client == null ? null : client.getItemContainer(InventoryID.BANK));
            boolean completeBankRead = bank != null
                && (bank.isAvailable() || bank.getStatus() == WealthLocationSnapshot.Status.UNPRICED);
            if (completeBankRead && bankWealthCaptureGate.observe(hash))
            {
                wealthBankCapturePending = false;
                WealthLocationsSnapshot durable = historyCaptureSnapshot(wealthLocationsSnapshot);
                engine.recordWealthSnapshot(durable, now);
                ticksSinceSave = 0;
                persist();
            }
        }
    }

    private void beginBankWealthVisit()
    {
        if (wealthBankVisitOpen) return;
        wealthBankVisitOpen = true;
        wealthBankReadPending = false;
        wealthBankCapturePending = true;
        wealthBankLocationSnapshot = null;
        bankWealthCaptureGate.beginVisit();
    }

    private ItemFlow wealthUnitQuote(int itemId, long capturedAtEpochMillis)
    {
        if (itemId == 995)
        {
            return new ItemFlow(itemId, "Coins", 1L, 1, 1L,
                ItemPriceSource.FACE_VALUE, capturedAtEpochMillis);
        }
        return itemValuationService == null ? null
            : itemValuationService.quoteForWealth(itemId, capturedAtEpochMillis);
    }

    private Map<Integer, Long> bankQuantities(ItemContainer bank)
    {
        Map<Integer, Long> quantities = new java.util.TreeMap<>();
        if (bank == null || bank.getItems() == null) return quantities;
        for (Item item : bank.getItems())
        {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) continue;
            int id = itemManager == null ? item.getId() : itemManager.canonicalize(item.getId());
            if (id > 0) quantities.merge(id, (long) item.getQuantity(), Long::sum);
        }
        return quantities;
    }

    private static String bankContentHash(ItemContainer bank)
    {
        if (bank == null || bank.getItems() == null) return "";
        StringBuilder key = new StringBuilder();
        Item[] items = bank.getItems();
        for (int slot = 0; slot < items.length; slot++)
        {
            Item item = items[slot];
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
            {
                key.append(slot).append(':').append(item.getId()).append(':')
                    .append(item.getQuantity()).append(';');
            }
        }
        return key.length() == 0 ? "empty-bank" : key.toString();
    }

    /** Closed, unavailable UI-only locations are omitted; opening them changes coverage. */
    private static WealthLocationsSnapshot historyCaptureSnapshot(WealthLocationsSnapshot source)
    {
        List<WealthLocationSnapshot> availableSources = new ArrayList<>();
        for (WealthLocationSnapshot location : source.getLocations())
        {
            if (location.getStatus() != WealthLocationSnapshot.Status.CLOSED)
            {
                availableSources.add(location);
            }
        }
        return new WealthLocationsSnapshot(source.getCapturedAtEpochMillis(), availableSources);
    }

    private WealthLocationSnapshot readCollectionBoxWealth(long now)
    {
        Widget frame = client == null ? null : client.getWidget(InterfaceID.GeCollect.FRAME);
        boolean visible = frame != null && !frame.isHidden();
        if (!visible)
        {
            return WealthLocationsFactory.collectionBox(false, true, Collections.emptyList(), now);
        }

        List<WealthLocationSnapshot.Item> items = new ArrayList<>();
        boolean allSlotsPresent = true;
        for (int slot = 0; slot < 8; slot++)
        {
            Widget widget = client.getWidget(InterfaceID.GeCollect.COLLECT_0 + slot);
            if (widget == null)
            {
                allSlotsPresent = false;
                continue;
            }
            int itemId = widget.getItemId();
            int quantity = widget.getItemQuantity();
            if (itemId <= 0 || quantity <= 0)
            {
                continue;
            }

            int canonicalId = itemManager == null ? -1 : itemManager.canonicalize(itemId);
            int unitPrice = 0;
            ItemPriceSource source = ItemPriceSource.UNPRICED;
            if (itemId == 995)
            {
                unitPrice = 1;
                source = ItemPriceSource.FACE_VALUE;
            }
            else if (itemManager != null && canonicalId > 0)
            {
                unitPrice = Math.max(0, itemManager.getItemPrice(canonicalId));
                if (unitPrice > 0)
                {
                    source = ItemPriceSource.GRAND_EXCHANGE;
                }
            }
            items.add(new WealthLocationSnapshot.Item(slot, itemId, wealthItemName(itemId),
                quantity, unitPrice, source, unitPrice > 0 ? now : 0L));
        }
        return WealthLocationsFactory.collectionBox(true, allSlotsPresent, items, now);
    }

    private String wealthItemName(int itemId)
    {
        if (itemManager == null)
        {
            return "Item " + itemId;
        }
        try
        {
            ItemComposition composition = itemManager.getItemComposition(itemId);
            return composition == null ? "Item " + itemId : composition.getName();
        }
        catch (RuntimeException ex)
        {
            return "Item " + itemId;
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!ingestion.canProcessItemContainer())
        {
            return;
        }
        markMeaningfulActivity();
        int containerId = event.getContainerId();
        if (containerId == InventoryID.BANK)
        {
            if (isMainBankUiOpen())
            {
                beginBankWealthVisit();
                wealthBankReadPending = true;
                if (!bankWealthCaptureGate.isCaptured()) wealthBankCapturePending = true;
                bankWealthCaptureGate.markBankContentsChanged();
            }
            engine.markContext(
                TrackingContext.TRANSFER,
                config.correlationTicks(),
                "Bank transfer");
        }

        if (containerId == InventoryID.INV
            || containerId == InventoryID.WORN)
        {
            // Inventory can fire after the bank widget is gone but before the next
            // GameTick. Close soft transfer evidence first so burial is not latched.
            if (!isBankOpen())
            {
                engine.markBankInterfaceClosed();
            }
            // The Grand Exchange open while the inventory settles: placing, collecting and refunds
            // are market movements, never consumption or plain gains.
            if (isGrandExchangeOpen())
            {
                engine.markContext(
                    TrackingContext.MARKET,
                    Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 8),
                    "Grand Exchange");
            }
            // Player trade UI open while invent settles → TRADE (Traded), not GAIN.
            if (isPlayerTradeOpen())
            {
                engine.markContext(
                    TrackingContext.MARKET,
                    Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 8),
                    "Player trade");
            }
            engine.markInventoryDirty();
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        if (event == null || event.getOffer() == null || event.getSlot() < 0)
        {
            return;
        }

        // The API replays every slot as EMPTY at login, followed by each offer
        // that the server restores. Keep these as baselines; the ledger is
        // observation-only until that seed is finished.
        GeOfferLedger.Snapshot snapshot = GeOfferLedger.Snapshot.fromOffer(
            event.getSlot(), event.getOffer());
        java.util.Optional<GeOfferLedger.Transition> observed = geOfferLedger.observe(snapshot);
        if (observed.isPresent())
        {
            handleGeOfferTransition(observed.get());
        }
    }

    private void handleGeOfferTransition(GeOfferLedger.Transition transition)
    {
        int observedItem = transition.getCurrent().getItemId() > 0 || transition.getPrevious() == null
            ? transition.getCurrent().getItemId() : transition.getPrevious().getItemId();
        engine.noteGeOfferObservation(transition, observedItem > 0 ? wealthItemName(observedItem) : "", System.currentTimeMillis());
        // The offer event itself is the strongest evidence that the inventory change settling now
        // (items into a sell offer, coins into a buy, a refund back) is a Grand Exchange movement.
        engine.markContext(
            TrackingContext.MARKET,
            Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 8),
            "Grand Exchange offer");
        {
            if (transition.hasComparableProgress() && transition.getQuantityTradedDelta() > 0)
            {
                long now = System.currentTimeMillis();
                int itemId = transition.getCurrent().getItemId();
                int canonicalId = itemManager == null ? -1 : itemManager.canonicalize(itemId);
                int unitPrice = itemManager == null || canonicalId <= 0 ? 0 : Math.max(0, itemManager.getItemPrice(canonicalId));
                // Observed booking (pass 10 step 45): in that mode the trade is settled here and the
                // inventory movement later is neutral; otherwise the observation is provenance only.
                if (engine.bookObservedGeSettlement(transition, wealthItemName(itemId), unitPrice, now) != null)
                {
                    return;
                }
                pendingGeOfferProvenance.addLast(new GeOfferProvenanceMatcher.Observation(
                    transition, now));
                while (pendingGeOfferProvenance.size() > MAX_PENDING_GE_OFFER_PROVENANCE)
                {
                    pendingGeOfferProvenance.removeFirst();
                }
            }
        }
    }

    /**
     * Links one recent, comparable offer observation to an already-booked MARKET receipt.
     * This is provenance only: it cannot add flows, set values or change receipt eligibility.
     */
    private void attachRecentGeOfferProvenance(ProfitTransaction transaction, long now)
    {
        if (transaction == null
            || transaction.getContext() != TrackingContext.MARKET
            || transaction.getType() != TransactionType.TRADE
            || transaction.getNote().toLowerCase(Locale.ROOT).contains("player trade"))
        {
            return;
        }

        long windowMillis = Math.max(1, config.correlationTicks()) * 600L;
        while (!pendingGeOfferProvenance.isEmpty()
            && now - pendingGeOfferProvenance.peekFirst().getObservedAtEpochMillis() > windowMillis)
        {
            pendingGeOfferProvenance.removeFirst();
        }

        GeOfferProvenanceMatcher.Observation observed = GeOfferProvenanceMatcher.uniqueMatch(
            transaction, pendingGeOfferProvenance, now, windowMillis).orElse(null);
        if (observed == null)
        {
            return;
        }

        transaction.setGeOfferProvenance(observed.toProvenance());
        pendingGeOfferProvenance.remove(observed);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        // Rune pouch contents live in varbits, not an item container. A change there is
        // an inventory change for accounting: casting from the pouch is a cost, filling
        // it from the inventory nets to zero.
        if (event == null || !config.includeRunePouch()
            || !com.gpmanager.engine.ContainerSnapshotFactory.isRunePouchVarbit(event.getVarbitId()))
        {
            return;
        }
        if (!ingestion.canProcessItemContainer())
        {
            return;
        }
        markMeaningfulActivity();
        if (!isBankOpen())
        {
            engine.markBankInterfaceClosed();
        }
        engine.markInventoryDirty();
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!ingestion.canIngestGameplay() || event == null)
        {
            return;
        }
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.SPAM && type != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }
        String message = normalize(event.getMessage());
        if (observeMeasuredChargeCheck(message, System.currentTimeMillis()))
        {
            // Charge Check chat is an explicit measurement, never an activity or title signal.
            return;
        }
        // Any unrelated chat between click and numeric response makes the target
        // association uncertain; the next Check must establish fresh intent.
        measuredChargeCheckIntent.clear();
        if (message.contains("accepted trade"))
        {
            // Trade completed chat — keep MARKET armed through inventory settle.
            engine.markContext(
                TrackingContext.MARKET,
                Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 8),
                "Player trade");
            markMeaningfulActivity();
            return;
        }
        // Chat / Make-X evidence may support a receipt intent, but is not title evidence.
        String chatSkill = ProcessSkillSignals.skillFromChatMessage(message);
        if (!chatSkill.isEmpty())
        {
            if (ProcessSkillSignals.armsOpenProcessSpendFromChat(chatSkill)
                && rewardPresentationModel != null
                && rewardPresentationModel.getArmedProcessSpendSkill().isEmpty())
            {
                rewardPresentationModel.noteProcessSpendIntent(
                    chatSkill, -1, System.currentTimeMillis());
            }
            // Chaos altar bone-save is not a spend — inventory may keep the bone.
            if (ProcessSkillSignals.isChaosAltarBoneSaveChat(message))
            {
                markMeaningfulActivity();
                return;
            }
            if (ProcessSkillSignals.isSinisterOfferingChat(message)
                && rewardPresentationModel != null)
            {
                rewardPresentationModel.noteProcessSpendIntent(
                    "Prayer",
                    -1,
                    System.currentTimeMillis(),
                    ProcessSkillSignals.PrayerSpendMode.OFFER.wireName());
            }
            markMeaningfulActivity();
            // Fall through — consume chat may also reinforce bury/drink intent below.
        }
        if (!isConsumptionChatMessage(message))
        {
            return;
        }
        // Live bury/drink often animate after the menu click; chat confirms the spend
        // and keeps open intent alive until inventory stabilizes (incl. coalesced picks).
        int intentTicks = Math.max(
            config.correlationTicks() * 2,
            config.stabilizationTicks() + 16);
        engine.reinforceConsumptionIntent(intentTicks);
        markMeaningfulActivity();
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!ingestion.canIngestGameplay() || event == null || event.isConsumed())
        {
            return;
        }
        // An unrelated later action supersedes an altar candidate before any XP arrives.
        prayerAltarTitleEvidence.clear();
        String option = normalize(event.getMenuOption());
        if (isHarvestGatherOption(option) || isInventoryConsumptionOption(option))
        {
            // A genuine action can produce an item without XP or an animation
            // (field crops). Capture its pre-action inventory on automatic start.
            markGameplayActivity(true);
        }
        interactionContextTracker.onMenuOptionClicked(event);
        refreshInteractionDisplay();
        markMeaningfulActivity();
        String target = normalize(event.getMenuTarget());
        String joined = option + " " + target;
        engine.observeKeyChestInteraction(option, target);
        armMeasuredChargeCheck(event, option, target);
        armChargeLoadTransfer(event, target);

        if (isInventoryConsumptionOption(option))
        {
            int itemId = resolveMenuItemId(event);
            // Always note intent — even when item id is unresolved (open intent).
            // Supplies Tracker confirms spend via inventory slot change after Eat/Drink;
            // we confirm on stable inventory removal / dose leftover.
            // Drink/bury animations often outlast correlationTicks; keep intent alive
            // through inventory lag and a coalesced Pick settle window.
            int intentTicks = Math.max(
                config.correlationTicks() * 2,
                config.stabilizationTicks() + 16);
            // Carry the menu verb so the settled row can say Drank/Ate/Buried honestly.
            engine.noteConsumptionIntent(
                itemId, intentTicks, false, com.gpmanager.engine.ActionEvidence.fromMenuOption(option));
            if (isLossOnlyProcessSpendOption(option))
            {
                String skill = processSpendSkillForOption(option);
                armLossOnlyProcessSpend(skill, itemId, ProcessSkillSignals.prayerModeFromOption(option));
            }
        }
        else if ("drop".equals(option))
        {
            int itemId = resolveMenuItemId(event);
            // Prefer resolved id only — open drop (itemId=-1) needs safer engine matching.
            if (itemId >= 0)
            {
                // Match consume TTL so dump-then-settle does not miss power-train drops.
                int intentTicks = Math.max(
                    config.correlationTicks() * 2,
                    config.stabilizationTicks() + 16);
                Player local = client.getLocalPlayer();
                if (local != null && local.getWorldLocation() != null)
                {
                    net.runelite.api.coords.WorldPoint tile = local.getWorldLocation();
                    engine.noteDropIntent(
                        itemId,
                        intentTicks,
                        tile.getX(),
                        tile.getY(),
                        tile.getPlane(),
                        true);
                    syncPlayerWorldLocation(local);
                }
                else
                {
                    engine.noteDropIntent(itemId, intentTicks, 0, 0, 0, false);
                }
            }
        }
        else if ("destroy".equals(option))
        {
            // Irreversible loss — consume-style intent, never recoverable own-drop.
            int itemId = resolveMenuItemId(event);
            int intentTicks = Math.max(
                config.correlationTicks() * 2,
                config.stabilizationTicks() + 16);
            engine.noteConsumptionIntent(itemId, intentTicks, true);
        }

        if ("make".equals(option))
        {
            // Make-X alone — soft-stick until transform XP / question supplies the title.
            softStickProduction = true;
        }

        // Use Tinderbox ↔ Logs (inventory Use) — same as Light when resolvable.
        if (ProcessSkillSignals.isFiremakingUsePair(option, target))
        {
            armLossOnlyProcessSpend("Firemaking", resolveMenuItemId(event));
        }
        // Use bones/ashes ↔ altar (PoH / wildy) — Prayer Offered after XP.
        if (ProcessSkillSignals.isPrayerAltarUsePair(option, target))
        {
            prayerAltarTitleEvidence.arm(
                System.currentTimeMillis(), RewardPresentationModel.PROCESS_SPEND_WAIT_MILLIS);
            int altarItemId = resolveMenuItemId(event);
            int intentTicks = Math.max(
                config.correlationTicks() * 2,
                config.stabilizationTicks() + 16);
            engine.noteConsumptionIntent(
                altarItemId, intentTicks, false, com.gpmanager.model.ActionKind.OFFER);
            armLossOnlyProcessSpend(
                "Prayer", altarItemId, ProcessSkillSignals.PrayerSpendMode.OFFER);
        }
        // Agility dispensers (Wilderness course rewards, Brimhaven tickets): the claim keeps the
        // course title; the rewards book as ordinary inventory gains.
        if (com.gpmanager.engine.AgilityCourses.isDispenser(target))
        {
            Player local = client.getLocalPlayer();
            String course = local == null || local.getWorldLocation() == null ? null
                : com.gpmanager.engine.AgilityCourses.courseName(local.getWorldLocation().getRegionID());
            observeActivity(course == null ? "Agility" : course, true);
        }
        // Sinister Offering cast — bones → Offered after Prayer XP.
        if (ProcessSkillSignals.isSinisterOfferingCast(option, target))
        {
            armLossOnlyProcessSpend(
                "Prayer", resolveMenuItemId(event), ProcessSkillSignals.PrayerSpendMode.OFFER);
        }

        // Farming / gather object actions: activity label only (accounting is inventory GAIN).
        if (isHarvestGatherOption(option))
        {
            observeActivity(activityHintForGatherOption(option), true);
        }
        else if (HudPlusProcessLabels.isTrackedObjectOption(option)
            && !isInventoryConsumptionOption(option))
        {
            // Process stations, thieve/hunter/agility/farm verbs — HUD / Insights hints.
            if (HudPlusProcessLabels.isTransformProductionOption(option))
            {
                String skill = HudPlusProcessLabels.transformSkillForOption(option);
                if (!skill.isEmpty())
                {
                    // A station/menu hint may arm production context, but XP owns its title.
                    noteStickyProcessTitle(skill);
                    armProductionContext(skill);
                }
                else
                {
                    observeNonProcessMenuActivity(HudPlusProcessLabels.activityHintFromOption(option));
                }
            }
            else if (isLossOnlyProcessSpendOption(option))
            {
                String skill = processSpendSkillForOption(option);
                if (!skill.isEmpty())
                {
                    armLossOnlyProcessSpend(
                        skill, resolveMenuItemId(event), ProcessSkillSignals.prayerModeFromOption(option));
                }
                else
                {
                    observeNonProcessMenuActivity(HudPlusProcessLabels.activityHintFromOption(option));
                }
            }
            else
            {
                observeNonProcessMenuActivity(HudPlusProcessLabels.activityHintFromOption(option));
            }
        }
        else if (HudPlusProcessLabels.gerundFromMenuOption(option) != null
            && !isInventoryConsumptionOption(option))
        {
            // NPC options like steal/pickpocket (not GAME_OBJECT).
            observeNonProcessMenuActivity(HudPlusProcessLabels.activityHintFromOption(option));
        }
        else if ("clean".equals(option))
        {
            noteStickyProcessTitle("Herblore");
            armProductionContext("Herblore");
        }
        else if (HudPlusProcessLabels.isTransformProductionOption(option)
            && isInventoryTransformOption(option))
        {
            // Inventory craft/fletch/mix without a scenery object click.
            String skill = HudPlusProcessLabels.transformSkillForOption(option);
            if (!skill.isEmpty())
            {
                noteStickyProcessTitle(skill);
                armProductionContext(skill);
            }
        }

        com.gpmanager.engine.BossRetrievalCatalogue.Service retrieval =
            com.gpmanager.engine.BossRetrievalCatalogue.forMenu(option, target);
        if (retrieval != null)
        {
            // Long window: dialogue + confirm + coins + items can take several ticks.
            boolean armed = engine.noteDeathReclaimIntent(
                retrieval, Math.max(config.correlationTicks() * 4, config.stabilizationTicks() + 30));
            if (armed)
            {
                observeActivity("Death reclaim", true);
                return;
            }
        }

        if (joined.contains("bank") || joined.contains("deposit"))
        {
            if (joined.contains("deposit box") || target.contains("deposit box"))
            {
                engine.markNeutralStorageTransfer("Deposit box transfer", config.correlationTicks());
            }
            else
            {
                engine.markContext(
                    TrackingContext.TRANSFER,
                    config.correlationTicks(),
                    "Bank/deposit transfer");
            }
            return;
        }

        com.gpmanager.engine.NeutralStorageClassifier.Kind neutral =
            com.gpmanager.engine.NeutralStorageClassifier.classify(joined);
        if (neutral == null)
        {
            neutral = com.gpmanager.engine.NeutralStorageClassifier.classify(target);
        }
        if (neutral != null)
        {
            engine.markNeutralStorageTransfer(
                com.gpmanager.engine.NeutralStorageClassifier.transferNote(neutral),
                config.correlationTicks());
            if (panel != null)
            {
                panel.observeWealthLocation("coffers",
                    com.gpmanager.engine.NeutralStorageClassifier.transferNote(neutral));
            }
            return;
        }

        String containerFamily =
            com.gpmanager.engine.evidence.UtilityContainerCatalogue.familyForItemName(target);
        if (containerFamily != null
            && ("fill".equals(option) || "empty".equals(option) || option.startsWith("empty")
                || "check".equals(option) || "open".equals(option) || "view".equals(option)))
        {
            boolean depositBox = joined.contains("deposit box");
            com.gpmanager.engine.evidence.ContainerEvidence.Kind kind =
                com.gpmanager.engine.evidence.UtilityContainerCatalogue.classifyOption(
                    option, depositBox);
            if (com.gpmanager.engine.evidence.UtilityContainerCatalogue.isOwnershipNeutral(kind))
            {
                String note = "Container " + kind.name().toLowerCase(Locale.ROOT)
                    + " (" + containerFamily + ")";
                if (com.gpmanager.engine.LiveProducerBridge.looksLikePlankSackAmbiguous(option, target))
                {
                    note = "Plank sack delta ambiguous — Review";
                }
                engine.markContext(
                    TrackingContext.TRANSFER,
                    config.correlationTicks(),
                    note);
            }
            if (kind == com.gpmanager.engine.evidence.ContainerEvidence.Kind.CALIBRATE_CHECK
                || "check".equals(option))
            {
                calibrateChargeFamily(containerFamily);
            }
            if (panel != null)
            {
                if (com.gpmanager.engine.evidence.ChargeFamilyIds.FORESTRY_KIT.equals(containerFamily))
                {
                    panel.observeWealthLocation("raid_bags", "Forestry kit opened");
                }
                else if (com.gpmanager.engine.evidence.ChargeFamilyIds.HERB_SACK.equals(containerFamily)
                    || com.gpmanager.engine.evidence.ChargeFamilyIds.SILK_LINED_HERB_SACK.equals(containerFamily)
                    || com.gpmanager.engine.evidence.ChargeFamilyIds.ESSENCE_POUCH.equals(containerFamily))
                {
                    panel.observeWealthLocation("raid_bags", containerFamily + " " + option);
                }
            }
            return;
        }

        // Wealth locate observe (never Net).
        if (panel != null)
        {
            com.gpmanager.engine.LiveProducerBridge.observeWealthFromMenu(
                panel::observeWealthLocation, option, target);
        }

        // A menu click alone cannot establish that a key was destroyed or dropped.
        // Its settled inventory loss is handled by the ordinary evidence path.
        if (("destroy".equals(option) || "drop".equals(option))
            && com.gpmanager.engine.LiveProducerBridge.looksLikeLootKeyGain(target))
        {
            return;
        }

        com.gpmanager.engine.MinigameTransferClassifier.LiveSignals liveSignals =
            currentMinigameLiveSignals();
        com.gpmanager.engine.MinigameTransferClassifier.Event minigame =
            com.gpmanager.engine.MinigameTransferClassifier.classify(joined, liveSignals);
        if (minigame != null)
        {
            engine.markMinigameTransfer(
                com.gpmanager.engine.MinigameTransferClassifier.transferNote(minigame),
                config.correlationTicks());
            if (panel != null)
            {
                if (minigame.name().startsWith("GIM"))
                {
                    panel.observeWealthLocation("gim", minigame.name());
                }
                else if (minigame.name().startsWith("RAID"))
                {
                    panel.observeWealthLocation("raid_bags", minigame.name());
                }
            }
            return;
        }

        if (option.startsWith("buy")
            || option.startsWith("sell")
            || option.startsWith("collect"))
        {
            observeActivity("Trading", true);
            engine.markContext(
                TrackingContext.MARKET,
                config.correlationTicks(),
                option + (target.isEmpty() ? "" : " " + target));
            return;
        }

        // Player–player trade: arm MARKET so settle classifies as TRADE → Traded flash.
        // Ownership-neutral peer exchange — no GE tax (note must include "player trade").
        if (option.contains("accept trade")
            || option.equals("trade with")
            || option.startsWith("trade with"))
        {
            observeActivity("Trading", true);
            engine.markContext(
                TrackingContext.MARKET,
                Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 8),
                "Player trade");
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        if (!ingestion.canProcessLoot())
        {
            return;
        }
        markGameplayActivity();
        if (engine.getActiveSession() == null)
        {
            return;
        }
        String npcName = event.getNpc() == null ? "NPC loot" : event.getNpc().getName();
        if (npcName == null || npcName.trim().isEmpty())
        {
            npcName = "NPC loot";
        }
        observeActivity(npcName, true);

        Map<Integer, Long> expectedLoot = new HashMap<>();
        for (ItemStack item : event.getItems())
        {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
            {
                continue;
            }
            expectedLoot.merge(
                itemManager.canonicalize(item.getId()),
                (long) item.getQuantity(),
                Long::sum);
        }

        engine.recordAction(npcName);
        int lootMemoryTicks = Math.max(
            config.correlationTicks(),
            Math.max(1, config.lootCorrelationSeconds()) * 1000 / 600);
        engine.markLootContext(
            expectedLoot,
            lootMemoryTicks,
            "Loot from " + npcName,
            npcName);
        offerObservedReward(
            RewardSourceKind.NPC_LOOT,
            npcName,
            "npc:" + npcName + ":" + System.nanoTime(),
            event.getItems());
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        if (!ingestion.canProcessLoot() || event == null)
        {
            return;
        }
        String npcName = event.getComposition() == null ? "NPC loot" : event.getComposition().getName();
        if (npcName == null || npcName.trim().isEmpty())
        {
            npcName = "NPC loot";
        }
        offerObservedReward(
            RewardSourceKind.SERVER_NPC_LOOT,
            npcName,
            "server-npc:" + npcName + ":" + System.nanoTime(),
            event.getItems());
    }

    @Subscribe
    public void onLootReceived(LootReceived event)
    {
        // Optional: only fires when Loot Tracker is enabled. Deduped against
        // NpcLootReceived / ServerNpcLoot for the same kill.
        if (!ingestion.canProcessLoot() || event == null || event.getItems() == null)
        {
            return;
        }
        String name = event.getName() == null || event.getName().trim().isEmpty()
            ? "Reward"
            : event.getName().trim();
        RewardSourceKind kind = RewardSourceKind.LOOT_TRACKER;
        if (event.getType() != null)
        {
            switch (event.getType())
            {
                case PLAYER:
                    kind = RewardSourceKind.PLAYER_LOOT;
                    break;
                case EVENT:
                    kind = isPendingRewardEventName(name)
                        ? RewardSourceKind.PENDING_REWARDS
                        : RewardSourceKind.LOOT_TRACKER;
                    break;
                case NPC:
                case PICKPOCKET:
                case UNKNOWN:
                default:
                    kind = RewardSourceKind.LOOT_TRACKER;
                    break;
            }
        }
        offerObservedReward(
            kind,
            name,
            "loottracker:" + name + ":" + System.nanoTime(),
            event.getItems(),
            Math.max(1, event.getAmount()));
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        if (!ingestion.canProcessLoot())
        {
            return;
        }
        markGameplayActivity();
        observeActivity("PKing", true);
        if (!config.enablePkTracking())
        {
            return;
        }

        Map<Integer, Long> expectedLoot = new HashMap<>();
        for (ItemStack item : event.getItems())
        {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0)
            {
                continue;
            }
            expectedLoot.merge(
                itemManager.canonicalize(item.getId()),
                (long) item.getQuantity(),
                Long::sum);
        }

        Player player = event.getPlayer();
        String label = "Player kill";
        if (config.storeOpponentNames()
            && player != null
            && player.getName() != null
            && !player.getName().trim().isEmpty())
        {
            label = "Kill: " + player.getName().trim();
        }

        long now = System.currentTimeMillis();
        syncPlayerWorldLocation(client.getLocalPlayer());
        int lootMemoryTicks = Math.max(
            config.correlationTicks(),
            Math.max(1, config.lootCorrelationSeconds()) * 1000 / 600);
        engine.markPkLootContext(expectedLoot, lootMemoryTicks, label, now);
        String sourceName = player != null && player.getName() != null && !player.getName().trim().isEmpty()
            ? player.getName().trim()
            : "Player kill";
        offerObservedReward(
            RewardSourceKind.PLAYER_LOOT,
            sourceName,
            "player:" + sourceName + ":" + System.nanoTime(),
            event.getItems());
        persist();
        recentPlayerCombatTicks = 0;
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event)
    {
        if (event.getSource() == client.getLocalPlayer())
        {
            if (event.getTarget() instanceof NPC)
            {
                // Use the existing authorized lifecycle for an actual interaction, never a menu hover.
                markGameplayActivity();
            }
            interactionContextTracker.onInteractingChanged(event);
            refreshInteractionDisplay();
        }
        if (!ingestion.canIngestGameplay() || client.getLocalPlayer() == null)
        {
            return;
        }

        boolean localToPlayer = event.getSource() == client.getLocalPlayer()
            && event.getTarget() instanceof Player;
        boolean playerToLocal = event.getTarget() == client.getLocalPlayer()
            && event.getSource() instanceof Player;
        if (localToPlayer || playerToLocal)
        {
            // Keep short-lived evidence for death-safety classification even
            // when PK accounting is disabled. That evidence prevents a lingering
            // NPC target from turning a PvP death into an ownership-neutral reclaim.
            recentPlayerCombatTicks = 20;
            if (config.enablePkTracking())
            {
                markMeaningfulActivity();
                observeActivity("PKing", true);
            }
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        Player localPlayer = client.getLocalPlayer();
        if (!ingestion.canIngestGameplay()
            || localPlayer == null
            || event.getActor() != localPlayer)
        {
            return;
        }

        // Preserve the pre-death held-key evidence before any disposition branch.
        // A later stabilized key loss can then close its audit row without treating
        // the unowned manifest as a loss.
        engine.beginLootKeyLocalDeathSettle(com.gpmanager.engine.LootKeyLifecycle.keyQuantities(
            client.getItemContainer(InventoryID.INV)));
        syncPlayerWorldLocation(localPlayer);

        // DeathKeep, Protect Item, and skull state are a one-time explanation
        // snapshot. They are never consumed by disposition or accounting logic.
        LocalDeathEvidence deathEvidence = LocalDeathEvidence.capture(
            client.getWidget(InterfaceID.DEATHKEEP),
            client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1,
            localPlayer.getSkullIcon(),
            itemManager == null ? null : itemManager::canonicalize,
            itemId ->
            {
                if (itemManager == null)
                {
                    return null;
                }
                net.runelite.api.ItemComposition composition = itemManager.getItemComposition(itemId);
                return composition == null ? null : composition.getName();
            });
        // Separate ownership input: this canonical INV/WORN read is intersected with
        // settled negative item flows by the engine. DeathKeep/Protect Item/skull remain
        // explanation-only and never determine a flow or its counted state.
        ContainerSnapshot deathHeldItems = snapshotFactory == null
            ? null
            : snapshotFactory.captureDeathHeldItems(config.includeEquipment());

        // A death outside a PvP-capable area cannot be a PvP death, even when a
        // poison/venom tick arrives after the target context has disappeared.
        // In PvP-capable areas require either recent player combat or NPC context.
        boolean pvpPossible = client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
            || client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) == 1;
        boolean pvmContext = interactionContextTracker != null
            && interactionContextTracker.hasPvmContext();
        LocalDeathDisposition disposition = localDeathDisposition(
            config.enablePkTracking(), recentPlayerCombatTicks, pvmContext, pvpPossible);

        // PvM or PK: drop NPC/process soft-stick so "Dark wizard" (etc.) cannot
        // keep titling HUD+ after death. Independent of enablePkTracking.
        clearHudPlusOnLocalDeath();
        if (disposition == LocalDeathDisposition.TRACK_PK)
        {
            markMeaningfulActivity();
            observeActivity("PKing", true);
            engine.markPkDeath("Player death", System.currentTimeMillis(), deathEvidence);
            persist();
            recentPlayerCombatTicks = 0;
            return;
        }
        if (disposition == LocalDeathDisposition.UNCLASSIFIED)
        {
            // Missing PvP evidence alone cannot prove this was a PvM death. Let the
            // inventory delta use ordinary loss classification rather than hiding a loss.
            engine.markUnclassifiedLocalDeath(deathEvidence);
            markMeaningfulActivity();
            recentPlayerCombatTicks = 0;
            return;
        }
        // PvM death: gear moves to a gravestone / Item Retrieval Service the player
        // still owns. The wipe is an ownership-neutral transfer; the reclaim window
        // later returns the items and books observed coins as the fee.
        markMeaningfulActivity();
        engine.markLocalPvmDeath(
            Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 16),
            deathEvidence,
            deathHeldItems == null ? null : deathHeldItems.getQuantities());
    }

    static LocalDeathDisposition localDeathDisposition(
        boolean pkTrackingEnabled,
        int playerCombatTicks,
        boolean pvmContext,
        boolean pvpPossible)
    {
        if (playerCombatTicks > 0)
        {
            // With PK accounting disabled, preserve the observed PvP evidence only
            // to prevent an unsafe PvM reclaim classification. The inventory loss
            // follows ordinary accounting instead of creating a PK transaction.
            return pkTrackingEnabled
                ? LocalDeathDisposition.TRACK_PK
                : LocalDeathDisposition.UNCLASSIFIED;
        }
        if (!pvpPossible)
        {
            return LocalDeathDisposition.TRACK_PVM_RECLAIM;
        }
        return pvmContext
            ? LocalDeathDisposition.TRACK_PVM_RECLAIM
            : LocalDeathDisposition.UNCLASSIFIED;
    }

    /**
     * Presentation-only reset when the local player dies. Clears interaction,
     * process soft-stick, live tray, and detected NPC activity so HUD+ does not
     * keep showing the killer (or last target) through death/respawn.
     */
    private void clearHudPlusOnLocalDeath()
    {
        interactionContextTracker.clear();
        stickyProcessTitle = null;
        softStickProduction = false;
        if (characterIdleTracker != null)
        {
            characterIdleTracker.clear();
        }
        if (rewardPresentationModel != null)
        {
            rewardPresentationModel.clearLiveCard();
        }
        detectedActivity = "General";
        activityCandidate = "General";
        activityEvidence = 0;
        clearConfirmedProcessTitle();
        activityHoldTicks = ActivityRetentionPolicy.resetTicks(config.activityResetSeconds());
        if (config.autoActivityDetection())
        {
            engine.setDetectedActivity("General", System.currentTimeMillis());
        }
        refreshInteractionDisplay();
    }

    /**
     * Process chat/XP can arrive after the player has engaged an NPC. Ignore a late
     * spend-skill signal while the current target or retained HUD context is an NPC,
     * so it cannot clear that target or retitle the HUD back to the spend skill.
     */
    private boolean suppressProcessSignalForNpcEngagement(String activity)
    {
        Player localPlayer = client.getLocalPlayer();
        boolean engagedWithNpc = (localPlayer != null && localPlayer.getInteracting() instanceof NPC)
            || (interactionContextTracker != null && interactionContextTracker.hasNpcContext());
        return HudPlusProcessLabels.shouldSuppressProcessSignalDuringNpcEngagement(
            activity, engagedWithNpc);
    }

    /**
     * Character Idle already cleared interaction. Drop leftover NPC activity
     * hints (loot {@code observeActivity}) so leaving Idle does not title HUD+
     * as Dark wizard without a new engage.
     */
    private void clearDetectedActivityAfterIdle()
    {
        if (!config.autoActivityDetection())
        {
            return;
        }
        if (!InsightsActivityBreakdown.isSpecificNpcActivityTitle(detectedActivity)
            && !InsightsActivityBreakdown.isSpecificNpcActivityTitle(activityCandidate))
        {
            return;
        }
        detectedActivity = "General";
        activityCandidate = "General";
        activityEvidence = 0;
        activityHoldTicks = ActivityRetentionPolicy.resetTicks(config.activityResetSeconds());
        engine.setDetectedActivity("General", System.currentTimeMillis());
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        Skill skill = event.getSkill();
        if (skill == null)
        {
            return;
        }
        Integer previous = lastSkillExperience.put(skill, event.getXp());
        if (previous == null || event.getXp() <= previous || !ingestion.canIngestGameplay())
        {
            return;
        }
        markGameplayActivity();
        characterIdleTracker.noteSkillingXp(skill.getName());
        // Specific skill name for HUD/Live — Insights rolls these into categories.
        long now = System.currentTimeMillis();
        // Prayer XP can also come from ordinary Bury/Scatter. Only explicit altar
        // use followed by this XP event authorizes a Prayer process title.
        boolean hasTitleEvidence = !"Prayer".equalsIgnoreCase(skill.getName());
        if (!hasTitleEvidence)
        {
            boolean altarXpEvidence = prayerAltarTitleEvidence.consumeForPrayerXp(now);
            boolean rankOneTarget = interactionContextTracker != null
                && interactionContextTracker.hasFreshInteractionContext();
            hasTitleEvidence = altarXpEvidence && !rankOneTarget;
        }
        if (skill == Skill.SLAYER)
        {
            sessionBoundaries.onSlayerXp();
        }
        if (skill == Skill.AGILITY)
        {
            // Agility is an activity in its own right: the course names it when the region is
            // known (Wilderness Agility Course, a rooftop), the skill does otherwise.
            String course = null;
            Player local = client.getLocalPlayer();
            if (local != null && local.getWorldLocation() != null)
            {
                course = com.gpmanager.engine.AgilityCourses.courseName(local.getWorldLocation().getRegionID());
            }
            if (course != null)
            {
                setConfirmedProcessTitle(course);
                observeActivity(course, true);
            }
            else
            {
                observeActivityPreferringProcessStick(skill.getName());
            }
        }
        else if (hasTitleEvidence)
        {
            // A process-selection hint becomes the title only when matching XP arrives.
            observeActivityPreferringProcessStick(skill.getName());
        }
        if (HudPlusProcessLabels.isTransformProcessSkill(skill.getName())
            && now <= productionWindowExpiresAtEpochMillis)
        {
            armProductionContext(skill.getName());
        }
        if (HudTrayState.isProcessSpendActivity(skill.getName()))
        {
            boolean animate = ChangeFeedbackPolicy.animateHudPlusTray(config);
            rewardPresentationModel.confirmProcessSpendXp(
                skill.getName(), now, animate);
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!ingestion.canProcessGameTick())
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                panel.refresh();
            }
            return;
        }

        advanceGeOfferSeed();

        syncActivitySession();
        sessionBoundaries.onTick(client, isBankOpen());
        Player localPlayer = client.getLocalPlayer();
        updateWildernessRisk(localPlayer);
        if (localPlayer != null && localPlayer.getWorldLocation() != null)
        {
            net.runelite.api.coords.WorldPoint tile = localPlayer.getWorldLocation();
            syncPlayerWorldLocation(localPlayer);
            applyNeutralZone(tile.getRegionID());
        }
        else
        {
            engine.clearPlayerWorldLocation();
        }
        boolean activeAnimation = localPlayer != null && localPlayer.getAnimation() != -1;
        boolean activeInteraction = localPlayer != null && localPlayer.getInteracting() != null;
        if (activeAnimation || activeInteraction)
        {
            markGameplayActivity();
        }
        else
        {
            advanceIdleDetection();
        }
        advanceProcessTitleTicks();
        advanceActivityDetection();
        interactionContextTracker.onGameTick();
        // Bank UI before Idle tick so Banking wins over Idle this same tick.
        if (isBankOpen())
        {
            engine.markBankInterfaceOpen(
                Math.max(config.correlationTicks(), config.stabilizationTicks() + 2));
            if (characterIdleModel != null)
            {
                characterIdleModel.setBankUiOpen(true);
            }
            if (rewardPresentationModel != null)
            {
                rewardPresentationModel.setBankUiOpen(true);
            }
            // Rising edge only — Banking ends combat soft-stick so Dark wizard
            // cannot reappear the moment the bank closes.
            if (!bankClearedInteractionThisOpen && interactionContextTracker != null)
            {
                interactionContextTracker.clearAndBlockNpcTickRearm();
                bankClearedInteractionThisOpen = true;
            }
        }
        else
        {
            bankClearedInteractionThisOpen = false;
            engine.markBankInterfaceClosed();
            if (characterIdleModel != null)
            {
                characterIdleModel.setBankUiOpen(false);
            }
            if (rewardPresentationModel != null)
            {
                rewardPresentationModel.setBankUiOpen(false);
            }
        }
        boolean newlyIdle = characterIdleTracker.onGameTick();
        if (newlyIdle)
        {
            // Idle wiped interaction; drop NPC activity hint so leave-Idle is Tracking
            // not "Dark wizard" from last loot observeActivity.
            clearDetectedActivityAfterIdle();
        }
        if (characterIdleModel != null && characterIdleModel.isCharacterIdle())
        {
            stickyProcessTitle = null;
            softStickProduction = false;
        }

        if (client.getLocalPlayer() != null
            && client.getLocalPlayer().getInteracting() instanceof Player)
        {
            // Internal death-safety evidence only. PK transaction and activity
            // accounting still obeys enablePkTracking.
            recentPlayerCombatTicks = 20;
        }
        else if (recentPlayerCombatTicks > 0)
        {
            recentPlayerCombatTicks--;
        }

        long now = System.currentTimeMillis();
        if (engine.maintainReceiptRetention(now) > 0)
        {
            ticksSinceSave = 0;
            persist();
        }
        engine.tickLootKeyLifecycle();
        engine.tickDeferredChestClaimLifecycle();
        pollLootKeyManifest(now);
        ProfitTransaction transaction = engine.processIfDirty(
            snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()),
            now);

        if (transaction != null)
        {
            attachRecentGeOfferProvenance(transaction, now);
            markMeaningfulActivity();
            observeTransactionActivity(transaction);
            debugTrace.record("gameplay",
                "tx " + transaction.getType()
                    + " net=" + transaction.getNet()
                    + " flows=" + (transaction.getFlows() == null ? 0 : transaction.getFlows().size()));
            offerFeedback(transaction, now);
            ticksSinceSave = 0;
            persist();
        }
        else
        {
            ticksSinceSave++;
        }

        partyProfitTracker.updateLocal(
            engine.getActiveSession(),
            engine.getMetrics(now),
            now,
            config.enablePartyTracking());

        if (ticksSinceSave >= SAVE_INTERVAL_TICKS)
        {
            ticksSinceSave = 0;
            persist();
        }

        syncRewardSuppression();
        syncActivityHold();
        rewardPresentationModel.tick(now);
        refreshWealthLocationsSnapshot(now);

        if (trackingInfoBox != null)
        {
            trackingInfoBox.sync();
        }
        panel.refresh();
    }

    private void syncActivityHold()
    {
        boolean busy = characterIdleModel == null || !characterIdleModel.isCharacterIdle();
        var current = rewardPresentationModel.current();
        boolean holdCard = RewardPresentationModel.isActivityHoldCard(current);
        boolean pendingHold = false;
        if (current != null && current.getSourceKind() != null)
        {
            pendingHold = current.getSourceKind().isPendingRewards()
                || com.gpmanager.engine.LiveProducerBridge.isPendingRewardUiOpen(current.getSourceName());
        }
        // Pending Rewards stay held while the card is open even if Character Idle fires early.
        rewardPresentationModel.setActivityHold((busy && holdCard) || pendingHold);
    }

    private void updateWildernessRisk(Player localPlayer)
    {
        if (trackingDisplayModel == null)
        {
            return;
        }
        boolean pvpPossible = client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
            || client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) == 1;
        boolean protectItemOn = client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1;
        int skull = localPlayer == null ? SkullIcon.NONE : localPlayer.getSkullIcon();
        if (!pvpPossible || !config.hudPlusShowWildernessRisk() || localPlayer == null)
        {
            trackingDisplayModel.setWildernessRisk(pvpPossible, null);
            observePvpForSidebar(pvpPossible, skull, protectItemOn, null);
            return;
        }

        int keepCount = wildernessRiskKeepCount(skull, protectItemOn);
        if (keepCount < 0)
        {
            trackingDisplayModel.setWildernessRisk(true, null);
            observePvpForSidebar(true, skull, protectItemOn, null);
            return;
        }

        WildernessRiskCalculator.Result risk = WildernessRiskCalculator.calculate(
            wildernessRiskItems(InventoryID.INV),
            wildernessRiskItems(InventoryID.WORN),
            keepCount);
        trackingDisplayModel.setWildernessRisk(true, risk);
        observePvpForSidebar(true, skull, protectItemOn, risk);
    }

    /**
     * Automatic boundaries (Tools › Automatic boundaries): Off does nothing, Mark paints the
     * Live ribbon, New session starts and ends sessions. An automatic session never ends a
     * session the owner started by hand; a bank boundary restarts whatever is live.
     */
    private void onSessionBoundary(SessionBoundaryTracker.Kind kind, SessionBoundaryTracker.Event event, String label)
    {
        com.gpmanager.ui.bento.BoundaryMode mode = kind == SessionBoundaryTracker.Kind.SLAYER ? config.boundarySlayer()
            : kind == SessionBoundaryTracker.Kind.RAID ? config.boundaryRaid() : config.boundaryBank();
        if (mode == com.gpmanager.ui.bento.BoundaryMode.OFF || panel == null)
        {
            return;
        }
        com.gpmanager.ui.bento.Ribbon.MarkKind markKind = kind == SessionBoundaryTracker.Kind.SLAYER
            ? com.gpmanager.ui.bento.Ribbon.MarkKind.TASK
            : kind == SessionBoundaryTracker.Kind.RAID ? com.gpmanager.ui.bento.Ribbon.MarkKind.RAID
            : com.gpmanager.ui.bento.Ribbon.MarkKind.BANK;
        if (mode == com.gpmanager.ui.bento.BoundaryMode.MARK)
        {
            panel.noteBoundaryMark(markKind, label);
            return;
        }
        long now = System.currentTimeMillis();
        switch (event)
        {
            case START:
                if (!engine.isCustomSessionActive() || panel.isAutoSessionActive())
                {
                    panel.startSessionNamed(label, true, kind == SessionBoundaryTracker.Kind.SLAYER
                        ? com.gpmanager.ui.bento.SessionKind.SLAYER
                        : kind == SessionBoundaryTracker.Kind.RAID ? com.gpmanager.ui.bento.SessionKind.RAIDS : null);
                }
                else
                {
                    panel.noteBoundaryMark(markKind, label);
                }
                break;
            case END:
                if (panel.isAutoSessionActive())
                {
                    panel.endSessionNow(now, com.gpmanager.model.SessionEndReason.BOUNDARY);
                }
                else
                {
                    panel.noteBoundaryMark(markKind, label);
                }
                break;
            default:
                if (kind == SessionBoundaryTracker.Kind.BANK && engine.isCustomSessionActive())
                {
                    panel.restartSessionAfterBank(now);
                }
                else
                {
                    panel.noteBoundaryMark(markKind, label);
                }
                break;
        }
    }

    /** Sidebar Live › PvP reads the same facts the HUD risk line uses; presentation only. */
    private void observePvpForSidebar(boolean pvpPossible, int skull, boolean protectItemOn,
        @Nullable WildernessRiskCalculator.Result risk)
    {
        if (panel == null)
        {
            return;
        }
        boolean skulled = skull != SkullIcon.NONE;
        boolean highRisk = skull == SkullIcon.SKULL_HIGH_RISK;
        boolean riskKnown = risk != null && risk.isComplete();
        panel.observePvp(pvpPossible, skulled, highRisk, protectItemOn, riskKnown, riskKnown ? risk.getRiskValue() : 0L);
    }

    private void syncPlayerWorldLocation(Player localPlayer)
    {
        if (localPlayer == null || localPlayer.getWorldLocation() == null)
        {
            engine.clearPlayerWorldLocation();
            return;
        }
        WorldPoint tile = localPlayer.getWorldLocation();
        String label = playerLocationLabel(tile.getRegionID(),
            client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1,
            client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) == 1);
        engine.updatePlayerWorldLocation(tile.getX(), tile.getY(), tile.getPlane(), label);
    }

    static String playerLocationLabel(int regionId, boolean inWilderness, boolean pvpWorld)
    {
        if (regionId == 7512) return "The Gauntlet";
        if (regionId == 7768) return "Corrupted Gauntlet";
        if (inWilderness) return "Wilderness";
        if (pvpWorld) return "PvP world";
        return null;
    }

    /**
     * Keep-count policy for the ordinary skull states and the pinned high-risk
     * icon. Other modes have different death rules, so their risk stays unknown.
     */
    static int wildernessRiskKeepCount(int skullIcon, boolean protectItemOn)
    {
        if (skullIcon == SkullIcon.NONE)
        {
            return protectItemOn ? 4 : 3;
        }
        if (skullIcon == SkullIcon.SKULL)
        {
            return protectItemOn ? 1 : 0;
        }
        if (skullIcon == SkullIcon.SKULL_HIGH_RISK)
        {
            return 0;
        }
        return -1;
    }

    @Nullable
    private List<WildernessRiskCalculator.ItemStack> wildernessRiskItems(int containerId)
    {
        if (itemManager == null)
        {
            return null;
        }
        ItemContainer container = client.getItemContainer(containerId);
        if (container == null)
        {
            return null;
        }
        Item[] items = container.getItems();
        if (items == null)
        {
            return null;
        }
        List<WildernessRiskCalculator.ItemStack> stacks = new ArrayList<>();
        for (Item item : items)
        {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
            {
                continue;
            }
            int canonicalId = itemManager.canonicalize(item.getId());
            if (canonicalId <= 0)
            {
                return null;
            }
            ItemComposition composition = itemManager.getItemComposition(canonicalId);
            if (composition == null)
            {
                return null;
            }
            String itemName = composition.getName();
            boolean runePouch = isRunePouchItemName(itemName);
            boolean runePouchHasContents = runePouch
                && (snapshotFactory == null || snapshotFactory.hasRunePouchContents());
            if (hasUnmeasuredRiskContents(itemName, runePouchHasContents))
            {
                return null;
            }
            int price = itemManager.getItemPrice(canonicalId);
            Long geUnitPrice = price > 0 ? (long) price : null;
            stacks.add(new WildernessRiskCalculator.ItemStack(
                canonicalId,
                item.getQuantity(),
                geUnitPrice));
        }
        return stacks;
    }

    /**
     * Risk stays unknown when carried contents are hidden from the measured inventory
     * slots. Rune-pouch contents are specifically detectable through live varbits.
     */
    static boolean hasUnmeasuredRiskContents(String itemName, boolean runePouchHasContents)
    {
        if (itemName == null)
        {
            return true;
        }
        if (isRunePouchItemName(itemName))
        {
            return runePouchHasContents;
        }
        return UtilityContainerCatalogue.familyForItemName(itemName) != null;
    }

    private static boolean isRunePouchItemName(String itemName)
    {
        return itemName != null && itemName.toLowerCase(Locale.ROOT).contains("rune pouch");
    }

    /**
     * Gauntlet-style instances store gear on entry and restore it on exit with no exit
     * click. Keep an ownership-neutral transfer window open every tick inside, and one
     * closing window after leaving so the restore settles as a transfer too.
     */
    private void applyNeutralZone(int regionId)
    {
        com.gpmanager.engine.NeutralZoneTracker.Signal signal = neutralZoneTracker.onRegion(regionId);
        if (signal == com.gpmanager.engine.NeutralZoneTracker.Signal.ENTERED)
        {
            engine.beginNeutralZoneTransfer();
            engine.markMinigameTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        }
        else if (signal == com.gpmanager.engine.NeutralZoneTracker.Signal.INSIDE)
        {
            engine.markMinigameTransfer("Neutral zone instance", config.stabilizationTicks() + 4);
        }
        else if (signal == com.gpmanager.engine.NeutralZoneTracker.Signal.LEFT)
        {
            engine.endNeutralZoneTransfer(
                Math.max(config.correlationTicks() * 2, config.stabilizationTicks() + 16));
        }
        trackingDisplayModel.setNeutralZoneActive(neutralZoneTracker.isInside());
    }

    /** Polls the real key container interface; menu and chat wording is not source evidence. */
    private void pollLootKeyManifest(long now)
    {
        if (client == null || engine == null)
        {
            return;
        }
        Widget root = client.getWidget(InterfaceID.WILDY_LOOT_CHEST, 0);
        boolean visible = root != null && !root.isHidden();
        engine.setLootKeyChestVisible(visible);
        if (!visible)
        {
            return;
        }

        for (int index = 0; index < 5; index++)
        {
            int keyItemId = com.gpmanager.engine.LootKeyLifecycle.keyItemIdForIndex(index);
            net.runelite.api.ItemContainer keyContents = client.getItemContainer(
                com.gpmanager.engine.LootKeyLifecycle.keyContainerIdForIndex(index));
            if (keyContents == null)
            {
                continue;
            }

            Map<Integer, Long> rawManifest = com.gpmanager.engine.LootKeyLifecycle.manifestFromContainer(
                keyContents, this::isNestedLootKeyContainerItem);
            Map<Integer, Long> manifest = new HashMap<>();
            Map<Integer, Long> unitPrices = new HashMap<>();
            Set<Integer> ambiguousUnitPrices = new HashSet<>();
            long manifestValue = 0L;
            boolean valueComplete = !hasUnknownLootKeyItemComposition(keyContents);
            for (Map.Entry<Integer, Long> item : rawManifest.entrySet())
            {
                int canonicalId = itemManager == null
                    ? item.getKey() : itemManager.canonicalize(item.getKey());
                long quantity = item.getValue();
                if (canonicalId <= 0 || quantity <= 0L)
                {
                    valueComplete = false;
                    continue;
                }
                manifest.merge(canonicalId, quantity, GpManagerPlugin::safeAddPositive);
                int unitPrice = itemManager == null ? 0 : itemManager.getItemPrice(canonicalId);
                if (unitPrice <= 0)
                {
                    valueComplete = false;
                    continue;
                }
                if (!ambiguousUnitPrices.contains(canonicalId))
                {
                    Long previousPrice = unitPrices.putIfAbsent(canonicalId, (long) unitPrice);
                    if (previousPrice != null && previousPrice.longValue() != (long) unitPrice)
                    {
                        unitPrices.remove(canonicalId);
                        ambiguousUnitPrices.add(canonicalId);
                        valueComplete = false;
                    }
                }
                try
                {
                    manifestValue = Math.addExact(manifestValue, Math.multiplyExact((long) unitPrice, quantity));
                }
                catch (ArithmeticException overflow)
                {
                    manifestValue = Long.MAX_VALUE;
                    valueComplete = false;
                }
            }
            engine.observeLootKeyContainer(
                keyItemId, manifest, unitPrices, manifestValue, valueComplete, now);
        }
    }

    private boolean isNestedLootKeyContainerItem(int itemId)
    {
        String name = lootKeyItemName(itemId);
        return name == null || name.contains("crate") || name.contains("casket");
    }

    private boolean hasUnknownLootKeyItemComposition(net.runelite.api.ItemContainer container)
    {
        if (container == null || container.getItems() == null)
        {
            return true;
        }
        for (net.runelite.api.Item item : container.getItems())
        {
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0
                && lootKeyItemName(item.getId()) == null)
            {
                return true;
            }
        }
        return false;
    }

    @javax.annotation.Nullable
    private String lootKeyItemName(int itemId)
    {
        if (itemManager == null)
        {
            return null;
        }
        try
        {
            net.runelite.api.ItemComposition composition = itemManager.getItemComposition(itemId);
            if (composition == null || composition.getName() == null || composition.getName().trim().isEmpty())
            {
                return null;
            }
            return composition.getName().toLowerCase(Locale.ROOT);
        }
        catch (RuntimeException unavailable)
        {
            return null;
        }
    }

    private static long safeAddPositive(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException overflow)
        {
            return Long.MAX_VALUE;
        }
    }

    private com.gpmanager.engine.MinigameTransferClassifier.LiveSignals currentMinigameLiveSignals()
    {
        int regionId = 0;
        try
        {
            if (client != null && client.getLocalPlayer() != null
                && client.getLocalPlayer().getWorldLocation() != null)
            {
                regionId = client.getLocalPlayer().getWorldLocation().getRegionID();
            }
        }
        catch (Exception ignored)
        {
            // Live signals are best-effort.
        }
        return new com.gpmanager.engine.MinigameTransferClassifier.LiveSignals(regionId, false, false);
    }

    private void offerFeedback(ProfitTransaction transaction, long now)
    {
        TrackingDisplay display = config.trackingDisplay();
        // B10 surface policy: one mapping decides which surfaces may show this event.
        // Routine rune/ammo spends reach the Ledger only; accounting is untouched.
        boolean trayAllowed = ActionPresentation.showsOn(
            transaction, ActionPresentation.Surface.HUD_PLUS_TRAY);
        boolean latestChangeAllowed = ActionPresentation.showsOn(
            transaction, ActionPresentation.Surface.LATEST_CHANGE);
        boolean floatingAllowed = ActionPresentation.showsOn(
            transaction, ActionPresentation.Surface.FLOATING_DROP);
        // HUD+: tray (+ optional floating). HUD/Infobox: floating only. Off: none.
        if (display != TrackingDisplay.OFF)
        {
            boolean animate = ChangeFeedbackPolicy.animateHudPlusTray(config);
            if (latestChangeAllowed)
            {
                latestDropModel.offer(
                    transaction,
                    now,
                    integratedArrivalMillis(),
                    animate);
            }
            if (ChangeFeedbackPolicy.showHudPlusTray(config))
            {
                if (trayAllowed)
                {
                    rewardPresentationModel.offerSkillingOrConfirmed(transaction, now, animate);
                }
                if (latestChangeAllowed)
                {
                    latestChangeModel.offer(transaction, now, integratedArrivalMillis());
                }
            }
            sessionItemLedger.record(transaction);
        }
        if (floatingAllowed && ChangeFeedbackPolicy.showFloatingDrops(config))
        {
            gpDropOverlay.enqueue(transaction);
        }
    }

    private void offerObservedReward(
        RewardSourceKind kind,
        String sourceName,
        String encounterKey,
        java.util.Collection<ItemStack> items)
    {
        offerObservedReward(kind, sourceName, encounterKey, items, 1);
    }

    private void offerObservedReward(
        RewardSourceKind kind,
        String sourceName,
        String encounterKey,
        java.util.Collection<ItemStack> items,
        int encounterMultiplicity)
    {
        // Unconfirmed ground loot paints on the HUD+ tray only.
        if (!ChangeFeedbackPolicy.showHudPlusTray(config))
        {
            return;
        }
        syncRewardSuppression();
        long now = System.currentTimeMillis();
        boolean animate = ChangeFeedbackPolicy.animateHudPlusTray(config);
        int multiplicity = Math.max(1, encounterMultiplicity);
        // Wilderness loot chest / key UI → Pending Rewards (not Ground Loot).
        if (kind != RewardSourceKind.PENDING_REWARDS
            && items != null
            && ("wilderness loot chest".equalsIgnoreCase(sourceName)
                || (sourceName != null && sourceName.toLowerCase(Locale.ROOT).contains("loot chest"))))
        {
            kind = RewardSourceKind.PENDING_REWARDS;
        }
        if (kind == RewardSourceKind.PENDING_REWARDS)
        {
            if (com.gpmanager.engine.ClueCostPairing.looksLikeClueActivity(sourceName))
            {
                engine.beginCluePath(encounterKey, sourceName);
            }
            rewardPresentationModel.offerPendingRewards(
                sourceName,
                encounterKey,
                RewardObservationFactory.fromItemStacks(itemManager, items),
                now,
                animate);
        }
        else
        {
            rewardPresentationModel.offerObservation(
                kind,
                sourceName,
                encounterKey,
                RewardObservationFactory.fromItemStacks(itemManager, items),
                now,
                animate,
                multiplicity);
        }
        if (trackingInfoBox != null)
        {
            trackingInfoBox.sync();
        }
    }

    private void calibrateChargeFamily(String familyId)
    {
        com.gpmanager.engine.evidence.ChargeAccountingGate.markCalibrated(familyId);
        persistChargeCalibration();
    }

    /**
     * Feed only exact numeric Check messages into the measured charge baseline.
     * Chat may provide evidence for accounting but is deliberately not activity/title
     * evidence. A missing GE component price or any arithmetic overflow rejects the
     * complete delta.
     */
    private boolean observeMeasuredChargeCheck(String message, long now)
    {
        com.gpmanager.engine.evidence.MeasuredChargeRead read =
            com.gpmanager.engine.evidence.MeasuredChargeRead.parseCheckMessage(message);
        if (read == null)
        {
            return false;
        }

        String targetIdentity = measuredChargeCheckIntent.consume(read, client.getTickCount());
        if (targetIdentity == null)
        {
            // A value without its fresh Check target is not enough to distinguish
            // another same-variant weapon; discard any older comparison baseline.
            engine.resetMeasuredChargeReads();
            return true;
        }
        com.gpmanager.engine.evidence.MeasuredChargeDelta delta =
            engine.observeMeasuredChargeRead(read, targetIdentity, now);
        if (!read.isBookable()
            || delta == null
            || delta.getComponentDeltas().isEmpty()
            || itemManager == null)
        {
            return true;
        }

        List<com.gpmanager.model.ItemFlow> losses = new ArrayList<>();
        long totalCost = 0L;
        try
        {
            for (com.gpmanager.engine.evidence.MeasuredChargeDelta.ComponentDelta component
                : delta.getComponentDeltas())
            {
                int itemId = component.getItemId();
                long quantityDelta = component.getQuantityDelta();
                int canonicalId = itemManager.canonicalize(itemId);
                if (itemId <= 0 || canonicalId <= 0 || quantityDelta >= 0L)
                {
                    return true;
                }
                net.runelite.api.ItemComposition composition = itemManager.getItemComposition(canonicalId);
                if (composition == null || composition.getName() == null
                    || composition.getName().trim().isEmpty())
                {
                    return true;
                }

                // Coins are a currency component with fixed face value, not a
                // market-priced item. Other components require a positive GE price.
                int unitPrice = itemId == 995 ? 1 : itemManager.getItemPrice(canonicalId);
                com.gpmanager.model.ItemPriceSource priceSource = itemId == 995
                    ? com.gpmanager.model.ItemPriceSource.FACE_VALUE
                    : com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE;
                if (unitPrice <= 0)
                {
                    return true;
                }
                long valueDelta = Math.multiplyExact(quantityDelta, (long) unitPrice);
                totalCost = Math.addExact(totalCost, Math.negateExact(valueDelta));
                losses.add(new com.gpmanager.model.ItemFlow(
                    itemId,
                    composition.getName(),
                    quantityDelta,
                    unitPrice,
                    valueDelta,
                    priceSource,
                    now));
            }
        }
        catch (ArithmeticException ex)
        {
            return true;
        }

        if (totalCost <= 0L)
        {
            return true;
        }
        engine.bookChargeSpend(delta, read.getVariant().getDisplayName(), losses, now);
        return true;
    }

    /**
     * Arm one narrowly-scoped, ownership-neutral load only for a real Use-on-item
     * action against an exact supported weapon and a source component whose canonical
     * id and item name both match that weapon's recipe.
     */
    private void armChargeLoadTransfer(MenuOptionClicked event, String target)
    {
        if (event.getMenuAction() != MenuAction.ITEM_USE_ON_ITEM || itemManager == null)
        {
            engine.clearChargeLoadTransfer();
            return;
        }
        com.gpmanager.engine.evidence.MeasuredChargeRead.Variant variant =
            com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemName(target);
        int weaponItemId = chargeWeaponItemId(event);
        if (variant == null)
        {
            variant = com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemId(weaponItemId);
        }
        int sourceItemId = event.getItemId();
        if (variant == null || sourceItemId <= 0)
        {
            engine.clearChargeLoadTransfer();
            return;
        }

        int canonicalId = itemManager.canonicalize(sourceItemId);
        if (canonicalId <= 0)
        {
            engine.clearChargeLoadTransfer();
            return;
        }
        net.runelite.api.ItemComposition composition = itemManager.getItemComposition(canonicalId);
        if (composition == null || composition.getName() == null
            || !com.gpmanager.engine.evidence.MeasuredChargeRead.isSupportedLoadComponent(
                variant, canonicalId, composition.getName()))
        {
            engine.clearChargeLoadTransfer();
            return;
        }

        int ticks = Math.max(4, config.stabilizationTicks() + 2);
        String targetIdentity = chargeWeaponTargetIdentity(event, variant, weaponItemId);
        engine.markChargeLoadTransfer(variant, canonicalId, composition.getName(), targetIdentity, ticks);
    }

    /**
     * Link one numeric Check response to the item slot that was actually checked.
     * The click contains identity only; the chat measurement remains the sole source
     * of component counts. Any other menu action supersedes the pending link.
     */
    private void armMeasuredChargeCheck(MenuOptionClicked event, String option, String target)
    {
        measuredChargeCheckIntent.clear();
        int rawItemId = chargeWeaponItemId(event);
        com.gpmanager.engine.evidence.MeasuredChargeRead.Variant itemVariant =
            com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemId(rawItemId);
        if (itemVariant == null)
        {
            itemVariant = com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemName(target);
        }

        if (("uncharge".equals(option) || "unload".equals(option))
            && itemVariant != null)
        {
            // These actions can return loaded components. A later lower Check value
            // cannot be treated as consumed charges across that unmeasured return.
            engine.resetMeasuredChargeReads();
            return;
        }
        if (!"check".equals(option) || itemVariant == null || !itemVariant.isImplemented())
        {
            return;
        }

        String targetIdentity = chargeWeaponTargetIdentity(event, itemVariant, rawItemId);
        if (targetIdentity == null)
        {
            return;
        }
        measuredChargeCheckIntent.arm(itemVariant, targetIdentity, client.getTickCount(), 2);
    }

    /** Resolve the weapon targeted by the menu action, distinct from an item-on-item source. */
    private int chargeWeaponItemId(MenuOptionClicked event)
    {
        if (event == null)
        {
            return -1;
        }
        int id = event.getId();
        if (com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemId(id) != null)
        {
            return id;
        }
        int itemId = event.getItemId();
        if (com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemId(itemId) != null)
        {
            return itemId;
        }
        if (event.getWidget() != null)
        {
            int widgetItemId = event.getWidget().getItemId();
            if (com.gpmanager.engine.evidence.MeasuredChargeRead.supportedVariantForItemId(widgetItemId) != null)
            {
                return widgetItemId;
            }
        }
        return -1;
    }

    /**
     * Same item/container/slot identity for the clicked weapon across Use-on-item
     * and Check. Without all location fields, a charge load remains Review only.
     */
    private String chargeWeaponTargetIdentity(
        MenuOptionClicked event,
        com.gpmanager.engine.evidence.MeasuredChargeRead.Variant variant,
        int weaponItemId)
    {
        if (event == null || variant == null || weaponItemId <= 0
            || event.getWidgetId() <= 0 || event.getParam0() < 0)
        {
            return null;
        }
        return event.getWidgetId()
            + ":" + event.getParam0()
            + ":" + event.getParam1()
            + ":" + weaponItemId
            + ":" + variant.name();
    }

    private int rawMenuItemId(MenuOptionClicked event)
    {
        if (event == null)
        {
            return -1;
        }
        int itemId = event.getItemId();
        if (itemId <= 0 && event.isItemOp())
        {
            itemId = event.getId();
        }
        if (itemId <= 0 && event.getWidget() != null)
        {
            itemId = event.getWidget().getItemId();
        }
        return itemId;
    }

    private void loadChargeCalibration()
    {
        if (configManager == null)
        {
            return;
        }
        String raw = configManager.getConfiguration(GpManagerConfig.GROUP, CHARGE_CALIBRATED_KEY);
        com.gpmanager.engine.evidence.ChargeAccountingGate.restore(
            com.gpmanager.engine.evidence.ChargeAccountingGate.decode(raw));
    }

    private void persistChargeCalibration()
    {
        if (configManager == null)
        {
            return;
        }
        configManager.setConfiguration(
            GpManagerConfig.GROUP,
            CHARGE_CALIBRATED_KEY,
            com.gpmanager.engine.evidence.ChargeAccountingGate.encode(
                com.gpmanager.engine.evidence.ChargeAccountingGate.snapshot()));
    }

    /** Loot Tracker EVENT names that are reward-UI / chest / clue — not floor piles. */
    static boolean isPendingRewardEventName(String name)
    {
        return com.gpmanager.engine.RewardChestCatalogue.isPendingRewardName(name)
            || com.gpmanager.engine.ActivityCrateCatalogue.classify(name)
                == com.gpmanager.engine.ActivityCrateCatalogue.Kind.BOUNTY_HUNTER_CRATE;
    }

    private void syncRewardSuppression()
    {
        boolean suppress = persistence.isIdentitySwitchHeld()
            || engine.isStopped()
            || (engine.getActiveSession() != null
                && engine.getActiveSession().isPaused()
                && !engine.isIdlePaused());
        rewardPresentationModel.setSuppressReveals(suppress);
        boolean keepExpanded = config.hudPlusRewardPresentation() == HudPlusRewardPresentation.KEEP_EXPANDED;
        rewardPresentationModel.setPinnedExpanded(keepExpanded, System.currentTimeMillis());
        rewardPresentationModel.setRevealDwellMillis(config.hudPlusRevealDurationMillis());
        rewardPresentationModel.setLootCoalesceTicks(config.hudPlusLootCoalesceTicks());
        rewardPresentationModel.setShowBankingFlashes(config.hudPlusShowBankingFlashes());
        rewardPresentationModel.setHudPlusAccumulation(
            config.hudPlusAccumulationMode(),
            config.hudPlusStreakInactivityMinutes());
        String scope = persistence.isTrackingReady()
            ? String.valueOf(System.identityHashCode(engine.getActiveSession()))
            : "held";
        rewardPresentationModel.setOwnerScope(scope);
    }

    /**
     * Opens RuneLite Configuration for this plugin (panel cog / overlay Configure).
     */
    void openPluginConfiguration()
    {
        configurationPanelOpener.open(this, panel);
    }

    @Subscribe
    public void onOverlayMenuClicked(OverlayMenuClicked event)
    {
        if (event == null || event.getOverlay() != overlay || event.getEntry() == null)
        {
            return;
        }
        String option = event.getEntry().getOption();
        if (GpManagerOverlay.MENU_CONFIGURE.equals(option))
        {
            openPluginConfiguration();
            return;
        }
        if (GpManagerOverlay.MENU_VIEW_LATEST_LOOT.equals(option)
            || GpManagerOverlay.MENU_OPEN_PANEL.equals(option))
        {
            // Drag-safe: context menu only — does not steal gameplay clicks.
            if (navigationButton != null)
            {
                clientToolbar.openPanel(navigationButton);
            }
            panel.onActivate();
            panel.refresh();
        }
        else if (GpManagerOverlay.MENU_PAUSE_TRACKING.equals(option)
            || GpManagerOverlay.MENU_RESUME_TRACKING.equals(option))
        {
            engine.togglePause(System.currentTimeMillis());
            persist();
            panel.refresh();
        }
        else if (GpManagerOverlay.MENU_PREVIEW_HUD_PLUS.equals(option))
        {
            overlay.startHudPlusPreview();
        }
        else if (GpManagerOverlay.MENU_RESET_HUD_PLUS.equals(option))
        {
            // Clears in-session preferred location; RuneLite may still remember
            // dragged overlay placement until the user resets it in client overlay config.
            overlay.resetHudPlusPosition();
        }
    }

    @Subscribe
    public void onInfoBoxMenuClicked(InfoBoxMenuClicked event)
    {
        if (event == null || event.getInfoBox() != trackingInfoBox || event.getEntry() == null)
        {
            return;
        }
        String option = event.getEntry().getOption();
        if (GpManagerOverlay.MENU_OPEN_PANEL.equals(option))
        {
            if (navigationButton != null)
            {
                clientToolbar.openPanel(navigationButton);
            }
            panel.onActivate();
            panel.refresh();
        }
        else if (GpManagerOverlay.MENU_PAUSE_TRACKING.equals(option)
            || GpManagerOverlay.MENU_RESUME_TRACKING.equals(option))
        {
            engine.togglePause(System.currentTimeMillis());
            panel.refresh();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event == null || event.getGroup() == null)
        {
            return;
        }
        if (GroundItemsConfigSnapshot.GROUP.equals(event.getGroup()))
        {
            // Refresh filter projection only — never replay stored rewards.
            refreshLootPresentationFilter();
            return;
        }
        if (!GpManagerConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        if ("includeEquipment".equals(event.getKey()) || "includeRunePouch".equals(event.getKey()))
        {
            // The tracked snapshot shape changed. Warm a new baseline so enabling or
            // disabling a container cannot appear as a synthetic gain or cost.
            engine.beginBaselinePriming();
        }
        if ("lootPresentationFilter".equals(event.getKey())
            || "accountingItemFilter".equals(event.getKey())
            || "minimumDisplayedLootValue".equals(event.getKey())
            || "reuseGroundItemsHighlightColors".equals(event.getKey()))
        {
            refreshLootPresentationFilter();
        }
        if ("trackingDisplay".equals(event.getKey())
            || "hudPlusFloatingDrops".equals(event.getKey())
            || "hudPlusRewardPresentation".equals(event.getKey())
            || "hudPlusRevealDurationMillis".equals(event.getKey())
            || "hudPlusLootCoalesceTicks".equals(event.getKey())
            || "hudPlusShowBankingFlashes".equals(event.getKey())
            || "hudPlusAccumulationMode".equals(event.getKey())
            || "hudPlusStreakInactivityMinutes".equals(event.getKey())
            || "reducedMotion".equals(event.getKey()))
        {
            syncRewardSuppression();
        }
        if ("enableDebugTrace".equals(event.getKey()))
        {
            debugTrace.setEnabled(config.enableDebugTrace());
        }
        if (trackingInfoBox != null)
        {
            trackingInfoBox.sync();
        }
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged event)
    {
        // RuneLite profile switch reloads Ground Items settings; re-project only.
        prayerAltarTitleEvidence.clear();
        refreshLootPresentationFilter();
    }

    @Subscribe
    public void onPluginChanged(PluginChanged event)
    {
        if (event != null && event.getPlugin() instanceof GroundItemsPlugin)
        {
            refreshLootPresentationFilter();
        }
    }

    private void refreshLootPresentationFilter()
    {
        lootPresentationFilter.refresh();
        trackingDisplayModel.invalidate();
        if (trackingInfoBox != null)
        {
            trackingInfoBox.sync();
        }
        if (panel != null)
        {
            panel.refresh();
        }
    }

    private void refreshInteractionDisplay()
    {
        trackingDisplayModel.invalidate();
        // refresh() schedules on the EDT when invoked from the client event thread.
        if (panel != null) panel.refresh();
    }

    private void markMeaningfulActivity()
    {
        if (!ingestion.canIngestGameplay())
        {
            return;
        }
        long now = System.currentTimeMillis();
        idleTicks = 0;
        // Inventory/bank context alone resumes IDLE but never Manual/Stop and
        // never auto-starts a tracker (startup needs gameplay activity).
        engine.resumeAfterIdle(now);
    }

    private void markGameplayActivity()
    {
        markGameplayActivity(false);
    }

    private void markGameplayActivity(boolean beforeItemChange)
    {
        if (client.getGameState() != GameState.LOGGED_IN || !ingestion.canIngestGameplay())
        {
            return;
        }
        long now = System.currentTimeMillis();
        idleTicks = 0;
        if (engine.startGeneralFromActivityIfNeeded(now))
        {
            if (beforeItemChange && client.getItemContainer(InventoryID.INV) != null)
            {
                engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
            }
            else
            {
                engine.beginBaselinePriming();
            }
            debugTrace.record("session", "auto-started General from gameplay activity");
        }
        // Lifecycle (logout/hop), Idle, and Recovery resume on gameplay; Manual/Stop do not.
        engine.resumeAfterLifecycle(now);
        engine.resumeAfterActivity(now);
    }

    private void advanceIdleDetection()
    {
        if (!config.idlePauseEnabled() || engine.getActiveSession() == null)
        {
            idleTicks = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (idleTicks++ == 0)
        {
            // The last gameplay action was the previous tick; the idle stretch starts here.
            idleStartedAt = now;
        }
        int thresholdTicks = Math.max(1, (Math.max(1, config.idleTimeoutSeconds()) * 1000 + 599) / 600);
        if (idleTicks >= thresholdTicks)
        {
            // Pause at the measured idle start so idle auto-end closes the session
            // when the player actually stopped, not when the timeout fired.
            engine.pauseForIdle(now, idleStartedAt);
        }
    }

    private void observeTransactionActivity(ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return;
        }
        TransactionType type = transaction.getType();
        switch (type)
        {
            case PK_LOOT:
            case PK_SUPPLY_COST:
            case PK_DEATH_LOSS:
            case PK_FEE:
                observeActivity("PKing", true);
                break;
            case LOOT:
            {
                String lootActivity = transaction.getActivityName();
                if (lootActivity != null && !lootActivity.trim().isEmpty()
                    && !InsightsActivityBreakdown.isGeneralizedCategory(lootActivity))
                {
                    observeActivity(lootActivity.trim(), true);
                }
                break;
            }
            case PROCESSING:
                // Inventory transforms are receipts. Positive StatChanged XP is
                // required separately to promote the rank-2 process title.
                break;
            case TRADE:
                observeActivity("Trading", true);
                break;
            default:
                break;
        }
    }

    private void observeActivity(String activity, boolean immediate)
    {
        if (!config.autoActivityDetection() || activity == null || activity.trim().isEmpty())
        {
            return;
        }

        String next = activity.trim();
        if (next.equalsIgnoreCase(activityCandidate))
        {
            activityEvidence++;
        }
        else
        {
            activityCandidate = next;
            activityEvidence = 1;
        }

        if (immediate || activityEvidence >= 2)
        {
            detectedActivity = next;
            activityHoldTicks = ActivityRetentionPolicy.resetTicks(config.activityResetSeconds());
            engine.setDetectedActivity(next, System.currentTimeMillis());
        }
    }

    private void advanceActivityDetection()
    {
        if (!config.autoActivityDetection())
        {
            return;
        }

        // Process titles use their own XP-refreshed tick lifetime rather than the
        // configurable, much longer activity timeout.
        if (processTitleHold.isActive() && HudPlusProcessLabels.isProcessTitle(detectedActivity))
        {
            return;
        }

        int resetTicks = ActivityRetentionPolicy.resetTicks(config.activityResetSeconds());
        if (resetTicks < 0)
        {
            activityHoldTicks = -1;
            return;
        }
        if (activityHoldTicks < 0)
        {
            activityHoldTicks = resetTicks;
        }
        if (activityHoldTicks > 0)
        {
            activityHoldTicks--;
            return;
        }
        if (!"General".equalsIgnoreCase(detectedActivity))
        {
            detectedActivity = "General";
            activityCandidate = "General";
            activityEvidence = 0;
            engine.setDetectedActivity("General", System.currentTimeMillis());
        }
    }

    private void advanceProcessTitleTicks()
    {
        if (processTitleHold.advance())
        {
            publishConfirmedProcessTitle();
        }
    }

    private void syncActivitySession()
    {
        ProfitSession active = engine.getActiveSession();
        String nextSessionId = active == null ? null : active.getId();
        if (nextSessionId == null ? activitySessionId == null : nextSessionId.equals(activitySessionId))
        {
            return;
        }

        activitySessionId = nextSessionId;
        clearConfirmedProcessTitle();
        String initial = active == null ? "General" : active.getActivityHint();
        if (initial == null || initial.trim().isEmpty())
        {
            initial = "General";
        }
        detectedActivity = initial.trim();
        activityCandidate = detectedActivity;
        activityEvidence = 0;
        activityHoldTicks = ActivityRetentionPolicy.resetTicks(config.activityResetSeconds());
    }

    private boolean isBankOpen()
    {
        return isBankingUiOpen();
    }

    private boolean isGrandExchangeOpen()
    {
        if (client == null) return false;
        Widget root = client.getWidget(InterfaceID.GE_OFFERS, 0);
        if (root != null && !root.isHidden()) return true;
        Widget side = client.getWidget(InterfaceID.GeOffersSide.ITEMS);
        return side != null && !side.isHidden();
    }

    private boolean isMainBankUiOpen()
    {
        if (client == null) return false;
        Widget bankContainer = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
        return bankContainer != null && !bankContainer.isHidden();
    }

    /**
     * Bank main or deposit-box UI visible — drives Banking header and Withdrew buffer.
     * Deposit-box transfers stay TRANSFER / neutral; this is presentation only.
     */
    private boolean isBankingUiOpen()
    {
        // The client retains bank contents after closing the interface. Only
        // interface visibility is evidence of an open bank; cached ownership
        // data must never suppress later gathering or supply consumption.
        if (isMainBankUiOpen())
        {
            return true;
        }
        try
        {
            Widget deposit = client.getWidget(InterfaceID.BankDepositbox.INVENTORY);
            if (deposit != null && !deposit.isHidden())
            {
                return true;
            }
            Widget depositContents = client.getWidget(InterfaceID.BankDepositbox.CONTENTS);
            return depositContents != null && !depositContents.isHidden();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /** True while the player–player trade screen is visible. */
    private boolean isPlayerTradeOpen()
    {
        try
        {
            Widget tradeTitle = client.getWidget(InterfaceID.Trademain.TITLE);
            return tradeTitle != null && !tradeTitle.isHidden();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * Inventory actions that destroy or spend a stack when confirmed by removal.
     * Normalized option text (lowercase, tags stripped). Patterns aligned with
     * Supplies Tracker Eat/Drink/Cast detection (BSD-2-Clause reference) without
     * requiring that plugin.
     */
    static boolean isInventoryConsumptionOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return false;
        }
        return option.contains("bury")
            || option.contains("scatter")
            || option.startsWith("eat")
            || option.startsWith("drink")
            || option.equals("empty")
            || option.equals("break")
            || option.startsWith("release")
            || option.startsWith("cast")
            || isLossOnlyProcessSpendOption(option);
    }

    /**
     * Loss-only process spends that wait for XP before HUD paints Burned/Offered
     * (Firemaking light, Prayer bury/offer). Cook is a transform — arms PRODUCTION.
     */
    static boolean isLossOnlyProcessSpendOption(String option)
    {
        return ProcessSkillSignals.isLossOnlyProcessSpendOption(option);
    }

    /** @deprecated use {@link #isLossOnlyProcessSpendOption(String)} */
    @Deprecated
    static boolean isProcessSpendOption(String option)
    {
        return isLossOnlyProcessSpendOption(option);
    }

    static String processSpendSkillForOption(String option)
    {
        return ProcessSkillSignals.processSpendSkillForOption(option);
    }

    /** Inventory-only transform options that are not scenery-tracked alone. */
    static boolean isInventoryTransformOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return false;
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        return "fletch".equals(lower)
            || "craft".equals(lower)
            || "mix".equals(lower)
            || "combine".equals(lower)
            || "string".equals(lower)
            || "cut".equals(lower)
            || "clean".equals(lower)
            || "tip".equals(lower)
            || "feather".equals(lower)
            || "attach".equals(lower)
            || "plank".equals(lower)
            || "tan".equals(lower)
            || "mould".equals(lower);
    }

    private void noteStickyProcessTitle(String title)
    {
        if (title == null || title.trim().isEmpty())
        {
            return;
        }
        stickyProcessTitle = title.trim();
        softStickProduction = true;
    }

    private void observeActivityPreferringProcessStick(String skillName)
    {
        if (skillName == null || skillName.trim().isEmpty())
        {
            return;
        }
        String skill = skillName.trim();
        if (suppressProcessSignalForNpcEngagement(skill))
        {
            return;
        }
        if (HudPlusProcessLabels.isProcessTitle(skill)
            || HudTrayState.isProcessSpendActivity(skill))
        {
            // This function is reached only after onStatChanged observed an
            // increasing XP value. A matching menu station may provide a more
            // specific label (Smithing XP after clicking Smelt), but never titles
            // until this XP evidence arrives.
            String title = skill;
            if (stickyProcessTitle != null && !stickyProcessTitle.isEmpty()
                && processTitleConfirmedByXp(stickyProcessTitle, skill))
            {
                title = stickyProcessTitle;
            }
            applyLiveProcessSkill(title, true);
            return;
        }
        // Non-process XP may still inform the session activity, but cannot extend
        // or resurrect a process title whose own XP evidence has stopped.
        observeActivity(skill, false);
    }

    /**
     * Promote a process title after positive XP. Receipt/menu/chat events only
     * arm their receipt/production intent and never call this method.
     */
    private void applyLiveProcessSkill(String skillName, boolean immediate)
    {
        if (!config.autoActivityDetection() || skillName == null || skillName.trim().isEmpty())
        {
            return;
        }
        String skill = skillName.trim();
        if (suppressProcessSignalForNpcEngagement(skill))
        {
            return;
        }
        if (shouldClearProcessSoftStick(skill))
        {
            stickyProcessTitle = null;
            softStickProduction = false;
        }
        if (rewardPresentationModel != null)
        {
            String armed = rewardPresentationModel.getArmedProcessSpendSkill();
            if (!armed.isEmpty() && !armed.equalsIgnoreCase(skill))
            {
                rewardPresentationModel.clearProcessSpendIntent();
            }
        }
        setConfirmedProcessTitle(skill);
        observeActivity(skill, immediate);
        if (characterIdleTracker != null)
        {
            characterIdleTracker.noteSkillingXp(skill);
        }
    }

    private void setConfirmedProcessTitle(String title)
    {
        processTitleHold.confirm(title, PROCESS_TITLE_TICKS);
        publishConfirmedProcessTitle();
    }

    private void publishConfirmedProcessTitle()
    {
        if (trackingDisplayModel != null)
        {
            trackingDisplayModel.setConfirmedProcessTitle(processTitleHold.getTitle());
        }
    }

    private void clearConfirmedProcessTitle()
    {
        prayerAltarTitleEvidence.clear();
        processTitleHold.clear();
        publishConfirmedProcessTitle();
    }

    /** Light / Tend-to / Use / bury: receipt intent only; XP owns the title. */
    private void armLossOnlyProcessSpend(String skill, int itemId)
    {
        armLossOnlyProcessSpend(skill, itemId, null);
    }

    private void armLossOnlyProcessSpend(
        String skill, int itemId, ProcessSkillSignals.PrayerSpendMode prayerMode)
    {
        if (skill == null || skill.isEmpty())
        {
            return;
        }
        if (rewardPresentationModel != null)
        {
            String mode = "";
            if ("Prayer".equalsIgnoreCase(skill))
            {
                ProcessSkillSignals.PrayerSpendMode modeOrDefault =
                    prayerMode == null ? ProcessSkillSignals.PrayerSpendMode.OFFER : prayerMode;
                mode = modeOrDefault.wireName();
            }
            rewardPresentationModel.noteProcessSpendIntent(
                skill, itemId, System.currentTimeMillis(), mode);
        }
    }

    private void observeNonProcessMenuActivity(String activity)
    {
        if (activity == null
            || HudPlusProcessLabels.isProcessTitle(activity)
            || HudTrayState.isProcessSpendActivity(activity))
        {
            return;
        }
        observeActivity(activity, true);
    }

    private static boolean processTitleConfirmedByXp(String title, String xpSkill)
    {
        if (title == null || xpSkill == null)
        {
            return false;
        }
        if (title.equalsIgnoreCase(xpSkill))
        {
            return true;
        }
        // Smelting is presented as the selected process, while the client reports
        // its actual XP under Smithing.
        return "Smelting".equalsIgnoreCase(title) && "Smithing".equalsIgnoreCase(xpSkill);
    }

    private boolean shouldClearProcessSoftStick(String skill)
    {
        return stickyProcessTitle != null
            && !stickyProcessTitle.isEmpty()
            && !stickyProcessTitle.equalsIgnoreCase(skill)
            && (HudPlusProcessLabels.isProcessTitle(skill)
                || HudTrayState.isProcessSpendActivity(skill));
    }

    private void armProductionContext(String skillNote)
    {
        int ticks = Math.max(1, config.correlationTicks());
        String note = skillNote == null || skillNote.trim().isEmpty()
            ? "Production" : skillNote.trim();
        engine.markContext(TrackingContext.PRODUCTION, ticks, note);
        long now = System.currentTimeMillis();
        long duration = ticks * RewardPresentationModel.MILLIS_PER_GAME_TICK;
        productionWindowExpiresAtEpochMillis = now + duration;
        rewardPresentationModel.beginProductionCountdown(now, duration);
    }

    /**
     * Parse "fee of 10,000 coins" / "pay 5000 gp" style messages for death reclaim.
     */
    static long parseCoinFeeFromChat(String message)
    {
        if (message == null || message.isEmpty())
        {
            return 0L;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?:fee of|pay(?:ing)?|costs?)\\s+([0-9][0-9,]*)\\s*(?:coins?|gp)?",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(message);
        if (!matcher.find())
        {
            return 0L;
        }
        try
        {
            return Long.parseLong(matcher.group(1).replace(",", ""));
        }
        catch (NumberFormatException ex)
        {
            return 0L;
        }
    }

    /**
     * Game/spam chat skill hint for routing receipt intent. Chat is not title evidence.
     */
    static String skillActivityFromChatMessage(String message)
    {
        return ProcessSkillSignals.skillFromChatMessage(message);
    }

    /**
     * Game/spam chat that confirms inventory spend after Eat/Bury/Drink/Scatter/altar offer.
     * Used to reinforce consume intent when menu {@code itemId} was {@code -1}.
     * Live bury often prints "You dig a hole and bury the bones." — match dig+bury
     * and contains() so color tags / preamble do not miss the reinforce.
     * <p>OSRS PoH gilded altar ([Gilded altar](https://oldschool.runescape.wiki/w/Gilded_altar)):
     * "The gods are pleased with your offering" / "…very pleased…". Chaos altar bone-save
     * ([Chaos Temple](https://oldschool.runescape.wiki/w/Chaos_Temple_(church))) must not
     * reinforce consume — invent may keep the bone.
     */
    static boolean isConsumptionChatMessage(String message)
    {
        if (message == null || message.isEmpty())
        {
            return false;
        }
        // OSRS: "The Dark Lord spares your sacrifice but still rewards you for your efforts."
        if (ProcessSkillSignals.isChaosAltarBoneSaveChat(message))
        {
            return false;
        }
        if (message.contains("bury") && (message.contains("dig") || message.startsWith("you bury")))
        {
            return true;
        }
        if (ProcessSkillSignals.isAltarOfferingChat(message))
        {
            return true;
        }
        return message.contains("you scatter ")
            || message.contains("you drink ")
            || message.contains("you eat ")
            || message.contains("dose of potion left")
            || message.startsWith("you bury ")
            || message.startsWith("you scatter ")
            || message.startsWith("you drink ")
            || message.startsWith("you eat ");
    }

    /** Object gather verbs already tracked for HUD context — used only for activity hints. */
    static boolean isHarvestGatherOption(String option)
    {
        if (option == null || option.isEmpty())
        {
            return false;
        }
        return option.equals("pick")
            || option.equals("pick fruit")
            || option.equals("harvest")
            || option.equals("chop")
            || option.equals("chop down")
            || option.equals("mine")
            || option.equals("fish")
            || option.equals("net")
            || option.equals("bait")
            || option.equals("lure")
            || option.equals("harpoon")
            || option.equals("cage");
    }

    static String activityHintForGatherOption(String option)
    {
        if (option == null)
        {
            return "General";
        }
        if (option.equals("pick") || option.equals("pick fruit") || option.equals("harvest")
            || option.equals("rake") || option.equals("plant"))
        {
            return "Farming";
        }
        if (option.equals("chop") || option.equals("chop down"))
        {
            return "Woodcutting";
        }
        if (option.equals("mine"))
        {
            return "Mining";
        }
        if (option.equals("fish") || option.equals("net") || option.equals("bait")
            || option.equals("lure") || option.equals("harpoon") || option.equals("cage"))
        {
            return "Fishing";
        }
        return "General";
    }

    /**
     * Inventory Bury/Drop/Eat menus sometimes report {@code getItemId() == -1}. Fall back to
     * item-op / widget / inventory-slot ids (Supplies Tracker pattern) and canonicalize so
     * intent matches snapshot item ids.
     */
    private int resolveMenuItemId(MenuOptionClicked event)
    {
        if (event == null)
        {
            return -1;
        }

        int itemId = event.getItemId();
        if (itemId < 0 && event.isItemOp())
        {
            itemId = event.getId();
        }
        if (itemId < 0)
        {
            Widget widget = event.getWidget();
            if (widget != null)
            {
                itemId = widget.getItemId();
            }
        }
        // CC_OP inventory Eat/Drink often leaves getItemId() == -1; read the clicked slot.
        if (itemId < 0)
        {
            itemId = itemIdFromInventorySlot(event.getParam0());
        }
        if (itemId < 0)
        {
            return -1;
        }
        return itemManager.canonicalize(itemId);
    }

    private int itemIdFromInventorySlot(int slot)
    {
        if (slot < 0)
        {
            return -1;
        }
        try
        {
            net.runelite.api.ItemContainer inventory = client.getItemContainer(InventoryID.INV);
            if (inventory == null)
            {
                return -1;
            }
            net.runelite.api.Item[] items = inventory.getItems();
            if (items == null || slot >= items.length)
            {
                return -1;
            }
            net.runelite.api.Item item = items[slot];
            return item == null ? -1 : item.getId();
        }
        catch (RuntimeException ex)
        {
            return -1;
        }
    }

    private void persist()
    {
        if (config.persistHistory())
        {
            persistence.scheduleSave();
        }
    }

    /** Integrated HUD/Infobox arrival window — separate from floating hang time. */
    private static long integratedArrivalMillis()
    {
        return LatestDropModel.INTEGRATED_ARRIVAL_MILLIS;
    }

    private BufferedImage loadIcon()
    {
        try (InputStream input = GpManagerPlugin.class.getResourceAsStream("/profit_manager_icon.png"))
        {
            if (input != null)
            {
                return ImageIO.read(input);
            }
        }
        catch (IOException ex)
        {
            log.debug("Unable to load GP Manager icon", ex);
        }

        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private String normalize(String text)
    {
        return text == null
            ? ""
            : text.replaceAll("<[^>]+>", "").trim().toLowerCase();
    }

    @Provides
    GpManagerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GpManagerConfig.class);
    }
}
