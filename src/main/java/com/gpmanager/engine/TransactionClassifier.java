package com.gpmanager;
import java.util.List;
import javax.inject.Singleton;
import static com.gpmanager.Context.*;
import static com.gpmanager.TransactionType.*;
@Singleton
class TransactionClassifier {
TransactionType classify(Context context, List<Flow> flows) {
if (context == Context.TRANSFER) return TransactionType.TRANSFER;
boolean hasGain = false;
boolean hasCost = false;
for (Flow flow : flows) {
// Direction comes from quantity. An unavailable unit price produces a
// zero value, but it is still a real gain or cost that belongs in Review.
hasGain |= flow.isGain();
hasCost |= flow.isCost();
}
if (context == MARKET) return TRADE;
if (context == Context.PK_LOOT && hasGain) return TransactionType.PK_LOOT;
if (context == PK_DEATH && hasCost) return PK_DEATH_LOSS;
if (context == Context.LOOT && hasGain) return TransactionType.LOOT;
if (hasGain && hasCost) {
return context == PRODUCTION ? PROCESSING : UNCERTAIN;
}
if (hasGain) return GAIN;
if (hasCost) return CONSUMPTION;
return ADJUSTMENT;
}
}
