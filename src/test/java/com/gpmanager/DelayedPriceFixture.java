package com.gpmanager;
import java.util.*;
import net.runelite.api.ItemComposition;

/** Detached potion receipts and mutable prices; never loads player data. */
final class DelayedPriceFixture {
  final Map<Integer,Integer> prices=new HashMap<>();String overrides="";
  final Valuation service;final Engine engine;
  DelayedPriceFixture() {
   var config=new GpManagerConfig(){public int stabilizationTicks(){return 1;}public String manualPriceOverrides(){return overrides;}};
   service=ModelProbe.valuation(config,id->ReviewFixesTest.proxy(ItemComposition.class,Map.of("getName",HighAlchSipReviewTest.NAMES.getOrDefault(id,"Item "+id),"isTradeable",true)),id->prices.getOrDefault(id,0));
   engine=new Engine(service,new TransactionClassifier(),config);
  }
  Transaction sip() {
   engine.ensureSession(1700000000000L);engine.setBaseline(new ContainerSnapshot(Map.of(2434,1L)));engine.noteConsume(2434,10,false,ActionKind.DRINK, null);engine.markDirty();
   var after=new ContainerSnapshot(Map.of(139,1L));for(int i=1;i<6;i++)engine.processIfDirty(after,1700000000000L+i*600,null);
   return engine.session().transactions().get(0);
  }
  void ready(){prices.putAll(Map.of(2434,3600,139,2600,141,1900,143,1000));}
}
