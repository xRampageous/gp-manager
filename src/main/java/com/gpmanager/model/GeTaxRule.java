package com.gpmanager;
import java.util.Set;
/**
* The one authoritative GE sell-tax rule shared by custody settlement and the read model.
*
* <p>Ordinary taxed items pay 2% of the sale price rounded DOWN per item (so sub-50 gp items pay
* nothing), capped at 5,000,000 gp per item. The wiki's Grand Exchange tax exemption list (checked
* 2026-09-13, ids resolved against the pinned RuneLite gameval {@code ItemID} constants) pays
* nothing regardless of price; a real client Collect All of the owner's seven-sale batch proved
* the exempt low-level food case (cooked chicken sold at 69 gp paid no tax). Tax is never
* approximated as a stack-wide 2%: it rounds per ITEM. Everything is integer-only and long-safe;
* there is no floating point here and no second exemption list anywhere else in the codebase.</p>
*/
class GeTaxRule {
/** Per-item cap on the GE sell tax. */
static final long MAX_TAX_PER_ITEM_GP = 5_000_000L;
/**
* Items exempt from the GE convenience fee, per the wiki's Grand Exchange tax exemption list.
* Items under 50 gp pay nothing through rounding anyway, so most tools here matter only when
* their price spikes.
*/
static final Set<Integer> EXEMPT_IDS = Ak.byId("d4").keySet();
/** True when the wiki exemption list pays no GE tax on this item at any price. */
static boolean awj(int /* itemId */a) {
 return EXEMPT_IDS.contains(a);
}

/** Tax on one item sold at {@code unitPrice}; 0 when the price is unknown or below the floor. */
static long adr(long /* unitPrice */a) {
 if (a <= 0L) return 0L;
 return Math.min(MAX_TAX_PER_ITEM_GP, a / 50L);
}

/** Item-aware per-item tax; an exempt item pays nothing at any price. */
static long adr(int /* itemId */a, long /* unitPrice */b) {
 return awj(a) ? 0L : adr(b);
}

/** Tax for a whole stack sold at one unit price; long-safe, 0 when unpriceable. */
static long axc(long /* unitPrice */a, long /* quantity */b) {
 long /* perItem */c = adr(a);
 if (c <= 0L || b <= 0L || c > Long.MAX_VALUE / b) return 0L;
 return c * b;
}

/** Item-aware stack tax; an exempt item pays nothing at any price. */
static long axc(int /* itemId */a, long /* unitPrice */b, long /* quantity */c) {
 return awj(a) ? 0L : axc(b, c);
}

/**
* Tax implied by an exact uniform execution: only a gross divisible by the quantity proves a
* single per-item sale price, so the tax is derived from that unit price. Returns 0 when the
* per-item price cannot be proven (including genuinely tax-free stacks).
*/
static long ake(long /* grossGp */a, long /* quantity */b) {
 if (a <= 0L || b <= 0L || a % b != 0L) return 0L;
 return axc(a / b, b);
}

/** Item-aware uniform-execution tax; an exempt item pays nothing at any price. */
static long ake(int /* itemId */a, long /* grossGp */b, long /* quantity */c) {
 return awj(a) ? 0L : ake(b, c);
}

/** Sentinel: no proven per-item price exists, so no tax can be asserted for an execution. */
static final long UNPROVABLE_TAX = -1L;
/**
* The uniform per-item tax of one execution with unprovability distinguished: {@link
* #UNPROVABLE_TAX} when the per-item price cannot be proven (a non-exempt gross that is not
* divisible by the quantity), otherwise the exact tax, which may legitimately be 0 (an exempt
* item, or a unit price below the per-item floor). Read-only; the tax law itself is unchanged:
* integer only, per-item floor, 5,000,000 gp/item cap, current exemption IDs.
*/
static long adn(int /* itemId */a, long /* grossGp */b, long /* quantity */c) {
 if (b <= 0L || c <= 0L) return UNPROVABLE_TAX;
 if (awj(a)) return 0L;
 if (b % c != 0L) return UNPROVABLE_TAX;
 long /* perItem */d = adr(b / c);
 if (d > 0L && c > Long.MAX_VALUE / d) return UNPROVABLE_TAX;
 return d * c;
}

/**
* The proven sell tax of one settled execution: the exact positive gap between the observed
* gross execution and the observed Received cash, accepted only when it equals the uniform
* per-item rule for that execution. Zero means nothing is proven — a genuinely tax-free
* stack, an exempt item, a non-uniform execution, or a gap that does not match the rule.
*
* <p>Booking and the read model share this one predicate, so a booked tax can never disagree
* with the receipt that explains it. It is a tax proof, not a result: it never implies the
* prior tracked value of the sold units.</p>
*/
static long jv(int /* itemId */a, long /* grossGp */b, long /* quantity */c, long /* receivedGp */d) {
 if (b <= 0L || c <= 0L || d < 0L) return 0L;
 long /* gap */e = b - d;
 if (e <= 0L) return 0L;
 long /* expected */f = ake(a, b, c);
 return f > 0L && e == f ? e : 0L;
}
}
