package com.gpmanager;

import static com.gpmanager.SafeMath.*;

import static java.lang.Math.*;

import java.util.*;

/**
 * One Grind's PvP record: kills, deaths, streaks and the best kill. A kill's money is the net of
 * the receipts carrying its id (loot less the supplies attached to it). While those receipts are
 * live the best kill is read from them, so a correction or undo shows at once.
 */
class PkTally {
    static final String KILL = "kill:";
    static final String DEATH = "death:";

    int kills;
    int deaths;

    /** Kills since the last death, or minus the deaths since the last kill. */
    int streak;

    int bestStreak;

    /** Best net of a kill whose receipts have all been compacted. */
    long bestKill;

    /** Net already compacted for a kill that still has live receipts. */
    Map<String, Long> compactedKillNet = new HashMap<>();

    /** Counts one kill or death and returns the id its receipts carry. */
    String record(boolean kill) {
        if (kill) {
            kills++;
            streak = max(streak, 0) + 1;
            bestStreak = max(bestStreak, streak);
        } else {
            deaths++;
            streak = min(streak, 0) - 1;
        }
        return (kill ? KILL : DEATH) + UUID.randomUUID();
    }

    int encounters() {
        return kills + deaths;
    }

    static boolean isEncounter(String encounterId) {
        return encounterId.startsWith(KILL) || encounterId.startsWith(DEATH);
    }

    /**
     * A kill receipt left the ledger: keep its net, and fold the kill into the best once none of
     * its receipts remain live.
     */
    void compact(Transaction receipt, List<Transaction> live) {
        String id = receipt.getEncounterId();
        if (!id.startsWith(KILL)) return;
        long net = compactedKillNet.merge(id, net(receipt), SafeMath::safeAdd);
        for (Transaction transaction : live) {
            if (id.equals(transaction.getEncounterId())) return;
        }
        compactedKillNet.remove(id);
        bestKill = max(bestKill, net);
    }

    long bestKill(List<Transaction> live) {
        var nets = new HashMap<>(compactedKillNet);
        for (Transaction transaction : live) {
            String id = transaction.getEncounterId();
            if (id.startsWith(KILL)) nets.merge(id, net(transaction), SafeMath::safeAdd);
        }
        long best = bestKill;
        for (long net : nets.values()) best = max(best, net);
        return nonNeg(best);
    }

    static long net(Transaction transaction) {
        Accounting.Amounts amounts = Accounting.transaction(transaction);
        return amounts.available && amounts.included ? amounts.getNet() : 0L;
    }
}
