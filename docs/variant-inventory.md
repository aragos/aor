# Supported variants

The reviewed AH1996 mechanics are the baseline. All games have three epochs and one opening card
per player. Variants are chosen with player registration, recorded explicitly in the initial game
revision and frozen at `start`.

| Option | Default | Alternative |
| --- | --- | --- |
| `deck-modification` | `disabled` | `enabled`: swap War and the physical Timber card `timber-2-1` between epochs 1 and 2 |
| `initial-card` | `any` | `no-crusades`, `no-events`, or `commodity-only`; mutually exclusive |
| `immediate-master-art-discard` | `disabled` | `enabled`: offer an optional discard immediately after acquiring Master Art |
| `map` | `standard` | `european`: Loire replaces Smolensk, with the land and sea changes in [rules](rules.md#13-variants) |

Opening restrictions filter each seat's eligible draw from the shuffled pile; they do not remove
physical cards from play. No-events still permits leaders. Commodity-only permits commodity cards
only. The deck modification changes epoch assignment and preserves all 64 physical cards.

The European option selects its own catalog and validated drawing throughout setup, expansion,
card targets, commodity payments and replay. Its Wine ownership track has seven boxes. The
[European map authoring guide](european-map.md) describes the derived definitions, report and drawing
regeneration commands.

Second-edition and tournament presets, improved setup, alternate advance/credit tables, optional
St. Gall support, alternate economy/leader interactions and rebuilt-marker-bonus selection were
retired at the user's request on 2026-10-27. Their earlier rulings do not select current behavior.
The standard board retains its reviewed catalog values and connections.

Verification: `RuleOptionsTest`, `SetupDealTest`, `VariantFlowTest`, `VariantGameCompletionTest`,
`EuropeanMapGameTest`, `RuleVariantServiceTest`, and the browser creation/setup checks cover configuration, opening draws,
physical-card conservation, replay, persistence and the shared registration screen.
