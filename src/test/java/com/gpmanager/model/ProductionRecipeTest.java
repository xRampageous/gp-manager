package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.*;

/** Physical recipes group captured legs, never forecast yield, doses or prices. */
public class ProductionRecipeTest
{
    static final long T0 = 1_700_000_000_000L;
    static Flow leg(int id, String name, long quantity)
    {
        return new Flow(id, name, quantity, 7, SafeMath.multiply(quantity, 7), PriceSource.GRAND_EXCHANGE, T0);
    }
    static Transaction receipt(String skill, Flow... flows)
    {
        return new Transaction(T0 + 600, TransactionType.PROCESSING, Context.PRODUCTION,
            skill, skill, true, List.of(flows), Confidence.CONFIRMED, "captured recipe", null);
    }
    static Engine engine()
    {
        JsonCodec.bind(new Gson());
        Engine engine = TargetFreshStartTest.engine();
        engine.ensureSession(T0);
        return engine;
    }
    static LiveSnapshot.Recent merged(Engine engine, Transaction transaction, int item, String name, long count)
    {
        engine.session().addTransaction(transaction, 2_000, false);
        long revision = engine.revision();
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 3_000, LiveContext.NONE);
        assertEquals("one recipe row", 1, snapshot.recent.size());
        LiveSnapshot.Recent row = snapshot.recent.get(0);
        assertEquals(name, row.name);
        assertEquals(item, row.item);
        assertEquals("items, never actions or doses", count, row.quantity);
        assertEquals(count > 1 ? Fmt.times(count) : "", row.qty == null ? "" : row.qty);
        assertEquals("no verb or method subtitle", row.qty == null ? "" : row.qty, LivePage.metaOf(row));
        assertEquals(1, row.receipts.size());
        assertEquals(snapshot.net, row.net());
        LedgerData detail = LedgerData.capture(engine, T0 + 3_000, LivePage.entryFor(row));
        assertNotNull(detail.detail);
        assertEquals(transaction.flows().size(), detail.detail.group.entries.size());
        assertEquals(count, detail.detail.group.quantity);
        assertEquals(row.net(), detail.detail.group.value);
        assertTrue("Ledger total reconciles",detail.balanced);
        assertEquals(snapshot.net,detail.net);
        assertEquals(transaction.revenue(),engine.metrics(T0+3000).income);
        assertEquals(transaction.costs(),engine.metrics(T0+3000).cost);
        assertEquals(revision, engine.revision());
        return row;
    }

    @Test public void shaftsRespectEachVerifiedLogsYieldAndRetainKnife()
    {
        for (int[] variant : new int[][] {{1511,15},{1521,30},{1519,45},{1517,60},{1515,75},{1513,90},{19669,105}})
            merged(engine(), receipt("Fletching", leg(variant[0],"Logs",-2),
                leg(ItemID.ARROW_SHAFT,"Arrow shaft",2L*variant[1])), ItemID.ARROW_SHAFT,"Arrow shaft",2L*variant[1]);
    }

    @Test public void feathersTipsAndStringsUseActualBatchOutputCounts()
    {
        merged(engine(), receipt("Fletching",leg(52,"Arrow shaft",-150),leg(314,"Feather",-150),
            leg(53,"Headless arrow",150)),53,"Headless arrow",150);
        merged(engine(), receipt("Fletching",leg(53,"Headless arrow",-150),leg(39,"Bronze arrowtips",-150),
            leg(882,"Bronze arrow",150)),882,"Bronze arrow",150);
        merged(engine(), receipt("Fletching",leg(819,"Bronze dart tip",-100),leg(314,"Feather",-100),
            leg(806,"Bronze dart",100)),806,"Bronze dart",100);
        merged(engine(), receipt("Fletching",leg(9142,"Mithril bolts",-100),leg(9189,"Sapphire bolt tips",-100),
            leg(9337,"Sapphire bolts",100)),9337,"Sapphire bolts",100);
        merged(engine(), receipt("Fletching",leg(48,"Longbow (u)",-14),leg(1777,"Bow string",-14),
            leg(839,"Longbow",14)),839,"Longbow",14);
        merged(engine(), receipt("Fletching",leg(9465,"Runite crossbow (u)",-14),leg(9438,"Crossbow string",-14),
            leg(9185,"Rune crossbow",14)),9185,"Rune crossbow",14);
    }

    @Test public void unfinishedPotionsAndMeasuredBonusOrSavedSecondaryAreBottles()
    {
        merged(engine(), receipt("Herblore",leg(257,"Ranarr weed",-14),leg(227,"Vial of water",-14),
            leg(99,"Ranarr potion (unf)",14)),99,"Ranarr potion (unf)",14);
        for (int[] potion : new int[][] {{99,231,139,2434},{3004,223,3026,3024},{3002,6693,6687,6685}})
            for (boolean bonus : new boolean[] {false,true})
                for (int secondaries : new int[] {0,12,14})
                {
                    int output=bonus?potion[3]:potion[2];
                    List<Flow> flows=new ArrayList<>();
                    flows.add(leg(potion[0],"Unfinished potion",-14));
                    if(secondaries>0) flows.add(leg(potion[1],"Secondary",-secondaries));
                    flows.add(leg(output,"Potion("+(bonus?4:3)+")",14));
                    merged(engine(),receipt("Herblore",flows.toArray(new Flow[0])),output,
                        "Potion("+(bonus?4:3)+")",14);
                }
    }

    @Test public void bronzeSteelCannonballsAndTipsAreMeasuredOutputs()
    {
        merged(engine(),receipt("Smelting",leg(436,"Copper ore",-14),leg(438,"Tin ore",-14),
            leg(2349,"Bronze bar",14)),2349,"Bronze bar",14);
        merged(engine(),receipt("Smelting",leg(440,"Iron ore",-9),leg(453,"Coal",-18),
            leg(2353,"Steel bar",9)),2353,"Steel bar",9);
        merged(engine(),receipt("Smithing",leg(2353,"Steel bar",-2),leg(2,"Steel cannonball",8)),2,"Steel cannonball",8);
        merged(engine(),receipt("Fletching",leg(1607,"Sapphire",-28),leg(9189,"Sapphire bolt tips",336)),9189,"Sapphire bolt tips",336);
    }

    @Test public void gemsSpinningGlassJewelleryAndBattlestavesShareTheSameRule()
    {
        merged(engine(),receipt("Crafting",leg(1623,"Uncut sapphire",-14),leg(1607,"Sapphire",14)),1607,"Sapphire",14);
        merged(engine(),receipt("Crafting",leg(1779,"Flax",-14),leg(1777,"Bow string",14)),1777,"Bow string",14);
        merged(engine(),receipt("Crafting",leg(1737,"Wool",-14),leg(1759,"Ball of wool",14)),1759,"Ball of wool",14);
        merged(engine(),receipt("Crafting",leg(1775,"Molten glass",-14),leg(229,"Vial",14)),229,"Vial",14);
        merged(engine(),receipt("Crafting",leg(2357,"Gold bar",-14),leg(1607,"Sapphire",-14),
            leg(1637,"Sapphire ring",14)),1637,"Sapphire ring",14);
        merged(engine(),receipt("Crafting",leg(1391,"Battlestaff",-14),leg(ItemID.WATER_ORB,"Water orb",-14),
            leg(1395,"Water battlestaff",14)),1395,"Water battlestaff",14);
    }

    @Test public void cakeReturnsTinAndPreparationReturnsVessels()
    {
        merged(engine(),receipt("Cooking",leg(1889,"Uncooked cake",-14),leg(1891,"Cake",14),
            leg(1887,"Cake tin",14)),1891,"Cake",14);
        merged(engine(),receipt("Cooking",leg(1953,"Pastry dough",-14),leg(2313,"Pie dish",-14),
            leg(2315,"Pie shell",14)),2315,"Pie shell",14);
        merged(engine(),receipt("Cooking",leg(6701,"Baked potato",-14),leg(6697,"Pat of butter",-14),
            leg(6703,"Potato with butter",14)),6703,"Potato with butter",14);
    }

    @Test public void unrelatedLegsWrongRatiosToolLossAndMixedDosesPreventMerging()
    {
        for (Flow extra : new Flow[] {leg(560,"Death rune",1),leg(385,"Shark",-1),leg(946,"Knife",-1)})
        {
            Engine e=engine();
            Transaction row=receipt("Fletching",leg(52,"Arrow shaft",-150),leg(314,"Feather",-150),leg(53,"Headless arrow",150),extra);
            e.session().addTransaction(row,2_000,false);
            assertTrue(LiveSnapshot.capture(e,T0+3000,LiveContext.NONE).recent.size()>1);
        }
        for (Transaction row : List.of(receipt("Fletching",leg(52,"Arrow shaft",-150),leg(314,"Feather",-149),leg(53,"Headless arrow",150)),
            receipt("Herblore",leg(99,"Ranarr potion (unf)",-14),leg(231,"Snape grass",-14),leg(139,"Prayer potion(3)",13),leg(2434,"Prayer potion(4)",1))))
        {
            Engine e=engine();e.session().addTransaction(row,2000,false);
            assertTrue(LiveSnapshot.capture(e,T0+3000,LiveContext.NONE).recent.size()>1);
        }
    }

    @Test public void reviewTransferUnknownAndCorrectionsRetainTheirBoundaries()
    {
        for (String boundary : List.of("review","transfer","unknown","corrected","split"))
        {
            Flow output=leg(53,"Headless arrow",150);
            if(boundary.equals("unknown")) output=new Flow(53,"Headless arrow",150,0,0,PriceSource.UNPRICED);
            Transaction row=receipt("Fletching",leg(52,"Arrow shaft",-150),leg(314,"Feather",-150),output);
            if(boundary.equals("review")) row=new Transaction(T0+600,TransactionType.UNCERTAIN,Context.GENERIC,"","",false,row.flows(),Confidence.UNCERTAIN,"",null);
            if(boundary.equals("transfer")) row=new Transaction(T0+600,TransactionType.TRANSFER,Context.TRANSFER,"","",false,row.flows(),Confidence.CONFIRMED,"",null);
            if(boundary.equals("corrected")) row.applyCorrection(Correction.COST,"fixture");
            if(boundary.equals("split")) row.stampSplit("Split keep 1/2");
            Engine e=engine(); e.session().addTransaction(row,2000,false);
            List<Semantics.Group> groups=SemanticProbe.capture(List.of(row),List.of(),"",null).groups;
            assertTrue(boundary,groups.stream().noneMatch(g->g.entries.size()>1));
        }
    }

    @Test public void repeatedGroupingFoldsGrossBucketsThroughSaturation()
    {
        Engine e=engine();
        long big=4_000_000_000_000_000_000L;
        for(int i=0;i<3;i++)
        {
            Transaction t=receipt("Fletching",new Flow(52,"Arrow shaft",-1,1,-1),
                new Flow(314,"Feather",-1,1,-1),new Flow(53,"Headless arrow",1,big,big));
            e.session().addTransaction(t,2000,false);
        }
        LiveSnapshot live=LiveSnapshot.capture(e,T0+3000,LiveContext.NONE);
        assertEquals(1,live.recent.size());
        assertEquals(Long.MAX_VALUE-6,live.net);
        assertEquals(live.net,live.recent.get(0).net());
        assertEquals(3,live.recent.get(0).quantity);
        assertEquals(3,live.recent.get(0).receipts.size());
    }

    @Test public void groupedRemovalUndoAndReloadKeepEveryCapturedPrice()
    {
        Engine e=engine();
        for(int i=0;i<2;i++) e.session().addTransaction(receipt("Fletching",leg(52,"Arrow shaft",-150),
            leg(314,"Feather",-150),leg(53,"Headless arrow",150)),2000,false);
        LiveSnapshot live=LiveSnapshot.capture(e,T0+3000,LiveContext.NONE);
        assertEquals(1,live.recent.size());
        List<String> ids=new ArrayList<>(live.recent.get(0).receipts);
        String original=JsonCodec.gson().toJson(e.session().transactions());
        assertTrue(e.correctEntries(ids,Correction.IGNORE));
        assertEquals(0,e.metrics(T0+3000).net);
        Engine restored=engine();restored.restore(SaveCopies.copy(e.savedState()),T0+3000);
        assertTrue(restored.undoCorrection());
        assertEquals(live.net,restored.metrics(T0+3001).net);
        assertEquals(original,JsonCodec.gson().toJson(restored.session().transactions()));
        assertEquals(1,LiveSnapshot.capture(restored,T0+3001,LiveContext.NONE).recent.size());
    }

    @Test public void alternativeInputsMergeByOutputWithoutLosingGrossBuckets()
    {
        Engine e=engine();
        e.session().addTransaction(receipt("Fletching",leg(1511,"Logs",-2),leg(52,"Arrow shaft",30)),2000,false);
        e.session().addTransaction(receipt("Fletching",leg(1521,"Oak logs",-1),leg(52,"Arrow shaft",30)),2000,false);
        LiveSnapshot live=LiveSnapshot.capture(e,T0+3000,LiveContext.NONE);
        assertEquals(1,live.recent.size());
        assertEquals(60,live.recent.get(0).quantity);
        assertEquals(2,live.recent.get(0).receipts.size());
        assertEquals(399,live.recent.get(0).net());
    }

    @Test public void catalogueIdsAreTheirNamedGamevalAndEachCompleteShapeIsAccepted() throws Exception
    {
        for(String[] row:GameData.rows("production_recipes"))
        {
            String[] parts=row[3].split(" "), names=row[5].split(",");
            assertEquals(parts.length,names.length);
            assertTrue(Integer.parseInt(row[2])>0);
            List<Flow> flows=new ArrayList<>();
            for(int i=0;i<parts.length;i++)
            {
                String[] part=parts[i].split(":");
                int id=Integer.parseInt(part[0]);
                assertEquals(names[i],id,ItemID.class.getField(names[i]).getInt(null));
                long quantity=part[1].equals("?")?-1:Long.parseLong(part[1]);
                flows.add(leg(id,names[i],quantity));
            }
            assertNotNull(row[4],ProductionRecipes.match(flows));
            flows.add(leg(995,"Coins",1));
            assertNull("unexplained cash cannot enter a recipe: "+row[4],ProductionRecipes.match(flows));
        }
    }

    @Test public void dairyAndDelayedWineDoNotAcquireNewRecipeMerging()
    {
        for(int dairy:new int[]{1927,2130,6697,24788})
            assertNull("churning stays outside this checkpoint",ProductionRecipes.match(List.of(
                leg(dairy,"Dairy ingredient",-1),leg(1985,"Cheese",1))));
        assertNull(ProductionRecipes.match(List.of(leg(1927,"Bucket of milk",-1),
            leg(1925,"Bucket",1),leg(1985,"Cheese",1))));
        assertNull(ProductionRecipes.match(List.of(leg(1937,"Jug of water",-1),
            leg(1987,"Grapes",-1),leg(1995,"Unfermented wine",1))));
    }

    @Test public void remainingPriorityVariantsUseVerifiedIndividualSteps()
    {
        merged(engine(),receipt("Fletching",leg(53,"Headless arrow",-150),leg(44,"Rune arrowtips",-150),
            leg(892,"Rune arrow",150)),892,"Rune arrow",150);
        merged(engine(),receipt("Fletching",leg(824,"Rune dart tip",-100),leg(314,"Feather",-100),
            leg(811,"Rune dart",100)),811,"Rune dart",100);
        merged(engine(),receipt("Fletching",leg(9456,"Blurite crossbow (u)",-14),leg(9438,"Crossbow string",-14),
            leg(9176,"Blurite crossbow",14)),9176,"Blurite crossbow",14);
        merged(engine(),receipt("Crafting",leg(6571,"Uncut onyx",-14),leg(6573,"Onyx",14)),6573,"Onyx",14);
        merged(engine(),receipt("Crafting",leg(19496,"Uncut zenyte",-14),leg(19493,"Zenyte",14)),19493,"Zenyte",14);
        merged(engine(),receipt("Crafting",leg(21347,"Amethyst",-2),leg(21338,"Amethyst bolt tips",30)),21338,"Amethyst bolt tips",30);
        merged(engine(),receipt("Smithing",leg(2349,"Bronze bar",-2),leg(31906,"Bronze cannonball",8)),31906,"Bronze cannonball",8);
        merged(engine(),receipt("Cooking",leg(1923,"Bowl",-14),leg(361,"Tuna",-14),
            leg(7086,"Chopped tuna",14)),7086,"Chopped tuna",14);
    }
}
