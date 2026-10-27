package com.gpmanager;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.MilestoneTest.onEdt;

public class LiveStatusPillTest {
    @Test public void statusesOccupyTheRightPillAndNamesStayOnOneRow() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            LiveSnapshot snapshot = TargetFreshStartTest.live(TargetFreshStartTest.engine(), 20L);
            snapshot.activityLabel = "Wilderness Agility Course";
            for (int width : new int[]{213, 225, 300}) {
                page.hero.setSize(width, 100);
                for (String status : new String[]{"AWAY", "CALIBRATING", "PAUSED"}) {
                    snapshot.idle = status.equals("AWAY");
                    snapshot.warming = status.equals("CALIBRATING");
                    snapshot.paused = status.equals("PAUSED");
                    page.apply(snapshot);
                    page.hero.setSize(width, page.hero.getPreferredSize().height);
                    ActivityHeaderLayoutTest.layout(page.hero);
                    JLabel pill = page.hero.word;
                    assertEquals(status, LivePage.wordOf(snapshot));
                    assertEquals(status + " pill colour", status.equals("AWAY") ? Kit.ACCENT
                            : status.equals("CALIBRATING") ? Kit.Tone.GAIN.color : Kit.Tone.REVIEW.color, pill.getForeground());
                    assertTrue("status has a visible pill border", pill.getBorder() != null);
                    Rectangle badge = SwingUtilities.convertRectangle(pill.getParent(), pill.getBounds(), page.hero);
                    Rectangle name = SwingUtilities.convertRectangle(page.hero.name.getParent(), page.hero.name.getBounds(), page.hero);
                    assertEquals("pill is at right edge", width - 4, badge.getMaxX(), 1d);
                    assertEquals("shared vertical center", name.getCenterY(), badge.getCenterY(), 1d);
                    assertEquals(status+" keeps activity readable", snapshot.activityLabel, page.hero.name.getText());
                    assertTrue("title fits", page.hero.name.getPreferredSize().width <= page.hero.name.getWidth());
                    assertEquals("one row", page.hero.name.getFontMetrics(page.hero.name.getFont()).getHeight(), page.hero.name.getPreferredSize().height);
                }
            }
            return null;
        });
    }

    @Test public void pillColoursFollowVisibleStatusAndResetAcrossTransitions() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            LiveSnapshot snapshot = TargetFreshStartTest.live(TargetFreshStartTest.engine(), 20L);
            snapshot.warming = true;
            page.apply(snapshot);
            assertEquals(Kit.Tone.GAIN.color, page.hero.word.getForeground());
            snapshot.idle = true;
            page.apply(snapshot);
            assertEquals(Kit.ACCENT, page.hero.word.getForeground());
            snapshot.offline = true;
            page.apply(snapshot);
            assertEquals("Logged out", page.hero.word.getText());
            assertEquals(Kit.Tone.LOSS.color, page.hero.word.getForeground());
            snapshot.hopping = true;
            page.apply(snapshot);
            assertEquals("Reconnecting", page.hero.word.getText());
            assertEquals(Kit.Tone.MARKET.color, page.hero.word.getForeground());
            snapshot.offline = false;
            snapshot.hopping = false;
            snapshot.idle = false;
            snapshot.paused = true;
            page.apply(snapshot);
            assertEquals("Paused", page.hero.word.getText());
            assertEquals(Kit.Tone.REVIEW.color, page.hero.word.getForeground());
            snapshot.paused = false;
            snapshot.warming = false;
            page.apply(snapshot);
            assertFalse(page.hero.word.isVisible());
            assertEquals(Kit.LABEL, page.hero.word.getForeground());
            return null;
        });
    }

    @Test public void resumingAndCompleteUseGreenWhileAnUnknownStateUsesTheDefault() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            LiveSnapshot snapshot = TargetFreshStartTest.live(TargetFreshStartTest.engine(), 20L);
            snapshot.present = false;
            snapshot.resumeSeconds = 3L;
            page.apply(snapshot);
            assertEquals("Resuming 3s", page.hero.word.getText());
            assertEquals(Kit.Tone.GAIN.color, page.hero.word.getForeground());
            page.hero.status(Kit.OFF, "", "Vorkath", "COMPLETE", null);
            assertEquals(Kit.Tone.GAIN.color, page.hero.word.getForeground());
            page.hero.status(Kit.OFF, "", "", "OTHER", null);
            assertEquals(Kit.LABEL, page.hero.word.getForeground());
            return null;
        });
    }

    @Test public void onlyLeftClickTogglesTheStatusDotAndDisabledDotsDoNothing() throws Exception {
        onEdt(() -> {
            Hero hero = new Hero();
            int[] toggles = {0};
            hero.status(Kit.Tone.GAIN.color, "", "", "", () -> toggles[0]++);
            DirectHeaderControlsTest.click(hero.dot, java.awt.event.MouseEvent.BUTTON3);
            DirectHeaderControlsTest.click(hero.dot, java.awt.event.MouseEvent.BUTTON2);
            assertEquals("other buttons cannot pause tracking", 0, toggles[0]);
            DirectHeaderControlsTest.click(hero.dot);
            assertEquals(1, toggles[0]);
            hero.status(Kit.OFF, "", "", "LOGGED OUT", null);
            DirectHeaderControlsTest.click(hero.dot);
            assertEquals(1, toggles[0]);
            assertEquals(Cursor.DEFAULT_CURSOR, hero.dot.getCursor().getType());
            return null;
        });
    }

    @Test public void countsRemainSeparateFromTheActivityInConditionalContent() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            Engine engine = TargetFreshStartTest.engine();
            LiveSnapshot snapshot = TargetFreshStartTest.live(engine, 20L);
            snapshot.reviews = 2;
            snapshot.keys = 1;
            page.apply(snapshot);
            assertNull(page.pageBar());
            assertTrue(SwingUtilities.isDescendingFrom(page.counts, page.body()));
            assertTrue(SwingUtilities.isDescendingFrom(page.hero.icons, page.body()));
            assertFalse(SwingUtilities.isDescendingFrom(page.hero.icons, page.hero));
            return null;
        });
    }

    @Test public void normalTrackingAndLogoutKeepTheirExistingStatusRules() throws Exception {
        onEdt(() -> {
            LivePage page = MilestoneTest.page();
            LiveSnapshot snapshot = TargetFreshStartTest.live(TargetFreshStartTest.engine(),20L);
            page.apply(snapshot); assertFalse(page.hero.word.isVisible());
            snapshot.offline = true; page.apply(snapshot);
            assertEquals("Logged out", page.hero.word.getText());
            assertNull(page.pageBar());
            snapshot.hopping = true; page.apply(snapshot);
            assertEquals("Reconnecting", page.hero.word.getText());
            return null;
        });
    }
}
