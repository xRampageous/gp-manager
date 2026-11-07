package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeRecentHeaderTest {
    private static final long NOW=1_700_000_000_000L;

    private static LiveSnapshot offers() {
        var engine=ReviewFixesTest.engine();
        engine.startCustom("Header offers",NOW);
        var slots=new OfferSlots();
        slots.observe(new OfferSlots.Snapshot(0,GrandExchangeOfferState.EMPTY,0,0,0,0,0));
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(0,
                GrandExchangeOfferState.SELLING,560,100,0,180,0)).get(),"Death rune",NOW+600);
        return LiveSnapshot.capture(engine,NOW+1000,LiveContext.NONE);
    }

    private static LivePage page() {
        return new LivePage(ReviewFixesTest.proxy(LivePage.Actions.class,Map.of()),id->null);
    }

    @Test public void geOffersLiveInRecentHeaderWhileKeysKeepTheirOriginalHome() throws Exception {
        SwingTestSupport.onEdt(()->{
            var page=page();var snapshot=offers();snapshot.keys=1;
            page.apply(snapshot);
            assertNotNull("open offers need a header icon",page.recent.picker);
            assertEquals("GE",page.recent.picker.getName());
            assertTrue(SwingUtilities.isDescendingFrom(page.recent.picker,page.recent.header));
            assertFalse(SwingUtilities.isDescendingFrom(page.recent.picker,page.signals));
            assertTrue(page.recent.picker.getToolTipText().contains("Grand Exchange offers still open: 1"));
            assertTrue(page.recent.picker.getToolTipText().contains("Death rune"));
            assertEquals(1,page.counts.getComponentCount());
            assertEquals("key",page.counts.getComponent(0).getName());
            assertTrue(page.recent.rows.isEmpty());
            return null;
        });
    }

    @Test public void unchangedOffersRetainTheSameButtonAndHoverText() throws Exception {
        SwingTestSupport.onEdt(()->{
            var page=page();var snapshot=offers();page.apply(snapshot);
            JComponent before=page.recent.picker;
            assertNotNull(before);
            page.apply(snapshot);
            assertSame(before,page.recent.picker);
            assertEquals(before.getToolTipText(),page.recent.picker.getToolTipText());
            return null;
        });
    }

    @Test public void aFoldedRecentTableKeepsTheOfferShortcutAndItsNavigation() throws Exception {
        SwingTestSupport.onEdt(()->{
            var opened=new AtomicReference<LedgerData.Entry>();
            var actions=(LivePage.Actions)java.lang.reflect.Proxy.newProxyInstance(LivePage.Actions.class.getClassLoader(),
                    new Class<?>[]{LivePage.Actions.class},(proxy,method,args)->{
                        if(method.getName().equals("openLedger"))opened.set((LedgerData.Entry)args[0]);
                        return method.getReturnType()==boolean.class?false:null;
                    });
            var page=new LivePage(actions,id->null);page.apply(offers());
            page.recent.setFolded(true);
            assertNotNull(page.recent.picker);
            assertTrue(page.recent.picker.isVisible());
            assertFalse(page.recent.body.isVisible());
            ((JButton)page.recent.picker).doClick();
            assertNotNull(opened.get());
            assertEquals(LedgerData.Scope.CURRENT_GRIND,opened.get().scope);
            return null;
        });
    }

    @Test public void clearingOffersRemovesTheHeaderIconWithoutRemovingKeys() throws Exception {
        SwingTestSupport.onEdt(()->{
            var page=page();var snapshot=offers();snapshot.keys=1;page.apply(snapshot);
            assertNotNull(page.recent.picker);
            snapshot.pendingMarket=0;snapshot.recent=List.of();page.apply(snapshot);
            assertNull(page.recent.picker);
            assertEquals(1,page.counts.getComponentCount());
            assertEquals("key",page.counts.getComponent(0).getName());
            return null;
        });
    }

    @Test public void headerFitsIconTitleAndValueAtEverySupportedWidth() throws Exception {
        SwingTestSupport.onEdt(()->{
            var page=page();page.apply(offers());
            assertNotNull(page.recent.picker);
            page.recent.setTitle("RECENT · 30").setTotal(Fmt.signed(Long.MAX_VALUE),Kit.Tone.GAIN);
            for(int width:new int[]{213,225,300}) {
                page.recent.setSize(width-12,120);layout(page.recent);
                var icon=rectangle(page.recent.picker,page.recent.header);
                var total=rectangle(page.recent.total,page.recent.header);
                System.out.println("GE header width="+width+" title="+page.recent.title.getWidth()+" needs="
                        +page.recent.title.getFontMetrics(page.recent.title.getFont()).stringWidth(page.recent.title.getText())
                        +" icon="+icon+" total="+total);
                assertTrue("icon has width at "+width,icon.width>0);
                assertTrue("icon is inside the header at "+width,icon.x>=0&&icon.getMaxX()<=page.recent.header.getWidth());
                assertFalse("icon and total overlap at "+width,icon.intersects(total));
                assertTrue("title fits at "+width,page.recent.title.getFontMetrics(page.recent.title.getFont())
                        .stringWidth(page.recent.title.getText())<=page.recent.title.getWidth());
            }
            return null;
        });
    }

    private static Rectangle rectangle(Component component,Container root) {
        return SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),root);
    }

    static JComponent preview(int width) {
        var page=page();page.apply(offers());
        var holder=new javax.swing.JPanel(new java.awt.BorderLayout());
        holder.setBackground(Kit.PANEL);
        holder.add(page.body(),java.awt.BorderLayout.NORTH);
        holder.setPreferredSize(new java.awt.Dimension(width,260));
        holder.setSize(holder.getPreferredSize());
        layout(holder);
        return holder;
    }

    private static void layout(Container container) {
        container.doLayout();
        for(var child:container.getComponents())if(child instanceof Container)layout((Container)child);
    }
}
