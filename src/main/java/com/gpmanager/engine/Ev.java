package com.gpmanager;
/** One owner-scoped observation. Quantity is spent once; until is an inclusive inbox tick. */
class Ev {
 int kind, item, x, y, plane = -1, until;
 long owner, qty;
 /** Source tick/time and inventory quantity at the observation. */
 long at, held;
 /** Receipt or physical target identity, independent of the human source label. */
 String id;
 String note;
 /** Typed source snapshot (kit contents); never persisted. */
 Object data;
 /** Consume command metadata: Destroy, verb, trusted spell label. */
 boolean flag;
 Au action;
 Bb label;
 /** Loot source context and activity; id is its encounter. */
 Ar.V v;
 Aj context;
 String name;
 Ev src() { return data instanceof Ev ? ((Ev) data).src() : this; }
}
