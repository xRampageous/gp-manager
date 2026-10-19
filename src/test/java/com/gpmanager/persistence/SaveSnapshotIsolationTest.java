package com.gpmanager;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Persisted graph closure and ownership; reflection stays in offline tests. */
public class SaveSnapshotIsolationTest {
    static Set<Object> inspected=Collections.newSetFromMap(new IdentityHashMap<>());
    static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> owner=type;owner!=null&&owner!=Object.class;owner=owner.getSuperclass())
            result.addAll(Arrays.asList(owner.getDeclaredFields()));
        return result;
    }
    static JsonElement seed(Type type, String path) {
        if(type instanceof ParameterizedType) {
            ParameterizedType p=(ParameterizedType)type;
            if(p.getRawType()==List.class) {JsonArray a=new JsonArray();a.add(seed(p.getActualTypeArguments()[0],path+"[0]"));return a;}
            if(p.getRawType()==Map.class) {JsonObject o=new JsonObject();o.add(p.getActualTypeArguments()[0]==Integer.class?"19":"key",seed(p.getActualTypeArguments()[1],path+".value"));return o;}
            throw new AssertionError(type);
        }
        Class<?> c=(Class<?>)type;
        if(c==String.class)return new JsonPrimitive(path);
        if(c==boolean.class||c==Boolean.class)return new JsonPrimitive(true);
        if(c.isPrimitive()||Number.class.isAssignableFrom(c))return new JsonPrimitive(123);
        if(c.isEnum())return new JsonPrimitive(((Enum<?>)c.getEnumConstants()[0]).name());
        JsonObject object=new JsonObject();
        for(Field f:fields(c)) if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers())&&!f.isSynthetic()) object.add(f.getName(),seed(f.getGenericType(),path+"."+f.getName()));
        return object;
    }
    static void aliases(Object a,Object b) throws Exception {
        if(a==null) {if(b!=null)throw new AssertionError();return;}
        Class<?> c=a.getClass();
        if(c.isEnum()||a instanceof String||a instanceof Number||a instanceof Boolean)return;
        if(a==b)throw new AssertionError("shared mutable reference: "+c);
        if(!inspected.add(a))return;
        if(a instanceof List) {List<?> x=(List<?>)a,y=(List<?>)b;for(int i=0;i<x.size();i++)aliases(x.get(i),y.get(i));return;}
        if(a instanceof Map) {Map<?,?> x=(Map<?,?>)a,y=(Map<?,?>)b;for(Object key:x.keySet())aliases(x.get(key),y.get(key));return;}
        for(Field f:fields(c))if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers())&&!f.isSynthetic()) {f.setAccessible(true);aliases(f.get(a),f.get(b));}
    }
    static void mutate(Object o) throws Exception {
        if(o==null||!inspected.add(o))return;
        if(o instanceof List) {for(Object v:(List<?>)o)mutate(v);((List<?>)o).clear();return;}
        if(o instanceof Map) {((Map<?,?>)o).clear();return;}
        Class<?> c=o.getClass();
        if(!c.getName().startsWith("com.gpmanager.")||c.isEnum())return;
        for(Field f:fields(c)) if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers())&&!f.isSynthetic()) {
            f.setAccessible(true);Object value=f.get(o);mutate(value);
            if(Modifier.isFinal(f.getModifiers()))continue;
            if(f.getType()==String.class)f.set(o,"mutated");
            else if(f.getType()==long.class)f.setLong(o,9876L);
            else if(f.getType()==int.class)f.setInt(o,9876);
            else if(f.getType()==boolean.class)f.setBoolean(o,!f.getBoolean(o));
        }
    }
    static SavedState capture(SavedState source) throws Exception {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {}) {
            @Override SavedState savedState() {
                assertTrue("capture reads under the engine monitor", Thread.holdsLock(this));
                return source;
            }
        };
        Gson gson = new Gson();
        SessionRepository repository = new SessionRepository(gson,
                FilepathTestSupport.root(Files.createTempDirectory("gp-detached-contract")), false);
        Persistence disk = new Persistence(null, null, repository, new OrderedPersistenceWriter(repository, null), engine);
        WriteIntent intent = disk.currentIntent(true);
        assertNull("capture must not encode JSON", intent.json);
        return intent.state;
    }

    static JsonElement payload(Gson gson, SavedState state) {
        JsonObject json = gson.toJsonTree(state).getAsJsonObject();
        json.remove("savedAtEpochMillis");
        json.remove("revision");
        return json;
    }

    @Test public void everyPersistedFieldSurvivesCaptureWithoutMutableAliases() throws Exception {
        Gson gson = new Gson();
        SavedState source = gson.fromJson(seed(SavedState.class, "root"), SavedState.class);
        JsonElement before = payload(gson, source);
        SavedState copy = capture(source);
        assertEquals(before, payload(gson, copy));
        inspected.clear();
        aliases(source, copy);
        inspected.clear();
        mutate(source);
        assertEquals("later corrections cannot mutate an enqueued payload", before, payload(gson, copy));
    }

    @Test public void nullAndEmptyStructuresStayDistinct() throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().create();
        for (String json : new String[]{"{}", "{\"history\":null,\"savedGrinds\":null,\"savedSessions\":null}",
                "{\"history\":[null],\"pendingClaims\":[null],\"lootingBag\":null}"}) {
            SavedState source = gson.fromJson(json, SavedState.class);
            assertEquals(payload(gson, source), payload(gson, capture(source)));
        }
    }

    @Test public void capturedSessionsHaveNoRuntimeListenersObserversOrMemo() throws Exception {
        Gson gson = new Gson();
        SavedState source = gson.fromJson(seed(SavedState.class, "root"), SavedState.class);
        source.generalSession.changeListener = () -> { throw new AssertionError("live listener escaped"); };
        source.generalSession.ownedValueObserver = new TrackedBasisLedger();
        source.generalSession.saveStamp = 17;
        Transaction receipt = source.generalSession.transactions.get(0);
        receipt.memo = new Object[]{"old"};
        receipt.castItemId = 123;
        SavedState copy = capture(source);
        assertNull(copy.generalSession.changeListener);
        assertNull(copy.generalSession.ownedValueObserver);
        assertEquals(0, copy.generalSession.saveStamp);
        assertNull(copy.generalSession.transactions.get(0).memo);
        assertEquals(0, copy.generalSession.transactions.get(0).castItemId);
        assertEquals(17, source.generalSession.saveStamp);
        assertEquals(123, receipt.castItemId);
    }
}
