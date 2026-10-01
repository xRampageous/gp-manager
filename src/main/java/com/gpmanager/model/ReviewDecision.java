package com.gpmanager;
import lombok.AllArgsConstructor;
import static com.gpmanager.Correction.*;
/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum ReviewDecision {
GAIN(REVENUE), COST(Correction.COST), TRANSFER(Correction.TRANSFER), IGNORE(Correction.IGNORE);
final Correction correction;
Correction toCorrection() {
 return correction;
}
}
