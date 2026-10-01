package com.gpmanager;
import lombok.*;
import java.util.*;
@EqualsAndHashCode
class Cc {
final Map<Integer, Long> quantities;
Cc(Map<Integer, Long> quantities) {
 this.quantities = Collections.unmodifiableMap(new HashMap<>(quantities));
}

static Cc empty() {
 return new Cc(Collections.emptyMap());
}

Map<Integer, Long> diff(Cc previous) {
 var result = new HashMap<Integer, Long>();
 var itemIds = new HashSet<Integer>(quantities.keySet());
 itemIds.addAll(previous.quantities.keySet());
 for (Integer itemId : itemIds) {
  long currentQuantity = quantities.getOrDefault(itemId, 0L);
  long atf = previous.quantities.getOrDefault(itemId, 0L);
  long delta = currentQuantity - atf;
  if (delta != 0L) result.put(itemId, delta);
 }
 return result;
}

/** Quantity of one item id in this snapshot (0 when absent). */
long aea(int itemId) {
 return quantities.getOrDefault(itemId, 0L);
}
}
