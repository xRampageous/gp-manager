package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Test support: reads kit tables back as text, every page, without widening the plugin's API. */
public final class TableRows
{
    private TableRows()
    {
    }

    /** Rows of every table under {@code root} whose title starts with {@code title}, as "name|qty|value|exact|tip". */
    public static List<String> of(Component root, String title)
    {
        List<String> texts = new ArrayList<>();
        for (Table table : tables(root, title))
        {
            for (Table.Row row : rows(table))
            {
                texts.add(row.name + "|" + row.qty + "|" + row.value + "|" + row.exact + "|" + row.tip);
            }
        }
        return texts;
    }

    /** Row keys of matching tables, every page. */
    public static List<String> keys(Component root, String title)
    {
        List<String> keys = new ArrayList<>();
        for (Table table : tables(root, title))
        {
            for (Table.Row row : rows(table))
            {
                keys.add(row.key);
            }
        }
        return keys;
    }

    /** Runs the open action of the first matching row whose key is {@code key}. */
    public static boolean open(Component root, String title, String key)
    {
        for (Table table : tables(root, title))
        {
            for (Table.Row row : rows(table))
            {
                if (row.key.equals(key) && row.open != null)
                {
                    row.open.run();
                    return true;
                }
            }
        }
        return false;
    }

    /** Row components actually built and visible in every table under {@code root}. */
    public static int visibleRows(Component root)
    {
        int count = 0;
        for (Table table : tables(root, ""))
        {
            if (table.body.isVisible())
            {
                for (Component child : table.body.getComponents())
                {
                    count += child instanceof javax.swing.JLabel ? 0 : 1;
                }
            }
        }
        return count;
    }

    public static List<Table> tables(Component root, String title)
    {
        List<Table> found = new ArrayList<>();
        collect(root, title, found);
        return found;
    }

    public static String title(Table table)
    {
        return ((javax.swing.JLabel) field(table, "title")).getText();
    }

    @SuppressWarnings("unchecked")
    private static List<Table.Row> rows(Table table)
    {
        return (List<Table.Row>) field(table, "rows");
    }

    private static void collect(Component component, String title, List<Table> found)
    {
        if (component instanceof Table && title((Table) component).startsWith(title))
        {
            found.add((Table) component);
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                collect(child, title, found);
            }
        }
    }

    private static Object field(Table table, String name)
    {
        try
        {
            Field field = Table.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(table);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }
    /** Names of the rows on the page shown now. */
    public static List<String> names(Table table) {
        @SuppressWarnings("unchecked")
        List<Table.Row> drawn = (List<Table.Row>) field(table, "drawn");
        List<String> names = new ArrayList<>();
        for (Table.Row row : drawn == null ? java.util.Collections.<Table.Row>emptyList() : drawn) {
            names.add(row.name);
        }
        return names;
    }

    public static String pageText(Table table) {
        return (page(table) + 1) + "/" + table.pages();
    }

    public static int page(Table table) { return (Integer) field(table, "page"); }

}
