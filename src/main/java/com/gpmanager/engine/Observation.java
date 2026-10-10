package com.gpmanager;

/** One owner-scoped observation. Quantity is spent once; until is an inclusive inbox tick. */
class Observation {
    int kind, item, x, y, until;
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

    ActionKind action;
    ActionLabel label;

    /** Loot source context and activity; id is its encounter. */
    ChargeCheck.ChargedItem variant;

    Context context;
    String name;

    Observation source() {
        return data instanceof Observation ? ((Observation) data).source() : this;
    }
}
