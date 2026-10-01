package com.gpmanager;
import static com.gpmanager.GameData.msg;
import com.google.gson.Gson;
/**
* The one JSON codec the plugin uses outside the injected {@link SessionRepository}: RuneLite's
* client Gson, bound once at plugin start-up. Plugin Hub rule: never construct a fresh Gson, so
* nothing in production code can fall back to one — an unbound codec is a programming error.
*/
class JsonCodec {
static volatile Gson gson;
static void bind(Gson clientGson) {
 gson = clientGson;
}

static boolean isBound() {
 return gson != null;
}

static Gson gson() {
 Gson bound = gson;
 if (bound == null) throw new IllegalStateException(msg("c"));
 return bound;
}
}
