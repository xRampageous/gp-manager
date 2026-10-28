package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.LongSupplier;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.MilestoneTest.onEdt;

/** Live category navigation and the bounded, unpaged Recent list preserve canonical bookings. */
public class LiveCategoryLayoutTest {
    static final long NOW=120_000;
    LongSupplier previous;
    @Before public void clock(){previous=SafeMath.clock;SafeMath.clock=()->NOW;}
    @After public void restore(){SafeMath.clock=previous;}
    static Engine engine(){
        var engine=new Engine(deltas->List.of(),new TransactionClassifier(),new GpManagerConfig(){});
        engine.ensureSession(0);return engine;
    }
    static void gain(Engine engine,int index,long at){
        engine.session().addTransaction(new Transaction(at,TransactionType.GAIN,Context.GENERIC,"","Test",true,
                List.of(new Flow(2000+index,"Item "+index,1,100,100,PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED,"",null),2000,false);
    }
    static Field field(Class<?> type,String name){
        try{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(ReflectiveOperationException ex){throw new AssertionError("Missing proposed presentation state: "+name,ex);}
    }
    static LedgerData.Entry category(Semantics.Table table){
        var entry=LedgerData.Entry.current();
        try{field(LedgerData.Entry.class,"table").set(entry,table);return entry;}
        catch(IllegalAccessException ex){throw new AssertionError(ex);}
    }
    static LedgerPage focused(SidebarPanel panel){
        try{return (LedgerPage)field(SidebarPanel.class,"categoryPage").get(panel);}
        catch(IllegalAccessException ex){throw new AssertionError(ex);}
    }
    static void recent(LedgerPage page){
        try{LedgerPage.Actions.class.getMethod("recent").invoke(page.actions);}
        catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    static void open(SidebarPanel panel,String label){
        panel.live.strips(panel.snapshot).stream().flatMap(Collection::stream).filter(cell->cell.label.equals(label.equals("DEATH")?"DEATHS":label)).findFirst().orElseThrow().open.run();
        SidebarPanelProbe.refresh(panel);
    }
    static final class Limit implements RecentFilter {
        int rows=15;
        public boolean isFlowIncluded(Flow flow){return true;}
        public int recentRows(){return rows;}
    }
    static LiveSnapshot snapshot(Engine engine,Limit limit){
        return LiveSnapshot.capture(engine,NOW,new LiveContext(false,limit,0,PvpState.NONE,"",false,"",false));
    }
    @Test public void recentDefaultsToFifteen(){
        var e=engine();for(int i=0;i<40;i++)gain(e,i,1000+i);
        assertEquals(15,LiveSnapshot.capture(e,NOW,null).recent.size());
    }
    @Test public void changingOnlyTheLimitRefreshesCachedRecentAndPreservesMoney(){
        var e=engine();for(int i=0;i<40;i++)gain(e,i,1000+i);
        var filter=new Limit();assertEquals(15,snapshot(e,filter).recent.size());
        long revision=e.revision();String before=new Gson().toJson(e.exportState());
        filter.rows=30;assertEquals(30,snapshot(e,filter).recent.size());
        assertEquals(revision,e.revision());assertEquals(before,new Gson().toJson(e.exportState()));
        assertEquals(40,LedgerData.capture(e,NOW,LedgerData.Entry.current()).income.groups.size());
    }
    @Test public void recentLimitsClampAtOneAndThirty(){
        var e=engine();for(int i=0;i<40;i++)gain(e,i,1000+i);
        var filter=new Limit();filter.rows=31;assertEquals(30,snapshot(e,filter).recent.size());
        filter.rows=0;assertEquals(1,snapshot(e,filter).recent.size());
    }
    @Test public void theNewSettingUsesADistinctKeyAndBoundedDefault()throws Exception{
        java.lang.reflect.Method method;
        try{method=GpManagerConfig.class.getMethod("recentRows");}
        catch(NoSuchMethodException ex){throw new AssertionError("Recent rows setting is missing",ex);}
        assertEquals(15,method.invoke(new GpManagerConfig(){}));
        assertEquals("recentRows",method.getAnnotation(ConfigItem.class).keyName());
        assertEquals(1,method.getAnnotation(Range.class).min());assertEquals(30,method.getAnnotation(Range.class).max());
    }
    @Test public void plainRecentRetainsItsCountAndTotalWithoutPaging()throws Exception{
        onEdt(()->{
            var e=engine();for(int i=0;i<40;i++)gain(e,i,1000+i);
            var filter=new Limit();filter.rows=30;var s=snapshot(e,filter);
            var page=MilestoneTest.page();page.apply(s);
            assertEquals(30,page.recent.body.getComponentCount());
            assertEquals("RECENT · 30",page.recent.title.getText());
            assertEquals(Fmt.signed(3000),page.recent.total.getText());
            assertTrue(page.recent.foldable);assertNull(page.recent.action);
            assertEquals("only the total remains in the right header",1,page.recent.right.getComponentCount());
            page.recent.setFolded(true);assertFalse(page.recent.body.isVisible());
            assertEquals(Fmt.signed(3000),page.recent.total.getText());
            page.recent.setFolded(false);assertTrue(page.recent.body.isVisible());
            return null;
        });
    }
    @Test public void liveWindowQueryKeepsWholeRunLedgerUnchanged(){
        var e=SavedSessionTest.window();var whole=LedgerData.capture(e,NOW,LedgerData.Entry.current());
        var live=LedgerData.capture(e,NOW,category(Semantics.Table.GAINS));
        assertEquals(1500,whole.income.total);assertEquals(500,live.income.total);assertEquals(500,live.net);
        assertEquals("Live session",live.scopeName);
    }
    @Test public void searchAndSelectionKeepTheLiveWindowButScopeChangeReturnsToWholeRun()throws Exception{
        var e=SavedSessionTest.window();var query=category(Semantics.Table.GAINS).withSearch("Coins").withSelection(null,null,null);
        assertEquals(500,LedgerData.capture(e,NOW,query).income.total);
        assertEquals(1500,LedgerData.capture(e,NOW,query.withScope(LedgerData.Scope.CURRENT_GRIND,null,null)).income.total);
    }
    @Test public void rateOnlyResetDoesNotChangeCategoryReceiptWindow(){
        var e=SavedSessionTest.window();var run=e.session();run.rateStartAt=NOW;run.rateSetMillis=NOW;
        assertEquals(500,LedgerData.capture(e,NOW,category(Semantics.Table.GAINS)).income.total);
    }
    @Test public void supplyIncludesChargesAndAllExistingSupplyKinds(){
        var e=engine();for(var transaction:RecentSupplyAndPickupTest.supplies())e.session().addTransaction(transaction,2000,false);
        var charge=new Transaction(100,TransactionType.CONSUMPTION,Context.GENERIC,"Measured charge spend · Toxic blowpipe","Test",true,
                List.of(new Flow(12934,"Zulrah's scales",-1,110,-110,PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED,"Measured Check difference for Toxic blowpipe.",null);
        charge.setAction(ActionKind.FIRE);e.session().addTransaction(charge,2000,false);
        var data=LedgerData.capture(e,NOW,category(Semantics.Table.COSTS_SUPPLIES));
        assertTrue(data.cost.groups.stream().anyMatch(group->group.charge));
        assertEquals(-LiveSnapshot.capture(e,NOW,null).supplies,data.cost.total);
        assertEquals(6,data.cost.groups.size());
    }
    @Test public void focusedTableStaysInsideLiveAndPagesFifteenRows()throws Exception{
        onEdt(()->{
            var e=engine();for(int i=0;i<40;i++)gain(e,i,1000+i);
            var panel=SavedSessionTest.panel(e);String before=new Gson().toJson(e.exportState());
            open(panel,"GAINS");assertEquals(Shell.LIVE,panel.shell.current);
            var page=focused(panel);assertEquals(40,page.income.rows.size());assertEquals(15,page.income.body.getComponentCount());
            assertFalse(((javax.swing.JButton)field(LedgerPage.class,"more").get(page)).isVisible());
            assertEquals(3,page.income.pages());page.income.showPage(2);assertEquals(10,page.income.body.getComponentCount());
            assertFalse(panel.live.recent.isVisible());assertEquals(Fmt.signed(panel.snapshot.income),page.income.total.getText());
            assertEquals(before,new Gson().toJson(e.exportState()));
            recent(page);SidebarPanelProbe.refresh(panel);assertTrue(panel.live.recent.isVisible());
            assertFalse(((javax.swing.JComponent)field(LivePage.class,"breakdown").get(panel.live)).isVisible());return null;
        });
    }
    @Test public void ledgerTabRetainsIndependentWholeRunQuery()throws Exception{
        onEdt(()->{
            var panel=SavedSessionTest.panel(SavedSessionTest.window());open(panel,"GAINS");
            assertEquals(500,focused(panel).data.net);
            panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);
            assertEquals(1500,panel.ledger.data.net);return null;
        });
    }
    @Test public void receiptsRemainInTheFocusedTableAndBackReturnsToItsCategory()throws Exception{
        onEdt(()->{
            var panel=SavedSessionTest.panel(SavedSessionTest.window());open(panel,"GAINS");
            var page=focused(panel);page.income.rows.get(0).open.run();SidebarPanelProbe.refresh(panel);
            assertNotNull(page.data.detail);assertEquals(500,page.data.detail.group.value);
            page.actions.selectionChanged(null,null,null);SidebarPanelProbe.refresh(panel);
            assertNull(page.data.detail);assertEquals(500,page.data.income.total);return null;
        });
    }
    @Test public void closingTheFocusedTableMakesItsOldCorrectionCallbackRefuse()throws Exception{
        onEdt(()->{
            var e=SavedSessionTest.window();var panel=SavedSessionTest.panel(e);open(panel,"GAINS");
            var page=focused(panel);String id=e.session().transactions().get(1).id();long revision=e.revision();
            recent(page);assertEquals(LedgerPage.CorrectionOutcome.REFUSED,page.actions.correct(id,Correction.IGNORE,revision));
            assertEquals(1500,e.metrics(NOW).net);return null;
        });
    }
    static MarketRows.Row marketRow(Engine engine){
        var row=new Gson().fromJson("{}",MarketRows.Row.class);
        row.scopeSessionId=engine.session().id();row.presentationId="offer";
        row.itemId=536;row.itemName="Dragon bones";row.side=GeOffer.Side.SELL;
        row.coverage=MarketRows.Coverage.FULLY_KNOWN;row.lifecycle=MarketRows.Lifecycle.REALIZED;
        row.realizedResultCorrectionAware=true;row.realizedResultGp=200;row.observedSettlementGp=400;
        row.trackedBasisConsumedGp=200;row.receivedEachGp=200;row.listedDuring="";row.settledQty=2;
        row.settlementId="";row.earlierSettlements="";return row;
    }
    @Test public void aTradeCollectedAcrossTwoWindowsShowsOnlyTheClickedWindowsResult(){
        var markets=new ArrayList<MarketRows.Row>();
        var e=new Engine(deltas->List.of(),new TransactionClassifier(),new GpManagerConfig(){}){
            @Override synchronized List<MarketRows.Row> marketRows(){return markets;}
        };
        e.ensureSession(0);var run=e.session();
        var first=SavedSessionTest.trade(5);run.addTransaction(first,2000,false);e.newLive(60_000);
        var second=SavedSessionTest.trade(60_005);run.addTransaction(second,2000,false);
        var original=marketRow(e);original.settlementId=second.id();original.earlierSettlements=" "+first.id();markets.add(original);
        var data=LedgerData.capture(e,NOW,category(Semantics.Table.MARKET));
        assertEquals(100,data.market.total);assertEquals(1,data.market.groups.get(0).quantity);
        assertEquals(100,data.market.groups.get(0).value);assertEquals(100,MarketText.marketPrimary(data,data.market.groups.get(0)));
        assertEquals(200,original.realizedResultGp);assertNull(original.savedNet);
        assertEquals(200,LedgerData.capture(e,NOW,LedgerData.Entry.current()).market.total);
    }
    @Test public void openOffersDoNotUseUpRecentRows(){
        var markets=new ArrayList<MarketRows.Row>();
        var e=new Engine(deltas->List.of(),new TransactionClassifier(),new GpManagerConfig(){}){
            @Override synchronized List<MarketRows.Row> marketRows(){return markets;}
        };
        e.ensureSession(0);for(int i=0;i<40;i++)gain(e,i,1000+i);
        var pending=marketRow(e);pending.lifecycle=MarketRows.Lifecycle.PENDING;markets.add(pending);
        var rows=LiveSnapshot.capture(e,NOW,null).recent;
        assertEquals(15,rows.stream().filter(row->!row.open).count());
        assertEquals(1,rows.stream().filter(row->row.open).count());
    }
    @Test public void unchangedTicksReuseTheFocusedQueryAndVisibleRows()throws Exception{
        onEdt(()->{
            var panel=SavedSessionTest.panel(SavedSessionTest.window());open(panel,"GAINS");
            var page=focused(panel);var data=page.data;var row=page.income.body.getComponent(0);
            for(int i=0;i<100;i++)SidebarPanelProbe.refresh(panel);
            assertSame(data,page.data);assertSame(row,page.income.body.getComponent(0));return null;
        });
    }
    @Test public void aNewRunClosesThePreviousCategoryView()throws Exception{
        onEdt(()->{
            var e=SavedSessionTest.window();var panel=SavedSessionTest.panel(e);open(panel,"GAINS");
            e.startCustom("Next run",NOW);SidebarPanelProbe.refresh(panel);
            assertNull(field(SidebarPanel.class,"category").get(panel));assertTrue(panel.live.recent.isVisible());return null;
        });
    }
    @Test public void recentTotalAndFocusedControlsFitNarrowAndScaledLayouts()throws Exception{
        onEdt(()->{
            var e=engine();for(int i=0;i<40;i++)gain(e,i,1000+i);
            var panel=SavedSessionTest.panel(e);open(panel,"GAINS");var page=focused(panel);
            panel.live.recent.setTotal(Fmt.signed(-2_147_483_647L),Kit.Tone.LOSS);
            for(int width:new int[]{213,225,300}){
                DirectHeaderControlsTest.size(page.bar,width-12);
                assertTrue(page.scope.getFontMetrics(page.scope.getFont()).stringWidth(page.scope.getText())
                        <=page.scope.getWidth()-page.scope.getInsets().left-page.scope.getInsets().right);
                for(var table:List.of(panel.live.recent,page.income)){
                    DirectHeaderControlsTest.size(table,width-12);
                    var bounds=DirectHeaderControlsTest.bounds(table.total,table);
                    assertTrue(bounds.x>=0&&bounds.getMaxX()<=table.getWidth());
                    assertTrue(table.total.getFontMetrics(table.total.getFont()).stringWidth(table.total.getText())<=table.total.getWidth());
                    for(double scale:new double[]{1,1.25,1.5}){
                        var image=new java.awt.image.BufferedImage((int)Math.ceil(table.getWidth()*scale),
                                (int)Math.ceil(table.getHeight()*scale),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        var graphics=image.createGraphics();graphics.scale(scale,scale);table.printAll(graphics);graphics.dispose();
                        var target=java.nio.file.Path.of("build/category-layout-measure/previews",(table==page.income?"gains":"recent")+"-"+width+"-"+scale+".png");
                        java.nio.file.Files.createDirectories(target.getParent());assertTrue(javax.imageio.ImageIO.write(image,"png",target.toFile()));
                    }
                }
            }
            return null;
        });
    }
    @Test public void everyLiveCategoryMatchesRecentWidthAndHeaderAtAllPanelSizes()throws Exception{
        onEdt(()->{
            var e=DeathViewTest.populated();for(int i=0;i<16;i++)gain(e,i,1000+i);
            e.session().addTransaction(SavedSessionTest.trade(7000),2000,false);
            var panel=SavedSessionTest.panel(e);panel.bindPvp(()->new PvpState(true,false,false,0));
            SidebarPanelProbe.bindSprites(panel,id->new java.awt.image.BufferedImage(24,24,java.awt.image.BufferedImage.TYPE_INT_ARGB));
            SidebarPanelProbe.refresh(panel);
            for(int width:new int[]{213,225,300}){
                if(panel.category!=null)recent(focused(panel));SidebarPanelProbe.refresh(panel);
                DirectHeaderControlsTest.size(panel.live.body,width-12);
                var bounds=DirectHeaderControlsTest.bounds(panel.live.recent,panel.live.body);
                for(String label:new String[]{"GAINS","SUPPLY","LOSS","TRADE","DEATHS","KILLS"}){
                    open(panel,label);var page=focused(panel);
                    DirectHeaderControlsTest.size(panel.live.body,width-12);
                    var table=label.equals("TRADE")?page.market:label.equals("GAINS")||label.equals("KILLS")?page.income:page.cost;
                    var actual=DirectHeaderControlsTest.bounds(table,panel.live.body);
                    assertEquals(label+" left edge at "+width,bounds.x,actual.x);
                    assertEquals(label+" width at "+width,bounds.width,actual.width);
                    assertEquals(panel.live.recent.header.getPreferredSize().height,table.header.getPreferredSize().height);
                    if(!table.rows.isEmpty())assertEquals(panel.live.recent.rowHeight(),table.rowHeight());
                    if(table.pages()>1){
                        table.showPage(table.pages()-1);
                        assertEquals("No empty padding below the last Live page",table.body.getComponentCount()*table.rowHeight(),table.body.getPreferredSize().height);
                    }
                }
            }
            return null;
        });
    }
    @Test public void compactHistoryNoticeStaysInLedgerAndNeverAppearsInLiveCategories()throws Exception{
        onEdt(()->{
            var e=compactedEngine();var panel=SavedSessionTest.panel(e);open(panel,"GAINS");
            var page=focused(panel);assertTrue(page.data.compacted);assertFalse(page.data.detailed);
            assertFalse("Live should start directly with its table",compactNotice(page.body));
            assertFalse("The whole Ledger toolbar stays hidden",page.bar.isVisible());
            assertTrue(page.income.rows.isEmpty());assertEquals("No entries",page.income.emptyText);
            assertEquals(Fmt.signed(100),page.income.total.getText());assertFalse(compactNotice(page.body));
            assertEquals(100,e.metrics(NOW).income);
            var ledger=LedgerData.capture(e,NOW,LedgerData.Entry.current());
            panel.ledger.apply(ledger);assertTrue("Ledger must still explain unavailable old detail",compactNotice(panel.ledger.body));
            return null;
        });
    }
    static boolean compactNotice(java.awt.Component component){
        if(component instanceof javax.swing.JLabel&&((javax.swing.JLabel)component).getText().contains("Detailed entries"))return true;
        if(component instanceof java.awt.Container)for(var child:((java.awt.Container)component).getComponents())if(compactNotice(child))return true;
        return false;
    }
    static Engine compactedEngine(){
        var e=engine();gain(e,0,1000);assertEquals(1,e.session().compactBefore(NOW,null));return e;
    }
    static void retainedPreviews(java.io.File out)throws java.io.IOException{
        var clock=SafeMath.clock;
        try{for(int width:new int[]{213,225,300}){
            var panel=SavedSessionTest.panel(compactedEngine());var frame=new javax.swing.JFrame("Offline retained totals");
            frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);
            try{
                frame.add(panel);frame.pack();frame.setSize(width,760);frame.setVisible(true);open(panel,"GAINS");
                SidebarPanelProbe.refresh(panel);frame.validate();
                KillViewTest.shot(panel,out,"live-retained-totals-"+width,width);
            }finally{frame.dispose();}
        }}finally{SafeMath.clock=clock;}
    }
    @Test public void expandedRowsShowSingleItemsAndSpellCastCounts()throws Exception{
        onEdt(()->{
            var e=engine();gain(e,0,1000);
            for(int i=0;i<3;i++){
                // Mixed synthetic resources exercise the recorded spell's composite display count.
                var cast=new Transaction(2000+i,TransactionType.CONSUMPTION,Context.GENERIC,"","Test",true,
                        List.of(new Flow(560,"Death rune",-1,100,-100,PriceSource.GRAND_EXCHANGE),
                                new Flow(562,"Chaos rune",-1,50,-50,PriceSource.GRAND_EXCHANGE),
                                new Flow(555,"Water rune",-1,30,-30,PriceSource.GRAND_EXCHANGE)),
                        Confidence.CONFIRMED,"",null);
                cast.setAction(ActionKind.CAST);cast.setActionLabel(ActionLabel.of("Ice Burst"));
                e.session().addTransaction(cast,2000,false);
            }
            var panel=SavedSessionTest.panel(e);open(panel,"GAINS");
            assertEquals("×1",focused(panel).income.rows.get(0).qty);
            open(panel,"SUPPLY");assertEquals("Ice Burst",focused(panel).cost.rows.get(0).name);
            assertEquals("×3",focused(panel).cost.rows.get(0).qty);
            panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);
            assertEquals("normal Ledger keeps its original quantity policy","",panel.ledger.income.rows.get(0).qty);
            return null;
        });
    }
    @Test public void liveSupplyPotionQuantitiesMatchRecentWithoutChangingMoney()throws Exception{
        onEdt(()->{
            var engine=engine();
            var single=RecentSupplyAndPickupTest.item(TransactionType.CONSUMPTION,ActionKind.DRINK,
                    143,"Prayer potion(1)",-1,250,1000);
            engine.session().addTransaction(single,2000,false);
            for(int sip=0;sip<3;sip++){
                var drink=new Transaction(2000+sip,TransactionType.CONSUMPTION,Context.GENERIC,"","Test",true,
                        List.of(new Flow(3024,"Super restore(4)",-1,4000,-4000,PriceSource.GRAND_EXCHANGE),
                                new Flow(3026,"Super restore(3)",1,3000,3000,PriceSource.GRAND_EXCHANGE)),
                        Confidence.CONFIRMED,"",null);
                drink.setAction(ActionKind.DRINK);engine.session().addTransaction(drink,2000,false);
            }
            BlightedDoseTest.drinks(BlightedDoseTest.service(BlightedDoseTest.RESTORE,
                    "Blighted super restore",2324,""),BlightedDoseTest.RESTORE)
                    .forEach(drink->engine.session().addTransaction(drink,2000,false));
            engine.session().addTransaction(RecentSupplyAndPickupTest.item(TransactionType.CONSUMPTION,
                    ActionKind.FIRE,11212,"Dragon arrow",-13,10,3000),2000,false);
            engine.session().addTransaction(RecentSupplyAndPickupTest.item(TransactionType.CONSUMPTION,
                    ActionKind.EAT,385,"Shark",-1,900,3001),2000,false);
            var panel=SavedSessionTest.panel(engine);String before=new Gson().toJson(engine.exportState());
            long cost=panel.snapshot.supplies;open(panel,"SUPPLY");var page=focused(panel);
            var quantities=new HashMap<String,String>();page.cost.rows.forEach(row->quantities.put(row.name,row.qty));
            assertEquals("1 dose",quantities.get("Prayer potion"));
            assertEquals("×3 doses",quantities.get("Super restore"));
            assertEquals("×4 doses",quantities.get("Blighted super restore"));
            assertEquals("×13",quantities.get("Dragon arrow"));assertEquals("×1",quantities.get("Shark"));
            for(var group:page.data.cost.groups)if(group.dose){
                var row=page.cost.rows.stream().filter(value->value.key.equals(group.id)).findFirst().orElseThrow();
                assertEquals(LiveSnapshot.toRecent(group,null).qty,row.qty);
            }
            assertEquals(-cost,page.data.cost.total);assertEquals(before,new Gson().toJson(engine.exportState()));
            panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);
            assertEquals("normal Ledger keeps its existing quantity style","×3",panel.ledger.cost.rows.stream()
                    .filter(row->row.name.equals("Super restore")).findFirst().orElseThrow().qty);
            return null;
        });
    }
}
