package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copy-safe presentation layout: ordered tile ids and hidden ids per page.
 * An empty legacy layout means the consumer uses that page's built-in order
 * with no tiles hidden.
 */
public class TileLayout
{
    private Map<String, PageLayout> pages = new LinkedHashMap<>();

    /** Gson and legacy default: no overrides, so existing page defaults apply. */
    public TileLayout()
    {
    }

    public TileLayout(Map<String, PageLayout> pages)
    {
        this.pages = copyPages(pages);
    }

    public static TileLayout legacyDefaults()
    {
        return new TileLayout(Collections.emptyMap());
    }

    /** Returns an immutable deep copy of the page map. */
    public Map<String, PageLayout> getPages()
    {
        return Collections.unmodifiableMap(copyPages(pages));
    }

    /** A missing or malformed page resolves to the legacy no-overrides state. */
    public PageLayout getPage(String pageId)
    {
        String key = normalizeId(pageId);
        PageLayout page = key.isEmpty() || pages == null ? null : pages.get(key);
        return page == null ? PageLayout.empty() : new PageLayout(page);
    }

    public List<String> getOrderedTileIds(String pageId)
    {
        return getPage(pageId).getOrderedTileIds();
    }

    public Set<String> getHiddenTileIds(String pageId)
    {
        return getPage(pageId).getHiddenTileIds();
    }

    public boolean isHidden(String pageId, String tileId)
    {
        String normalized = normalizeId(tileId);
        return !normalized.isEmpty() && getHiddenTileIds(pageId).contains(normalized);
    }

    private static Map<String, PageLayout> copyPages(Map<String, PageLayout> source)
    {
        Map<String, PageLayout> copy = new LinkedHashMap<>();
        if (source != null)
        {
            for (Map.Entry<String, PageLayout> entry : source.entrySet())
            {
                String key = normalizeId(entry.getKey());
                if (!key.isEmpty() && entry.getValue() != null)
                {
                    copy.put(key, new PageLayout(entry.getValue()));
                }
            }
        }
        return copy;
    }

    private static String normalizeId(String value)
    {
        return value == null ? "" : value.trim();
    }

    /** Per-page order and visibility overrides. */
    public static final class PageLayout
    {
        private List<String> orderedTileIds = new ArrayList<>();
        private Set<String> hiddenTileIds = new LinkedHashSet<>();

        /** Gson and legacy default: no page-specific overrides. */
        public PageLayout()
        {
        }

        public PageLayout(List<String> orderedTileIds, Set<String> hiddenTileIds)
        {
            this.orderedTileIds = normalizeOrder(orderedTileIds);
            this.hiddenTileIds = normalizeHidden(hiddenTileIds);
        }

        private PageLayout(PageLayout source)
        {
            this(source == null ? null : source.orderedTileIds,
                source == null ? null : source.hiddenTileIds);
        }

        private static PageLayout empty()
        {
            return new PageLayout(Collections.emptyList(), Collections.emptySet());
        }

        public List<String> getOrderedTileIds()
        {
            return Collections.unmodifiableList(normalizeOrder(orderedTileIds));
        }

        public Set<String> getHiddenTileIds()
        {
            return Collections.unmodifiableSet(normalizeHidden(hiddenTileIds));
        }

        private static List<String> normalizeOrder(List<String> source)
        {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (source != null)
            {
                for (String value : source)
                {
                    String id = normalizeId(value);
                    if (!id.isEmpty())
                    {
                        ids.add(id);
                    }
                }
            }
            return new ArrayList<>(ids);
        }

        private static Set<String> normalizeHidden(Set<String> source)
        {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (source != null)
            {
                for (String value : source)
                {
                    String id = normalizeId(value);
                    if (!id.isEmpty())
                    {
                        ids.add(id);
                    }
                }
            }
            return ids;
        }
    }
}
