package com.gpmanager;

import com.google.gson.Gson;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.LiveCategoryLayoutTest.*;

/** Live has no search entry points; Ledger keeps its independent searchable scope. */
public class LedgerOnlySearchTest {
    java.util.function.LongSupplier previous;
    @Before public void clock(){previous=SafeMath.clock;SafeMath.clock=()->NOW;}
    @After public void restore(){SafeMath.clock=previous;}
    @Test public void liveCannotOpenAFieldOrAcceptAHiddenQuery() throws Exception {
        onEdt(()->{
            var panel=CategoryToggleTest.panel();String before=new Gson().toJson(panel.engine.exportState());
            for(var category:CategoryToggleTest.CATEGORIES) {
                open(panel,category.label);var page=focused(panel);
                page.toggleSearch();page.actions.searchChanged("Item 39");SidebarPanelProbe.refresh(panel);
                assertFalse(page.searching);assertEquals("",page.data.entry.search);
                assertNull(page.search.getParent());assertNull(CategoryToggleTest.find(panel.live.body));
                assertNull(CategoryToggleTest.table(page,category).picker);
                assertEquals(before,new Gson().toJson(panel.engine.exportState()));
            }
            return null;
        });
    }
    @Test public void ledgerSearchStillFiltersClearsAndSurvivesATabSwitch() throws Exception {
        onEdt(()->{
            var panel=CategoryToggleTest.panel();String before=new Gson().toJson(panel.engine.exportState());
            panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);var page=panel.ledger;
            int all=page.data.income.groups.size();var find=CategoryToggleTest.find(page);
            find.doClick();SidebarPanelProbe.refresh(panel);assertTrue(page.searching);
            page.search.setText("Item 39");SidebarPanelProbe.refresh(panel);
            assertEquals(1,page.data.income.groups.size());assertEquals("Item 39",panel.ledgerSearch);
            panel.shell.show(Shell.LIVE);SidebarPanelProbe.refresh(panel);open(panel,"GAINS");
            assertNull(CategoryToggleTest.find(panel.live.body));assertEquals("",panel.category.search);
            panel.shell.show(Shell.LEDGER);SidebarPanelProbe.refresh(panel);
            assertEquals("Item 39",page.search.getText());assertEquals(1,page.data.income.groups.size());
            find.doClick();SidebarPanelProbe.refresh(panel);assertFalse(page.searching);
            assertEquals("",page.data.entry.search);assertEquals(all,page.data.income.groups.size());
            assertEquals(before,new Gson().toJson(panel.engine.exportState()));return null;
        });
    }
}
