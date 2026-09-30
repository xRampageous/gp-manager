# Engine and presentation contract

Current candidate: 1.0.0, schema 108. Settings group `gpmanager` (bridged once from
`profitmanager` at start-up; key names unchanged). Java declarations share `com.gpmanager`;
source directories preserve the engine/model/persistence/UI organization. This is
an internal contract, not a public extension API.

## Financial authority

`GpManagerEngine` settles observed changes into canonical `ProfitTransaction`
receipts owned by one session. Model projections derive correction-aware totals
from those receipts and retained summaries. UI filters, labels and notifications
cannot book or value money. Unknown evidence remains unavailable rather than zero.
Booked prices do not change when current market prices change.

The settlement pipeline handles transfer/custody evidence, death-owned returns,
source-backed loot, consumption/production and ordinary residual changes. Exact
quantity partitions use the shared `FlowFilters.claim` and `ItemFlow.part/rest`
primitives. Distinct loot sources and PK encounters retain separate receipts; any
additional presentation callbacks run after canonical booking completes.

GE observations maintain offer and custody lifecycles before financial settlement.
Deferred loot-key claims settle only against measured contents. Supported charge
patterns book per-use estimates; exact same-target Check measurements reconcile
those receipts and book only the remaining spend. Receipts retain whether their
charge evidence is Estimated or Measured.

## Consumers and mutations

Live, Ledger, Grinds, HUD+ and CSV read the engine/model projections. Sidebar page
snapshots carry availability/status alongside numbers. Domain actions from Swing
are dispatched through the sidebar mutation boundary to RuneLite's client thread;
presentation refreshes return to Swing's EDT. Session IDs, not editable names,
identify owners across snapshots.

Ledger corrections are engine mutations with canonical audit/undo state. Targets
and saved Grind setups change owner metadata, not historical money. Retention
compacts closed detail while preserving totals and unresolved claims.

## Persistence

`PersistenceCoordinator` binds RuneLite RS-profile identity and makes detached
`SavedState` snapshots. `OrderedPersistenceWriter` serializes writes with generation
and revision checks. An external disk conflict fences the current generation:
tracking, autosave and reset refuse stale work until restart reloads disk truth.
Abandoned-generation completions cannot become the active owner's save status.

Schema 108 is the only supported save schema; there is no migration path or config
schema mechanism. Reset commits a validated replacement and prevents an obsolete
backup from resurrecting cleared data. See [account isolation](../ACCOUNT_ISOLATION.md).

## Verification

`releaseCheck` covers regression tests, both offline accounting simulations, safety,
configuration/branding compatibility, metadata, packaging and public-source checks.
Live acceptance remains separate in [the matrix](../LIVE_ACCEPTANCE_MATRIX.md).
