# Supported scenarios for 1.0

Current review: September 13; remaining work: backlog. This matrix describes implemented behavior, not live-game certification. Automated coverage is in `src/test/java` and `src/simulation/java`; live checks remain in the release checklist.

| Scenario | Behavior and limits | Automated evidence |
| --- | --- | --- |
| Login/relogin | Primes a stable baseline; initial carried items excluded | GpManagerEngineTest |
| Banking/equipment | Transfer context excluded; temporary remove/restore changes stabilized | GpManagerEngineTest, ContainerSnapshotTest |
| Ordinary gains and supplies | Values observed inventory/equipment changes | GpManagerEngineTest, generalSimulation |
| Delayed NPC loot | Correlates expected item IDs and quantities with positive flows | GpManagerEngineTest |
| Mixed production | Processing when context supports it; otherwise uncertain | TransactionClassifierTest, GpManagerEngineTest |
| Market/shop context | Classifies observed exchanges; GE Collect has estimated sale/tax matching (`applyGeSellTax`), with item-only returns excluded; actual offer/partial-fill reconciliation remains B01 | TransactionClassifierTest, GeSellTax* |
| PK loot/death | Correlates loot and losses with passive context and encounters | GpManagerEngineTest, pkSimulation |
| Default and custom tracking | One durable General owner; named custom sessions suspend it and return on finish without replacing its pause owner | SessionOwnershipTest, SessionRepositoryRecoveryTest |
| Optional profit targets | Positive whole-GP absolute net target per owner; no accounting effect | ProfitTargetParserTest, SessionRepositoryRecoveryTest |
| Pause/logout/idle | Separate pause ownership; paused time excluded | GpManagerEngineTest, ProfitSessionTest |
| Corrections/undo | Reasons and transitions retained; eligible undo restoration | ProfitSessionTest, migration tests |
| Long sessions | Detailed rows bounded; summarized revenue/costs preserve session totals | ProfitSessionTest, SessionRepositoryRecoveryTest: 10,000 rows, 200 saves/reloads |
| Recovery | Previous valid save available if primary is corrupt or missing; reset commits when primary replaced; obsolete backup invalidated on destructive align failure | SessionRepositoryRecoveryTest, PersistenceSafetyTest |
| Account isolation | Per RS-profile storage; legacy unassigned until claim; stale revisions refused | PersistenceSafetyTest |
| CSV exports | Detail, summary, diagnostics, activities, encounters and audit | CsvExporterTest |
| Party totals | Opt-in temporary snapshots | PartyProfitMessageTest, PartyProfitSummaryTest |
| Continuous Insights | UTC-day recorded revenue, costs, active time, activity net and gained-item values across General/custom trackers; no migrated backfill | TrackingInsightsTest |

## Limits requiring manual review

- GE matching uses menu context and captured market valuations, not actual offer identity/fill price. Partial collections, refunds and multi-offer matching require B01. Do not treat the current helper formula as proof of correctly reconciled realized profit.
- Potion doses/transformations use observed inventory changes and captured market estimates. Toxic Blowpipe and supported seas/swamp Trident variants now book only complete, priced component decreases between exact Check reads; their first read only seeds a session-local baseline. Exact item-on-item load losses are ownership-neutral, while menu Check clicks, animations and probabilistic charge ratios never book. Missing component prices fail the whole delta closed. Charge/uncharge dialogue receipts and other weapon families remain unsupported, and the Check path still needs live-client acceptance (B04/B05).
- RuneLite canonicalizes IDs for captured items; this does not guarantee every noted or charged variant is valued correctly.
- Loot-key/chest models and partial receipt regressions exist. Bank/transfer movements do not create provenance, and a chest click alone never proves a claim. A measured key loss plus a nearby positive inventory delta can still be ambiguous when an unrelated gain lands in the same correlation window; verify client event ordering before treating that delta as chest contents. Quantitative multi-key/relog/direct-bank claims also remain open under B02. Reclaim chat alone books no cost; actual carried-coin losses use normal settlement. LMS/raid/GIM menu hints exist, but raid/GIM interface flags remain placeholders (B04).
- Missing prices may remain unpriced or be ignored according to settings. High-alchemy fallback is optional and is not a sale-price guarantee. Optional currency proxies (`useCurrencyProxies`) value curated untradeables via traded counterparts. Manual overrides always win.
- Detailed breakdowns and rolling rates cover retained rows after compaction. Full-session totals include summarized rows; exports expose those amounts separately. Corrections cannot target discarded detail.
- Ledger rows whose value cannot be established are labelled **Unpriced**, not `0 gp`. Their pricing provenance is distinct from their transaction classification; corrections apply only to retained selected rows.
- Ledger filters inspect the entire retained owner ledger and reveal results incrementally (All / Gains / Costs / Review / Transfers / Tax / Charges / Splits). Compacted rows remain summarized totals rather than inspectable detail.
- Local history is isolated per RuneLite RS profile key (account hash + world-type profile). Root legacy `sessions.json` stays unassigned until explicitly claimed. Simultaneous clients sharing one account scope use write locks and revision fencing (no silent last-writer-wins). See `docs/ACCOUNT_ISOLATION.md`.
- CSV export is not a CSV import/restore format. Preserve JSON for recovery.
- Insights daily summaries retain the newest 400 UTC days per tracker. Existing data begins reporting from its first post-upgrade observation; historical daily charts are not inferred.
- Party snapshots are individual reports only. The protocol does not prove a common accounting scope, loot split, settlement, or comparable time range; the panel must not interpret their sum as a shared profit total. Personal **Split…** on Ledger/Review is a separate correction path (keep qty in Net) and never uses Party messages.
- Wealth accordion is locate-only (PoH / STASH / DWMS-class when observed). It does not invent GE profit for items sitting in unopened storage.

These boundaries define the proposed 1.0 scope. Live certification and further activity coverage need separate evidence before being advertised.

## Export scope

Explicit accounting eligibility currently reaches diagnostics and comparison headline metrics. Detailed/transaction/activity/PK exports preserve raw records or projections and may differ when an accounting filter is active. B03 tracks uniform filtered reports and explicit raw-audit labeling; do not claim all export files already match the filtered HUD.
