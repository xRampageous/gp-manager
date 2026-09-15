package com.gpmanager.model;

/** Pairwise detached comparison; nullable fields are unavailable, never zero. */
public final class SessionComparison
{
    private final String leftSessionId, rightSessionId;
    private final Long leftDurationMillis, rightDurationMillis, leftActiveMillis, rightActiveMillis;
    private final Long leftNet, rightNet, leftRate, rightRate;
    private final Long leftGains, rightGains, leftSupplies, rightSupplies, leftLoss, rightLoss;
    private final Integer leftKills, rightKills, leftDeaths, rightDeaths;
    private final String status;
    private final java.util.List<ItemDelta> topItemDeltas;
    private final ActivityAverageSnapshot sameNameAverage;

    /** One item's net on each side; sorted by the size of the difference. */
    public static final class ItemDelta
    {
        private final int itemId;
        private final String itemName;
        private final long leftNet, rightNet;
        public ItemDelta(int itemId, String itemName, long leftNet, long rightNet)
        { this.itemId = itemId; this.itemName = itemName == null ? "" : itemName; this.leftNet = leftNet; this.rightNet = rightNet; }
        public int getItemId() { return itemId; }
        public String getItemName() { return itemName; }
        public long getLeftNet() { return leftNet; }
        public long getRightNet() { return rightNet; }
        public long getDelta() { return rightNet - leftNet; }
    }

    public SessionComparison(String leftSessionId, String rightSessionId, Long leftDurationMillis, Long rightDurationMillis,
        Long leftActiveMillis, Long rightActiveMillis, Long leftNet, Long rightNet, Long leftRate, Long rightRate,
        Long leftGains, Long rightGains, Long leftSupplies, Long rightSupplies, Long leftLoss, Long rightLoss,
        Integer leftKills, Integer rightKills, Integer leftDeaths, Integer rightDeaths, String status)
    {
        this(leftSessionId, rightSessionId, leftDurationMillis, rightDurationMillis, leftActiveMillis, rightActiveMillis, leftNet, rightNet,
            leftRate, rightRate, leftGains, rightGains, leftSupplies, rightSupplies, leftLoss, rightLoss, leftKills, rightKills, leftDeaths,
            rightDeaths, status, null, null);
    }

    public SessionComparison(String leftSessionId, String rightSessionId, Long leftDurationMillis, Long rightDurationMillis,
        Long leftActiveMillis, Long rightActiveMillis, Long leftNet, Long rightNet, Long leftRate, Long rightRate,
        Long leftGains, Long rightGains, Long leftSupplies, Long rightSupplies, Long leftLoss, Long rightLoss,
        Integer leftKills, Integer rightKills, Integer leftDeaths, Integer rightDeaths, String status,
        java.util.List<ItemDelta> topItemDeltas, ActivityAverageSnapshot sameNameAverage)
    { this.topItemDeltas = topItemDeltas == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(topItemDeltas));
      this.sameNameAverage = sameNameAverage;
      this.leftSessionId=leftSessionId;this.rightSessionId=rightSessionId;this.leftDurationMillis=leftDurationMillis;this.rightDurationMillis=rightDurationMillis;this.leftActiveMillis=leftActiveMillis;this.rightActiveMillis=rightActiveMillis;this.leftNet=leftNet;this.rightNet=rightNet;this.leftRate=leftRate;this.rightRate=rightRate;this.leftGains=leftGains;this.rightGains=rightGains;this.leftSupplies=leftSupplies;this.rightSupplies=rightSupplies;this.leftLoss=leftLoss;this.rightLoss=rightLoss;this.leftKills=leftKills;this.rightKills=rightKills;this.leftDeaths=leftDeaths;this.rightDeaths=rightDeaths;this.status=status==null?"UNAVAILABLE":status; }
    public String getLeftSessionId(){return leftSessionId;} public String getRightSessionId(){return rightSessionId;}
    public Long getLeftDurationMillis(){return leftDurationMillis;} public Long getRightDurationMillis(){return rightDurationMillis;}
    public Long getLeftActiveMillis(){return leftActiveMillis;} public Long getRightActiveMillis(){return rightActiveMillis;}
    public Long getLeftNet(){return leftNet;} public Long getRightNet(){return rightNet;} public Long getLeftRate(){return leftRate;} public Long getRightRate(){return rightRate;}
    public Long getLeftGains(){return leftGains;} public Long getRightGains(){return rightGains;} public Long getLeftSupplies(){return leftSupplies;} public Long getRightSupplies(){return rightSupplies;} public Long getLeftLoss(){return leftLoss;} public Long getRightLoss(){return rightLoss;}
    public Integer getLeftKills(){return leftKills;} public Integer getRightKills(){return rightKills;} public Integer getLeftDeaths(){return leftDeaths;} public Integer getRightDeaths(){return rightDeaths;}
    public String getStatus(){return status;} public boolean isAvailable(){return "AVAILABLE".equals(status);}
    /** Up to five items by |right − left|; null when either side's per-item split is unavailable. */
    public java.util.List<ItemDelta> getTopItemDeltas() { return topItemDeltas; }
    public boolean isTopItemDeltasAvailable() { return topItemDeltas != null; }
    /** The left session's same-name average (excluding the left session itself); null when there is none. */
    public ActivityAverageSnapshot getSameNameAverage() { return sameNameAverage; }
}
