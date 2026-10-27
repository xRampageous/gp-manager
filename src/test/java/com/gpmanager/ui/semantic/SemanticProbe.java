package com.gpmanager;

import java.util.*;
import java.util.function.Predicate;

/** Test convenience: production captures now pass their session-id set explicitly. */
final class SemanticProbe {
    static Semantics.Result capture(List<Transaction> transactions,List<MarketRows.Row> rows,String id,Predicate<Flow> visible) {
        return Semantics.capture(transactions,rows,Phrases.empty(id)?Set.of():Set.of(id),visible);
    }
    static Semantics.Result capture(List<Transaction> transactions,List<MarketRows.Row> rows,Set<String> ids,Predicate<Flow> visible) {
        return Semantics.capture(transactions,rows,ids,visible);
    }
}
