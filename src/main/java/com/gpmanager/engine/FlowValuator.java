package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface FlowValuator
{
    List<ItemFlow> value(Map<Integer, Long> quantityDeltas);

    /**
     * Values flows using the caller's evidence-capture time. Existing valuators remain
     * source-compatible and may ignore the timestamp until they support price provenance.
     */
    default List<ItemFlow> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis)
    {
        return value(quantityDeltas);
    }
}
