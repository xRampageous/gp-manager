# Supported accounting scenarios

Current candidate: 1.0.0, schema 108. These are offline-tested accounting contracts;
real RuneLite event delivery and long-session behavior remain separately tracked
in [live acceptance](LIVE_ACCEPTANCE_MATRIX.md). Tests do not establish live certification.

| Scenario | Contract | Representative offline coverage |
| --- | --- | --- |
| Opening inventory and lifecycle | Opening stock is baseline only; logout/hop/profile changes cannot reuse temporary loot, combat or collection evidence | GpManagerEngineTest, PlayerCombatLifecycleTest, PersistenceLifecycleC0aTest |
| Banking/equipment/pouch | Ownership movements are neutral; stale soft bank hints cannot suppress confirmed consumption | BankVisibilityRegressionTest, ContainerSnapshotTest, GeEvidenceOwnershipTest |
| NPC/player loot | Only measured collected quantities book; distinct sources/encounters remain separate, including shared item IDs | CollectedLootVisibilityTest, PkChestAndReclaimSettlementTest |
| Supplies/production | Exact observed removal and context determine consumption or processing; ambiguous mixed changes remain uncounted by default | ConsumptionBurialEngineTest, GpManagerEngineTest |
| Own drops | Confirmed drops say Dropped; nearby own pickups reverse only the matched loss, preserving surplus and other gains; old sessions, death contexts and corrected receipts cannot authorize recovery | OwnDropRecoveryEngineTest, HudTrayTest |
| Drinks and vials | Confirmed drinks use the matching (1) dose quote; four sips group as ×4 doses; incompatible, missing or overflowing authority stays in uncounted Review with both raw quotes; empty vial item flows are omitted and filled vials remain priced | ItemValuationServiceTest, LedgerItemContributionProjectionTest, LiveActionWordingTest |
| Recent visibility | Hide from recent saves a presentation preference; removing it restores the row; Net, Ledger and exports remain intact; Ground Items still applies explicit, wildcard and quantity rules to gains | RecentVisibilityTest, ConfigProxyCompatibilityTest |
| GE | Reservation, fill, collection, cancel/reuse, tax and basis reconcile once; ambiguous collections fail closed to Review | GeCustodyEngineTest, GePartialCollectAttributionTest, GePerFillTaxTest |
| Loot keys/chests | Held tokens are deferred; exact measured contents settle claims once; unresolved claims survive save/retention | KeyChestClaimTest, PkChestAndReclaimSettlementTest |
| PvM death/reclaim | Lost ownership, returned items and observed fees remain distinct from new loot | DeathReclaimLifecycleTest, PlayerCombatLifecycleTest |
| Charges | Supported exact Check measurements seed independent target baselines and book measured decreases; ambiguous loads stay in Review | ChargeLoadReviewLifecycleTest, measured charge suites |
| Grind owners | Free play owns receipts outside named Grinds; names are editable labels, IDs retain ownership; targets do not change money | SessionOwnershipTest, ProfitSessionTest, HudInsightTest |
| Corrections/splits/undo | Canonical decisions update all projections; retained decision history restores prior reason text, including reload and partial recovery | ProfitSessionTest, ItemSplitAccountingTest, TrackedBasisCorrectionFenceTest |
| Retention/CSV | Closed detail folds into exact summaries; unresolved claims are retained; COMPACTED CSV totals reconcile without invented item rows | RetentionCompactionSoakTest, CsvExporterTest |
| Save conflicts/reset/recovery | Revision/generation checks refuse stale writes; conflict requires disk reload after restart; reset backs up and cannot resurrect obsolete data | PersistenceConflictFenceTest, PersistenceSafetyTest, SessionRepositoryRecoveryTest |
| Pricing | Captured prices remain frozen; unavailable prices are not guessed; unsupported economies fail automatic valuation closed | ItemValuationServiceTest, GeUnknownBasisTaxNetTest |
| HUD/filtering | Compact empty row, monetary wrapping, owner isolation and hidden loot count in folio are presentation; GE receipts are excluded from that count; gain filters cannot change Net | HudBuilderTest, HudSizingTest, HudInsightTest, ConfigProxyCompatibilityTest |

The sidebar is Live, Ledger and Grinds. Profile backups can be restored through
Backups & recovery; CSV is an export, not a restore format. Tests and
simulation sources are development verification; their classes are absent from the
runtime plugin JAR. Schema 108 is fresh and unsupported save schemas are refused.
