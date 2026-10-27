package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static com.gpmanager.GpManagerPluginProbe.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production-seam exact spell evidence: the real plugin menu path (spellbook selection then
 * widget-target click) must retain the exact spell name through settlement, while generic and
 * stale interactions honestly fall back to Cast. Target names are never spell identity.
 */
public class ExactSpellEvidenceProductionTest
{
    private static final long T0 = 1_000_000_000_000L;
    private static final int DEATH_RUNE = ItemID.DEATHRUNE;
    private static final int CHAOS_RUNE = ItemID.CHAOSRUNE;
    private static final int WATER_RUNE = ItemID.WATERRUNE;
    private static final int BLOOD_RUNE = ItemID.BLOODRUNE;
    private static final int NATURE_RUNE = ItemID.NATURERUNE;
    private static final int ASTRAL_RUNE = ItemID.ASTRALRUNE;
    private static final int EARTH_RUNE = ItemID.EARTHRUNE;
    private static final int[] ICE_BURST_RUNES = {DEATH_RUNE, CHAOS_RUNE, WATER_RUNE};

    @Test
    public void autocastNameSurvivesNettedRunePickupAndPotionIntent() throws Exception
    {
        Harness h = harness("Nechryael");
        h.animate("Ice Burst", 1979);
        h.engine.noteConsume(2434, 10, false, ActionKind.DRINK, null);
        h.inventory.put(CHAOS_RUNE, 66L); // pickup hides the chaos spent
        Transaction cast = h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L);
        Transaction receipt = h.engine.session().transactions().stream()
            .filter(t -> t.action() == ActionKind.CAST).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Ice Burst", receipt.spellName());
        assertEquals("Ice Burst", recentFor(h, receipt.id()).name);
        assertEquals("measured net quantities stay unchanged", 46L, h.engine.session()
            .transactions().stream().flatMap(t -> t.flows().stream())
            .filter(f -> f.item == CHAOS_RUNE).mapToLong(f -> f.quantity).sum());
        Transaction restored = new com.google.gson.Gson().fromJson(
            new com.google.gson.Gson().toJson(receipt), Transaction.class);
        assertEquals("the observed spell survives restart", "Ice Burst", restored.spellName());
    }

    @Test
    public void aLaterSelectionCannotRenameTheObservedAutocast() throws Exception
    {
        Harness h = harness("Nechryael");
        h.animate("Ice Burst", 1979);
        h.select("Ice Barrage");
        h.client.autocastName = "Ice Barrage";
        Transaction cast = h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L);
        assertEquals("Ice Burst", cast.spellName());
    }

    @Test
    public void selectedAutocastDoesNotNameUnrelatedAnimationsOrOtherPlayers() throws Exception
    {
        for (int animation : new int[] {-1, 829})
        {
            Harness h = harness("Nechryael");
            h.animate("Ice Burst", animation);
            Transaction cast = h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L);
            assertEquals("", cast.spellName());
        }
        Harness h = harness("Nechryael");
        h.client.autocastName = "Ice Burst";
        h.client.animation = 1979;
        AnimationChanged other = new AnimationChanged();
        other.setActor((Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (p, m, a) -> m.getReturnType() == int.class ? 1979 : null));
        h.plugin.onAnimationChanged(other);
        assertEquals("", h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L).spellName());
    }

    @Test
    public void differentObservedSpellsDoNotMergeDuringStabilization() throws Exception
    {
        Harness h = harness("Nechryael");
        h.animate("Ice Burst", 1979);
        h.inventory.put(DEATH_RUNE, 19L);
        h.inventory.put(WATER_RUNE, 19L);
        h.engine.processIfDirty(new ContainerSnapshot(h.inventory), T0 + 100L, null);
        h.animate("Ice Barrage", 1979);
        h.inventory.put(DEATH_RUNE, 18L);
        h.inventory.put(WATER_RUNE, 18L);
        settleStable(h.engine, h.inventory, T0 + 700L);
        List<Transaction> rows = h.engine.session().transactions();
        assertEquals(2, rows.size());
        assertEquals("Ice Burst", rows.get(0).spellName());
        assertEquals("Ice Barrage", rows.get(1).spellName());
        assertEquals(-260L, rows.stream().mapToLong(Transaction::net).sum());
    }

    @Test
    public void confirmedAutocastEvidenceExpiresAndClearsAtOwnerFence() throws Exception
    {
        for (boolean fence : new boolean[] {false, true})
        {
            Harness h = harness("Nechryael");
            h.animate("Ice Burst", 1979);
            if (fence) h.engine.clearActionEvidence();
            else h.tickStable(10, T0 + 100L);
            assertEquals("", h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 8_000L).spellName());
        }
    }

    @Test
    public void observedAncientSpellWinsEvenWhenNettedCostsFitAStandardSpell() throws Exception
    {
        for (boolean bolt : new boolean[] {false, true})
        {
            Harness h = harness("Nechryael");
            h.animate("Ice Burst", 1979);
            h.inventory.put(bolt ? CHAOS_RUNE : DEATH_RUNE, 19L);
            h.inventory.put(WATER_RUNE, bolt ? 18L : 17L);
            Transaction cast = settleStable(h.engine, h.inventory, T0 + 100L);
            assertEquals(bolt ? "Water Bolt" : "Water Blast", Spells.spellFor(cast.flows()));
            assertEquals("Ice Burst", cast.spellName());
            Transaction copied = MoneySnapshot.copy(cast);
            assertEquals("detached UI reads keep the execution identity", "Ice Burst", copied.spellName());
        }
    }

    @Test
    public void disabledOrUnknownAutocastNeverSuppliesAName() throws Exception
    {
        for (String name : new String[] {"Ice Burst", "unknown spell"})
        {
            Harness h = harness("Nechryael");
            h.client.autocastEnabled = !name.equals("Ice Burst");
            h.animate(name, 1979);
            assertEquals("", h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L).spellName());
        }
    }

    @Test
    public void explicitManualTargetKeepsItsSpellWithAnotherAutocastSelected() throws Exception
    {
        Harness h = harness("Nechryael");
        h.select("Ice Barrage");
        h.target("Guard");
        h.animate("Ice Burst", 1979);
        assertEquals("Ice Barrage", h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L).spellName());
    }

    @Test
    public void malformedObservedNamesDegradeSafelyAndMemoTracksIdentity() throws Exception
    {
        Harness h = harness("Nechryael");
        Transaction cast = h.settle(new int[] {DEATH_RUNE, WATER_RUNE}, T0 + 100L);
        long net = cast.net();
        assertEquals("", cast.spellName());
        cast.observedSpellName = "Ice Burst";
        assertEquals("Ice Burst", cast.spellName());
        for (String invalid : new String[] {"Cast", "unknown spell", "Ice Burst\u0000", "Ice Burst".repeat(40)})
        {
            cast.observedSpellName = invalid;
            assertEquals("", cast.spellName());
        }
        cast.observedSpellName = "Ice Burst";
        cast.setAction(ActionKind.DRINK);
        assertEquals("a non-cast ignores a spell stamp", "", cast.spellName());
        assertEquals("identity never creates or removes money", net, cast.net());
    }

    @Test
    public void pickupChurnDoesNotEraseTheAlreadyCapturedCast() throws Exception
    {
        Harness h = harness("Nechryael");
        h.animate("Ice Burst", 1979);
        h.inventory.put(DEATH_RUNE, 19L);
        h.inventory.put(WATER_RUNE, 19L);
        for (int tick = 0; tick < 6; tick++)
        {
            h.inventory.put(ItemID.COINS, 100_001L + tick);
            h.engine.processIfDirty(new ContainerSnapshot(h.inventory), T0 + tick * 600L, null);
        }
        settleStable(h.engine, h.inventory, T0 + 4_000L);
        Transaction cast = h.engine.session().transactions().stream()
            .filter(t -> t.action() == ActionKind.CAST).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Ice Burst", cast.spellName());
    }

    @Test
    public void anUnidentifiedLaterCastCannotInheritTheEarlierSpell() throws Exception
    {
        Harness h = harness("Nechryael");
        h.animate("Ice Burst", 1979);
        h.inventory.put(DEATH_RUNE, 19L);
        h.inventory.put(WATER_RUNE, 19L);
        h.engine.processIfDirty(new ContainerSnapshot(h.inventory), T0 + 100L, null);
        h.client.autocastEnabled = false;
        h.animate("Ice Burst", 1979);
        h.inventory.put(DEATH_RUNE, 18L);
        h.inventory.put(WATER_RUNE, 18L);
        settleStable(h.engine, h.inventory, T0 + 700L);
        List<Transaction> rows = h.engine.session().transactions();
        assertEquals(2, rows.size());
        assertEquals("Ice Burst", rows.get(0).spellName());
        assertEquals("", rows.get(1).spellName());
    }

    @Test
    public void iceBurstTwoStageWidgetTargetBooksTheExactName() throws Exception
    {
        Harness harness = harness("Vorkath");
        Transaction cast = harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 100L);

        assertNotNull("the rune spend settles", cast);
        assertEquals(TransactionType.CONSUMPTION, cast.type());
        assertTrue(cast.counts());
        assertEquals(ActionKind.CAST, cast.action());
        assertNotNull("the two-stage interaction keeps its exact spell", cast.actionLabel());
        assertEquals("Ice Burst", cast.actionLabel().value());
        assertEquals(-180L, cast.net());
        assertEquals("rune accounting is untouched", 3, cast.flows().size());
        assertEquals("the spell spend stays a supply",
            CostKind.SUPPLIES, CostKind.of(cast, cast.flows().get(0)));

        LiveSnapshot.Recent row = recentFor(harness, cast.id());
        assertEquals("Ice Burst", row.name);
        assertEquals("Cast", LivePage.metaOf(row));
    }

    @Test
    public void iceBarrageTwoStageWidgetTargetBooksItsOwnExactName() throws Exception
    {
        Harness harness = harness("Vorkath");
        Transaction cast = harness.cast("Ice Barrage", "Guard",
            new int[] {DEATH_RUNE, BLOOD_RUNE, WATER_RUNE}, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.actionLabel());
        assertEquals("Ice Barrage", cast.actionLabel().value());
        assertEquals("Ice Barrage", recentFor(harness, cast.id()).name);
    }

    @Test
    public void replacementSelectionKeepsOnlyTheLatestSpell() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Ice Burst");
        harness.select("Ice Barrage");
        harness.target("Guard");
        Transaction cast = harness.settle(ICE_BURST_RUNES, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.actionLabel());
        assertEquals("a later selection replaces the earlier evidence",
            "Ice Barrage", cast.actionLabel().value());
    }

    @Test
    public void expiredEvidenceFallsBackToGenericCast() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Ice Burst");
        harness.target("Guard");
        // No matching consumption: the bounded evidence (18 ticks at the default settings)
        // expires on its own ticks.
        harness.tickStable(24, T0 + 100L);
        Transaction cast = harness.settle(ICE_BURST_RUNES, T0 + 8_000L);

        assertNotNull(cast);
        assertEquals(ActionKind.CAST, cast.action());
        assertNull("expired evidence must never name a later rune loss",
            cast.actionLabel());
        assertEquals("Cast", recentFor(harness, cast.id()).name);
    }

    @Test
    public void targetNamesNeverBecomeSpellNames() throws Exception
    {
        Harness harness = harness("Vorkath");
        Transaction burst = harness.cast("Ice Burst", "Vengeance", ICE_BURST_RUNES, T0 + 100L);
        assertNotNull(burst);
        assertEquals("the NPC name is not spell evidence",
            "Ice Burst", burst.actionLabel().value());

        Harness plain = harness("Vorkath");
        plain.targetNamed("Ice Burst");
        Transaction generic = plain.settle(ICE_BURST_RUNES, T0 + 100L);
        assertNotNull(generic);
        assertNull("a target named like a spell cannot fabricate one",
            generic.actionLabel());
        assertEquals("Cast", recentFor(plain, generic.id()).name);
    }

    @Test
    public void selectedWidgetMustBelongToTheSpellbook() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.selectWidget(widget(99 << 16 | 3, "<col=ff9040>Ice Burst</col>", "Cast"));
        harness.target("Guard");
        Transaction cast = harness.settle(ICE_BURST_RUNES, T0 + 100L);

        assertNotNull(cast);
        assertNull("a selected widget outside the spellbook is not spell evidence",
            cast.actionLabel());
    }

    @Test
    public void vengeanceSelfCastUsesDirectSpellbookEvidence() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Vengeance");
        Transaction cast = harness.settle(new int[] {DEATH_RUNE, ASTRAL_RUNE, EARTH_RUNE}, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.actionLabel());
        assertEquals("Vengeance", cast.actionLabel().value());
    }

    @Test
    public void highLevelAlchemyUsesTheSelectedSpellOnAWidgetTarget() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("High Level Alchemy");
        harness.targetOnWidget("Nature rune");
        Transaction cast = harness.settle(new int[] {NATURE_RUNE}, T0 + 100L);

        assertNotNull(cast);
        assertNotNull(cast.actionLabel());
        assertEquals("High Level Alchemy", cast.actionLabel().value());
    }

    @Test
    public void profileFenceClearsPendingSpellEvidenceButKeepsBookedLabels() throws Exception
    {
        Harness harness = harness("Vorkath");
        Transaction cast = harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 100L);
        assertNotNull(cast);
        long net = cast.net();
        int flows = cast.flows().size();

        // A pending, unbooked selection exists when the owner fence runs.
        harness.select("Ice Burst");
        harness.target("Guard");
        harness.engine.clearActionEvidence();

        assertNotNull("booked schema-105 labels survive the presentation owner fence",
            cast.actionLabel());
        assertEquals("Ice Burst", cast.actionLabel().value());
        assertEquals(net, cast.net());
        assertEquals(flows, cast.flows().size());
        assertEquals(TransactionType.CONSUMPTION, cast.type());
        assertTrue(cast.counts());

        Transaction later = harness.settle(ICE_BURST_RUNES, T0 + 2_000L);
        assertNotNull(later);
        assertEquals(ActionKind.CAST, later.action());
        assertNull("pending pre-fence evidence must not label a later cast",
            later.actionLabel());
    }

    @Test
    public void lifecyclePauseClearsStaleSpellEvidence() throws Exception
    {
        Harness harness = harness("Vorkath");
        harness.select("Ice Burst");
        harness.target("Guard");
        // Logout/relog fence: the lifecycle pause clears transient action evidence, and the
        // post-login baseline priming keeps the first change from inheriting anything.
        harness.engine.pauseForLifecycle(T0 + 200L);
        harness.tickStable(2, T0 + 300L);
        harness.engine.resume(T0 + 1_000L, PauseReason.LIFECYCLE);
        harness.engine.setBaseline(new ContainerSnapshot(harness.inventory));
        Transaction cast = harness.settle(ICE_BURST_RUNES, T0 + 2_000L);

        assertNotNull(cast);
        assertEquals(ActionKind.CAST, cast.action());
        assertNull("a paused/relogged session must not inherit pre-logout spell evidence",
            cast.actionLabel());
    }

    @Test
    public void ledgerGroupsRepeatedExactSpellsAndSeparatesDifferentSpells() throws Exception
    {
        Harness harness = harness("Vorkath");
        assertNotNull(harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 100L));
        assertNotNull(harness.cast("Ice Burst", "Guard", ICE_BURST_RUNES, T0 + 2_000L));
        assertNotNull(harness.cast("Ice Barrage", "Guard", ICE_BURST_RUNES, T0 + 3_000L));
        // A generic cast with no exact evidence stays generic.
        harness.targetNamed("Guard");
        assertNotNull(harness.settle(ICE_BURST_RUNES, T0 + 4_000L));

        LedgerData data = LedgerData.capture(harness.engine, T0 + 5_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", null, null, null, null));
        Semantics.Group burstGroup = groupNamed(data.cost.groups, "Ice Burst", "Cast");
        Semantics.Group barrage = groupNamed(data.cost.groups, "Ice Barrage", "Cast");
        Semantics.Group generic = groupNamed(data.cost.groups, "Cast", "Cast");
        assertNotNull(burstGroup);
        assertNotNull(barrage);
        assertNotNull(generic);
        assertTrue("repeated exact casts aggregate before paging", burstGroup.actionGroup());
        assertEquals(2, burstGroup.count);
        assertEquals(-360L, burstGroup.value);
        assertEquals("a different spell is never merged in", 1, barrage.count);
        assertEquals(-180L, barrage.value);
        assertEquals("a generic cast is never mislabelled", 1, generic.count);
        assertFalse(burstGroup.id.equals(barrage.id));
        assertFalse(burstGroup.id.equals(generic.id));
        assertEquals("grouped exact spells reconcile to the canonical session net",
            -720L, data.cost.total);
    }

    // ── production harness ─────────────────────────────────────────────────────────────────────

    private static final class Harness
    {
        final Engine engine;
        final GpManagerPlugin plugin;
        final MutableSpellClient client;
        final Map<Integer, Long> inventory = new HashMap<>();

        Harness(Engine engine, GpManagerPlugin plugin, MutableSpellClient client)
        {
            this.engine = engine;
            this.plugin = plugin;
            this.client = client;
        }

        void select(String spell)
        {
            client.selected = spellWidget(spell);
            plugin.onMenuOptionClicked(click("Cast", spell, MenuAction.CC_OP, spellWidget(spell), -1));
        }

        void animate(String spell, int animation)
        {
            client.autocastName = spell;
            client.animation = animation;
            AnimationChanged event = new AnimationChanged();
            event.setActor(client.local);
            plugin.onAnimationChanged(event);
        }

        void selectWidget(Widget widget)
        {
            client.selected = widget;
            plugin.onMenuOptionClicked(click("Cast", "unknown", MenuAction.CC_OP, widget, -1));
        }

        void target(String targetName)
        {
            plugin.onMenuOptionClicked(click("Cast", targetName, MenuAction.WIDGET_TARGET_ON_NPC, null, -1));
        }

        void targetNamed(String targetName)
        {
            client.selected = null;
            target(targetName);
        }

        void targetOnWidget(String targetName)
        {
            plugin.onMenuOptionClicked(click("Cast", targetName, MenuAction.WIDGET_TARGET_ON_WIDGET, null, -1));
        }

        Transaction cast(String spell, String targetName, int[] runes, long now)
        {
            select(spell);
            target(targetName);
            return settle(runes, now);
        }

        Transaction settle(int[] runes, long now)
        {
            for (int rune : runes)
            {
                inventory.put(rune, inventory.get(rune) - 1L);
            }
            return settleStable(engine, inventory, now);
        }

        void tickStable(int ticks, long now)
        {
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            for (int i = 0; i < ticks; i++)
            {
                engine.processIfDirty(snapshot, now + i * 600L, null);
            }
        }
    }

    private static Harness harness(String sessionName) throws Exception
    {
        Engine engine = productionEngine();
        long now = T0;
        engine.startCustom(sessionName, now);
        MutableSpellClient client = new MutableSpellClient();
        GpManagerPlugin plugin = plugin(engine, client.client());
        Harness harness = new Harness(engine, plugin, client);
        harness.inventory.put(ItemID.COINS, 100_000L);
        for (int rune : new int[] {DEATH_RUNE, CHAOS_RUNE, WATER_RUNE, BLOOD_RUNE, NATURE_RUNE,
            ASTRAL_RUNE, EARTH_RUNE})
        {
            harness.inventory.put(rune, 20L);
        }
        engine.setBaseline(new ContainerSnapshot(harness.inventory));
        return harness;
    }

    private static LiveSnapshot.Recent recentFor(Harness harness, String transactionId)
    {
        LiveSnapshot snapshot = LiveSnapshot.capture(harness.engine, T0 + 60_000L, LiveContext.NONE);
        return snapshot.recent.stream()
            .filter(row -> transactionId.equals(row.receiptId))
            .findFirst().orElseThrow(AssertionError::new);
    }

    private static Semantics.Group groupNamed(
        List<Semantics.Group> groups, String name, String action)
    {
        for (Semantics.Group group : groups)
        {
            if (name.equals(group.name) && action.equals(group.label.isEmpty() ? ""
                : group.label))
            {
                return group;
            }
        }
        return null;
    }

    private static Transaction settleStable(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(snapshot, now + i * 600L, null);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Engine productionEngine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Flow(id, runeName(id), delta.getValue(), price(id),
                    delta.getValue() * price(id),
                    id == ItemID.COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static GpManagerPlugin plugin(Engine engine, Client client) throws Exception
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "engine", engine);
        set(plugin, "config", config);
        set(plugin, "client", client);
        set(plugin, "targets",
            GpManagerPluginProbe.tracker(client, engine, config));
        set(plugin, "charges", new ChargeIntake(client, null, config, engine, null));
        return plugin;
    }

    private static MenuOptionClicked click(String option, String target, MenuAction action,
        @javax.annotation.Nullable Widget widget, int itemId)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return option;
                    case "getTarget": return target;
                    case "getType": return action;
                    case "getWidget": return widget;
                    case "getItemId": return itemId;
                    case "getIdentifier": return 0;
                    case "getItemOp": return 0;
                    case "isItemOp": return false;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

    private static Widget spellWidget(String spell)
    {
        return widget(InterfaceID.MAGIC_SPELLBOOK << 16 | 17,
            "<col=ff9040>" + spell + "</col>", "Cast");
    }

    private static Widget widget(int id, String text, String name)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getId": return id;
                    case "getText": return text;
                    case "getName": return name;
                    case "getItemId": return -1;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
    }

    /** Client double whose selected spell widget can be set per interaction. */
    private static final class MutableSpellClient
    {
        String autocastName;
        boolean autocastEnabled = true;
        int animation;
        final Player local = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (p, m, a) ->
            {
                if (m.getName().equals("getAnimation")) return animation;
                if (m.getReturnType() == int.class) return 0;
                if (m.getReturnType() == boolean.class) return false;
                return null;
            });
        @javax.annotation.Nullable
        Widget selected;

        Client client()
        {
            return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[] {Client.class}, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "getGameState": return GameState.LOGGED_IN;
                        case "getSelectedWidget": return selected;
                        case "getWidget": return null;
                        case "getItemContainer": return null;
                        case "getLocalPlayer": return local;
                        case "getVarpValue": return (int) args[0] == VarPlayerID.AUTOCAST_SPELL_OBJ
                            && autocastName != null ? ItemID._70_ICE_BURST : 0;
                        case "getVarbitValue": return (int) args[0] == VarbitID.AUTOCAST_SET
                            && autocastName != null && autocastEnabled ? 1 : 0;
                        case "getItemDefinition": return (ItemComposition) Proxy.newProxyInstance(
                            ItemComposition.class.getClassLoader(), new Class<?>[] {ItemComposition.class},
                            (p, m, a) -> m.getName().equals("getName") ? autocastName : null);
                        case "getTickCount": return 0;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
        }
    }

    private static String runeName(int id)
    {
        if (id == ItemID.COINS) return "Coins";
        if (id == DEATH_RUNE) return "Death rune";
        if (id == CHAOS_RUNE) return "Chaos rune";
        if (id == WATER_RUNE) return "Water rune";
        if (id == BLOOD_RUNE) return "Blood rune";
        if (id == NATURE_RUNE) return "Nature rune";
        if (id == ASTRAL_RUNE) return "Astral rune";
        if (id == EARTH_RUNE) return "Earth rune";
        return "Item " + id;
    }

    private static int price(int id)
    {
        if (id == ItemID.COINS) return 1;
        if (id == DEATH_RUNE) return 100;
        if (id == CHAOS_RUNE) return 50;
        if (id == WATER_RUNE) return 30;
        if (id == BLOOD_RUNE) return 200;
        if (id == NATURE_RUNE) return 100;
        if (id == ASTRAL_RUNE) return 50;
        if (id == EARTH_RUNE) return 10;
        return 10;
    }

}
