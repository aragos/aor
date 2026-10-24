# Playing a one-epoch game

Create a table with three through six names and keep each private player link. Only one epoch is
supported. The creator selects the rebuilt-marker bonus option before starting. See the
[setup walkthrough](manual-setup.md) for sealed bids and access links.

The browser shows the current phase, the player who must act, public board pieces and advances,
your private hand and available commands. Guided controls submit the same text commands as the
command input. Province identifiers and advance letters come from the public catalog. Commands
submitted through a private player link omit the actor prefix; replay logs include it.

## Ordinary phases

| Phase | Commands and behavior |
| --- | --- |
| Order of Play | `buy-tokens -2`, `buy-tokens 0`, `buy-tokens 12`. Purchases stay sealed until everyone submits. Tied players use `choose-turn-position 1`. |
| Draw Card | The first box may `cancel-adjustment wine shortage` or `cancel-adjustment stone surplus`, paying each marker separately; `pass` starts automatic draws. |
| Buy Card | Eligible owners may use `buy-card` ($10) and, with Master Art, `discard-card hand-4` in either order. Each is optional and available once; `pass` continues. |
| Play Cards | `play-card hand-4` with any required choice below. Play as many as desired, then `pass`. |
| Purchase | `upgrade-ships` or `pass`; `buy-advance A` as often as affordable, then `pass`; mandatory `stabilize cash` or `stabilize misery`; `relieve-misery 1` as allowed, then `pass`. |
| Expansion | `place 3 milan`, `attack florence`, `reduce-marker genoa`, `buy-expansion-card`, then `end-expansion`. Unused pool tokens return to stock. |
| Income | Automatic piece flips, income and two recorded shortage/surplus rolls. Eligible majority draws resolve automatically. |

A placement may specify `land`, `ship`, or `caravan <intermediate-province>` after the destination.
Without a route, the engine prefers land. Attacks calculate the exact additional commitment;
`place` also validates commitments before spending tokens. Excess attacking tokens are rejected.
Shipping usage includes failed attacks and is tracked separately for each destination this turn.

`reduce-marker` exchanges your coloured marker for one coloured token during your Expansion
activity, making the marker available elsewhere. Rebuilding that province never earns bonus credit.
Event rebuilds count only with the setup option `include`. Ordinary expansion markers count in
both settings; ties for the expansion reward favor the earlier player.

## Cards and interrupted activities

Your hand references do not encode card identities. Playing a card makes its identity public.
Examples of explicit choices:

```text
play-card hand-4 commodity wine
play-card hand-8 player p2
play-card hand-12 province acre
play-card hand-16 category science
play-card hand-20 protect 10
```

A non-burden event with no eligible effect can be played without a target and resolves with no
effect. Temporary immunity does not turn a card into a permanent burden. Burdens remain in hand
and count toward stabilization; Master Art and Wind/Watermill can still discard or exchange them.

The prompt may temporarily belong to another player. The original phase activity resumes after
the interruption:

| Prompt | Answer |
| --- | --- |
| Renaissance at phase opening | `swap-order p2` or `pass`. Once per turn; the adjacent target must be active and not own Renaissance. |
| Civil War victim | `civil-war-loss cash` or `civil-war-loss tokens`. |
| Event replacement with no stock tokens | `replace-event-token pool` or `replace-event-token none`. |
| Cathedral opportunity | `cathedral` or `pass`. Uses are tracked separately against each opponent. |
| Military cards before a War reroll | Play eligible military cards, then `resolve-war` or `pass`. |
| War loser cessions | `cede-war milan florence`. Choose the required supportable markets. |
| Patronage during advance purchases | `patronize-leader rashid-ad-din`; pay the exposed leader's protection once. |
| Wind/Watermill opportunity | `windmill` or `pass`; after a successful recorded roll, `offer-card hand-8`. The other card is chosen randomly and recorded privately. |

Tied Wars continue at later players' card rounds, including later turns. An unresolved tie after the
last Final Card Play round is cleared. Chaos skips ordinary phase activities and discards the player's hand; its pieces
remain neutral defenders. Income and order boxes retain the starting player count.

## Ending, replay and recovery

Drawing the last card or acquiring all 26 advances makes the current turn final. Finish Purchase,
Expansion and Income, then play every non-burden card in Final Card Play. That final round retains
the final turn's events and newly rolled shortages/surpluses. It has no Master Art discard or
shortage cancellation. The empty deck never produces another draw.

Scores are printed advance costs plus cash minus the misery box's value. Tied active players can
share victory. Chaos has no numeric score or victory; if everyone enters Chaos, the engine stops
with no winner rather than opening an unanswerable phase.

Every revision stores a delta of accepted actions and recorded outcomes. Restart replays all
revisions without drawing new cards or rolling new dice. Draws, discards and exchanges are
redacted for players outside their permitted audience. Optimistic revision checks reject stale
commands.

Two source rulings remain pending: domination-marker exhaustion during War/Crusades and payment
box 11 for all Wool markets plus Industry and a shortage. These cases return explicit typed
rejections; they are not silently resolved with an invented rule. See [the rules reference](rules.md).
