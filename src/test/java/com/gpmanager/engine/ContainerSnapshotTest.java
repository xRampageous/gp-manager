package com.gpmanager.engine;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ContainerSnapshotTest
{
    @Test
    public void computesItemByItemDeltas()
    {
        Map<Integer, Long> beforeValues = new HashMap<>();
        beforeValues.put(995, 1_000L);
        beforeValues.put(385, 2L);

        Map<Integer, Long> afterValues = new HashMap<>();
        afterValues.put(995, 750L);
        afterValues.put(385, 1L);
        afterValues.put(561, 10L);

        Map<Integer, Long> diff =
            new ContainerSnapshot(afterValues).diff(new ContainerSnapshot(beforeValues));

        assertEquals(Long.valueOf(-250L), diff.get(995));
        assertEquals(Long.valueOf(-1L), diff.get(385));
        assertEquals(Long.valueOf(10L), diff.get(561));
        assertEquals(3, diff.size());
        assertEquals(new ContainerSnapshot(afterValues), new ContainerSnapshot(afterValues));
        assertNotEquals(new ContainerSnapshot(afterValues), new ContainerSnapshot(beforeValues));
    }
}
