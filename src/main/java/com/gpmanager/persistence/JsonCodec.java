package com.gpmanager.persistence;

import com.google.gson.Gson;

/**
 * The one JSON codec the plugin uses outside the injected {@link SessionRepository}: RuneLite's
 * client Gson, bound once at plugin start-up and customised through {@code newBuilder()} by
 * {@link UnknownFieldPreservation#wrap}. Plugin Hub rule: never construct a fresh Gson, so nothing
 * in production code can fall back to one — an unbound codec is a programming error, not a default.
 */
public final class JsonCodec
{
    private static volatile Gson gson;

    private JsonCodec()
    {
    }

    public static void bind(Gson clientGson)
    {
        gson = UnknownFieldPreservation.wrap(clientGson);
    }

    public static boolean isBound()
    {
        return gson != null;
    }

    public static Gson gson()
    {
        Gson bound = gson;
        if (bound == null)
        {
            throw new IllegalStateException("JsonCodec.bind(gson) must run at plugin start-up before any JSON work");
        }
        return bound;
    }
}
