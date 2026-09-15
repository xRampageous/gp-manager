# Engine API contract

Status: frozen for the Bento sidebar hand-off (pass 10B, 2026-09-15).

This document is the client contract.  It was produced from the `engine.` and
`persistence.` call sites under `src/main/java/com/gpmanager/ui/bento`.  A
**stable** entry point has detached, immutable return data and may be used by a
page snapshot.  A **may change** entry point is an action, compatibility adapter,
or a model whose fields may grow; callers must use the named availability/status
methods and must not infer missing values as zero.

## Rules that apply to every entry point

* All `GpManagerEngine` public reads and writes are synchronized on the engine
  monitor.  Return lists/maps/snapshots are detached or immutable.  The client
  must call them off the Swing EDT when a page snapshot can do so.
* Engine listeners are registered with `addAlertListener(Consumer<AlertEvent>)`.
  The engine records the event while locked, copies listeners, releases the lock,
  then calls each listener.  Listener failures are isolated.  Persistence writer
  callbacks likewise run from the writer thread against a detached `SavedState`;
  no engine lock is held while a callback is invoked.
* `null`, `UNAVAILABLE`, `PARTIAL`, an empty coverage flag, or an absent source is
  unavailable evidence, never zero.  Page code must show the coverage/status
  supplied by the model.
* SavedState schema is **23** and config schema is **31**.  Steps 44–47 did not
  add a persisted field or migration.

## Live

| Entry point | Contract | Status |
| --- | --- | --- |
| `getActiveSession()` / `getGeneralSession()` / `isCustomSessionActive()` | Detached session references used only for current chrome; a missing session is waiting/unavailable. | stable |
| `getMetrics(long now)` | Current session metrics; active time excludes pauses and stopped time. Coverage is carried by `SessionMetrics`. | stable |
| `getOverallToday(long now)` | Profile-local current-day aggregate; all figures and coverage are fail-closed. | stable |
| `getGoalDefinitions()`, `getGoalProgress(GoalDefinition,long)`, `getGoalProgress(long)` | Persisted goal definitions and derived progress. No goal means no progress, not zero. | stable |
| `getDeathReclaimStatus()`, `getPkMetrics()` | Reclaim and PvP read models; unknown place/finance dimensions remain unavailable. | stable |
| `isIdlePaused()`, `isStopped()` | Session state flags. | stable |
| `togglePause(long)`, `startCustomSession(String,SessionMode,long)`, `finishCustomSession(long[,SessionEndReason])`, `setActiveSessionCategory(SessionCategory)`, `setActiveSessionEndReason(SessionEndReason)`, `renameActiveSession(String)`, `setSessionIdleAutoEnd(int)` | User actions; each invalidates the appropriate read-model caches and persists through the normal coordinator. | may change |

## Ledger and Review

| Entry point | Contract | Status |
| --- | --- | --- |
| `getActiveSession()`, `getHistory()`, `getHistory(HistoryQuery,long)`, `getHistorySession(String)` | Detached session collections; Free play is owned by the profile but excluded from named-session lists. | stable |
| `getReviewInbox(long)` | Automatic uncertain/unpriced rows only. Review rows remain uncounted until an explicit decision. | stable |
| `correctTransaction(String,TransactionCorrection,long,String)`, `undoLastCorrection(long)`, `decideAll(ReviewDecision,Predicate<ReviewRow>,long)`, `undoLastTransaction(long)`, `restoreLastUndo(long)` | Explicit owner mutations on the shared correction/undo stack. | may change |
| `setHistorySessionFavorite`, `setHistorySessionExcluded`, `setHistorySessionCategory`, `setHistorySessionTags`, `setHistorySessionNotes`, `renameHistorySession`, `deleteHistorySession` | Metadata/history actions; they do not invent accounting evidence. | may change |

## Sessions, Insights and records

| Entry point | Contract | Status |
| --- | --- | --- |
| `getHistorySummary(String,long)` | One detached session-strip read model; includes metadata, highlight, Supplies/Other split and independent availability. | stable |
| `getRunHistorySnapshot(long)`, `getActivityAverage(String,String)`, `getPreviousPkSessionSummary(String)` | Run/category/PvP summaries; rates are absent when duration or accounting coverage is unavailable. | stable |
| `getRecords(long)` | Retained `RecordsSnapshot` (best/longest/drop and record coverage); it never scans the UI or creates receipts. | stable |
| `compareSessions(String,String,long)` | Pairwise detached `SessionComparison`; each side has independent availability; `getTopItemDeltas()` (five, by absolute difference, null when compaction lost a side's item split) and `getSameNameAverage()` (the left session's activity average, left excluded, null when none). | stable |
| `mergeHistorySessions(List<String>,long)` returning `MergeOutcome`, `undoLastMerge()`, `getUndoableMergeId()` | Merges consecutive closed history sessions of one owner kind into one (`ProfitSession.mergedFrom`); rollups rebuild so Overall is unchanged; refusal reasons `TOO_FEW`, `NOT_FOUND`, `ACTIVE`, `NOT_CONSECUTIVE`, `MIXED_OWNER`. The originals are kept untouched in memory for one exact undo until the next merge or a client restart. | may change |
| `ProfitSession.getItemNets()` | Counted net per item (retained contributions plus live receipts); null when compaction removed rows whose split was never retained. | stable |
| `getInsightsWindow(int,long)`, `getPkWindow(int,long)`, `getPkPlaceSummaries(int,long)`, `getDailyRollups(LocalDate,LocalDate)` | Canonical current/previous windows, PvP and daily rollups. Consumers use per-dimension coverage. | stable |
| `getTrackingInsights(int,long)`, `getTrackingInsightsProjectionStatus()`, `getTrackingInsightsTrend(int,long)`, `getTrackingInsightsTrendByDay(int,long)` | Deprecated compatibility projection still used by `InsightsSnapshot` (only for the notable-drop **milestones** - kill count, session elapsed and GP/h at observation - which no other read model carries) and diagnostics. It is **not dead**; it goes once a milestone read model exists on `InsightsWindowSnapshot`. | may change |

Reads that build on the day rollups (`getInsightsWindow`, `getPkWindow`, `getRecords`, `getOverallTotals`, `getDailyRollups`) share one cache keyed on a mutation generation: a repeat read never rebuilds from sessions, and ticks never rebuild at all (an idle storm of 6,000 ticks is asserted at zero rebuilds). Live active time drops the cache; the next read rebuilds once.

## Wealth and Tools

| Entry point | Contract | Status |
| --- | --- | --- |
| `getLatestWealth(long)`, `getWealthTimeline(int,long)`, `getWealthBreakdown(long)`, `getWealthTrend(int,long)`, `getWealthChangeSince(WealthAnchor,long)`, `getWealthChangeFacts(long)`, `getWealthTopMovers(WealthAnchor,int,long)` | Locate-only wealth snapshots. Missing/unpriced/capped locations are unavailable and never affect counted Net. | stable |
| `observeCoinStore(CoinStore,long,long)`, `clearCoinStore(CoinStore)`, `markCoinStoreUnused(CoinStore,boolean)`, `isCoinStoreUnused(CoinStore)`, `getCoinStoreGaps(long)` | Wealth-only coin stores (NMZ coffer, Blast Furnace coffer, servant moneybag), persisted across restarts, seven-day freshness, never a receipt. A store the owner never has is marked unused and counts as zero; `getCoinStoreGaps` names what is neither fresh nor unused. Once no gap remains the engine derives the contract's `coffers` source from the stores' sum, so `WealthBreakdown` totals and shares become available. `CoinStore.getTitle()` is the display name. | stable |
| `getRetentionStatus([int,long])`, `getProfileSizeEstimate()` | Retention window/pending compaction and detached profile-size estimates. | stable |
| `getDataHealth(long)` | Detached diagnostics: rebuilt-day count, unavailable rollup dimensions, retention/profile size, unknown-field bag count and coin-store freshness. Persistence recovery name and rotated-backup count come from `SaveStatus`/`SessionRepository` and remain empty here when no recovery occurred. | stable |
| `getTileLayout()`, `setTileLayout(TileLayout)`, `setGoalDefinitions(List<GoalDefinition>)`, `resetTrackingData(long)`, `clearCompletedHistory()`, `compactOlderThan(int,long)`, `ensureArmedAfterConfigReseed(long)` | Tools actions and persisted display/read-model settings. | may change |
| `createSavedState()`, `restore(SavedState[,long])`, `inspectBackup(String[,long])`, `restoreProfile(String,long)`, `exportProfile()` | Persistence boundary. Restore is atomic and resets transient evidence; export requires a bound profile identity. | stable |
| `setGeBookingMode(GeBookingMode)`, `getGeBookingMode()`, `setGeSellSpentIsNet(boolean)`, `bookObservedGeSettlement(GeOfferLedger.Transition,String,int,long)` | `PROVENANCE_ONLY` (default, config `geBookingObserved` off): observations are presentation provenance only; settlements are proven independent of them. `OBSERVED`: the plugin calls `bookObservedGeSettlement` for every comparable transition with traded progress (live, and the resumed progress `GeOfferLedger.takeResumedProgress()` reports after a login seed); it books a counted MARKET trade from the slot deltas via `GeObservedSettlement`, and every GE inventory movement (placing, collecting, bank collection, refunds - not player trades) becomes an ownership-neutral transfer. `geSellSpentIsNet` (config, default on) decides whether a sell's coins are taken as net of tax (no tax row) or gross (formula tax row); the live completed-offer comparison settles it. | stable |
| `getDataHealth(long).getRebuiltDays()` | Days whose active time a restore clamped to what the session could have played (legacy read-accrual inflation); those days report active-time coverage *partial*. | stable |

## Persistence and export entry points

`PersistenceCoordinator` owns account binding and the ordered writer:

* `getActiveIdentity()`, `getSaveStatus()`, `replaceStateNow()`, and
  `restoreProfile(String,long)` are the sidebar's save/recovery actions.
* `SessionRepository.detach(SavedState)`, `replaceStateDetailed(SavedState)`,
  `getDataDirectory()`, and `getExportDirectory()` provide detached writes and
  export destinations. `getLastRecoveredFrom()` reports the exact rotated
  backup filename used by the last load; empty means the primary loaded.
* `CsvExporter.exportSession(ProfitSession,Path,int,BiPredicate)` returns a
  `CsvExportResult` containing detail, summary, diagnostics, activities and PK
  paths. Filtered exports carry the same projection status as the page.
* `SavedState.CURRENT_SCHEMA_VERSION` is the displayable schema constant. No
  Step 47 migration is required.

Recovery is never inferred from a missing file: `SaveStatus.getRecoveredFrom()`
and `SessionRepository.getLastRecoveredFrom()` are the only recovery signals.

## Evidence/config boundary

No new RuneLite config key or varbit is required by passes 8–10. Existing client
producers remain responsible for `sessionIdleAutoEndMinutes`,
`receiptRetentionDays`, `maxHistorySessions`, `rollingRateMinutes`, activity
display/filter settings, and the configured charge-review window. GE uses the
observed offer slot/state/quantity/price/`getSpent()` fields; charge accounting
uses exact Check messages and stable target identity; coin stores use the named
client container/widget observations. Menu text, animations, probabilities and
missing varbits are not accounting evidence.

## Charges

`bookChargeSpend` attributes a measured spend to the session's current activity (the weapon is in the note), so Top activities never grow a weapon row; `MeasuredChargeReadTracker` keeps one baseline per stable target identity (bounded), so two same-variant weapons are tracked side by side and a Check of one is never a decrease on the other.

## Sweep record

`rg` confirms `getTrackingInsights*` is still called by `InsightsSnapshot`,
`DiagnosticsService`, and tests, so no compatibility method or projection field
was removed. No dead persistence inbox path was found. Alert callbacks were
changed to release the engine monitor before invocation; persistence callbacks
already use detached snapshots. This is the complete removal list: **none**.
