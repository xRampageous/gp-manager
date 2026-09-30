# Charge accounting

Supported charged items book per-use estimates from their observed cast, hit or
use triggers. Ledger receipts identify these as **Estimated** and keep their
captured component price and source. Fractional components carry their exact
value across uses rather than truncating each use to zero.

Supported patterns include blowpipe, tridents, tomes, the bottomless compost
bucket, crystal weapons, sanguinesti staff, warped sceptre, venator bow, Ates,
Eye of Ayak, blood fury and scythe. Supported variants retain their own recipes.
The scythe counts damaging hits, including multi-target sweeps, and remains
estimate-only. Eye of Ayak keeps its tear/rune mode per staff; its cast trigger
is learned from local evidence.

## Measured Checks

A Check interaction binds a short-lived target identity. A matching numeric chat
response supplies the measured balance. Each stable target keeps an independent
baseline; checking one weapon cannot overwrite another weapon's baseline.

The first valid Check seeds a baseline. A later same-target decrease reconciles
the intervening estimates and books only the remainder, so a measured spend is
never counted on top of its estimate. Measured receipts say **Measured** and keep
the captured unit price and source. Ambiguous evidence remains in Review.

Blowpipe scales and darts remain independent; a dart-type switch cannot infer
dart spend. Trident recipes convert supported decreases into their component
quantities. The bucket uses half a compost unit per application. Its countdown
and run-out notices are use evidence and cannot reset a different item's Check
baseline. Missing or unsupported prices never become a guessed cost.

## Loads and lifecycle

A rise in a supported charge read is a load. Its context keeps the corresponding
inventory components as a neutral transfer. Ambiguous component losses wait in
an uncounted Review row; a supported same-target Check can resolve only the
quantities it establishes. Partial confirmation leaves the remainder in Review.

Target baselines and load-correlation evidence are transient. Variant/custody
changes, restart, hop and profile changes clear unsafe comparisons; a new Check
then seeds a fresh baseline. Durable unresolved Review receipts remain attached
to their original accounting owner.

Offline regression tests cover estimates, measured reconciliation, multiple
targets, partial loads, fractional prices, missing prices and overflow. Actual
RuneLite callback delivery remains in [live acceptance](../LIVE_ACCEPTANCE_MATRIX.md).
