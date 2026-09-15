# `com.gpmanager.engine.evidence`

Per-source timed evidence plus charge/container accounting helpers.

## Bank soft vs hard

Historical P1: burial after bank close must be CONSUMPTION, not TRANSFER.
Soft bank-open evidence drops on UI close; hard deposit/withdraw evidence
survives a short TTL. See `GpManagerEngine` and `ConsumptionBurialEngineTest`.

## Measured charges

- {@link MeasuredChargeRead} / {@link MeasuredChargeReadTracker} — exact numeric
  Check reads and session-local negative differences for Toxic blowpipe and
  supported Trident variants. No menu, animation or probability may book cost.
- {@link ChargeFamilyIds} / {@link ChargeRecipeCatalogue} — stable family
  identities; Blowpipe and Trident are implemented, remaining families are
  catalogue-only.
- {@link ChargeSpendBooking} — requires every measured component delta and an
  exact GE or fixed coin face-value price before counted CONSUMPTION is added.

## Utility-container calibration

- {@link ChargeAccountingGate} — per-profile Check evidence for utility-container
  families only; it does not authorize weapon charge costs.

## Containers

- {@link ContainerEvidence} — store / withdraw / empty-to-bank markers.
- {@link UtilityContainerCatalogue} — bags/sacks/barrels, silk-lined herb sack,
  forestry kit (widget), plank sack (ambiguous → Review), essence pouches,
  deposit-box Empty → bank (ownership-neutral).
