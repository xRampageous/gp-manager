package com.gpmanager;

import java.util.*;
import com.google.gson.Gson;
import org.junit.*;
import static org.junit.Assert.*;

/** Captured historical sips are financial facts, not permission to rewrite later item basis. */
public class CapturedBlightedRecoveryTest {
    static final int[] IDS=BlightedDoseTest.RESTORE;
    static final long AT=1700000000000L;
    java.util.function.LongSupplier previous;
    @Before public void clock(){previous=SafeMath.clock;SafeMath.clock=()->AT;JsonCodec.bind(new Gson());}
    @After public void restore(){SafeMath.clock=previous;}
    static Engine engine(){var e=new Engine(BlightedDoseTest.service(IDS,"Blighted super restore",2720,""),
            new TransactionClassifier(),new GpManagerConfig(){});e.ensureSession(AT);return e;}
    static Transaction sip(int[] ids,String family,int dose,int unit,long at){
        var flows=new ArrayList<Flow>();flows.add(new Flow(ids[dose-1],family+"("+dose+")",-1,unit*dose,-unit*dose,PriceSource.GRAND_EXCHANGE,at));
        if(dose>1)flows.add(new Flow(ids[dose-2],family+"("+(dose-1)+")",1,unit*(dose-1),unit*(dose-1),PriceSource.GRAND_EXCHANGE,at));
        var row=new Transaction(at,dose>1?TransactionType.UNCERTAIN:TransactionType.CONSUMPTION,Context.GENERIC,"","PKing",dose==1,flows,
                dose>1?Confidence.UNCERTAIN:Confidence.LIKELY,dose>1?GameData.msg("consumed-dose"):"Consumed",null);
        row.setAction(ActionKind.DRINK);return row;
    }
    static String json(Object x){return new Gson().toJson(x);}
    static String basis(Engine e){var s=new SavedState();e.basis.writeTo(s);return json(s.trackedBasis());}
    static void locked(Engine e){for(int id:IDS){var p=e.basis.pool(id);p.availableQty=7;p.availableBasisGp=7000;p.acquiredAt=AT+50000;p.fencedAt=AT+60000;}}
    static Transaction add(Engine e,int dose){var row=sip(IDS,"Blighted super restore",dose,581,AT+dose);e.session().addTransaction(row,2000,false);return row;}

    @Test public void capturedSipsMergeWithAlreadyCountedDoseWithoutUsingTodaysPrice(){
        var e=engine();var pending=new ArrayList<Transaction>();for(int dose=4;dose>1;dose--)pending.add(add(e,dose));
        var finalDose=add(e,1);String booked=json(finalDose);locked(e);String stock=basis(e);
        for(var row:pending){String flows=json(row.flows());assertTrue(e.recoverDose(e.session(),row,AT+100000));assertEquals(flows,json(row.flows()));}
        assertEquals(booked,json(finalDose));assertEquals(stock,basis(e));assertEquals(-2324,e.metrics(AT+100000).net);
        assertTrue(e.getReviewRows(AT+100000).isEmpty());
        var groups=SemanticProbe.capture(e.session().transactions(),List.of(),"",null).groups;
        assertEquals(1,groups.size());assertEquals("Blighted super restore",groups.get(0).name);
        assertEquals(4,groups.get(0).quantity);assertEquals(-2324,groups.get(0).value);
    }
    @Test public void boundedQueueReloadAndRepeatCannotBookAgain(){
        var e=engine();for(int dose=4;dose>1;dose--)add(e,dose);add(e,1);locked(e);
        e.restore(SaveCopies.copy(e.savedState()),AT+100000);
        for(int left=2;left>=0;left--){e.retryDose(AT+100000);assertEquals(left,e.getReviewRows(AT+100000).size());}
        String once=json(e.savedState());e.retryDose(AT+200000);assertEquals(once,json(e.savedState()));
        var next=engine();next.restore(SaveCopies.copy(e.savedState()),AT+300000);
        assertTrue(next.doseRetries.isEmpty());assertEquals(-2324,next.metrics(AT+300000).net);
        assertEquals(basis(e),basis(next));
    }
    @Test public void financialRecoveryAllowsLaterCorrectionAndUndoWithoutChangingBasis(){
        var e=engine();var row=add(e,4);locked(e);String stock=basis(e);
        assertTrue(e.recoverDose(e.session(),row,AT+100000));assertTrue(e.correctTransaction(row.id(),Correction.IGNORE,"owner"));
        assertEquals(0,e.metrics(AT+100002).net);assertEquals(stock,basis(e));
        assertTrue(e.undoCorrection());assertEquals(-581,e.metrics(AT+100004).net);assertEquals(stock,basis(e));
    }
    @Test public void genuinelyReviewedAndUndoneDecisionsRemainUntouched(){
        for(boolean undone:new boolean[]{false,true}){
            var e=engine();var row=add(e,4);assertTrue(e.correctTransaction(row.id(),Correction.IGNORE,"owner"));
            if(undone)assertTrue(e.undoCorrection());locked(e);String before=json(row);
            assertFalse(e.recoverDose(e.session(),row,AT+100000));assertEquals(before,json(row));
        }
    }
    @Test public void capturedPricesMustProveAnExactDoseAndSupportedFamily(){
        for(int variant=0;variant<9;variant++){
            var e=engine();var row=add(e,4);locked(e);
            if(variant==0){row.flows.get(0).unitPrice++;row.flows.get(0).value--;}
            if(variant==1)row.flows.get(1).quantity=2;
            if(variant==2)row.flows.get(1).priceSource=PriceSource.HIGH_ALCHEMY;
            if(variant==3){for(var f:row.flows)f.name=f.name.replace("super restore","Saradomin brew");}
            if(variant==4)row.setSourceClaimId("claim");
            if(variant==5)row.flows.get(0).raid="raid";
            if(variant==6){row.flows.get(0).name="Blighted super restore(5)";row.flows.get(0).unitPrice=2905;row.flows.get(0).value=-2905;
                row.flows.get(1).name="Blighted super restore(4)";row.flows.get(1).unitPrice=2324;row.flows.get(1).value=2324;}
            if(variant==7)row.flows.get(0).item=1113;
            if(variant==8)row.flows.get(0).item=row.flows.get(1).item;
            String before=json(row);assertFalse("unsafe variant "+variant,e.recoverDose(e.session(),row,AT+100000));assertEquals(before,json(row));
        }
    }
    @Test public void restoredHistoricalSpendsNeverCreateTrackedItemCredit(){
        var e=engine();var row=add(e,4);locked(e);e.basis.epoch=AT+10000;String stock=basis(e);
        assertTrue(e.recoverDose(e.session(),row,AT+100000));
        var s=SaveCopies.copy(e.savedState());var next=engine();next.restore(s,AT+200000);
        assertEquals(stock,basis(next));assertTrue(next.correctTransaction(row.id(),Correction.REVENUE,"owner"));
        assertEquals(stock,basis(next));assertTrue(next.undoCorrection());assertEquals(stock,basis(next));
    }
    @Test public void differentPotionFamiliesRemainSeparate(){
        var e=engine();var restore=add(e,4);locked(e);assertTrue(e.recoverDose(e.session(),restore,AT+100000));
        var brew=sip(new int[]{6691,6689,6687,6685},"Saradomin brew",1,1000,AT+9);e.session().addTransaction(brew,2000,false);
        var groups=SemanticProbe.capture(e.session().transactions(),List.of(),"",null).groups;
        assertEquals(2,groups.size());assertEquals(Set.of("Blighted super restore","Saradomin brew"),
                groups.stream().map(g->g.name).collect(java.util.stream.Collectors.toSet()));
    }
    @Test public void savedProvenanceMarkerIsStable(){
        assertEquals("Recovered from original blighted dose prices; later tracked item values are unchanged.",GameData.msg("captured-dose"));
    }
    static Engine previewEngine(){
        var e=engine();var pending=new ArrayList<Transaction>();for(int dose=4;dose>1;dose--)pending.add(add(e,dose));add(e,1);
        var fresh=BlightedDoseTest.drinks(BlightedDoseTest.service(IDS,"Blighted super restore",2720,""),IDS);
        int index=0;for(var row:fresh){row.timestamp=AT+100+index++;e.session().addTransaction(row,2000,false);}
        var extra=SaveCopies.copy(fresh.get(0));extra.id=Phrases.uuid();extra.timestamp=AT+110;e.session().addTransaction(extra,2000,false);
        locked(e);for(var row:pending)assertTrue(e.recoverDose(e.session(),row,AT+100000));return e;
    }
    static void previews(java.io.File out)throws java.io.IOException{
        var previous=SafeMath.clock;SafeMath.clock=()->AT+100000;
        try{for(int width:new int[]{213,225,300}){
            var e=previewEngine();var panel=new SidebarPanel(e,new GpManagerConfig(){},null,null,null);
            var frame=new javax.swing.JFrame("Offline captured-dose preview");
            frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);
            try{
                frame.add(panel);frame.pack();frame.setSize(width,540);frame.setVisible(true);SidebarPanelProbe.refresh(panel);frame.validate();
                var row=panel.snapshot.recent.get(0);assertEquals("Blighted super restore",row.name);assertEquals(Fmt.times(9)+" doses",row.qty.trim());
                assertEquals(-5724,row.value);assertEquals(1,panel.snapshot.recent.size());
                var image=new java.awt.image.BufferedImage(width,540,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();panel.printAll(g);g.dispose();
                javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"captured-blighted-"+width+".png"));
            }finally{frame.dispose();}
        }}finally{SafeMath.clock=previous;}
    }
}
