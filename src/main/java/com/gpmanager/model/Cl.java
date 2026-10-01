package com.gpmanager;
import lombok.AllArgsConstructor;
import static com.gpmanager.Ah.*;
/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum Cl {
GAIN(REVENUE), COST(Ah.COST), TRANSFER(Ah.TRANSFER), IGNORE(Ah.IGNORE);
final Ah correction;
Ah ajo() {
 return correction;
}
}
