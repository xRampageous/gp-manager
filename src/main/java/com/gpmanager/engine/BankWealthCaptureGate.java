package com.gpmanager.engine;

/** Requires fresh bank-container evidence plus two identical reads per visit. */
public final class BankWealthCaptureGate
{
    private boolean open;
    private boolean captured;
    private boolean freshContentsObserved;
    private String candidateHash = "";
    private int stableReads;

    public void beginVisit()
    {
        open = true;
        captured = false;
        freshContentsObserved = false;
        candidateHash = "";
        stableReads = 0;
    }

    public void endVisit()
    {
        open = false;
        captured = false;
        freshContentsObserved = false;
        candidateHash = "";
        stableReads = 0;
    }

    /**
     * Marks a BANK container event observed while the main bank interface is visible.
     * Reads before this event may be a cached or loading container and cannot capture.
     */
    public void markBankContentsChanged()
    {
        if (!open || captured) return;
        freshContentsObserved = true;
        candidateHash = "";
        stableReads = 0;
    }

    /** Returns true once per open visit, after fresh evidence and two adjacent equal reads. */
    public boolean observe(String contentHash)
    {
        if (!open || captured || !freshContentsObserved
            || contentHash == null || contentHash.trim().isEmpty())
        {
            return false;
        }
        String hash = contentHash.trim();
        if (hash.equals(candidateHash))
        {
            stableReads++;
        }
        else
        {
            candidateHash = hash;
            stableReads = 1;
        }
        if (stableReads < 2)
        {
            return false;
        }
        captured = true;
        return true;
    }

    public boolean isCaptured() { return captured; }
}
