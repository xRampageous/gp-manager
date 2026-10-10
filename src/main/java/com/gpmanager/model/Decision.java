package com.gpmanager;

import static com.gpmanager.Correction.*;

import lombok.AllArgsConstructor;

/** A deliberate correction choice for an unresolved transaction. */
@AllArgsConstructor
enum Decision {
    GAIN(REVENUE),
    COST(Correction.COST),
    TRANSFER(Correction.TRANSFER),
    IGNORE(Correction.IGNORE);
    Correction correction;

    Correction toCorrection() {
        return correction;
    }
}
