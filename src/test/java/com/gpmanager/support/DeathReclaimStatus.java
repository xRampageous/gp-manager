package com.gpmanager;

class DeathReclaimStatus {
    boolean pending, armed;
    long items, age;

    DeathReclaimStatus(boolean awaiting, boolean armed, long outstandingCount, long ageTicks) {
        pending = awaiting;
        this.armed = awaiting && armed;
        items = SafeMath.positive(outstandingCount);
        age = SafeMath.positive(ageTicks);
    }
}
