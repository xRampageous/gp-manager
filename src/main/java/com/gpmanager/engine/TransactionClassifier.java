package com.gpmanager;
import java.util.List;
import javax.inject.Singleton;
import static com.gpmanager.Aj.*;
import static com.gpmanager.Ai.*;
@Singleton
class TransactionClassifier {
Ai classify(Aj /* context */b, List<Ab> /* flows */c) {
 if (b == Aj.TRANSFER) return Ai.TRANSFER;
 boolean /* hasGain */d = false;
 boolean /* asg */e = false;
 for (Ab /* flow */f : c) {
  // Direction comes from quantity. An unavailable unit price produces a
  // zero value, but it is still a real gain or cost that belongs in Review.
  d |= f.isGain();
  e |= f.isCost();
 }
 if (b == MARKET) return TRADE;
 if (b == Aj.PK_LOOT && d) return Ai.PK_LOOT;
 if (b == PK_DEATH && e) return PK_DEATH_LOSS;
 if (b == Aj.LOOT && d) return Ai.LOOT;
 if (d && e) {
  return b == PRODUCTION ? PROCESSING : UNCERTAIN;
 }
 if (d) return GAIN;
 if (e) return CONSUMPTION;
 return ADJUSTMENT;
}
}
