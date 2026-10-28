package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.LiveCategoryLayoutTest.*;

/** Owner live feedback: compact optional PvP view, below-card badges and exact death drills. */
public class LiveDeathFeedbackTest {
    java.util.function.LongSupplier clock;
    @Before public void before() { clock=SafeMath.clock;SafeMath.clock=()->NOW; }
    @After public void after() { SafeMath.clock=clock; }

    @Test public void automaticViewFollowsActualDangerWithoutChangingRecordedStats() throws Exception {
        SwingTestSupport.onEdt(()->{
            var e=DeathViewTest.populated();e.session().addEncounter(false,2000);
            var page=MilestoneTest.page();var snapshot=LiveSnapshot.capture(e,NOW,null);
            assertTrue(snapshot.pvp);
            assertFalse(labels(page,snapshot).contains("DEATHS"));
            assertFalse(labels(page,snapshot).contains("KILLS"));
            assertFalse(labels(page,snapshot).contains("BEST"));
            assertFalse(labels(page,snapshot).contains("STREAK"));
            long net=e.metrics(NOW).net;
            snapshot.possible=true;page.apply(snapshot);
            assertEquals(List.of("KILLS","DEATHS","BEST","STREAK"),
                    page.strips(snapshot).get(1).stream().map(c->c.label).collect(java.util.stream.Collectors.toList()));
            assertEquals("1",page.strips(snapshot).get(1).get(1).value);
            snapshot.possible=false;page.apply(snapshot);
            assertFalse(labels(page,snapshot).contains("DEATHS"));
            assertFalse(labels(page,snapshot).contains("KILLS"));
            assertEquals(net,e.metrics(NOW).net);
            assertTrue(snapshot.pvp);assertEquals(1,snapshot.deaths);
            return null;
        });
    }

    @Test public void alwaysViewShowsSafeHistoryButNeverSafeRisk() throws Exception {
        SwingTestSupport.onEdt(()->{
            var method=GpManagerConfig.class.getMethod("pvpView");
            assertEquals("Auto",method.invoke(new GpManagerConfig(){}).toString());
            assertEquals(List.of("Auto","Always"),Arrays.stream(method.getReturnType().getEnumConstants())
                    .map(Object::toString).collect(java.util.stream.Collectors.toList()));
            var config=(GpManagerConfig)java.lang.reflect.Proxy.newProxyInstance(GpManagerConfig.class.getClassLoader(),
                    new Class<?>[]{GpManagerConfig.class},(p,m,a)->m.getName().equals("pvpView")
                        ? method.getReturnType().getEnumConstants()[1] : m.invoke(new GpManagerConfig(){},a));
            var panel=new SidebarPanel(DeathViewTest.populated(),config,null,null,null);
            panel.bindPvp(()->new PvpState(false,false,false,1_000_000));SidebarPanelProbe.refresh(panel);
            assertTrue(labels(panel.live,panel.snapshot).contains("DEATHS"));
            assertFalse(panel.live.risk.isVisible());
            open(panel,"DEATHS");assertEquals(Semantics.Table.DEATH,panel.category.table);
            assertFalse(focused(panel).data.cost.groups.isEmpty());
            return null;
        });
    }

    @Test public void moneyAndPvpFitInTwoRowsAndBadgesFollowHero() throws Exception {
        SwingTestSupport.onEdt(()->{
            var e=DeathViewTest.populated();e.session().addTransaction(SavedSessionTest.trade(7000),2000,false);
            var s=LiveSnapshot.capture(e,NOW,null);s.possible=true;s.reviews=3;
            s.income=16_400_000;s.supplies=2_110_000;s.loss=12_200_000;s.market=-2300;
            var page=MilestoneTest.page();page.apply(s);
            assertEquals(2,page.strips(s).size());
            assertEquals(List.of("GAINS","SUPPLY","LOSS","TRADE"),page.strips(s).get(0).stream()
                    .map(c->c.label).collect(java.util.stream.Collectors.toList()));
            assertTrue(Arrays.asList(page.body.getComponents()).indexOf(page.signals)>
                    Arrays.asList(page.body.getComponents()).indexOf(page.hero));
            for(int width:new int[]{213,225,300})for(var row:page.strips(s)){
                var strip=Hero.strip(row);DirectHeaderControlsTest.size(strip,width-12);
                for(var box:strip.getComponents())for(var part:((java.awt.Container)box).getComponents())
                    if(part instanceof javax.swing.JLabel){
                        var label=(javax.swing.JLabel)part;
                        assertTrue(label.getText()+" clipped at "+width+": "+label.getFontMetrics(label.getFont()).stringWidth(label.getText())+">"+label.getWidth(),
                                label.getFontMetrics(label.getFont()).stringWidth(label.getText())<=label.getWidth());
                    }
            }
            return null;
        });
    }

    @Test public void heldAndBagItemsOpenTheirOwnReceiptEvenForTheSameItemId() throws Exception {
        SwingTestSupport.onEdt(()->{
            var e=engine();var held=DeathRowGroupingTest.loss("death:same",false,
                    new Flow(1079,"Rune platelegs",-1,100,-100));
            var bag=DeathRowGroupingTest.loss("death:same",true,
                    new Flow(1079,"Rune platelegs",-2,200,-400));
            e.session().addTransaction(held,2000,false);e.session().addTransaction(bag,2000,false);
            var panel=SavedSessionTest.panel(e);panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);
            var page=panel.ledger;page.cost.rows.get(0).open.run();SidebarPanelProbe.refresh(panel);
            var heldRow=page.items(page.data.detail.group).stream().filter(r->r.name.equals("Rune platelegs")).findFirst().orElseThrow();
            assertNotNull("held item is an exact receipt link",heldRow.open);heldRow.open.run();SidebarPanelProbe.refresh(panel);
            assertEquals(held.id(),page.data.detail.exact.transaction);
            page.items(page.data.detail.group).stream().filter(r->r.name.equals("Looting bag")).findFirst().orElseThrow().open.run();
            var bagRow=page.items(page.data.detail.group).get(0);
            assertNotNull("bag contents are exact receipt links too",bagRow.open);bagRow.open.run();SidebarPanelProbe.refresh(panel);
            assertEquals(bag.id(),page.data.detail.exact.transaction);
            assertEquals(-400,page.data.detail.exact.value);assertEquals(2,page.data.detail.exact.quantity);
            assertEquals(-500,e.metrics(NOW).net);return null;
        });
    }

    @Test public void graveGearNeverAppearsInRecentOrLedger() throws Exception {
        SwingTestSupport.onEdt(()->{
            var e=engine();var grave=DeathViewTest.grave(false);
            e.session().addTransaction(grave,2000,false);
            var panel=SavedSessionTest.panel(e);panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);
            assertTrue(panel.ledger.data.cost.groups.isEmpty());
            assertEquals(0,e.metrics(NOW).net);
            assertTrue(LedgerData.capture(e,NOW,LedgerData.Entry.current()).cost.groups.isEmpty());
            return null;
        });
    }

    static void previews(java.io.File out) throws java.io.IOException {
        var previous=SafeMath.clock;SafeMath.clock=()->NOW;
        try {
            for(int width:new int[]{213,225,300})for(String mode:List.of("auto-safe","auto-danger","always-safe")){
                var config=new GpManagerConfig(){@Override public PvpView pvpView(){
                    return mode.equals("always-safe")?PvpView.Always:PvpView.Auto;}};
                var panel=new SidebarPanel(DeathViewTest.populated(),config,null,null,null);
                panel.bindPvp(()->new PvpState(mode.equals("auto-danger"),false,false,75000));
                var frame=new javax.swing.JFrame("Offline live feedback preview");
                frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10_000,-10_000);
                try {
                    frame.add(panel);frame.pack();frame.setSize(width,760);frame.setVisible(true);
                    SidebarPanelProbe.refresh(panel);
                    var s=panel.snapshot;s.income=16_400_000;s.supplies=2_110_000;s.loss=12_200_000;s.market=-2300;
                    s.net=s.income-s.supplies-s.loss+s.market;s.reviews=3;s.deaths=2;s.bestKill=24000;s.streak=1;
                    panel.live.apply(s);frame.validate();
                    var image=new java.awt.image.BufferedImage(width,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var g=image.createGraphics();panel.printAll(g);g.dispose();
                    javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"live-feedback-"+mode+"-"+width+".png"));
                } finally {frame.dispose();}
            }
        } finally {SafeMath.clock=previous;}
    }

    static List<String> labels(LivePage page,LiveSnapshot s){return page.strips(s).stream().flatMap(Collection::stream)
            .map(c->c.label).collect(java.util.stream.Collectors.toList());}
}
