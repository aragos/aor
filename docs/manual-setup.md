# Setup and Order of Play walkthrough

## Create a table

Use the JDK/Node requirements and run commands in [README](../README.md). Start the server with
`./gradlew :server:run`, the client with `npm start`, then open <http://localhost:5173>. The production
web bundle also needs the API server.

Choose Alice, Bob and Carol, the variants and undo policy on the creation form. Every game has three
epochs. Current variants are:

- Swap Timber and War between decks 1 and 2.
- Restrict the opening deal to no Crusades, no events or commodities only. These restrictions are
  mutually exclusive, still deal one card each and leave excluded cards available for later draws.
- Offer an immediate optional discard when Master Art is acquired.
- Use the European map, including Loire in games with four or more players.

Creation records every resolved option in revision 1 and automatically starts setup and records the
opening shuffle/deal in revision 2. Options and undo policy are then frozen. There is no **Start
setup** button. Retired edition presets, improved setup and rebuilt-marker settings are unavailable.

Save all three private seat links and open each in its own tab. They identify seats independently of
cookies and may share a browser profile; share only the intended player's link. The first player is
the local game's creator. Hosted games select registered accounts and use membership access instead
of transferable seat credentials; see [deployment](deployment.md).

## Complete capital bidding

1. Each tab immediately shows one private card, $40, 36 stock tokens, 25 stock markers, no ships and
   zero misery. Network responses must hide other hands and future deck order.
2. Submit Alice's bid of 10, then replace it with `bid-capital 12`. Her tab shows the pending amount;
   the others show only readiness. Cash remains $40 and public history contains no bid yet.
3. Stop and restart the server, then refresh the tabs. Alice's private card and pending bid must be
   unchanged. Reuse the same `AOR_DATA_DIR` if configured.
4. Submit Bob's bid of 12 and Carol's bid of 0. The batch reveals together: cash becomes $28, $28
   and $40. Recorded, color-labeled tie dice decide between Alice and Bob; equal rolls repeat.
   Carol chooses last.
5. Choose capitals in the displayed order using buttons, the map or `choose-capital <id>`. Wrong
   players and already assigned capitals are rejected without changing history. Three-player
   choices are `venice`, `genoa` and `barcelona`.
6. The last choice opens **Turn 1 — Order of Play**. Each player has a capital marker, 24 stock
   markers, 36 stock tokens, no pool tokens, zero misery and no ships. Capital-choice order remains
   separate from the turn order being determined. Page titles now identify the assigned capital.

## Buy tokens and resolve tied positions

1. Enter a purchase of 4 for Alice: the preview shows $4 cost, four tokens and $24 remaining. Submit,
   then replace it with `buy-tokens 5`. Other tabs see only readiness; public cash and token totals
   do not change before the final submission.
2. Restart and refresh. Alice's saved amount stays 5 and remains private. Unaffordable, fractional
   and malformed purchases must fail without replacing it.
3. Submit 5 for Bob and Carol. The batch reveals once: cash becomes $23, $23 and $35; each player
   has five pool tokens and 31 stock tokens. Capital bids remain separately visible. No turn-order
   dice are used.
4. All are tied. The earliest capital chooser is placed provisionally; the next chooser selects
   before/after. Choose after with `choose-turn-position 2`. Wrong players and unavailable slots
   fail without changing cash or sequence.
5. Restart while the last chooser is pending. The sequence and chooser stay unchanged. That player
   can insert before, between or after; the middle button submits `choose-turn-position 2`.
6. **Turn 1 — Play Cards** opens with finalized order boxes **2, 4, 6**. Draw Card resolves
   automatically and nobody yet owns an optional Buy Card advance. Each hand now has its opening
   card plus one regular draw. Restart once more and check the same resources, cards and history.

Repeat with 4–6 players: Paris, London and Hamburg become available in that order. Order boxes are
**1, 3, 5, 6**, **1–5** and **1–6** respectively. Test larger and separate tied groups. Continue with
[the full-game guide](manual-full-game.md).

## Negative and over-stock purchases

In a fresh game, bid 0 for everyone and complete the recorded tie rolls and capital choices. Each
player retains $40. Submit:

| Player | Command | Cash after reveal | Pool | Stock | Order box |
| --- | --- | --- | --- | --- | --- |
| Alice | `buy-tokens -2` | $38 | 0 | 36 | 2 |
| Bob | `buy-tokens 0` | $40 | 0 | 36 | 4 |
| Carol | `buy-tokens 38` | $2 | 36 | 0 | 6 |

Order follows the signed submitted amounts. Negative purchases pay their absolute value and receive
no tokens; over-stock purchases pay and rank by the full amount but receive only available stock.
Distinct amounts require no tie prompt.

## Commands, API and recovery

Forms and the command input share validation. Common setup commands are:

```text
bid-capital 12
choose-capital venice
buy-tokens -2
choose-turn-position 2
```

Capital bids are whole numbers from 0 through 40. Token purchases range from negative available cash
to positive available cash. Both submissions may be replaced until their complete batch reveals.
Turn-position slots are one-based insertion positions within the tied group, distinct from printed
order boxes. Own actor/capital prefixes are supported; impersonating another seat, authority outcomes,
unsupported options and multiple commands are rejected. `start` remains recorded engine syntax;
newly created tables have already executed it.

| Endpoint | Request | Response |
| --- | --- | --- |
| `POST /api/games` | `{"name":"Friday Renaissance","players":["Alice","Bob","Carol"]}`; optional `options` and `undoPolicy`. Names are trimmed, single-line, 1–80 characters; omission defaults to `Age of Renaissance`. | Started game ID, name and private relative seat links; 201 |
| `GET /api/games/{id}` | `Authorization: Bearer <token>` | Viewer-safe state, prompt, choices, history and revision |
| `POST /api/games/{id}/commands` | Same authorization; `{"expectedRevision":6,"command":"buy-tokens 5"}` | Updated viewer-safe snapshot |
| `GET /api/health` | None | `{"status":"ok"}` |

The token comes from the link's `#token=…` fragment and travels in the authorization header. Use the
latest snapshot revision. Unknown credentials return 401; malformed requests 400; rejected commands
422 with typed diagnostics; stale revisions 409. Stale recovery refreshes and preserves correctable
drafts, then requires explicit resubmission. Pending replacements keep the same committed revision;
complete reveals, including their outcomes, advance it once. Each tie-position choice advances it.

Responses and structured errors use `Cache-Control: no-store`. The creator receives all local links
at creation but gains no extra hand access through their own snapshot. Save links: the server stores
credential hashes and cannot recover a lost URL.

Games and private submissions survive restart through replay of recorded outcomes. Failed writes
preserve saved state; corrupt or incompatible files return `STORAGE_UNAVAILABLE` and are not reset.
Only current versions are supported; administrative upgrades are explicit and audited. Use one
server per data directory and separate temporary directories for disposable runs.

Undo defaults to regular turn; competitive, friendly turn and friendly game are creation choices.
Use **Undo last action** or the suffix editor under **Commands** within the
[documented scopes](design.md#4-revisions-and-undo). History aliases can show capitals, names or IDs
without changing canonical entries. [README](../README.md) owns verification commands; core/service
checks cover private batches, signed purchases, ties, authorization, replay, revisions and atomic writes.
