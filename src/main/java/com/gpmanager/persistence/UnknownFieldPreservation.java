package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forward compatibility for the saved state (pass 10 step 43): JSON fields this build does not
 * know are carried through load → save unchanged on any object that {@link RetainsUnknownFields},
 * so a profile written by a newer plugin keeps its data when an older build saves it again.
 * Known fields always win — an unknown field never overwrites one this build serialises. No reflection:
 * the set of known fields is whatever the build's own serialisation of the object emits.
 */
public final class UnknownFieldPreservation
{
    /** Implemented by the persisted classes that carry a transient bag of unknown JSON fields. */
    public interface RetainsUnknownFields
    {
        Map<String, JsonElement> getUnknownJsonFields();

        void setUnknownJsonFields(Map<String, JsonElement> fields);
    }

    private UnknownFieldPreservation()
    {
    }

    /** The same Gson with unknown-field preservation registered; safe to call on an already-wrapped instance. */
    public static Gson wrap(Gson gson)
    {
        if (gson == null)
        {
            return null;
        }
        if (gson.getAdapter(SavedState.class) instanceof PreservingAdapter)
        {
            return gson;
        }
        return gson.newBuilder().registerTypeAdapterFactory(new Factory()).create();
    }

    /** Deep-copies the bag so a detached copy does not share mutable JSON trees with its source. */
    public static Map<String, JsonElement> copyOf(Map<String, JsonElement> fields)
    {
        if (fields == null || fields.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<String, JsonElement> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : fields.entrySet())
        {
            copy.put(e.getKey(), e.getValue() == null ? null : e.getValue().deepCopy());
        }
        return copy;
    }

    private static final class Factory implements TypeAdapterFactory
    {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
        {
            if (!RetainsUnknownFields.class.isAssignableFrom(type.getRawType()))
            {
                return null;
            }
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
            return new PreservingAdapter<>(delegate, elements);
        }
    }

    private static final class PreservingAdapter<T> extends TypeAdapter<T>
    {
        private final TypeAdapter<T> delegate;
        private final TypeAdapter<JsonElement> elements;

        PreservingAdapter(TypeAdapter<T> delegate, TypeAdapter<JsonElement> elements)
        {
            this.delegate = delegate;
            this.elements = elements;
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException
        {
            if (value == null)
            {
                out.nullValue();
                return;
            }
            JsonElement tree = delegate.toJsonTree(value);
            Map<String, JsonElement> extras = ((RetainsUnknownFields) value).getUnknownJsonFields();
            if (tree != null && tree.isJsonObject() && extras != null && !extras.isEmpty())
            {
                JsonObject object = tree.getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : extras.entrySet())
                {
                    if (!object.has(e.getKey()))
                    {
                        object.add(e.getKey(), e.getValue());
                    }
                }
            }
            elements.write(out, tree);
        }

        @Override
        public T read(JsonReader in) throws IOException
        {
            JsonElement tree = elements.read(in);
            if (tree == null || tree.isJsonNull())
            {
                return null;
            }
            T value = delegate.fromJsonTree(tree);
            if (value != null && tree.isJsonObject())
            {
                // What the build knows is whatever its own serialisation of the object names; the rest is
                // carried. A known field that happened to be null in the input is carried too, which is
                // harmless: on write the build's own (non-null) value always takes precedence.
                JsonElement echo = delegate.toJsonTree(value);
                JsonObject known = echo != null && echo.isJsonObject() ? echo.getAsJsonObject() : new JsonObject();
                Map<String, JsonElement> extras = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : tree.getAsJsonObject().entrySet())
                {
                    if (!known.has(e.getKey()))
                    {
                        extras.put(e.getKey(), e.getValue());
                    }
                }
                ((RetainsUnknownFields) value).setUnknownJsonFields(extras);
            }
            return value;
        }
    }
}
