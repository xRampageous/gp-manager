package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import org.junit.Test;
import static org.junit.Assert.*;

/** The existing item-row layout carries production output; no added wording or extra line. */
public class ProductionRowLayoutTest
{
    @Test public void requestedExamplesKeepNamesIconsCountsAndOneLineAtSupportedWidths() throws Exception
    {
        SwingTestSupport.onEdt(()->{
            Engine engine=ProductionRecipeTest.engine();
            for(Transaction receipt:List.of(
                ProductionRecipeTest.receipt("Cooking",ProductionRecipeTest.leg(1933,"Pot of flour",-14),
                    ProductionRecipeTest.leg(1937,"Jug of water",-14),ProductionRecipeTest.leg(1931,"Pot",14),
                    ProductionRecipeTest.leg(1935,"Jug",14),ProductionRecipeTest.leg(2307,"Bread dough",14)),
                ProductionRecipeTest.receipt("Cooking",ProductionRecipeTest.leg(2287,"Uncooked pizza",-14),ProductionRecipeTest.leg(2289,"Plain pizza",14)),
                ProductionRecipeTest.receipt("Fletching",ProductionRecipeTest.leg(52,"Arrow shaft",-150),ProductionRecipeTest.leg(314,"Feather",-150),ProductionRecipeTest.leg(53,"Headless arrow",150)),
                ProductionRecipeTest.receipt("Fletching",ProductionRecipeTest.leg(48,"Longbow (u)",-14),ProductionRecipeTest.leg(1777,"Bow string",-14),ProductionRecipeTest.leg(839,"Longbow",14)),
                ProductionRecipeTest.receipt("Fletching",ProductionRecipeTest.leg(1607,"Sapphire",-28),ProductionRecipeTest.leg(9189,"Sapphire bolt tips",336))))
                engine.session().addTransaction(receipt,2000,false);
            LiveSnapshot snapshot=LiveSnapshot.capture(engine,ProductionRecipeTest.T0+3000,LiveContext.NONE);
            assertEquals(5,snapshot.recent.size());
            LivePage page=new LivePage(ReviewFixesTest.proxy(LivePage.Actions.class,Map.of()),id->new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB));
            page.apply(snapshot);
            for(Table.Row row:page.recent.rows)
            {
                assertNotNull(row.icon);
                assertTrue(row.qty.matches("×(?:14|150|336)"));
                assertFalse(row.name.matches(".*(?:Made|Cooked|Smelted|Strung|·).*"));
                assertTrue(row.tip.startsWith(row.name));
                assertFalse(row.exact.isEmpty());
            }
            for(int width:new int[]{213,225,300})
            {
                page.recent.setSize(width-12,page.recent.getPreferredSize().height);
                layout(page.recent);
                for(Component row:page.recent.body.getComponents())
                {
                    assertTrue(row.getWidth()>0);
                    assertEquals(page.recent.rowHeight(),row.getHeight());
                    if(row instanceof JPanel)
                        for(Component child:((JPanel)row).getComponents())
                        {
                            assertTrue(child.getX()>=0);
                            assertTrue(child.getX()+child.getWidth()<=row.getWidth());
                        }
                }
                // Optional offline artifact, outside the repository and without client/player data.
                String output=System.getProperty("gp.production.preview");
                if(output!=null)
                {
                    Path folder=Path.of(output);Files.createDirectories(folder);
                    BufferedImage image=new BufferedImage(page.recent.getWidth(),page.recent.getHeight(),BufferedImage.TYPE_INT_RGB);
                    var graphics=image.createGraphics();page.recent.paint(graphics);graphics.dispose();
                    ImageIO.write(image,"png",folder.resolve("production-"+width+".png").toFile());
                }
            }
            return null;
        });
    }
    static void layout(Container parent)
    {
        parent.doLayout();
        for(Component child:parent.getComponents())if(child instanceof Container)layout((Container)child);
    }
}
