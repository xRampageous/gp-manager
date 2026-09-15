package com.gpmanager.model;

public final class HistoryQuery
{
    private final SessionCategory category;
    private final String searchText;
    private final HistoryDateRange dateRange;
    private final HistorySort sort;
    private final boolean favoritesOnly;

    public HistoryQuery(
        SessionCategory category,
        String searchText,
        HistoryDateRange dateRange,
        HistorySort sort,
        boolean favoritesOnly)
    {
        this.category = category == null ? SessionCategory.ALL : category;
        this.searchText = searchText == null ? "" : searchText.trim();
        this.dateRange = dateRange == null ? HistoryDateRange.ALL_TIME : dateRange;
        this.sort = sort == null ? HistorySort.NEWEST : sort;
        this.favoritesOnly = favoritesOnly;
    }

    public static HistoryQuery all()
    {
        return new HistoryQuery(
            SessionCategory.ALL,
            "",
            HistoryDateRange.ALL_TIME,
            HistorySort.NEWEST,
            false);
    }

    public SessionCategory getCategory() { return category; }
    public String getSearchText() { return searchText; }
    public HistoryDateRange getDateRange() { return dateRange; }
    public HistorySort getSort() { return sort; }
    public boolean isFavoritesOnly() { return favoritesOnly; }
}
