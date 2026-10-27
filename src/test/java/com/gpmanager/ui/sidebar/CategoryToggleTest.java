package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import java.awt.*;
import javax.swing.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.LiveCategoryLayoutTest.*;

public class CategoryToggleTest {
    java.util.function.LongSupplier clock;
    @Before public void clock() { clock=SafeMath.clock; SafeMath.clock=()->NOW; }
    @After public void restore() { SafeMath.clock=clock; }
    static final Semantics.Table[] CATEGORIES={Semantics.Table.GAINS,Semantics.Table.COSTS_SUPPLIES,Semantics.Table.COSTS_LOSS,Semantics.Table.MARKET,Semantics.Table.valueOf("DEATH")};
    static SidebarPanel panel() {
        var engine=engine();for(int i=0;i<40;i++)gain(engine,i,1000+i);
        engine.session().addTransaction(SavedSessionTest.trade(5000),2000,false);
        var panel=SavedSessionTest.panel(engine);
        panel.bindPvp(()->new PvpState(true,false,false,0));SidebarPanelProbe.refresh(panel);return panel;
    }
    static JButton find(LedgerPage page) {
        var found=find(page.bar);return found!=null?found:Objects.requireNonNull(find(page.body));
    }
    static JButton find(Container root) {
        for(var child:root.getComponents()) {
            if(child instanceof JButton && "Search".equals(((JButton)child).getAccessibleContext().getAccessibleName())) return (JButton)child;
            if(child instanceof Container) {var found=find((Container)child);if(found!=null)return found;}
        }
        return null;
    }
    static Table table(LedgerPage page,Semantics.Table category) {
        return category==Semantics.Table.GAINS?page.income:category==Semantics.Table.MARKET?page.market:page.cost;
    }
    @Test public void everyCategoryTogglesBackWithoutChangingBookings() throws Exception {
        onEdt(()->{
            var panel=panel();String before=new Gson().toJson(panel.engine.exportState());
            for(var category:CATEGORIES) {
                open(panel,category.label);assertEquals(category,panel.category.table);
                open(panel,category.label);assertNull(panel.category);assertTrue(panel.live.recent.isVisible());
                assertFalse(panel.live.breakdown.isVisible());assertEquals(before,new Gson().toJson(panel.engine.exportState()));
            }
            return null;
        });
    }
    @Test public void switchingCategoriesResetsThePagerAndKeepsSearchInLedger() throws Exception {
        onEdt(()->{
            var panel=panel();open(panel,"GAINS");var page=focused(panel);page.income.showPage(2);
            for(var category:CATEGORIES) {
                if(panel.category.table==category) continue;
                open(panel,category.label);assertEquals(category,panel.category.table);
                assertEquals("",page.data.entry.search);assertFalse(page.searching);assertEquals(0,table(page,category).page);
                assertNull(find(panel.live.body));assertNull(table(page,category).picker);
            }
            return null;
        });
    }
    @Test public void liveCategoriesHaveNoSearchAndKeepTheirPager() throws Exception {
        onEdt(()->{
            var panel=panel();open(panel,"GAINS");var page=focused(panel);
            assertFalse(page.bar.isVisible());assertNull(page.bar.getParent());assertNull(find(panel.live.body));
            assertNull(page.search.getParent());assertSame(page.income.right,page.income.next.getParent());
            page.income.next.doClick();assertEquals(1,page.income.page);
            assertFalse(page.searching);assertEquals("",page.data.entry.search);
            return null;
        });
    }
    @Test public void detailHasNoSearchAndTheCategoryCanStillCloseIt() throws Exception {
        onEdt(()->{
            var panel=panel();open(panel,"GAINS");var page=focused(panel);page.income.rows.get(0).open.run();SidebarPanelProbe.refresh(panel);
            assertNotNull(page.data.detail);assertNull(find(panel.live.body));assertNull(page.search.getParent());
            open(panel,"GAINS");assertNull(panel.category);assertTrue(panel.live.recent.isVisible());
            return null;
        });
    }
    @Test public void categoryTitleTotalAndPagerFitNarrowHeaders() throws Exception {
        onEdt(()->{
            var panel=panel();open(panel,"GAINS");var page=focused(panel);var table=page.income;
            for(int width:new int[]{213,225,300}) {
                DirectHeaderControlsTest.size(table,width-12);
                for(var part:new JComponent[]{table.title,table.total,table.previous,table.counter,table.next}) {
                    var bounds=DirectHeaderControlsTest.bounds(part,table);
                    assertTrue(part.getClass()+" left at "+width,bounds.x>=0);
                    assertTrue(part.getClass()+" right at "+width,bounds.getMaxX()<=table.getWidth());
                }
                assertTrue("full category label at "+width,table.title.getFontMetrics(table.title.getFont()).stringWidth(table.title.getText())<=table.title.getWidth());
            }
            return null;
        });
    }
    @Test public void ordinaryLedgerKeepsItsScopeSearchAndActions() throws Exception {
        onEdt(()->{
            var panel=panel();panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);var page=panel.ledger;
            assertTrue(page.bar.isVisible());assertTrue(page.scope.isVisible());assertTrue(page.more.isVisible());
            assertEquals("Search",find(page).getText());assertNull(find(page).getIcon());
            assertTrue(SwingUtilities.isDescendingFrom(find(page),page.bar));return null;
        });
    }
    /** Actual offscreen Swing layouts; no client or game input. */
    static void previews(java.io.File out) throws java.io.IOException {
        var previous=SafeMath.clock;SafeMath.clock=()->NOW;
        try {
            for(int width:new int[]{213,225,300}) for(var category:CATEGORIES)
                preview(out,width,category,false);
            preview(out,225,Semantics.Table.GAINS,true);
        } finally {SafeMath.clock=previous;}
    }
    static void preview(java.io.File out,int width,Semantics.Table category,boolean detail) throws java.io.IOException {
        var panel=panel();var frame=new JFrame("GP Manager offline category preview");
        frame.setUndecorated(true);
        frame.setFocusableWindowState(false);frame.setLocation(-10_000,-10_000);
        try {
            frame.add(panel);frame.pack();frame.setSize(width,760);frame.setVisible(true);frame.validate();
            assertEquals(width,panel.getWidth());
            open(panel,category.label);var page=focused(panel);
            if(detail)page.income.rows.get(0).open.run();
            for(int i=0;i<2;i++){SidebarPanelProbe.refresh(panel);frame.validate();}
            ShellProbe.scrollCurrentToTop(panel.shell);
            var image=new java.awt.image.BufferedImage(width,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
            javax.imageio.ImageIO.write(image,"png",new java.io.File(out,"category-"+category+"-"+width+(detail?"-detail":"")+".png"));
        } finally {frame.dispose();}
    }
}
