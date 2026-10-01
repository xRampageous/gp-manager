package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Builds a deterministic engine and configuration for isolated accounting and persistence tests.
 */
public final class EngineTestFixtures
{
    /** Fixed clock after every fixture timestamp so retention/recovery is deterministic. */
    public static final long NOW = 1_726_510_000_000L;

    private EngineTestFixtures()
    {
    }

    public static GpManagerConfig config()
    {
        return new GpManagerConfig()
        {
            @Override
            public boolean autoStartSession()
            {
                return false;
            }
        };
    }

    /** Deterministic valuator: 100 gp per unit for any item, so flows never depend on prices. */
    public static FlowValuator valuator()
    {
        return deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                flows.add(new Ab(entry.getKey(), "Item " + entry.getKey(),
                    entry.getValue(), 100, entry.getValue() * 100L));
            }
            return flows;
        };
    }

    public static Am engine()
    {
        return engine(config());
    }

    public static Am engine(GpManagerConfig config)
    {
        if (!JsonCodec.isBound())
        {
            JsonCodec.bind(new Gson());
        }
        return new Am(valuator(), new TransactionClassifier(), config);
    }

}
