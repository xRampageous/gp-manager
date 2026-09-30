# Immediate interaction display context

Implemented in the shared activity label without changing reward identities, accounting
activity or transaction values.

## Activity label (HUD+ and Live)

HUD+ and the Live status line render one shared label, resolved by `ActivityLabel`:

1. **Fresh NPC target** — the NPC being fought or interacted with, including the
   four-second release grace in `InteractionContextTracker`. Scenery objects and opponent
   names never title: a bank booth, door, tree or stairs click leaves the label alone.
2. **Session activity** — a skill, a loot source, PKing, Trading, Death reclaim or an
   Agility course, from the engine's activity evidence. Raids and minigames keep their
   curated name (ToB, CoX, ToA, GotR, …) over a fresh target.
3. **Named run** — the Grind name when a named run is active and no activity evidence exists.
4. **Blank** — quiet Free play shows no label.

Generic placeholders ("General", "NPC loot") never surface: the detector ignores the NPC
loot fallback and the label drops them defensively.

The label is presentation-only. It never changes a transaction's type, value, counted
state or totals, and it never books or values money.

## What does not title

- Scenery and traversal: bank booths, deposit boxes, doors, gates, stairs, ladders,
  portals, trees, rocks and fishing spots as objects. The activity (Woodcutting, Mining,
  Fishing) comes from skill evidence instead.
- Opponent player names: PvP reads as `PKing`; opponent identities are never display
  evidence and are never stored.
- Generic fallbacks: `General` and `NPC loot`.
- Receipts and consumption: eating, drinking, burying and menu clicks do not title.

## Implementation

- `InteractionContextTracker`: client-thread NPC target with a four-second release grace,
  published through `PresentationSink.interactionChanged`. It also answers the one backend
  fact — whether a death happened in positive PvM context.
- `ActivityDetector`: client-thread activity evidence (loot source, skill XP, gather verbs,
  agility course, PKing, Trading, Death reclaim) written to the session hint.
- `ActivityLabel` (`ui/sidebar`): the one ladder read by `HudBuilder.title` and
  `LivePage.identityOf`; `LiveSnapshot` carries the target so both surfaces agree.
- `Fmt.activity`: curated short names and trailing-qualifier compaction.

## Verification

`InteractionContextTrackerTest` (scenery and opponent names never publish, combat targets
survive scenery clicks), `ActivityDetectorTest` (the NPC loot fallback never becomes the
activity), `ActivityLabelTest` (the ladder and placeholders), `HudBuilderTest` (header
preference and the curated raid override) and `LivePresentationTest` (Live renders the same
ladder).
