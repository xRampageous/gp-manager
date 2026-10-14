package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A boat recovery is paid from the bank with an amountless line ("Payment has been taken from
 * your bank."), while the Recover window shows each boat row's exact fee. The Recover click reads
 * the row's fee and the line books it once; without a fresh click nothing books (owner
 * 2026-10-14).
 */
public class BoatRecoveryTest
{
    private static final long T0 = 1_000_000L;

    /** Stub widget parents; each proxy's getParent reads its entry here. */
    private static final Map<Widget, Widget> PARENTS = new IdentityHashMap<>();

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    };

    @Test
    public void theRecoverWindowRowsShowTheirOwnFees()
    {
        Widget ultimate = row("Ultimate Sun", "Deepfin Point", "4,500");
        Widget junior = row("Junior Jim", "The Pandemonium", "250");
        Widget list = container(ultimate, junior);
        assertEquals(4500L, Widgets.rowFee(list, "Ultimate Sun"));
        assertEquals(4500L, Widgets.rowFee(ultimate, "ultimate sun"));
        assertEquals(250L, Widgets.rowFee(list, "junior jim"));
        assertEquals("a boat the window does not show", 0L, Widgets.rowFee(list, "Red Rock"));
    }

    @Test
    public void theClickedRecoverButtonResolvesItsOwnRow()
    {
        Widget button = text("Recover");
        Widget ultimate = rowWith(button, "Ultimate Sun", "Deepfin Point", "57,500");
        container(ultimate);
        assertEquals(57500L, Widgets.rowFee(button, "ultimate sun"));
    }

    @Test
    public void theArmedAmountlessLineBooksOnceFromTheClick()
    {
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "Deepfin Point", "4,500"));

        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        f.tick[0] = 104;
        f.plugin.onChatMessage(bankLine(ChatMessageType.SPAM));

        List<Transaction> rows = f.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals("Recovered Ultimate Sun", rows.get(0).getNote());
        assertEquals(ActionKind.FEE, rows.get(0).getActionKind());
        assertEquals(4500L, rows.get(0).quantity(ItemID.COINS, false));
        assertTrue(rows.get(0).isCounted());

        f.plugin.onChatMessage(bankLine(ChatMessageType.SPAM));
        assertEquals("the fee is one-shot", 1, f.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void aGameMessageLineBooksTheSameWay()
    {
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "250"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        f.tick[0] = 102;
        f.plugin.onChatMessage(bankLine(ChatMessageType.GAMEMESSAGE));
        assertEquals(250L,
            f.engine.getActiveSession().getTransactions().get(0).quantity(ItemID.COINS, false));
    }

    @Test
    public void aStaleOrUnownedLineBooksNothing()
    {
        Fixture none = new Fixture(100);
        none.plugin.onChatMessage(bankLine(ChatMessageType.SPAM));
        assertEquals("no Recover click, nothing to book", 0,
            none.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void anAmountedBankLineKeepsItsInstancePath()
    {
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "4,500"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        f.tick[0] = 104;
        ChatMessage line = new ChatMessage();
        line.setType(ChatMessageType.SPAM);
        line.setMessage("Payment has been taken from your bank: 3,000 x Coins.");
        f.plugin.onChatMessage(line);
        List<Transaction> rows = f.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals("Instance fee (bank)", rows.get(0).getNote());
        assertEquals(3000L, rows.get(0).quantity(ItemID.COINS, false));
    }

    @Test
    public void anUnreadableRecoverRowBooksNothing()
    {
        Fixture f = new Fixture(100);
        Widget list = container(row("Someone Else", "Nowhere", "999"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        f.tick[0] = 102;
        f.plugin.onChatMessage(bankLine(ChatMessageType.GAMEMESSAGE));
        assertEquals("no fee read, nothing books", 0,
            f.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void theFirstNumberAfterTheNameIsTheFee()
    {
        // The live layout is flat: name, location, then the fee box (owner live 2026-10-14).
        Widget flat = container(
            text("1 - Ascended Mind"), text("Location: Port Sarim"), text("250"),
            text("2 - Earnest Calm"), text("Location: The Pandemonium"), text("57,500"));
        assertEquals(250L, Widgets.rowFee(flat, "ascended mind"));
        assertEquals(57500L, Widgets.rowFee(flat, "earnest calm"));

        Widget twoNumbers = container(row("Ultimate Sun", "4,500", "250"));
        assertEquals("the first number after the name is the fee", 4500L,
            Widgets.rowFee(twoNumbers, "ultimate sun"));
    }

    @Test
    public void theLiveFeeBoxClickBooksItsOwnNumber()
    {
        // The live window's Recover click lands on the fee box itself (owner live 2026-10-14).
        Widget fee = text("57,500");
        container(
            text("1 - Ascended Mind"), text("Location: Port Sarim"), text("250"),
            text("2 - Earnest Calm"), text("Location: The Pandemonium"), fee);
        assertEquals(57500L, Widgets.rowFee(fee, "earnest calm"));
        // A target naming another row still resolves through that row's own fee (the game's
        // target always names the clicked row; this is the safe fallback).
        assertEquals(250L, Widgets.rowFee(fee, "ascended mind"));
    }

    @Test
    public void aScrolledNestedRowStillReadsItsFee()
    {
        // The live window nests the rows under a scroll wrapper (owner live 2026-10-14).
        Widget wrapper = container(
            row("Ultimate Sun", "Deepfin Point", "4,500"),
            row("Junior Jim", "The Pandemonium", "250"));
        Widget list = container(wrapper);
        assertEquals(4500L, Widgets.rowFee(list, "Ultimate Sun"));
        assertEquals(250L, Widgets.rowFee(list, "junior jim"));
    }

    @Test
    public void aNullChildNeverBreaksTheReadOrTheCapture()
    {
        // The client's child arrays carry null entries; the read and the capture must survive.
        Widget row = row("Ultimate Sun", "Deepfin Point", "4,500");
        Widget list = stub("", new Widget[] {null, row, null});
        assertEquals(4500L, Widgets.rowFee(list, "Ultimate Sun"));
        assertFalse(Widgets.describe(list).isEmpty());
    }

    @Test
    public void aRecoverClickOutsideTheWindowWidgetStillReadsItsRow()
    {
        // The row scan is the filter: the click's window widget only feeds the miss diagnostic.
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "Deepfin Point", "4,500"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list, 0), "recover", "ultimate sun");
        f.tick[0] = 102;
        f.plugin.onChatMessage(bankLine(ChatMessageType.GAMEMESSAGE));
        List<Transaction> rows = f.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(4500L, rows.get(0).quantity(ItemID.COINS, false));
    }

    @Test
    public void theConfirmDialogRefreshesTheArmThroughASlowDecision()
    {
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "Deepfin Point", "4,500"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        // The confirm dialog ("...for 4,500 coins?") is answered 50 ticks later; its click
        // refreshes the arm so the payment line still books.
        f.dialog[0] = container(
            text("Recover this boat for 4,500 coins?"), text("Yes."), text("No."));
        f.tick[0] = 150;
        f.plugin.noteBoatRecoveryClick(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "yes.",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.Chatmenu.UNIVERSE, -1)),
            "yes.", "");
        f.tick[0] = 153;
        f.plugin.onChatMessage(bankLine(ChatMessageType.GAMEMESSAGE));
        List<Transaction> rows = f.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(4500L, rows.get(0).quantity(ItemID.COINS, false));
    }

    @Test
    public void thePackPaymentBooksTheSameNamedRow()
    {
        // Owner live 2026-10-14: with coins in the pack the game prints nothing; the settle's
        // exact coins loss books the armed fee's named row and nothing else.
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(T0);
        engine.setBaseline(new ContainerSnapshot(mapOf(995, 10_000L)));
        engine.armBoatRecoveryFee(4500L, "Ultimate Sun", SafeMath.now());
        engine.markDirty();
        Transaction row = null;
        for (int tick = 1; tick <= 5; tick++)
        {
            Transaction settled = engine.processIfDirty(
                new ContainerSnapshot(mapOf(995, 5_500L)), T0 + tick * 600L);
            if (settled != null) row = settled;
        }
        assertNotNull(row);
        assertEquals("Recovered Ultimate Sun", row.getNote());
        assertEquals(ActionKind.FEE, row.getActionKind());
        assertEquals(4500L, row.quantity(ItemID.COINS, false));
        assertEquals("and the generic booking never repeats it", 4500L,
            engine.getMetrics(T0 + 10_000L).costs);
    }

    @Test
    public void aDialogNeverArmsOnItsOwn()
    {
        Fixture f = new Fixture(100);
        f.dialog[0] = container(text("Your task is to kill 150 trolls."), text("Okay."));
        f.plugin.noteBoatRecoveryClick(
            ChargeLoadAnyItemTest.click(MenuAction.CC_OP, "okay.",
                ChargeLoadAnyItemTest.widget(0, InterfaceID.Chatmenu.UNIVERSE, -1)),
            "okay.", "");
        f.tick[0] = 102;
        f.plugin.onChatMessage(bankLine(ChatMessageType.GAMEMESSAGE));
        assertEquals("an unrelated dialog's number never books", 0,
            f.engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void theFeeSurvivesASlowConfirmDialog()
    {
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "Deepfin Point", "250"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        f.tick[0] = 100 + 50;
        f.plugin.onChatMessage(bankLine(ChatMessageType.SPAM));
        List<Transaction> rows = f.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(250L, rows.get(0).quantity(ItemID.COINS, false));
    }

    @Test
    public void theAmountlessPatternIsItsOwnShape()
    {
        assertTrue(GameData.pattern("bank-payment-amountless")
            .matcher("payment has been taken from your bank.")
            .matches());
        assertTrue("the game's repeat count",
            GameData.pattern("bank-payment-amountless")
                .matcher("payment has been taken from your bank. (2)")
                .matches());
        assertFalse(GameData.pattern("bank-payment-amountless")
            .matcher("payment has been taken from your bank: 4,500 x coins.")
            .matches());
    }

    @Test
    public void theRepeatSuffixedLineBooksTheSameWay()
    {
        // Owner live 2026-10-14: two recoveries in a row make the game append " (2)".
        Fixture f = new Fixture(100);
        Widget list = container(row("Ultimate Sun", "Deepfin Point", "4,500"));
        f.plugin.noteBoatRecoveryClick(recoverClick(list), "recover", "ultimate sun");
        f.tick[0] = 102;
        ChatMessage line = new ChatMessage();
        line.setType(ChatMessageType.GAMEMESSAGE);
        line.setMessage("Payment has been taken from your bank. (2)");
        f.plugin.onChatMessage(line);
        List<Transaction> rows = f.engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals(4500L, rows.get(0).quantity(ItemID.COINS, false));
    }

    private static final class Fixture
    {
        final int[] tick;
        final Widget[] dialog = new Widget[1];
        final Engine engine;
        final GpManagerPlugin plugin;
        Fixture(int startTick)
        {
            tick = new int[] {startTick};
            engine = HudBuilderTest.engine();
            engine.ensureSession(T0);
            Client client = client(tick, dialog);
            ChargeIntake intake = new ChargeIntake(client, null, CONFIG, engine, null);
            plugin = new GpManagerPluginProbe();
            try
            {
                set(plugin, "config", CONFIG);
                set(plugin, "engine", engine);
                set(plugin, "charges", intake);
                set(plugin, "client", client);
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Widget container(Widget... children)
    {
        return stub("", children);
    }

    private static Widget row(String... texts)
    {
        Widget[] children = new Widget[texts.length];
        for (int index = 0; index < texts.length; index++) children[index] = text(texts[index]);
        return stub("", children);
    }

    private static Widget rowWith(Widget extra, String... texts)
    {
        Widget[] children = new Widget[texts.length + 1];
        children[0] = extra;
        for (int index = 0; index < texts.length; index++) children[index + 1] = text(texts[index]);
        return stub("", children);
    }

    private static Widget text(String value)
    {
        return stub(value, new Widget[0]);
    }

    private static Widget stub(String textValue, Widget[] children)
    {
        Widget widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getText": return textValue;
                    case "getDynamicChildren": return children;
                    case "getChildren": return new Widget[0];
                    case "getParent": return PARENTS.get(proxy);
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        for (Widget child : children) if (child != null) PARENTS.put(child, widget);
        return widget;
    }

    private static MenuOptionClicked recoverClick(Widget clicked)
    {
        return recoverClick(clicked, InterfaceID.SailingBoatSelection.BOATS_CONTAINER_RECENT);
    }

    private static MenuOptionClicked recoverClick(Widget clicked, int param1)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return "Recover";
                    case "getTarget": return "";
                    case "getType": return MenuAction.CC_OP;
                    case "getWidget": return clicked;
                    case "getParam1": return param1;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

    private static ChatMessage bankLine(ChatMessageType type)
    {
        ChatMessage message = new ChatMessage();
        message.setType(type);
        message.setMessage("Payment has been taken from your bank.");
        return message;
    }

    private static Map<Integer, Long> mapOf(Object... values)
    {
        Map<Integer, Long> result = new HashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2)
        {
            result.put((Integer) values[index], (Long) values[index + 1]);
        }
        return result;
    }

    private static Client client(int[] tick, Widget[] dialog)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                if ("getTickCount".equals(method.getName())) return tick[0];
                if ("getWidget".equals(method.getName()))
                    return (int) args[0] == InterfaceID.Chatmenu.UNIVERSE ? dialog[0] : null;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    }
}
