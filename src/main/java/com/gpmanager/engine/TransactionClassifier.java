package com.gpmanager;
import java.util.List;
import javax.inject.Singleton;
import static com.gpmanager.Aj.*;
import static com.gpmanager.Ai.*;
@Singleton
class TransactionClassifier {
Ai classify(Aj context, List<Ab> flows) {
 if (context == Aj.TRANSFER) return Ai.TRANSFER;
 boolean hasGain = false;
 boolean asg = false;
 for (Ab flow : flows) {
  // Direction comes from quantity. An unavailable unit price produces a
  // zero value, but it is still a real gain or cost that belongs in Review.
  hasGain |= flow.isGain();
  asg |= flow.isCost();
 }
 if (context == MARKET) return TRADE;
 if (context == Aj.PK_LOOT && hasGain) return Ai.PK_LOOT;
 if (context == PK_DEATH && asg) return PK_DEATH_LOSS;
 if (context == Aj.LOOT && hasGain) return Ai.LOOT;
 if (hasGain && asg) {
  return context == PRODUCTION ? PROCESSING : UNCERTAIN;
 }
 if (hasGain) return GAIN;
 if (asg) return CONSUMPTION;
 return ADJUSTMENT;
}
}
