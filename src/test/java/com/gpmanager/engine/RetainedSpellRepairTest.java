package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.TransactionType.*;

public class RetainedSpellRepairTest {
    static List<Flow> runes() {
        return new ArrayList<>(List.of(new Flow(562,"Chaos rune",-4,105,-420),
                new Flow(560,"Death rune",-2,191,-382), new Flow(555,"Water rune",-4,4,-16)));
    }
    static Transaction row(List<Flow> flows) {
        return new Transaction(20L, CONSUMPTION, Context.GENERIC,"","Magic",true,flows,
                Confidence.LIKELY,"Stable inventory/equipment value decreased without a stronger context.",null);
    }
    @Test public void aPotionCastSeparatesWithoutRepricingOrChangingNetAndIsIdempotent() {
        Engine engine = TargetFreshStartTest.engine();
        Session session = engine.getActiveSession();
        List<Flow> flows = runes();
        flows.add(new Flow(2434,"Prayer potion(4)",-1,9612,-9612));
        flows.add(new Flow(139,"Prayer potion(3)",1,6962,6962));
        Transaction old = row(flows); old.setActionKind(ActionKind.SUPPLIES);
        session.addTransaction(old,2000,false);
        String id = old.id;
        long net = session.metrics(30L).net;
        engine.recheck(i -> 0);
        assertEquals(2,session.getTransactions().size());
        Transaction spell = session.getTransactions().stream().filter(t -> t.getActionKind()==ActionKind.CAST).findFirst().orElseThrow();
        assertEquals("Ice Burst",spell.spellName());
        assertEquals(3,spell.flows.size());
        assertSame(flows.get(0),spell.flows.get(0));
        assertEquals(id,old.id);
        assertEquals(2,old.flows.size());
        assertEquals(net,session.metrics(30L).net);
        assertEquals(20L,spell.timestampEpochMillis);
        long revision = engine.getRevision();
        engine.recheck(i -> 0);
        assertEquals(revision,engine.getRevision());
        assertEquals(2,session.getTransactions().size());
    }

    @Test public void anUnlabelledFullRecipeLeavesLossAndJoinsTheSpell() {
        Engine engine = TargetFreshStartTest.engine();
        Transaction old = row(runes());
        engine.getActiveSession().addTransaction(old,2000,false);
        assertEquals(CostKind.LOSS,CostKind.of(old,old.flows.get(0)));
        engine.recheck(i -> 0);
        assertEquals(ActionKind.CAST,old.getActionKind());
        assertEquals("Ice Burst",old.spellName());
        assertEquals(CostKind.SUPPLIES,CostKind.of(old,old.flows.get(0)));
    }

    @Test public void ambiguousNettedRunesTransfersDeathsMarketsAndDecisionsStayUntouched() {
        Engine engine = TargetFreshStartTest.engine();
        Session session = engine.getActiveSession();
        List<Transaction> rows = new ArrayList<>();
        Transaction partial = row(List.of(new Flow(562,"Chaos rune",-8,105,-840),new Flow(555,"Water rune",-8,4,-32)));
        rows.add(partial);
        for (TransactionType type : List.of(TRANSFER,TRADE,PK_DEATH_LOSS,UNCERTAIN)) {
            Transaction r=row(runes()); r.type=type;rows.add(r);
        }
        Transaction dropped = row(runes()); dropped.note="Dropped";rows.add(dropped);
        Transaction corrected = row(runes()); corrected.correction=Correction.IGNORE;rows.add(corrected);
        Transaction decided = row(runes()); rows.add(decided);
        for(Transaction r:rows) session.addTransaction(r,2000,false);
        session.ownedValueObserver = null;
        assertTrue(session.correctTransaction(decided.id,Correction.IGNORE,30L,"owner"));
        assertTrue(session.undoCorrection(40L));
        engine.recheck(i -> 0);
        for(Transaction r:rows) assertNull(r.getType()+"/"+r.getNote()+"/"+r.spellName(),r.getActionKind());
        assertEquals(rows.size(),session.getTransactions().size());
    }

    @Test public void damagedCorrectionHistoryCannotBeAssumedToContainNoDecisions() {
        for (int broken = 0; broken < 3; broken++) {
            Engine engine = TargetFreshStartTest.engine();
            Session session = engine.getActiveSession();
            Transaction old = row(runes()); session.addTransaction(old, 2000, false);
            if (broken == 0) session.correctionHistory = null;
            else if (broken == 1) session.correctionHistory.add(null);
            else session.correctionHistory.add(new CorrectionRecord("owner", Arrays.asList((CorrectionRecord.Change)null)));
            engine.recheck(i -> 0);
            assertNull(old.getActionKind());
        }
    }
}
