package com.gpmanager;

import java.awt.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.MilestoneTest.onEdt;

public class ActivityHeaderLayoutTest {
    @Test public void longActivitiesStayReadableAndCenteredBesideTheStatusPill() throws Exception {
        onEdt(() -> {
            for (int width : new int[]{225,300}) {
                for (String title : new String[]{"Wilderness Agility Course", "Hallowed Sepulchre",
                        "Tombs of Amascut", "Chambers of Xeric: Challenge Mode",
                        "Phantom Muspah", "Theatre of Blood", "Alchemical Hydra", "Wintertodt",
                        "Smithing"}) {
                    Hero hero=new Hero();
                    JLabel pill=hero.word;
                    hero.setSize(width,100);
                    hero.status(Color.GREEN,"Tracking",Fmt.activity(title),"AWAY",null);
                    hero.setSize(width,hero.getPreferredSize().height);layout(hero);
                    assertFalse(title,hero.name.getText().contains("…"));
                    assertEquals(title,Fmt.activity(title),hero.name.getText());
                    assertEquals("one row",hero.name.getFontMetrics(hero.name.getFont()).getHeight(),hero.name.getPreferredSize().height);
                    Rectangle name=SwingUtilities.convertRectangle(hero.name.getParent(),hero.name.getBounds(),hero);
                    Rectangle button=SwingUtilities.convertRectangle(pill.getParent(),pill.getBounds(),hero);
                    assertEquals(title+" vertical center",name.getCenterY(),button.getCenterY(),1d);
                    assertTrue(title+" does not overlap controls",name.getMaxX()<=button.getX());
                    assertTrue(title+" rendered text fits",hero.name.getPreferredSize().width<=hero.name.getWidth());
                    assertTrue(title+" stays readable",hero.name.getFont().getSize2D()>=10f);
                }
            }
            return null;
        });
    }
    static void layout(Container parent){parent.doLayout();for(Component c:parent.getComponents())if(c instanceof Container)layout((Container)c);}
}
