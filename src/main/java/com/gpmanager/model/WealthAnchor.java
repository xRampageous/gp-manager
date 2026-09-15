package com.gpmanager.model;

/** Reference point used by wealth-history comparisons. */
public enum WealthAnchor
{
    /** Compare the most recent two captured bank visits. */
    LAST_BANK_VISIT,
    /** Compare the latest capture to the last capture at or before today's start. */
    TODAY,
    /** Compare the latest capture to the last capture at or before 30 days ago. */
    THIRTY_DAYS,
    /** Compare the latest capture to the last capture at or before seven elapsed days ago. */
    SEVEN_DAYS
}
