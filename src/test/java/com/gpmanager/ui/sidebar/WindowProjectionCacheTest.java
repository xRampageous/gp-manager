package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import java.util.function.LongSupplier;
import org.junit.*;
import static org.junit.Assert.*;

/** Reuse frozen financial facts while time, visibility and ownership remain independently live. */
public class WindowProjectionCacheTest {
    static final long START=60_000, NOW=120_000;
    LongSupplier clock;
    @Before public void setUp(){clock=SafeMath.clock;SafeMath.clock=()->NOW;JsonCodec.bind(new Gson());}
    @After public void tearDown(){SafeMath.clock=clock;}

    static class CountedSession extends Session {
        int windowFolds;
        CountedSession(){super(DURABLE_OWNER_NAME,0,SessionMode.GENERAL);}
        @Override Metrics metrics(List<Transaction> rows,long elapsed){windowFolds++;return super.metrics(rows,elapsed);}
    }
    static Engine engine(){
        var e=new Engine(d->List.of(),new TransactionClassifier(),new GpManagerConfig(){});
        var run=new CountedSession();e.general=e.current=run;e.attachListeners(run);return e;
    }
    static Transaction gain(Engine e,long at,int id,long amount){
        var row=new Transaction(at,TransactionType.GAIN,Context.GENERIC,"","Test",true,
            List.of(new Flow(id,"Item "+id,1,(int)amount,amount,PriceSource.GRAND_EXCHANGE)),Confidence.CONFIRMED,"",null);
        e.session().addTransaction(row,10000,false);return row;
    }
    static Engine window(){var e=engine();gain(e,5,2000,1000);e.newLive(START);gain(e,START+5,2001,500);return e;}
    static LiveSnapshot live(Engine e,long at){return LiveSnapshot.capture(e,at,null);}
    static CountedSession run(Engine e){return (CountedSession)e.session();}

    @Test public void unchangedTicksReuseMoneyWhileTheClockAndRateAdvance(){
        var e=window();var first=live(e,NOW);assertEquals(500,first.net);assertEquals(30_000,first.rate);
        for(int i=1;i<=100;i++)live(e,NOW+i*600);
        var later=live(e,NOW+60_000);assertEquals(120_000,later.elapsed);assertEquals(15_000,later.rate);
        assertEquals("one receipt-window fold for unchanged money",1,run(e).windowFolds);
        assertEquals(1500,e.metrics(NOW).net);
    }
    @Test public void freshRecentDoesNotBuildTheWholeRunsSemanticProjection(){
        var e=window();live(e,NOW);
        assertTrue("only the requested window needs projecting",e.summary().scopes.isEmpty());
        var all=LedgerData.capture(e,NOW,LedgerData.Entry.current());assertEquals(1500,all.net);
        assertFalse("Ledger still builds its whole-run scope",e.summary().scopes.isEmpty());
    }
    @Test public void visibilityChangesReuseMoneyAndKeepTheirOwnRecentResult(){
        var e=window();var hidden=new boolean[]{false};
        var f=new RecentFilter(){
            public boolean isFlowIncluded(Flow flow){return !hidden[0];}
            public String version(){return Boolean.toString(hidden[0]);}
        };
        var ctx=new LiveContext(false,f,0,PvpState.NONE,"",false,"",false);
        assertEquals(1,LiveSnapshot.capture(e,NOW,ctx).recent.size());hidden[0]=true;
        var after=LiveSnapshot.capture(e,NOW+600,ctx);assertTrue(after.recent.isEmpty());assertEquals(500,after.net);
        assertEquals(1,run(e).windowFolds);
    }
    @Test public void aNewBookingAndCorrectionsInvalidateTheWindowTogether(){
        var e=window();live(e,NOW);var added=gain(e,NOW+5,2002,200);
        var next=live(e,NOW+600);assertEquals(700,next.net);assertEquals(2,next.recent.size());
        assertTrue(e.correctTransaction(added.id(),Correction.IGNORE,"Test"));
        var corrected=live(e,NOW+800);assertEquals(500,corrected.net);
        assertEquals(3,run(e).windowFolds);
        assertEquals(500,LedgerData.capture(e,NOW+800,LedgerData.Entry.current().live(Semantics.Table.GAINS)).net);
    }
    @Test public void aChangedBoundaryIsDetectedEvenWithoutARevisionChange(){
        var e=window();gain(e,START+10,2002,200);live(e,NOW);long revision=e.revision();
        run(e).sessionStartAt=START+8;run(e).sessionStartMillis=START+8;
        var next=live(e,NOW+600);assertEquals(200,next.net);assertEquals(1,next.recent.size());
        assertEquals(revision,e.revision());assertEquals(2,run(e).windowFolds);
    }
    @Test public void restartPauseAndRestoreOnlyChangeTheLiveClock(){
        var e=window();live(e,NOW);e.restartRate(NOW, true);var reset=live(e,NOW);
        assertEquals(0,reset.elapsed);assertEquals(500,reset.net);
        e.togglePause(NOW+10_000);assertEquals(10_000,live(e,NOW+20_000).elapsed);
        e.restoreRate(NOW+20_000);assertEquals(60_000,live(e,NOW+30_000).elapsed);
        assertEquals(500,live(e,NOW+30_000).net);assertEquals(1500,e.metrics(NOW+30_000).net);
    }
    @Test public void aNewRunAndOwnerCannotReuseThePreviousWindow(){
        var e=window();live(e,NOW);e.startCustom("Vorkath",NOW);gain(e,NOW+5,2003,300);
        e.newLive(NOW+10);gain(e,NOW+15,2004,400);
        var next=live(e,NOW+60_000);assertEquals(400,next.net);assertEquals(1,next.recent.size());
        e.restoreProfile("another",new SavedState(),NOW+70_000);e.ensureSession(NOW+70_000);
        assertEquals(0,live(e,NOW+80_000).net);assertTrue(live(e,NOW+80_000).recent.isEmpty());
    }
    @Test public void wholeRunModeRetainsItsOriginalAccountingAndGrouping(){
        var e=window();live(e,NOW);e.showWholeRun();var whole=live(e,NOW+600);
        assertFalse(whole.fresh);assertEquals(1500,whole.net);assertEquals(2,whole.recent.size());
        assertEquals(NOW+600,whole.elapsed);
    }
    @Test public void oldOpenOffersStayFirstWhileOldSalesCancelledAndForeignRowsStayOut(){
        var markets=new ArrayList<MarketRows.Row>();
        var e=new Engine(d->List.of(),new TransactionClassifier(),new GpManagerConfig(){}){
            @Override synchronized List<MarketRows.Row> marketRows(){return markets;}
        };
        e.ensureSession(0);var old=gain(e,5,2000,1000);e.newLive(START);var current=gain(e,START+5,2001,500);
        String[] names={"Pending old","Partial old","Old sale","Fresh sale","Cancelled","Foreign"};
        MarketRows.Lifecycle[] states={MarketRows.Lifecycle.PENDING,MarketRows.Lifecycle.PARTIALLY_REALIZED,
            MarketRows.Lifecycle.REALIZED,MarketRows.Lifecycle.REALIZED,MarketRows.Lifecycle.CANCELLED_RETURNED,
            MarketRows.Lifecycle.PENDING};
        for(int i=0;i<names.length;i++){
            var row=LiveCategoryLayoutTest.marketRow(e);row.presentationId=names[i];row.itemName=names[i];
            row.timestampEpochMillis=i+10;row.lifecycle=states[i];
            row.settlementId=i==3||i==4?current.id():i==0||i==5?"":old.id();
            row.earlierSettlements="";if(i==5)row.scopeSessionId="other-owner";
            markets.add(row);
        }
        var snapshot=live(e,NOW);var namesSeen=new ArrayList<String>();
        for(var row:snapshot.recent)namesSeen.add(row.name);
        assertEquals(List.of("Partial old","Pending old","Fresh sale"),namesSeen);
        assertTrue(snapshot.recent.get(0).open);assertTrue(snapshot.recent.get(1).open);assertFalse(snapshot.recent.get(2).open);
        assertEquals(500,snapshot.net);assertEquals(1500,snapshot.run);
    }
}
