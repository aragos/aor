# Age of Renaissance: Rules Reference

Summary of the rules of *Age of Renaissance* (Avalon Hill, 1996; Greenwood & Scarborough) as needed for the engine.

**Baseline:** the 1996 Avalon Hill rules plus the official Avalon Hill *Errata and Clarifications* (7 May 1998).
The 1999 EuroGames 2nd edition and common optional rules are listed in [§13 Variants](#13-variants) and are not
part of the baseline.

**Confidence.** Each rule here was cross-checked against at least two independent secondary sources (see
[Sources](#sources)) unless it is tagged. Tags:

- **[Qn]**: open question. See [§14](#14-open-questions). The text states the reading we currently favour.
- **[data]**: a value that belongs in the static game data (map, cards, tables) rather than in rules logic.

---

## 1. Glossary

| Term | Meaning |
| --- | --- |
| **Player** | A participant, identified by their chosen capital: Venice, Genoa, Barcelona, Paris, London, Hamburg. |
| **Token** | A square piece representing market penetration. 36 per player. |
| **Domination marker** | A round piece representing control of a market. 25 per player. Counts as *market number* tokens. |
| **Stock** | Tokens not on the board and not in the expansion pool. |
| **Expansion pool** | Tokens bought in the Order of Play phase, to be placed during that turn's Expansion phase. |
| **New / white piece** | A token or marker placed this turn. It is white side up until the Income phase flips it. |
| **Coloured piece** | A piece placed in an earlier turn. Only coloured pieces act as a base for expansion and as "control" for Caravan. |
| **Area** | One of eight regions I–VIII, bounded by thick coloured borders. Which areas are in play depends on player count. |
| **Province** | A land space on the map. Either a *market* or a *satellite*. |
| **Market** | A province with a circled number 2–6 (its *market number*). It produces a commodity (or none) and can be dominated. |
| **Satellite** | A province with a square "1". Holds at most one token, can never be dominated, produces nothing, and has arrows to the markets it *supports*. |
| **Capital** | One of the six home markets, marked with the capital's crest. |
| **Coastal province** | A province with a light-blue coastal-water strip. Ships can only deliver tokens to coastal provinces. |
| **Sea / ocean** | Medium-blue sea areas and the dark-blue Atlantic (the only ocean). Sea areas are always in play. |
| **Overseas boxes** | Far East (East Indies, China, India) and New World (North America, South America). Treated as coastal provinces that belong to no area. |
| **Presence** | Holding tokens in a market without dominating it. |
| **Domination** | Holding a domination marker in a market. |
| **Control** | A province is *controlled* by a player holding a coloured domination marker there, or coloured tokens at least equal to the market number. |
| **Order-of-play box** | The numbered box a player occupies on the order-of-play track. The *number printed on the box* (not the rank) is used in combat. |
| **Written cash** | The cash a player records secretly during Order of Play. Several events refer to it. |
| **Advance** | One of 26 civilization advances A–Z, bought with cash. Worth its cost in points at game end. |
| **History card** | One of 64 cards (commodity, event, leader), split into three epoch decks. |
| **Misery index** | A per-player track of increasing values from 0 to 1000. Passing 1000 is **Chaos**, which eliminates the player. |
| **Misery burden** | An event card that has been voided and can no longer be played. It stays in hand and still costs stabilization. |

All fractions are **rounded up** unless stated otherwise.

---

## 2. Components

### 2.1 Per player

- 36 tokens, 25 domination markers, 12 commodity markers (bookkeeping only), 1 ship marker, 1 misery marker and 1
  order-of-play marker.
- A player mat with:
    - the income track. It has 25 circles, which hold the domination markers not yet on the map; the last uncovered
      circle shows the income.
    - the expansion pool.

### 2.2 Shared

- **Three dice:** white, black and green (the "coloured" die).
- **64 history cards** in three epoch decks: 27 commodity, 18 event and 19 leader cards (see [§11](#11-history-cards)).
- **26 advances**, recorded on each player's Advance Log (see [§10](#10-advances)).
- **Order-of-play track, misery track and ship upgrade display.** The ship display has galley, seaworthy vessel and
  ocean navigation rows.
- **Leader display** and **three shortage/surplus markers.**
- **Bank.** Money is unlimited.

### 2.3 Map

- The board has eight areas (I–VIII) plus the overseas boxes. Area membership, market numbers, commodities, satellite
  arrows, adjacency and coastal-water links are all **[data]**.
- **Fez sea access.** Fez directly adjoins both the Canary Basin and Western Mediterranean
  (user correction, 2026-10-28). This applies to both supported maps.
- **Home areas:**

| Capital | Home area |
| --- | --- |
| Hamburg | I |
| London | II |
| Paris | III |
| Barcelona | IV |
| Venice | VII |
| Genoa | VII |

- **Areas in play by player count:**

| Players | Capitals in play | Areas in play |
| --- | --- | --- |
| 3 | Venice, Genoa, Barcelona | IV–VIII |
| 4 | + Paris | III–VIII |
| 5 | + London | II–VIII |
| 6 | + Hamburg | I–VIII |

- Provinces and coastal waters in unused areas do not exist for any purpose. Sea areas always exist.
- Some seas belong to geographical areas and lie within their printed area borders. This membership
  does not remove sea spaces when the area's provinces are excluded from a reduced board. The user
  confirmed the following assignments on 2026-10-26 and corrected the Aegean on 2026-10-27;
  all other seas have no area assignment:

| Sea | Area |
| --- | --- |
| Baltic Sea | I |
| Greenland Sea | II |
| Black Sea | V |
| Eastern Mediterranean | VI |
| Adriatic Sea | VII |
| Central Mediterranean | VIII |
| Aegean Sea | VIII |
| Canary Basin | VIII |

- **Area V** (Black Sea, Russia, Anatolia) can only be entered by a player that owns **Overland East**.
- **Iceland** (II) and **West Africa** (VIII) can only be reached by ship, using **The Heavens** or **Seaworthy
  Vessels**.
- **Overseas boxes:**
    - Far East: each box is a market of 5 and produces two commodities.
        - East Indies: Silk, Spice
        - China: Silk, Spice
        - India: Gold, Spice
    - New World: each box is a market of 6 and produces three commodities.
        - North America: Grain, Cloth, Fur
        - South America: Metal, Spice, Gold
    - A domination marker in an overseas box counts toward every commodity it produces.
- **Board errata:**
    - The Bay of Biscay touches the coastal waters from Edinburgh to Seville.
    - Paris connects by coastal water to London and Portsmouth.
    - Bari connects to Corfu.
    - Barca's coastal waters connect to the Eastern Mediterranean. Libya's do not connect to the Central Mediterranean.
    - The Norwegian Sea has no game function.
    - Satellite support exists only where an arrow is printed (for example, St. Gall does not support Nuremberg).
- Unnamed land spaces are impassable.

---

## 3. Setup

Creating a game records the selected variants and starts setup immediately. Players enter their
private views at the first decision; there is no separate Start setup action in the application.

1. **Game length.** Games use three epochs, with no setup option for a shorter game (project
   decision, 2026-10-24). The 26th-advance ending trigger still applies ([§8](#8-end-of-game)).
2. **Pieces.**
    - Each player places one domination marker on its capital.
    - The remaining markers go on the mat circles; the "0" circle is never covered.
    - All tokens go to stock.
    - Misery markers start at 0.
    - No player has ships.
3. **Cards.** Shuffle epoch 1 and deal **one** card per player, applying any selected initial-deal
   restriction ([§13](#13-variants)). Excluded cards remain available for later draws.
4. **Capital bid.**
    - Each player secretly writes a bid of $0–$40.
    - Players choose a capital in descending bid order, from the capitals in play. Ties are broken by die roll, and the
      higher roll chooses first.
    - **Every player pays their own bid.** Starting cash is $40 minus the bid.
    - The capital order is kept; it breaks later turn-order ties ([§5.1](#51-order-of-play)).

Setup ends after capital selection. Turn 1 begins with the regular Order of Play phase
([§5.1](#51-order-of-play)), which establishes that turn's order and expansion pools.

---

## 4. Turn Structure

Each turn has the phases below, in this order. Within every phase, players act **one at a time in current turn order**,
unless the phase is simultaneous.

| # | Phase | Summary |
| --- | --- | --- |
| 1 | [Order of Play](#51-order-of-play) | Secretly buy expansion tokens and establish this turn's order. |
| 2 | [Draw Card](#52-draw-card) | First player may cancel a shortage/surplus; every player draws one card. |
| 3 | [Buy Card](#53-buy-card) | Urban Ascendancy owners may buy one extra card; Master Art owners may discard one. |
| 4 | [Play Cards](#54-play-cards) | Players play any number of commodity, event and leader cards. |
| 5 | [Purchase](#55-purchase) | Ship upgrade → advances → stabilization → misery relief. |
| 6 | [Expansion](#56-expansion) | Place expansion tokens, compete for markets, buy cards with tokens. |
| 7 | [Income](#57-income) | Flip pieces, collect income, roll shortages/surpluses. |

After the last turn's Income phase comes the [Final Card Play](#8-end-of-game).

**Renaissance swap.** At the start of any phase, a player owning Renaissance may swap order with an adjacent player that
does not own it ([§10.3](#103-advance-effects)).

---

## 5. Phases in Detail

### 5.1 Order of Play

1. **Secret purchase (simultaneous).**
    - Each player secretly records its total cash, the number of tokens it buys, and the cash remaining (its *written
      cash*).
    - Tokens cost $1 each.
    - The absolute bid must not exceed the player's available cash.
    - **Negative bid.** A player may instead bid a negative amount *−k*. It pays $k, receives no tokens, and acts
      before any non-negative bid. More negative bids act earlier.
    - **Over-bid.** A player may bid more tokens than its stock holds. It pays the full bid and receives only what stock
      holds.
2. **Reveal and pay.** Each player pays the bank and moves the purchased tokens from stock to its expansion pool.
3. **Reorder.**
    - Players are ordered by **signed bid, lowest first**. An over-bid retains its full amount for ordering, even if
      fewer tokens are available in stock.
    - Ties: the player that chose its capital later decides its position relative to the other tied players.
      Resolve a tied group in capital-choice order: the earliest chooser forms the initial sequence, and each later
      chooser inserts itself before, between or after those already placed. Later capital choosers decide last.
    - Players in Chaos count as bidding 0.
4. **Boxes.** Players occupy the order-of-play boxes for the player count:

| Players | Boxes used |
| --- | --- |
| 3 | 2, 4, 6 |
| 4 | 1, 3, 5, 6 |
| 5 | 1, 2, 3, 4, 5 |
| 6 | 1, 2, 3, 4, 5, 6 |

5. **Advance bookkeeping.** At the start of this phase, previously purchased advances become "old". This matters for
   misery relief, which uses only the MR of advances bought later in the current turn.

The order established here applies from Draw Card onward. The purchased pool is available during the same turn's
Expansion phase; neither the order nor the pool is deferred to a different turn.

Recorded cash at the end of each turn is public.

### 5.2 Draw Card

1. **Cancel shortage/surplus.**
    - The player first in turn order may pay a commodity's base value ([§9.1](#91-commodity-values)) to remove shortage/surplus markers.
    - Each marker is removed separately. Gold and Ivory share the roll "12", so removing that joint marker costs $10 once. Double markers may each be bought off separately (Boone FAQ §3.2g).
2. **Draw.**
    - Each player in turn order draws one card.
    - When the draw pile is empty, the next epoch begins ([§11.1](#111-epochs-and-recycling)).

### 5.3 Buy Card

- **Urban Ascendancy.** In turn order, each owner may buy one extra card for $10.
- **Master Art.** Each owner may then discard one card from hand with no effect, whether or not they bought one.

### 5.4 Play Cards

Each player gets one round in turn order: play **any number** of commodity, event and leader cards in any order,
or pass. War ties extend the phase ([§11.4](#114-event-cards)).

**Hand rules:**

- Hand size is unlimited, but every card held raises stabilization cost ([§5.5](#55-purchase)).
- Cards may be held across turns.
- Hands are secret. Showing cards or bribing is forbidden. The penalty is +1 misery and the loss of half of written
  cash.
  This tabletop conduct rule is not implemented or enforced by the application (user decision,
  2026-10-24).

**Commodity cards:**

- When a commodity card is played, every player that dominates at least one province of that commodity is paid
  ([§9](#9-commodities-and-payout)).
- For a dual card (Cloth/Wine, Ivory/Gold), the player names which commodity it counts as.

**Event cards:**

- The player chooses the target where one is needed.
- Unless stated otherwise, effects last for the rest of the turn.
- A voided event becomes a **misery burden** ([§11.4](#114-event-cards)).
- History-card counter symbols refer to coloured counters (AH1996 §11.5). Event replacement tokens,
  Crusades markers and markets ceded in War are coloured; in particular a Crusades marker can be an
  expansion base during the same turn. This differs from white pieces newly placed during Expansion.
- **Implementation inference for an otherwise unplayable target:** a non-burden event with no eligible target may be
  played without naming a target, consuming it with no effect. If any eligible target exists, one must be selected.
  Temporary protection still excludes protected targets; it does not create a permanent burden. This keeps mandatory
  Final Card Play possible when every potential target is temporarily protected or no eligible marker remains.

**Leader cards** ([§11.3](#113-leader-cards)):

- The first leader played in a turn goes in the Leader Display and benefits only its player.
- Later leaders lie in front of their players. Owners of **Patronage** may claim their discounts.
- The player of an exposed leader may put *protection money* on it.
    - A Patronage claimant must pay that amount to the leader's player.
    - All protection money goes to the bank at the end of the Purchase phase.
- Leaders are removed from play after the Purchase phase.

**Losing a domination marker to an event:**

- When an event reduces a domination marker to a token and the victim has no tokens in stock, the victim may take the
  token from their expansion pool.
- Otherwise the province is left empty.

### 5.5 Purchase

Each player in turn order completes these steps in sequence:

1. **Ship upgrade.** At most one upgrade per turn, at $10 ([§6.4](#64-shipping)).
2. **Advances.**
    - A player may buy any number of advances.
    - A prerequisite may be bought earlier in the same step.
    - Apply pricing, credits, discounts and Printed Word rebates as specified in [§10.2](#102-pricing).
    - An advance's ability takes effect immediately.
    - An advance's credit only takes effect in the subsequent round.
    - A **Papal Decree** in force may ban one category.
    - Each **Religion** advance bought raises misery by one space.
3. **Stabilization.**
    - Pay the triangular cost of the number of cards in hand, n· (n+1)/2: 0, 1, 3, 6, 10, 15, 21, 28, 36, 45, …
    - **Middle Class** halves this, rounded up.
    - Partial payment is not allowed. A player that does not pay instead moves up the misery track by the fewest spaces
      whose step values add up to at least the cost ([§7.2](#72-paying-in-misery)).
4. **Misery relief** ([§7.3](#73-misery-relief)).

### 5.6 Expansion

1. **Civil War.** Civil War's turn-order penalty takes effect at the start of this phase.
2. **Holy Indulgence.**
    - Each owner gains 2 stock tokens into its pool per non-owner, or $1 from the bank per missing stock token.
    - Each non-owner returns 2 pool tokens to stock per owner, paying $1 per missing token.
3. **Placement.**
    - In turn order, each player places its whole expansion pool ([§6](#6-expansion-placement-and-movement)).
    - It may fight competitions ([§6.6](#66-competition)) and may buy one card with tokens:
        - The price rises with each card bought by *anyone* this phase: 3, 6, 9, 12, 15, 18 tokens.
        - At most one card per player per phase.
    - Unplaced tokens return to stock. They cannot be saved.
4. **Expansion bonus.**
    - The player with the most *new* (white) domination markers draws one free card.
    - Ties go to the player that expanded earliest.
    - **Rebuilt-marker option:** markers rebuilt after an event count only when
      `rebuilt-marker-bonus` is `include`. The default is `exclude`. This setup choice is frozen
      when the game starts; both interpretations are supported.
    - Rebuilding a marker voluntarily exchanged for a token never earns bonus credit.

### 5.7 Income

1. **Flip.** All white pieces become coloured.
2. **Income.**
    - Each player collects the income printed in the last uncovered circle on its mat. The circles match the number
      of domination markers on the map; read the value for the number of players that *started* the game:
      `15 + (uncovered circles - 1) * (initial player count)` [data].
    - **Middle Class** adds $10.
3. **New World.** Each owner improves misery by one space.
4. **Interest & Profit.**
    - Each owner doubles its cash on hand, but the gain is capped at this turn's income (Middle Class bonus included).
    - This turn's income itself is not doubled.
    - Example: $108 on hand and $100 income gives +$100.
5. **Shortage/surplus.**
    1. Remove old markers.
    2. Roll all three dice twice.
        - Each time, white + black picks the commodity ([§9.1](#91-commodity-values)); "12" is both Gold and Ivory.
        - Green 1–3 is a **shortage** and 4–6 is a **surplus**.
    3. A shortage and a surplus on the same commodity cancel.
    4. Two of the same kind on one commodity apply to the first two payouts of that commodity, not twice to one payout.
    5. The **majority holder** acts now: the player dominating strictly more provinces of that commodity than any
       other. A tie means none.
        - Shortage: the majority holder draws a free card (one per marker).
        - Surplus: the majority holder pays $1 per province of that commodity it dominates (once per marker).

    - A marker is consumed when a card of that commodity is played. Remaining markers are removed at the start of
      Income's shortage/surplus step, before rolling new markers.

---

## 6. Expansion, Placement and Movement

### 6.1 Bases

- A player may place tokens reachable from any province where it has a **coloured** token or domination marker.
- Its **home capital** is always a base, even if another player dominates it; a player can never be wiped off
  the board.
- Pieces placed this turn are not bases this turn. The one exception is Caravan's attack-through ([§6.3](#63-caravan)).
- Pieces never move once placed. They leave the board only through competition, events or the voluntary swap
  ([§6.5](#65-domination)).

### 6.2 Overland

A player may place any number of tokens into a province that shares a **land border** with a base, subject to the
stacking limits below.

### 6.3 Caravan

**Caravan** owners may place up to two land provinces away from a base.

- The province in between must not be **controlled** by another player. White pieces and partial token sets do not
  block.
- Placing tokens in the intermediate province is optional.
- If the intermediate province is full (at its market number), the player must win a competition there before
  passing through. The official May 1998 errata confirms this requirement.
- A player may attack a second province *through* a province it has just won.
- Caravan cannot be combined with shipping in one placement.

### 6.4 Shipping

- **Delivery rules.**
    - Ships deliver tokens only to coastal provinces.
    - Tokens may never end at sea.
    - Shipping capacity is counted **per destination province per turn**, so the total shipped in a turn is unlimited.

- **Fleet types.** These are on the ship upgrade display. Each upgrade costs $10, and only one is allowed per turn.

| Fleet | Levels | Reach | Capacity per province |
| --- | --- | --- | --- |
| Galleys | 2, 4, 6, 8 | Up to *level* coastal-water spaces from a base. Travels only through light-blue coastal waters. | *level* tokens |
| Seaworthy Vessels (S) | 10, 12, 14, 16 | Any coastal province on the main map. Not overseas boxes; Area V still needs Overland East. | *level* tokens |
| Ocean Navigation (T) | 1, 2, 3, 4 | Any coastal province, plus the Far East. Also the New World if the player owns **New World**. | unlimited |

- **Changing fleet type.**
    - Buying Seaworthy Vessels moves the marker to *Seaworthy 10*, and buying Ocean Navigation moves it to *ON 1*.
      Earlier upgrades, including one bought this turn, are lost.
    - Seaworthy Vessels may be bought before reaching Galley 8.
- **Overseas limit.** The Ocean Navigation level is the number of overseas boxes in which the player may have *any*
  piece.
- **The Heavens.**
    - Galleys of an owner may cross one sea area per shipment as if it were coastal water. The crossing counts as one
      space.
    - The limit is one sea per shipment, not one per turn.
    - The Heavens or Seaworthy Vessels gives access to Iceland and West Africa.

### 6.5 Domination

- **Stacking.**
    - The tokens in a market may never exceed its market number, except while attacking.
    - A satellite holds at most one token in total.
    - A dominated province holds only its marker. No extra tokens may be added to it, except as part of an attack on it.
- **Coexistence.** Tokens of several players may coexist in a market as long as the total stays at or below the market
  number. No competition occurs.
- **Domination.**
    - When a player has tokens equal to the market number in a market with no other pieces, those tokens are replaced by
      one white domination marker, taken from the mat.
    - The tokens go back to **stock**.
- **Voluntary swap.** A player may replace one of its domination markers with a token in order to reuse the marker
  elsewhere. This may not be done to create "new" markers for the expansion bonus.

### 6.6 Competition

A competition is required when placing tokens would push a market above its market number, or when the market holds a
domination marker.

- A token cannot be attacked without attacking the whole province.
- There is no limit on attacks except tokens and shipping capacity.
- A failed attack may be repeated in the same phase with fresh tokens.

**Tokens required**

The tokens required are the **market number + the defenders' strength + modifiers**, but never fewer than the market
number.

- **Defender strength:**
    - tokens present, or the market number for a domination marker.
    - Against several defenders, add all their strengths. A plus-modifier applies if it applies for *any* defender. A
      minus-modifier applies only if it would apply against *every* defender.
- **Attacker's existing tokens.** The attacker's own tokens already in the province count toward the requirement.
- **Exact commitment (project ruling).** The existing attacker tokens plus newly committed tokens
  must equal the calculated requirement. Reject both insufficient commitments and excess tokens
  before taking any tokens or requesting a roll. The restriction is against exceeding the attack
  requirement, not against exceeding the province's market number.
- **Capital doubling.** The original owner's pieces count double in its own capital, for both defence and attack. Apply
  doubling after Nationalism.
    - Defending: Genoa's marker in Genoa (market 5) needs 5 + 2·5 = **15** tokens to attack.
    - Attacking: Genoa attacking Paris's marker in Genoa needs ⌈ (5 + 5) / 2⌉ = **5** tokens.

**Modifiers** (in tokens)

| Source | Effect |
| --- | --- |
| Defender's supporting satellite | +1 per satellite holding the defender's coloured token whose arrow points at the province. Applies only to a domination marker. |
| Attacker's supporting satellite (Cosmopolitan) | −1 per such satellite of the attacker. |
| Nationalism | In the owner's home area: −1 when the owner attacks, +1 when the owner defends. Cancels if both sides own it. |
| Military advantage (Armor, Stirrups, Long Bow, Gunpowder) | −1 when the holder attacks, +1 when the holder defends. Armor + Stirrups together give 2. See [§11.4](#114-event-cards) for which cards beat which. |

**Resolution**

Roll all three dice. The **attacker wins** if

- the **black die > white die**, or
- the **green die > the attacker's order-of-play box number**.

Otherwise the defender wins.

- **Military advantage.** An attacker with a military advantage that applies against this defender also wins on black =
  white.
- **Proselytism.** The attacker also wins on green = box number.
- **Cathedral.** Instead of rolling, a Cathedral owner may declare an automatic win. It works once per turn against each
  non-owner, on attack or defence. A province where any defender owns Cathedral cannot be taken this way. It does not
  apply to War.

The attacker's chance without modifiers is 1 − (box/6)· (21/36). For box 1 to 6 this gives 90.3%, 80.6%, 70.8%, 61.1%,
51.4% and 41.7%.

**Outcome**

- **Attacker wins:**
    - All defending pieces in the province return to their owners' stocks or mats.
    - If the attacker's tokens reach the market number, they become a white domination marker.
    - Defenders' satellite tokens are not affected.
    - Domination conversion returns the attacker's tokens to stock as in §6.5. An exact required
      commitment may exceed the market number because it must also overcome defenders and modifiers;
      no tokens beyond that calculated requirement may be committed.
- **Defender wins:** **all** attacking tokens committed, including those already present, return to stock.

**Wind/Watermill (baseline edition).** Right after taking a province the victim *controlled*, the winner may try a card
trade. This works once per turn, and not after taking a province where the players merely coexisted.

1. The winner pays tokens equal to the market number and rolls one die.
2. On a roll ≤ the market number, the winner gives the victim one card of the winner's choice and takes one random card
   from the victim. Both must hold cards.

---

## 7. Misery

### 7.1 Track

- Each player's misery marker starts at 0 and never goes below 0.
- The track is a ladder of increasing **box values** [data]. Known values:
    - Steps of 10 from 0 to 100 (0, 10, 20, ..., 100).
    - Steps of 25 from 125 to 200.
    - Steps of 50 from 250 to 500.
    - Steps of 100 from 600 to 1000.
    - The last box is 1000.
- Effects move the marker in **spaces** (boxes). Costs and final scoring use box **values**.
- Moving past 1000 means **Chaos**. The player is eliminated:
    - its cards are discarded and its pieces stay on the board as neutral defenders (its Nationalism still applies);
    - it counts as bidding 0 in Order of Play;
    - income tables and order-of-play boxes still use the starting player count;
    - other players' advances cannot be used against its pieces.

### 7.2 Paying in misery

When a player cannot or will not pay stabilization, its marker moves up by the fewest spaces whose step values add up to
at least the cost.

- Example: at 90, owing $15. One space (90→100) is only worth $10, so the marker moves two spaces to 125.
- Example: at 100, owing $21. One space (100→125) is worth $25, so one space is enough.

### 7.3 Misery relief

- **Tiers.**
    - A player's *tier count* is the minimum number of advances it owns in any of the six categories.
    - It needs at least one tier to buy relief at all.
- **How far.** Per turn, a player may move down up to one space per tier.
  The application shows this decision for players with a completed tier even when they cannot
  afford relief or are already at zero misery, explaining the limit and offering Pass.
- **Cost.**
    - Each space costs the difference between the two box values.
    - The **MR** ratings of advances bought *this turn* are pooled and reduce the cost. Unused MR is lost.
    - **Enlightenment** halves the cost.

### 7.4 Misery triggers

| Trigger | Change |
| --- | --- |
| Buying a Religion advance | +1 space |
| Buying Human Body | −1 space |
| Buying Improved Agriculture | −1 space |
| New World owner, each Income phase | −1 space |
| Misery relief | −1 space per tier per turn, paid in cash |
| Unpaid stabilization | + spaces ([§7.2](#72-paying-in-misery)) |
| Famine | every player +4, −1 per Grain market dominated, −1 for Improved Agriculture, to a minimum of 0 |
| Mysticism Abounds | every player +4, −1 per Science advance owned |
| Revolutionary Uprisings | every player +1 per Commerce advance owned |
| Religious Strife | every player +1 per Religion advance owned |
| Civil War | victim +1 |
| The Crusades | player +1 |
| War! | winner +1, loser +2; each tie gives both +1 |
| Showing cards or bribery | +1 |

---

## 8. End of Game

The game ends **at the end of the turn** in which:

- a player acquires its 26th advance; or
- the last card of the final epoch's deck is drawn.

The final turn is played in full, including Income, then proceeds to Final Card Play. Once the final deck is exhausted,
no more cards can be drawn for any reason.

### Final Card Play

This step is part of the final turn and takes place after its Income phase. In turn order, each player plays **all**
cards left in its hand, in any order it chooses. Misery burdens are not played.

- Shortages and surpluses rolled in the final Income phase apply, and nobody may buy them off.
- Master Art cannot discard cards here.
- Enlightened Ruler still protects its player, because this step is the same turn.
- Civil War here takes no cash. Its token half applies, which is half of zero.
- A tied War is left unresolved.

**Score.** Each player scores the total cost of its advances, plus its cash, minus the **value** of its misery box. The
highest score wins. A tie results in more than one winner.

---

## 9. Commodities and Payout

### 9.1 Commodity values

| Commodity | Base value | Dice roll (white+black) |
| --- | ---: | ---: |
| Stone | 1 | 2 |
| Wool | 2 | 3 |
| Timber | 3 | 4 |
| Grain | 4 | 5 |
| Cloth | 5 | 6 |
| Wine | 5 | 7 |
| Metal | 6 | 8 |
| Fur | 7 | 9 |
| Silk | 8 | 10 |
| Spice | 9 | 11 |
| Gold | 10 | 12 |
| Ivory | 10 | 12 |

### 9.2 Payout

When a commodity card is played, each player dominating **n ≥ 1** provinces of that commodity receives the amount in
**box n** of that commodity's payout track. Box n pays **base value × n²**. For example, 3 Wine markets pay 5 × 9 = $45.

- **Shortage.** Read one box higher (n + 1).
- **Surplus.** Read one box lower (n − 1). A single province on surplus pays $0.
- **Industry.** The owner reads one box higher than it otherwise would.
- **Overseas boxes.** An overseas box counts as one province for each commodity it produces.
- Commodity cards print ten payment boxes (AH1996 §7.11). Board ownership-track lengths count
  markets; they do not cap payments. The quadratic formula continues beyond the printed boxes;
  nine Wool markets plus Industry and a shortage pay `2 × 11² = $242` (user ruling, 2026-10-23).

---

## 10. Advances

### 10.1 Table

- **Cost** is both the base price and the end-game points.
- **Cr** is the credit this advance gives toward *other* advances in the same category, once owned.
- **MR** is the misery-relief rating ([§7.3](#73-misery-relief)).

| Ltr | Advance | Category | Cost | Cr | Prereq | MR |
| --- | --- | --- | ---: | ---: | --- | ---: |
| A | The Heavens | Science | 30 | 20 | – | 5 |
| B | Human Body | Science | 60 | 20 | – | 10 |
| C | Laws of Matter | Science | 90 | 20 | – | 5 |
| D | Enlightenment | Science | 120 | 20 | – | 50 |
| E | Patronage | Religion | 30 | 20 | – | 10 |
| F | Holy Indulgence | Religion | 60 | 20 | – | 0 |
| G | Proselytism | Religion | 90 | 20 | – | 0 |
| H | Cathedral | Religion | 120 | 20 | F | 25 |
| I | Caravan | Commerce | 20 | 10 | – | 5 |
| J | Wind/Watermill | Commerce | 40 | 10 | I | 5 |
| K | Improved Agriculture | Commerce | 50 | 10 | J | 25 |
| L | Interest & Profit | Commerce | 80 | 10 | K | 0 |
| M | Industry | Commerce | 110 | 0 | L | 5 |
| N | Written Record | Communication | 30 | 10 | – | 5 |
| O | Printed Word | Communication | 60 | 10 | N | 10 |
| P | Master Art | Communication | 90 | 10 | O | 5 |
| Q | Renaissance | Communication | 120 | 0 | P | 100 |
| R | Overland East | Exploration | 40 | 20 | – | 5 |
| S | Seaworthy Vessels | Exploration | 80 | 20 | – | 5 |
| T | Ocean Navigation | Exploration | 120 | 20 | A, S | 5 |
| U | New World | Exploration | 160 | 0 | T, V | 25 |
| V | Urban Ascendancy | Civics | 20 | 10 | – | 5 |
| W | Nationalism | Civics | 60 | 20 | – | 5 |
| X | Institutional Research | Civics | 100 | 30 | – | 10 |
| Y | Cosmopolitan | Civics | 150 | 40 | R | 25 |
| Z | Middle Class | Civics | 170 | 50 | K | 50 |

Category totals are 300 each for Science, Religion, Commerce and Communication, 400 for Exploration, and 500 for Civics.
The maximum is 2100.

### 10.2 Pricing

The price of an advance is its cost minus all of the following, with a floor of 0:

- the **Cr** of every owned advance in the same category *before this purchasing phase*;
- **Institutional Research**, -10 if owned and the advance is in Science, Commerce, Communication or Exploration;
- **leader discounts** from leaders played this turn that the buyer may use, meaning its own leaders and Patronage
  claims ([§11.3](#113-leader-cards)).

**Written Record** adds +$10 to each leader discount the owner uses.

Excess discount is lost, with one exception: **Printed Word**.

- When an owner plays a leader that discounts an advance the owner *already* has, it receives that discount as an
  immediate cash rebate.
- A leader with two such advances pays two rebates.
- Patronage and Written Record never apply to rebates.

### 10.3 Advance effects

| Advance | Effect |
| --- | --- |
| A The Heavens | Galleys may cross one sea per shipment.<br>Gives access to Iceland and West Africa. |
| B Human Body | −1 misery space on purchase. |
| C Laws of Matter | Owner is immune to Alchemist's Gold. |
| D Enlightenment | Misery relief costs half. |
| E Patronage | May use the discounts of leaders played by others, except the leader in the Leader Display.<br>Must pay any protection money placed on that leader to its player.<br>Cannot be combined with Printed Word rebates. |
| F Holy Indulgence | Token transfer at the start of Expansion ([§5.6](#56-expansion)). |
| G Proselytism | Wins competitions on green ≥ box number ([§6.6](#66-competition)). |
| H Cathedral | One automatic competition win per turn against each non-owner ([§6.6](#66-competition)). |
| I Caravan | Places tokens up to two land provinces away ([§6.3](#63-caravan)). |
| J Wind/Watermill | Card trade after a takeover ([§6.6](#66-competition)). |
| K Improved Agriculture | −1 misery space on purchase.<br>−1 to Famine. |
| L Interest & Profit | Doubles cash in the Income phase, capped at income ([§5.7](#57-income)). |
| M Industry | One payout box higher for the owner ([§9.2](#92-payout)). |
| N Written Record | +$10 to each leader discount the owner uses. |
| O Printed Word | Rebates for already-owned advances ([§10.2](#102-pricing)). |
| P Master Art | May discard one card per Buy Card phase. |
| Q Renaissance | Once per turn, at the start of any phase, may swap order with an adjacent player (directly above or below) that does not own Renaissance. The swap lasts for the rest of the turn.<br>Simultaneous requests resolve in turn order.<br>Usable after Civil War has moved the owner last. |
| R Overland East | Allows entry into Area V. |
| S Seaworthy Vessels | Switches the fleet to Seaworthy 10 ([§6.4](#64-shipping)). |
| T Ocean Navigation | Switches the fleet to ON 1.<br>Gives access to the Far East, subject to the overseas limit. |
| U New World | Gives access to the New World.<br>−1 misery space each Income phase. |
| V Urban Ascendancy | May buy one extra card for $10 in the Buy Card phase. |
| W Nationalism | Competition modifier in the home area ([§6.6](#66-competition)).<br>+1 on War dice. |
| X Institutional Research | −10 on every Science, Commerce, Communication and Exploration advance. |
| Y Cosmopolitan | The owner's satellites support its attacks ([§6.6](#66-competition)). |
| Z Middle Class | +$10 income.<br>Halves stabilization cost. |

---

## 11. History Cards

### 11.1 Epochs and recycling

- Each card carries its epoch number, printed in a **circle** (the card is recycled) or a **square** (the card leaves
  the game after use) [data]. Recycling classifications are verified against the Liumas card
  reference, PDF pages 2–3; see the [card inventory](card-inventory.md).
- When a card must be drawn and the pile is empty:
    - the new pile is the **next epoch's deck** plus all **circle cards already in the discard pile**;
    - cards still in hands are not included.
- After the final epoch, no more cards can be drawn.

| Epoch | Years | Commodity | Event | Leader | Total |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | 750–1250 | 11 | 13 | 5 | 29 |
| 2 | 1250–1500 | 9 | 5 | 8 | 22 |
| 3 | 1500–1750 | 7 | 0 | 6 | 13 |

### 11.2 Commodity cards

| Epoch | Cards |
| --- | --- |
| 1 | Stone ×2, Wool ×2, Timber, Cloth/Wine, Ivory/Gold, Metal, Fur, Silk, Spice |
| 2 | Timber ×2, Grain ×2, Cloth, Wine, Metal, Silk, Spice |
| 3 | Cloth, Wine, Metal, Fur, Silk, Spice, Gold |

### 11.3 Leader cards

Leader cards are free to play. Their discounts apply only in the Purchase phase of the turn they are played.

| Epoch | Leader | Discounts |
| --- | --- | --- |
| 1 | Charlemagne | Nationalism 20 |
| 1 | Dionysus Exiguus | Written Record 20 |
| 1 | Rashid ad Din | Written Record 10, Overland East 10 |
| 1 | St. Benedict | Written Record 10, Patronage 10 |
| 1 | Walter the Penniless | Overland East 20 (30 if The Crusades was played this turn) |
| 2 | Christopher Columbus | New World 30, Ocean Navigation 30 |
| 2 | Desiderius Erasmus | Printed Word 20, Renaissance 20 |
| 2 | Ibn Majid | Ocean Navigation 20, Cosmopolitan 20 |
| 2 | Johann Gutenberg | Printed Word 30 |
| 2 | Marco Polo | Cosmopolitan 20, Overland East 20 (40 each if Mongol Armies has been played) |
| 2 | Nicolaus Copernicus | The Heavens 20, Institutional Research 20 |
| 2 | Prince Henry | Ocean Navigation 20, Institutional Research 20 |
| 2 | William Caxton | Printed Word 20 |
| 3 | Andreas Vesalius | Human Body 20, Enlightenment 20 |
| 3 | Bartolomé de Las Casas | Cosmopolitan 30 |
| 3 | Galileo Galilei | The Heavens 20, Renaissance 20 |
| 3 | Henry Oldenburg | Enlightenment 30 |
| 3 | Leonardo da Vinci | Master Art 20, Human Body 20, Renaissance 20 |
| 3 | Sir Isaac Newton | Laws of Matter 20, Enlightenment 20 |

### 11.4 Event cards

**General rules:**

- A **voided** event that is still in hand becomes a **misery burden**.
    - It cannot be played, counts for stabilization, and cannot be discarded except by Master Art or traded away by
      Wind/Watermill.
- Alchemist's Gold becomes a burden once every active player owns Laws of Matter; Mysticism Abounds becomes a burden
  once every active player owns all four Science advances. These are explicit permanent card conditions. Temporary
  Enlightened Ruler protection filters event targets for the turn and does not permanently turn another card into a burden.

**Epoch 1**

- **Alchemist's Gold**
    - The target player pays half its written cash to the bank, capped at its current cash.
    - Protection money already on leaders is safe.
    - Laws of Matter makes a player immune.
- **Armor**
    - Military advantage for the rest of the turn ([§6.6](#66-competition)), including War.
- **Stirrups**
    - As Armor, but it does not apply against the player of Armor.
    - It can be combined with Armor for a total of 2.
- **Civil War** (on target player)
    - The target gains +1 misery.
    - Its capital's domination marker, if any, becomes a token.
    - It loses either half its last recorded cash or half its expansion tokens; the target chooses.
    - It moves to last in turn order at the start of Expansion.
- **Enlightened Ruler** (on oneself)
    - For the rest of the turn, the player is immune to Mysticism Abounds, Religious Strife, Civil War, Revolutionary
      Uprisings, Rebellion and Alchemist's Gold.
    - It does not undo effects already applied.
- **Famine**
    - See [§7.4](#74-misery-triggers).
- **Mysticism Abounds**
    - See [§7.4](#74-misery-triggers).
    - It becomes an unplayable misery burden once every active player owns all four Science advances.
- **Papal Decree**
    - The player may forbid all players from buying one category (Science, Religion or Exploration) this turn.
    - It is voided by Religious Strife in the same turn.
    - It becomes a burden once Religious Strife has occurred in epoch 3.
- **Pirates/Vikings**
    - Reduce domination markers in coastal provinces of the player's choice to tokens: 1 marker in epoch 1, 2 in epoch
      2, 3 in epoch 3.
    - It may hit overseas boxes.
- **Rebellion**
    - Reduce one domination marker in any province to a token.
    - It may not target a New World box or a foreign capital.
- **Revolutionary Uprisings**
    - See [§7.4](#74-misery-triggers).
- **The Crusades**
    - Place one of the player's domination markers in any market of Area VI, removing whatever is there.
    - The player gains +1 misery.
    - Boosts Walter the Penniless.
    - It becomes a burden once Mongol Armies has been played.
**Physical marker exhaustion (project ruling, 2026-10-23).** After Crusades or War removes the
destination's old pieces, place a coloured marker from stock if available. With all 25 deployed,
the receiving player chooses separately for each award: forgo the new marker, or move a coloured
marker from another controlled province. A moved marker leaves its source empty and spends no
square tokens. A forgone destination stays empty. The card's other effects still resolve; a War
loser gains no additional misery for a marker its winner forgoes. Existing access and overseas
support limits still govern which provinces may be ceded. Voluntarily vacated sources do not earn
expansion-bonus credit if rebuilt later in the turn.

- **War!** (on target player)
    1. Both players roll one die. Nationalism and military advantage each add +1.
    2. The higher roller gains +1 misery and the lower +2.
    3. The loser cedes a number equal to the roll difference (after modifiers) of domination markers (province control)
       to the winner. The loser chooses the provinces and removes their markers, the winner adds their markers instead.
       Only provinces the winner can support may be ceded: Area V requires Overland East, overseas boxes require Ocean
       Navigation, and the New World also requires New World. Shipping range does not restrict ceding a province. If the
       loser cannot cede the required number, it gains one additional misery space for each missing marker (AH1996 §11.5).
    4. **On a tie,** both gain +1 misery and roll again at the start of each following player's Play Cards round.
        - A military-advantage card played before a re-roll still applies.
        - A sole applicable military advantage wins a modified tie, as the military cards specify. The modified roll
          difference is then zero, so no markers change hands, but winner/loser misery still applies.
        - A tied War can continue into the next turn's Play Cards phase. It ends unresolved only after the last player's
          Final Card Play round (official AH errata, 7 May 1998, §11.2 and §11.4).

**Epoch 2**

- **Black Death**
    - Choose an area. Every token there returns to its stock.
    - Every domination marker there is reduced to a single token.
- **Gunpowder**
    - Military advantage.
    - Voids Armor and Stirrups. Unplayed copies become burdens; copies already played this turn stop working against the
      Gunpowder player.
- **Long Bow**
    - As Gunpowder, but it does not apply against the player of Gunpowder.
- **Mongol Armies**
    - The player collects $10.
    - Marco Polo's discounts double if he is played afterwards.
    - Voids The Crusades.
- **Religious Strife**
    - See [§7.4](#74-misery-triggers).
    - Voids Papal Decree.

---

## 12. Information and Randomness

These notes are for the engine.

**Hidden information:**

- History-card hands and drawn-card identities; only their owner sees them.
- The written cash and token bids during Order of Play, until revealed.
- The capital bids during setup, until revealed.
- In the improved setup, which card is returned.

**Public:**

- Recorded end-of-turn cash, the board, advances owned and misery.

**Random events:**

- Card shuffles at epoch changes.
- The capital-bid tie-break roll.
- Competition rolls (3 dice).
- War rolls.
- The Wind/Watermill roll.
- The shortage/surplus rolls (3 dice, twice per turn).
- Random card selection in Wind/Watermill trades.

**Decisions by players other than the active one:**

- Patronage claims and protection money.
- Renaissance swaps at phase start.
- Civil War victim's choice.
- War loser's choice of markers.
- Cathedral declared by a defender.
- Order-of-play tie choice.

---

## 13. Variants

The baseline remains the reviewed AH1996 rules, with these independent choices recorded before play:

- **Deck modification, off by default:** exchange the epoch-2 physical card `timber-2-1` and the
  epoch-1 War card. Timber moves to deck 1; War moves to deck 2. All 64 physical cards remain present.
- **Initial card deal, mutually exclusive:** unrestricted (default), no Crusades, no events, or only
  commodities. Each player receives one card. No-events permits leaders; commodity-only excludes
  both events and leaders. Excluded cards remain in the deck for later draws.
- **Immediate Master Art discard, off by default:** buying Master Art immediately offers its
  optional discard, in addition to the ordinary Buy Card phase ability.
- **European map, off by default:** start from the standard board, remove Smolensk and its incident
  connections, and add inland Loire in Area III (market 3, Wine). Loire borders St. Malo, Paris and
  Bordeaux; St. Malo and Bordeaux no longer share a land border. Add London–Chester and
  Novgorod–Kiev land borders. Remove Eastern Mediterranean contacts with Crete, Libya and Barca;
  add Central Mediterranean contacts with Libya and Alexandria. Merge the Aegean into the Central
  Mediterranean: all former Aegean coastal contacts belong to the combined Central Mediterranean
  in Area VIII, and the Aegean no longer exists as a separate sea. A Heavens crossing between any
  two of the combined sea's coasts counts as one sea. Coastal-water steps, capitals,
  area cutoffs and other facts follow the standard board. Loire is available with four or more
  players; the full map has seven Wine markets. Commodity payments keep the usual quadratic rule.

Choose variants on the same screen as player registration. Every resolved option appears in the
initial command log; creation automatically records `start` and freezes the options. Earlier presets
and all other optional mechanics are retired. Event-rebuilt markers do not count toward the expansion
bonus; voluntary rebuilds do not count either. The [command reference](commands.md) owns exact keys
and values.

---

## 14. Open Questions

The recycling ruling below remains part of the baseline. Earlier improved-setup rulings are historical; that variant was retired on 2026-10-27.

**Verified recycling classification (2026-10-24):**

- **Q7:** the user-supplied Liumas card reference confirms that every leader plus Armor,
  Stirrups, Long Bow, Gunpowder and Mongol Armies is removed after use. All other cards recycle:
  40 recyclable and 24 single-use cards. PDF page 2 marks event recycling; page 3 specifies that
  all commodities recycle and no leaders do. Every implemented status matches this reference.

**Historical reinsertion ruling (retired 2026-10-27):**

- **Q14 / improved setup:** the user approved shuffling the five held-out cards back in after
  turn 1's regular Draw Card phase for 3–4 players, or turn 2's for 5–6 players. Published sources
  disagree; this is the approved project rule. Alternate timings may be introduced later as
  separate options and are not part of the current implementation.

**Resolved setup compatibility ruling (2026-10-24):**

- The user selected treating "get three, choose two" as incompatible with reduced initial-deal
  decks for now. The current reduced-deck option is commodity-only initial dealing; it cannot
  be combined with improved setup. Each option remains available independently.

**Additional approved rule defaults (2026-10-24):**

- **Pirates/Vikings:** select the full epoch-dependent number of distinct eligible coastal
  markers, or all eligible markers when fewer are available.
- **Alternate Wind/Watermill:** making a commodity neutral clears its entire shortage/surplus
  status, including both markers of a double adjustment.
- **Second-edition Patronage credit:** use 20, preserving the reviewed value despite a source
  table that prints 10.
- **Completion-based misery relief:** grant 1/2/3 additional spaces immediately on reaching
  2/3/4 advances in each of Science, Commerce and Communication. Each threshold is awarded once;
  the final threshold requires four in each category, not every Commerce advance.

The user approved these implemented defaults despite the source ambiguities recorded in the
[card inventory](card-inventory.md) and [variant inventory](variant-inventory.md).

**Resolved implementation decisions (2026-10-23):**

- **Q15 / card-marker exhaustion:** the user allows the receiving player to forgo a new marker
  or move one from another controlled province. The vacated source becomes empty; the destination's
  prior pieces are removed even when placement is forgone. A War award declined by its winner
  adds no extra misery to its loser.
- **Q16 / commodity overflow:** the user selected the quadratic progression beyond printed box 10.
  All nine Wool markets plus Industry and a shortage pay $242.

- **Q16 / commodity payout overflow:** the user chose to continue the quadratic formula beyond
  the ten printed payment boxes. Nine Wool markets with Industry and a shortage pay $242.
- **Q10 / attack commitment:** the user disallowed attacks with excess tokens. Commit exactly the
  calculated requirement, including attacker tokens already present; reject extra committed tokens.
- **Expansion bonus (§5.6):** the user requested both interpretations of event-rebuilt markers as
  a game option. `rebuilt-marker-bonus` supports `exclude` (default) and `include`.
- **Caravan (§6.3):** the official May 1998 errata requires a successful competition in a fully
  occupied intermediate province before passing through it.

**Map transcription:** the [catalog inventory](data-inventory.md) records the component references,
province and economic tables, connections and outstanding review questions. The photographed board has no
Loire market, six Area I satellites (including Smolensk), Libya in Area VI and the printed Aegean label.
The user confirmed on 2026-10-23 that Gallipoli and Angora do not touch; they have no direct coastal-water
connection. This resolves the final recorded map question. The inventory records the source checks and ruling.
The user confirmed on 2026-10-25 that Tunis and Sicily touch through their coastal-water spaces.
They share a direct coastal-water connection and no land border.
The user confirmed on 2026-10-26 that Oran and Granada have no direct coastal-water connection,
and that Salonika and Corfu have no coastal-water connection. Salonika and Corfu retain their
land border and printed satellite support.
The user also confirmed on 2026-10-26 that Angora and Constantinople share a land border, in addition
to their coastal-water connection.
Further user corrections on 2026-10-26 remove the Smyrna–Crete and Naples–Sicily coastal connections
and add Bari–Durazzo, Portsmouth–St Malo, and London–Bruges. Bari retains its coastal connection
with Naples. Corsica is part of Cagliari; the northern Greek island landmass is part of Athens;
and Bari includes Italy's toe. Kamyshin connects by land to Sarai and Tana; Tana does not touch Poti.
The [connection inventory](connection-inventory.md) records the complete reviewed graph.
On the standard board, Athens and Crete retain their shared coastal border. On 2026-10-27 the user corrected the sea
transcription: the Aegean and Central Mediterranean are separate seas, both in Area VIII. Crete
adjoins the Aegean, Central Mediterranean and Eastern Mediterranean. This supersedes the earlier
combined-sea interpretation and the exclusion of Crete from the Eastern Mediterranean. A crossing
between Central Mediterranean and Aegean coasts cannot treat the two seas as one Heavens crossing.
The European variant deliberately combines those seas; see [variants](#13-variants).
Further Baltic corrections on 2026-10-27 add the Copenhagen–Stettin coastal-water border and the
Malmo–Kongsberg land border. Malmo does not adjoin the North Sea, and Lubeck does not adjoin the
Baltic Sea. Copenhagen also does not adjoin the Baltic Sea. Visby's only coastal neighbor is
Stockholm; the map must clearly separate it from all
other land/coastal spaces.
Malmo and Lubeck have neither a land nor a coastal-water border; Malmo and Stettin share a
coastal-water border. Copenhagen includes Funen, Zealand and northern Jutland, with a land border
to the remaining southern Hamburg province.
Malmo's only support arrow points to Stockholm; it does not support Lubeck.
The user also confirmed that Paris and Chester have no direct sea-space incidences, and that
Wales and Armagh share a direct coastal-water border. Paris and Chester retain their existing
coastal-water connections.
The Belgrade correction adds Adriatic coastal access and direct coastal-water borders with
Dubrovnik, Durazzo and Bari. Belgrade shares land borders with Dubrovnik and Durazzo; those two
provinces no longer share either a land or a coastal-water border with each other.
Durazzo has no direct sea-space incidences; its coastal-water neighbors remain available.
Smyrna adjoins both the Aegean Sea and Eastern Mediterranean.

---

## Sources

The original rules summary was assembled from secondary sources. The catalog milestone also uses photographs
of the printed board and a scan of the original rulebook; exact locators and source discrepancies are in the
[review inventory](data-inventory.md).

- Printed Avalon Hill board photographs:
  <https://boardgamegeek.com/image/192678/age-of-renaissance>,
  <https://boardgamegeek.com/image/667248/age-of-renaissance> and the sharper
  <https://boardgamegeek.com/image/4041958/age-of-renaissance>.
- Original AH1996 rulebook scan: <https://www.scribd.com/document/709810410/Age-of-Renaissance-regles-VO>.
- Chris Conboy (Liumas), *Age Of Renaissance Cards Reference*, user-supplied local file
  `AOR_Cards_Reference_by_Liumas_2008-09.pdf`: PDF page 2 covers all events and recycling marks;
  page 3 covers commodity quantities and all leader discounts/recycling. The
  [card inventory](card-inventory.md) records artifact metadata, checksum and verification.

- Official Avalon Hill *Errata and Clarifications* (7 May 1998), M. Boone FAQ v0.5 (1999), the EuroGames 2nd-edition
  change list, the Advance Log and the commodity log, all bundled in one
  PDF: <https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf>
- Hexagonia rules rewrite (2017): <http://www.hexagonia.com/rules/AgeOfRenaissance.pdf>
- C. Conboy, *Condensed Complete Rules*
  (2009): <https://tesera.ru/images/items/1180616/AOR_Condensed_Complete_Rules_2009-09.pdf>
- BPA/WBC tournament
  pages: <https://www.boardgamers.org/yearbkex14/aorpge.htm>, <https://www.boardgamers.org/yearbook02/aorpge.htm>
- Card summary: <http://sob-zine.org/PlayerAids/age_of_rennaisance_cards.html>
- Map data cross-checks. These repositories are unlicensed, so they are reference only and their data must not be
  copied:
    - <https://github.com/pjsavola/aor>
    - <https://github.com/brotherBear/aor-java-neo>
    - <https://github.com/alkemann/aor>
