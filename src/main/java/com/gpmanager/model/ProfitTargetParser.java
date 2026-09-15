package com.gpmanager.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalLong;

/** Parses a positive whole-GP owner target without floating point rounding. */
public final class ProfitTargetParser
{
    private ProfitTargetParser() {}

    /** Returns a parsed positive whole-GP value, or zero for empty/invalid input. */
    public static long parseOrZero(String input)
    {
        return parse(input).orElse(0L);
    }

    public static OptionalLong parse(String input)
    {
        if (input == null)
        {
            return OptionalLong.empty();
        }
        String value = input.trim().toLowerCase(Locale.ROOT).replace(",", "");
        if (value.isEmpty() || value.startsWith("-") || value.startsWith("+"))
        {
            return OptionalLong.empty();
        }
        BigDecimal multiplier = BigDecimal.ONE;
        if (value.endsWith("b"))
        {
            multiplier = BigDecimal.valueOf(1_000_000_000L);
            value = value.substring(0, value.length() - 1);
        }
        else if (value.endsWith("m"))
        {
            multiplier = BigDecimal.valueOf(1_000_000L);
            value = value.substring(0, value.length() - 1);
        }
        else if (value.endsWith("k"))
        {
            multiplier = BigDecimal.valueOf(1_000L);
            value = value.substring(0, value.length() - 1);
        }
        try
        {
            BigDecimal parsed = new BigDecimal(value).multiply(multiplier).setScale(0, RoundingMode.UNNECESSARY);
            long amount = parsed.longValueExact();
            return amount > 0L ? OptionalLong.of(amount) : OptionalLong.empty();
        }
        catch (NumberFormatException | ArithmeticException ex)
        {
            return OptionalLong.empty();
        }
    }
}
