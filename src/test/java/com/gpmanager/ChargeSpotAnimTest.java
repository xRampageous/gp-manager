package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.ActorSpotAnim;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * 1.1: casts are read from the player's spot anims, not the deprecated single graphic. An anim
 * plays on for several ticks and others join it, so only one not playing before is a new cast.
 */
public class ChargeSpotAnimTest
{
    private final List<Integer> casts = new ArrayList<>();
    private final ChargeIntake intake = new ChargeIntake(null, null, null, null, null)
    {
        @Override
        void ach(int graphicId, long now)
        {
            casts.add(graphicId);
        }
    };

    private static ActorSpotAnim anim(int id, int startCycle)
    {
        return (ActorSpotAnim) Proxy.newProxyInstance(ActorSpotAnim.class.getClassLoader(),
            new Class<?>[] {ActorSpotAnim.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getId": return id;
                    case "getStartCycle": return startCycle;
                    default:
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
    }

    @Test
    public void aCastStillPlayingIsNotBookedAgainWhenAnotherAnimJoins()
    {
        intake.spotAnims(Arrays.asList(anim(1251, 100)), 0L);
        intake.spotAnims(Arrays.asList(anim(1251, 100), anim(85, 130)), 0L);
        assertEquals(Arrays.asList(1251, 85), casts);
    }

    @Test
    public void theSameGraphicCastAgainIsANewAnim()
    {
        intake.spotAnims(Arrays.asList(anim(1251, 100)), 0L);
        intake.spotAnims(Arrays.asList(anim(1251, 160)), 0L);
        intake.spotAnims(Arrays.asList(), 0L);
        intake.spotAnims(Arrays.asList(anim(1251, 220)), 0L);
        assertEquals(Arrays.asList(1251, 1251, 1251), casts);
    }

    @Test
    public void twoNewAnimsOnOneChangeAreEachOffered()
    {
        // ach itself keeps one booking per tick and graphic, and ignores graphics no weapon uses.
        intake.spotAnims(Arrays.asList(anim(1251, 100), anim(1252, 100)), 0L);
        assertEquals(Arrays.asList(1251, 1252), casts);
    }
}
