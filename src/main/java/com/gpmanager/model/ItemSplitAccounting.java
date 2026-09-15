package com.gpmanager.model;

/**
 * Personal item-split provenance helpers. Splits adjust personal Net share via
 * the correction timeline — never Party settlement, never fake Used eats.
 */
public final class ItemSplitAccounting
{
    public static final String SPLIT_SHARE_MARK = "Split share";
    public static final String SPLIT_KEEP_PREFIX = "Split keep ";

    private ItemSplitAccounting()
    {
    }

    public static String reason(long keepQuantity, long totalQuantity, String optionalNote)
    {
        long total = Math.max(0L, totalQuantity);
        long keep = Math.max(0L, Math.min(keepQuantity, total));
        StringBuilder sb = new StringBuilder(SPLIT_KEEP_PREFIX)
            .append(keep).append('/').append(total)
            .append(" · ").append(SPLIT_SHARE_MARK);
        if (optionalNote != null)
        {
            String trimmed = optionalNote.trim();
            if (!trimmed.isEmpty())
            {
                sb.append(" · ").append(trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed);
            }
        }
        String value = sb.toString();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    public static boolean isSplitReason(String reasonOrExplanation)
    {
        if (reasonOrExplanation == null || reasonOrExplanation.isEmpty())
        {
            return false;
        }
        String lower = reasonOrExplanation.toLowerCase();
        return lower.contains("split keep") || lower.contains("split share");
    }

    public static long gainQuantity(ProfitTransaction transaction, int itemId)
    {
        if (transaction == null || itemId <= 0)
        {
            return 0L;
        }
        long total = 0L;
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow != null && flow.getItemId() == itemId && flow.getQuantityDelta() > 0L)
            {
                total += flow.getQuantityDelta();
            }
        }
        return total;
    }
}
