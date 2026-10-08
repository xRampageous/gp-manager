package com.gpmanager;
import lombok.*;
import java.util.*;
@EqualsAndHashCode
class Cc {
Map<Integer, Long> quantities;
Cc(Map<Integer, Long> /* quantities */a) {
 this.quantities = Collections.unmodifiableMap(new HashMap<>(a));
}

static Cc empty() {
 return new Cc(Collections.emptyMap());
}

Map<Integer, Long> diff(Cc /* previous */a) {
 var /* result */b = new HashMap<Integer, Long>();
 var /* itemIds */c = new HashSet<Integer>(quantities.keySet());
 c.addAll(a.quantities.keySet());
 for (Integer /* itemId */d : c) {
  long /* currentQuantity */e = quantities.getOrDefault(d, 0L);
  long /* atf */f = a.quantities.getOrDefault(d, 0L);
  long /* delta */g = e - f;
  if (g != 0L) b.put(d, g);
 }
 return b;
}

/** Quantity of one item id in this snapshot (0 when absent). */
long aea(int /* itemId */a) {
 return quantities.getOrDefault(a, 0L);
}
}
