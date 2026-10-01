package com.gpmanager;
import lombok.*;
import java.util.*;
@EqualsAndHashCode
class ContainerSnapshot {
final Map<Integer, Long> quantities;
ContainerSnapshot(Map<Integer, Long> quantities) {
this.quantities = Collections.unmodifiableMap(new HashMap<>(quantities));
}

static ContainerSnapshot empty() {
return new ContainerSnapshot(Collections.emptyMap());
}

Map<Integer, Long> diff(ContainerSnapshot previous) {
var result = new HashMap<Integer, Long>();
var itemIds = new HashSet<Integer>(quantities.keySet());
itemIds.addAll(previous.quantities.keySet());
for (Integer itemId : itemIds) {
long currentQuantity = quantities.getOrDefault(itemId, 0L);
long previousQuantity = previous.quantities.getOrDefault(itemId, 0L);
long delta = currentQuantity - previousQuantity;
if (delta != 0L) result.put(itemId, delta);
}
return result;
}

/** Quantity of one item id in this snapshot (0 when absent). */
long quantityOf(int itemId) {
return quantities.getOrDefault(itemId, 0L);
}
}
