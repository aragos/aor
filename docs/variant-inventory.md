# Supported variants

The reviewed AH1996 mechanics are the baseline. All games have three epochs and one opening card
per player. Variants are chosen with player registration, recorded explicitly in the initial game
revision and frozen at `start`.

| Option | Default | Alternative |
| --- | --- | --- |
| `deck-modification` | `disabled` | `enabled`: swap War and the physical Timber card `timber-2-1` between epochs 1 and 2 |
| `initial-card` | `any` | `no-crusades`, `no-events`, or `commodity-only`; mutually exclusive |
| `immediate-master-art-discard` | `disabled` | `enabled`: offer an optional discard immediately after acquiring Master Art |
| `map` | `standard` | European map reserved pending a reviewed province and adjacency specification |

Opening restrictions filter each seat's eligible draw from the shuffled pile; they do not remove
physical cards from play. No-events still permits leaders. Commodity-only permits commodity cards
only. The deck modification changes epoch assignment and preserves all 64 physical cards.

The European option is shown as unavailable and rejected by the engine. Required follow-up: province
additions/removals, land/coastal/sea contacts, support arrows, region membership, capitals and map
geometry. No speculative connections are implemented.

Second-edition and tournament presets, improved setup, alternate advance/credit tables, optional
St. Gall support, alternate economy/leader interactions and rebuilt-marker-bonus selection were
retired at the user's request on 2026-10-27. Their earlier rulings do not select current behavior.
Baseline catalog values and reviewed map connections remain unchanged.

Verification: `RuleOptionsTest`, `SetupDealTest`, `VariantFlowTest`, `VariantGameCompletionTest`,
`RuleVariantServiceTest`, and the browser creation/setup checks cover configuration, opening draws,
physical-card conservation, replay, persistence and the shared registration screen.
