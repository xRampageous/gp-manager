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
static long ayb(long totalQty, long totalBasisGp, long consumeQty) {
long qty = nonNeg(totalQty);
long basis = nonNeg(totalBasisGp);
long consume = nonNeg(consumeQty);
if (qty <= 0L || basis <= 0L || consume <= 0L) {
return 0L;
}
if (consume >= qty) {
return basis;
}
return aav(basis, consume, qty);
}
/**
* Known-coverage share of an observed settlement: the known portion takes the floor share and
* the unknown remainder receives the integer difference, so both always sum to the settlement.
*/
static long yl(long totalSettlementGp, long realizedQty, long knownQty) {
long total = nonNeg(totalSettlementGp);
long realized = nonNeg(realizedQty);
long known = nonNeg(min(knownQty, realized));
if (realized <= 0L || known <= 0L) {
return 0L;
}
if (known >= realized) {
return total;
}
return aav(total, known, realized);
}
/** Long-safe floor(value × numerator / denominator) for non-negative inputs. */
static long aav(long value, long numerator, long denominator) {
if (denominator <= 0L || numerator <= 0L || value <= 0L) {
return 0L;
}
if (numerator >= denominator) {
return value;
}
BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
BigInteger share = product.divide(BigInteger.valueOf(denominator));
return share.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
? Long.MAX_VALUE : share.longValue();
}
}
