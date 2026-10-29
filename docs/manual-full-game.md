# Playing a three-epoch game

Choose 3–6 players, current variants and undo policy at creation; setup starts automatically.
Use the [setup walkthrough](manual-setup.md) for private bids and access links.

The compact **AoR** table identifies your capital in the page title. It shows the actor, current
choice, public board and advances, private hand and relevant resources. Object inspection has its own
surface: inspecting a province, card or advance preserves a prepared action and its map targets.
Guided controls submit the same commands as text input; player-link commands may omit the actor,
while replay logs use canonical IDs. **Commands** exposes history and authorized edits.

## Ordinary phases

| Phase | Commands and behavior |
| --- | --- |
| Order of Play | `buy-tokens -2`, `buy-tokens 0`, `buy-tokens 12`. Private until the batch reveals; tied players use `choose-turn-position 1`. |
| Draw Card | The first box may `cancel-adjustment wine shortage` or `cancel-adjustment stone surplus`. Each marker costs the commodity's base value, shown before confirmation. `pass` starts automatic draws. |
| Buy Card | Urban Ascendancy offers `buy-card` for $10; Master Art offers `discard-card hand-4`. Each is optional once, in either order; `pass` continues. |
| Play Cards | `play hand-4` with its required choice. Play as many as desired, then `pass`. |
| Purchase | `upgrade-ships` or `pass`; affordable `buy-advance A` purchases, then `pass`; `stabilize cash/misery` if cost is positive; eligible `relieve-misery 1`, then `pass`. |
| Expansion | `place 3 milan`, `attack florence`, `reduce-marker genoa`, `buy-expansion-card`, then `pass`. Unused pool tokens return to stock. |
| Income | Automatic piece flips, income and two recorded shortage/surplus rolls; eligible majority draws resolve automatically. |

While waiting in Buy Card, an eligible Urban Ascendancy owner can queue a $10 purchase. This is a
private draft in the open player view, with Cancel available. It submits once when buying becomes
legal, clears if the phase/turn or eligibility changes, and never retries a failed request. Closing
or reloading loses it. It does not automatically pass a remaining Master Art choice.

Advance tiles/details show how many matching leaders in each printed epoch have not been publicly
played. Counts include held cards, future decks and privately discarded leaders to preserve secrecy;
they are not a promise that those leaders can still be drawn. Markets and commodity-card inspection
offer **Full payout boxes**, closed by default. Commodity cards show current shortage/surplus badges.

Zero-cost stabilization is skipped. A misery payment shows the resulting printed box or Chaos.
Once a player has an advance tier, relief stays visible even if unaffordable or no spaces remain;
the explanation and Pass let the player finish purchasing.

## Expansion and results

The engine chooses a legal route, preferring routes that spare galley capacity. Commands name the
destination; attacks use exactly the additional tokens required. Validation precedes token spending,
and excess attack commitments are rejected. Failed attacks still consume destination-specific shipping.

Candidates omit destinations with no commitment possible under the pool or shipping limits. A full
market is primary when legal; otherwise **Place maximum possible tokens** uses the largest legal
partial placement. Smaller legal placements remain available. Each player's summary shows white
markers, and the acting player's summary names military cards with their matchup qualifications.
These summaries do not replace the exact competition calculation.

`reduce-marker` exchanges your coloured marker for one coloured token and frees the marker. Current
rules exclude voluntary and event rebuilds from the expansion bonus. Ordinary expansion markers count;
ties favor the earlier player. The reviewed rules own [bonus details](rules.md).

Competition results remain visible through expansion unless cleared. Losing attacks have a red
background and explicit loss text; displayed dice state their colors. Clearing notices leaves their
activity entries intact and permits new results to appear. Activity is open by default and explains
draw reasons, epoch/end triggers, protection money, leader rebates and cash gains/spending alongside
the related event. Continuation entries are omitted. For shortage rolls, white plus black selects
the commodities and green selects shortage (1–3) or surplus (4–6), canceling an opposite adjustment.

## Cards and interruptions

Hand references do not encode card identities. Playing a card makes its identity public. Examples:

```text
play hand-4 commodity wine
play hand-8 player p2
play hand-12 province acre
play hand-16 category science
play hand-20 protect 10
play hand-24 area vi
play hand-28 provinces paris london genoa
```

Selecting Crusades, Rebellion or Pirates/Vikings immediately activates eligible map targets. Map
clicks prepare the draft; the action button commits it. Other finite target buttons remain usable.
A non-burden event with no eligible effect can be played without a target and resolves with no effect.
Temporary immunity does not create a permanent burden. Burdens remain in hand and count toward
stabilization; Master Art and Wind/Watermill may still discard or exchange them.

Interruptions identify the responder and enclosing activity, which resumes afterward:

| Prompt | Answer |
| --- | --- |
| Renaissance at phase opening | `swap-order p2` or `pass`; once per turn against an adjacent active non-owner. |
| Civil War victim | `civil-war-loss cash` or `civil-war-loss tokens`. |
| Event replacement with no stock tokens | `replace-event-token pool` or `replace-event-token none`. |
| Cathedral | Eligible attacker/defender uses `cathedral` or `pass`; usage is tracked per opponent. |
| Military cards before a War reroll | Play eligible military cards, then `pass`. |
| War cession | `cede-war milan florence`; choose the required supportable markets. |
| Marker exhaustion after Crusades/War | `forgo-marker` or `move-marker genoa` from another controlled coloured marker. |
| Patronage in advance purchases | `patronize rashid-ad-din`; pay the exposed leader's protection once. |
| Wind/Watermill takeover | `windmill` or `pass`; after a successful roll, `offer-card hand-8`. The other card is randomly chosen and privately recorded. |
| Immediate Master Art variant | `discard-card <reference>` or `pass` immediately after purchase; resume advances before stabilization. |

Tied Wars reroll at later players' card rounds and may continue into another turn. A tie remaining
after the last Final Card Play round is cleared. Chaos skips ordinary activities and discards the
hand; pieces remain neutral defenders. Income/order boxes retain the starting player count.

## Ending, rulings and recovery

Drawing epoch three's final card or acquiring all 26 advances makes the current turn final. Complete
Purchase, Expansion and Income, then play every non-burden card in Final Card Play. That phase retains
the turn's events and new adjustments but offers no Master Art discard or shortage cancellation.
No draws remain after epoch-three exhaustion. Earlier empty piles advance only upon a draw request:
recycle eligible discarded circle cards into the next deck, exclude held cards and square cards,
and resume the suspended draw with a fresh reference.

Scores are printed advance costs plus cash minus the misery box. Tied active players may share
victory. Chaos has no numeric score; if everyone enters Chaos, the game finishes without a winner.

At the 25-marker limit, each War/Crusades award allows forgoing or moving another controlled marker.
The destination's old pieces are removed either way; moving empties the source without spending
tokens. Other card effects still apply, and a forgone War award adds no extra misery to the loser.
Access/overseas support limits remain. Commodity payouts continue `base value × box²` beyond printed
box 10: nine Wool markets, Industry and a shortage pay $242. [Rules](rules.md) own these reviewed rulings.

Revision deltas replay all effective history without fresh randomness. Draws, discards and exchanges
remain audience-restricted. Stale submissions refresh for explicit review/resubmission; scoped undo
and suffix edits preserve prior outcomes and audit history.
