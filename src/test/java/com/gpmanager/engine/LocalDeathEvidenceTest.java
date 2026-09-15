package com.gpmanager.engine;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.runelite.api.SkullIcon;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class LocalDeathEvidenceTest
{
    @Test
    public void capturesNestedCanonicalKeptItemsAndFormatsOnlyExplanationEvidence()
    {
        Widget nested = widget(19553, 1);
        Widget duplicate = widget(19553, 1);
        Widget coins = widget(995, 250_000);
        Widget child = widget(-1, 0, new Widget[] {duplicate}, new Widget[] {nested});
        Widget root = widget(-1, 0, new Widget[] {child, coins}, null);

        LocalDeathEvidence evidence = LocalDeathEvidence.capture(
            root,
            true,
            SkullIcon.LOOT_KEYS_TWO,
            id -> id == 19_553 ? 19_553 : id,
            id -> id == 19_553 ? "Amulet of torture" : id == 995 ? "Coins" : null);

        String explanation = evidence.appendTo(
            "Ownership-neutral transfer: Death.",
            Arrays.asList(
                new com.gpmanager.model.ItemFlow(4151, "Abyssal whip", -1, 2_000_000, -2_000_000),
                new com.gpmanager.model.ItemFlow(385, "Shark", -2, 800, -1_600),
                new com.gpmanager.model.ItemFlow(526, "Bones", 1, 35, 35)));

        assertTrue(explanation, explanation.contains("Kept: "));
        assertTrue(explanation.contains("Amulet of torture ×2"));
        assertTrue(explanation.contains("Coins ×250,000"));
        assertTrue(explanation, explanation.contains("lost: Abyssal whip, Shark ×2"));
        assertTrue(explanation.contains("Protect Item on"));
        assertTrue(explanation.contains("skull: loot keys ×2"));
        assertFalse("positive gains are not described as losses", explanation.contains("Bones"));
    }

    @Test
    public void absentDeathKeepWidgetIsReportedAsUnavailable()
    {
        LocalDeathEvidence evidence = LocalDeathEvidence.capture(null, false, SkullIcon.NONE, null, null);
        String explanation = evidence.appendTo("Death loss.", null);
        assertTrue(explanation.contains("Kept: unavailable"));
        assertTrue(explanation.contains("lost: none observed"));
        assertTrue(explanation.contains("Protect Item off"));
        assertTrue(explanation.contains("skull: none"));
    }

    private static Widget widget(int itemId, int quantity)
    {
        return widget(itemId, quantity, null, null);
    }

    private static Widget widget(int itemId, int quantity, Widget[] children, Widget[] nestedChildren)
    {
        return proxy(Widget.class, (name, args) ->
        {
            if ("getItemId".equals(name)) return itemId;
            if ("getItemQuantity".equals(name)) return quantity;
            if ("getChildren".equals(name)) return children;
            if ("getNestedChildren".equals(name)) return nestedChildren;
            return null;
        });
    }

    private interface Answer
    {
        Object answer(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (object, method, args) ->
        {
            Object value = answer.answer(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive())
            {
                return value;
            }
            if (method.getReturnType() == boolean.class)
            {
                return false;
            }
            if (method.getReturnType() == long.class)
            {
                return 0L;
            }
            return 0;
        }));
    }
}
