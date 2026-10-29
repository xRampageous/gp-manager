package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;

import static com.gpmanager.GpManagerPluginProbe.set;
import static org.junit.Assert.assertEquals;

/**
 * A private boss instance is paid coffer first, then the bank for the rest, in the same tick
 * (Death Cost Tracker's in-game capture). Every instance line arrives as SPAM: both parts
 * count, and a lone SPAM bank line with an empty coffer books the bank-paid fee. A GAMEMESSAGE
 * bank line is reclaim evidence and never an instance fee here.
 */
public class InstanceFeeTest
{
    private final GpManagerConfig config = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    };

    private int tick = 100;

    @Test
    public void theBankShareOfAnInstanceFeeCountsWithTheCofferShare() throws Exception
    {
        Engine engine = engine();
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(spam("Payment has been taken from your coffer: 15,325 Coins."));
        plugin.onChatMessage(spam("Payment has been taken from your bank: 9,675 x Coins"));
        assertEquals(25_000L, engine.metrics(2_000L).cost);
        assertEquals(2, engine.session().transactions().size());
    }

    @Test
    public void aLaterBankPaymentIsNotAnInstanceFee() throws Exception
    {
        Engine engine = engine();
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(spam("Payment has been taken from your coffer: 15,325 Coins."));
        tick++;
        plugin.onChatMessage(spam("Payment has been taken from your bank: 9,675 x Coins"));
        assertEquals(15_325L, engine.metrics(2_000L).cost);
    }

    @Test
    public void aCofferOnlyInstanceFeeBooksTheCapturedLiveLine() throws Exception
    {
        Engine engine = engine();
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(spam("Payment has been taken from your coffer: 12,500 Coins."));
        assertEquals(12_500L, engine.metrics(2_000L).cost);
        assertEquals(1, engine.session().transactions().size());
    }

    @Test
    public void aSpamBankLineWithNoCofferBooksTheBankPaidFee() throws Exception
    {
        Engine engine = engine();
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(spam("Payment has been taken from your bank: 12,500 Coins."));
        assertEquals(12_500L, engine.metrics(2_000L).cost);
        assertEquals(1, engine.session().transactions().size());
        assertEquals("Instance fee (bank)",
            engine.session().transactions().get(0).note());
    }

    @Test
    public void aGameMessageBankLineIsNotAnInstanceFee() throws Exception
    {
        Engine engine = engine();
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(gameMessage("Payment has been taken from your bank: 12,500 Coins."));
        assertEquals(0L, engine.metrics(2_000L).cost);
        assertEquals(0, engine.session().transactions().size());
    }

    private Engine engine()
    {
        Engine engine = new Engine(deltas -> {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(id, "Item " + id, qty, 1, qty)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(FixtureReads.empty());
        return engine;
    }

    private GpManagerPlugin plugin(Engine engine) throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class},
            (proxy, method, args) -> "getTickCount".equals(method.getName()) ? tick : null);
        set(plugin, "config", config);
        set(plugin, "engine", engine);
        set(plugin, "client", client);
        set(plugin, "charges", new ChargeIntake(null, null, config, engine, null));
        return plugin;
    }

    private static ChatMessage spam(String text)
    {
        ChatMessage message = new ChatMessage();
        message.setType(ChatMessageType.SPAM);
        message.setMessage(text);
        return message;
    }

    private static ChatMessage gameMessage(String text)
    {
        ChatMessage message = new ChatMessage();
        message.setType(ChatMessageType.GAMEMESSAGE);
        message.setMessage(text);
        return message;
    }

}
