package com.gpmanager;
import static java.lang.Math.*;
/** Saturating long/int arithmetic shared by the accounting model; overflow clamps toward the sign of the addend. */
class Ae {
/** Absolute value that saturates instead of overflowing on {@code Long.MIN_VALUE}. */
/** The value, or zero when it is negative. */
static long nonNeg(long value) {
 return max(0L, value);
}

static long abs(long value) {
 return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
}

static long safeAdd(long left, long right) {
 try {
  return addExact(left, right);
 } catch (ArithmeticException ex) {
  return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

static long aha(long left, long right) {
 try {
  return subtractExact(left, right);
 } catch (ArithmeticException ex) {
  return right < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

static long agz(long left, long right) {
 try {
  return multiplyExact(left, right);
 } catch (ArithmeticException ex) {
  return (left >= 0L) == (right >= 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

/** A long clamped into the int range (unit prices). */
static int toInt(long value) {
 return (int) max(Integer.MIN_VALUE, min(Integer.MAX_VALUE, value));
}

/** Non-negative count sum saturating at {@link Integer#MAX_VALUE}. */
static int agy(int left, int right) {
 long sum = (long) max(0, left) + max(0, right);
 return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
}
}
