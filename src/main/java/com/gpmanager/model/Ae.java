package com.gpmanager;
import static java.lang.Math.*;
/** Saturating long/int arithmetic shared by the accounting model; overflow clamps toward the sign of the addend. */
class Ae {
/** The wall clock every accounting read uses; the rework's replay sets it to recorded time. */
static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
static long now() {
 return clock.getAsLong();
}

/** Absolute value that saturates instead of overflowing on {@code Long.MIN_VALUE}. */
/** The value, or zero when it is negative. */
static long nonNeg(long /* value */a) {
 return max(0L, a);
}

static long abs(long /* value */a) {
 return a == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(a);
}

static long safeAdd(long /* left */a, long /* right */b) {
 try {
  return addExact(a, b);
 } catch (ArithmeticException /* ex */c) {
  return b >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

static long aha(long /* left */a, long /* right */b) {
 try {
  return subtractExact(a, b);
 } catch (ArithmeticException /* ex */c) {
  return b < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

static long agz(long /* left */a, long /* right */b) {
 try {
  return multiplyExact(a, b);
 } catch (ArithmeticException /* ex */c) {
  return (a >= 0L) == (b >= 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

/** A long clamped into the int range (unit prices). */
static int toInt(long /* value */a) {
 return (int) max(Integer.MIN_VALUE, min(Integer.MAX_VALUE, a));
}

/** Non-negative count sum saturating at {@link Integer#MAX_VALUE}. */
static int agy(int /* left */a, int /* right */b) {
 long /* sum */c = (long) max(0, a) + max(0, b);
 return c > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) c;
}
}
