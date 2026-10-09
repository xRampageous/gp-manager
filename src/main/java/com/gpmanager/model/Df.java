package com.gpmanager;
import java.math.BigInteger;
import static com.gpmanager.Ae.nonNeg;
import static java.lang.Math.*;
/**
* Integer-only pooled basis math shared by the known-basis ledger and the read model.
*
* <p>Every allocation is floor-based and the remainder stays with the other side, so parts always
* sum to the whole: no invented GP, no decimals, no drift. All multiplication is long-safe.</p>
*/
class Df {
/** Floor-share of a pooled quantity/basis pair; consuming everything takes the exact remainder. */
static long ayb(long /* totalQty */a, long /* totalBasisGp */b, long /* consumeQty */c) {
 long /* qty */d = nonNeg(a);
 long /* basis */e = nonNeg(b);
 long /* consume */f = nonNeg(c);
 if (d <= 0L || e <= 0L || f <= 0L) return 0L;
 if (f >= d) return e;
 return aav(e, f, d);
}

/**
* Known-coverage share of an observed settlement: the known portion takes the floor share and
* the unknown remainder receives the integer difference, so both always sum to the settlement.
*/
static long yl(long /* totalSettlementGp */a, long /* realizedQty */b, long /* knownQty */c) {
 long /* total */d = nonNeg(a);
 long /* realized */e = nonNeg(b);
 long /* known */f = nonNeg(min(c, e));
 if (e <= 0L || f <= 0L) return 0L;
 if (f >= e) return d;
 return aav(d, f, e);
}

/** Long-safe floor(value × numerator / denominator) for non-negative inputs. */
static long aav(long /* value */a, long /* numerator */b, long /* denominator */c) {
 if (c <= 0L || b <= 0L || a <= 0L) return 0L;
 if (b >= c) return a;
 BigInteger /* product */d = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
 BigInteger /* share */e = d.divide(BigInteger.valueOf(c));
 // b < c, so the share is below a and fits a long.
 return e.longValue();
}
}
