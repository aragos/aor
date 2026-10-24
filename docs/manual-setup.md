# Manual setup and Order of Play walkthrough

## Run locally

Use the JDK and Node requirements in [README](../README.md). From the repository root:

```sh
npm install
./gradlew :server:run
```

In a second terminal, run `npm start` and open <http://localhost:5173>. Vite proxies `/api` to the local server on
port 8080. The server is required even after `npm run build`; the production bundle does not host an API.

Create a game with Alice, Bob and Carol, using the supported one-epoch length. Save all three private seat links and open
each in its own tab. Links identify players independently of cookies, so the tabs may share a browser profile.
The first player, Alice, is the creator and can configure the rebuilt-marker bonus or start setup. The links grant access to
their respective seats; share only the intended player's link.

## Complete setup

1. In Alice's tab, click **Start setup**. Each tab should show one private card, $40, 36 tokens, 25 markers, no ships
   and zero misery. No other player's card or remaining deck order should appear in its network responses.
2. Seal Alice's capital bid at 10. Replace it with 12 using the command input: `bid-capital 12`. Her tab shows her
   pending bid; the others show only that she submitted. Cash remains $40 and the history has no bid entry yet.
3. Stop the server with Ctrl+C, start it again with `./gradlew :server:run`, and refresh all tabs. Cards and Alice's
   pending bid must be unchanged. If you changed `AOR_DATA_DIR`, reuse that directory on restart.
4. Submit Bob's capital bid of 12 through the form and Carol's bid of 0 through the command input. All bids reveal
   together. Cash must become $28, $28 and $40. The history must include tie rolls for Alice and Bob; higher dice
   results choose first, with equal rolls repeated. Carol chooses last.
5. Try selecting a capital from the wrong player's command input. Expect a validation error, no history change and
   the command still in the input so it can be corrected. Choose capitals in the displayed order using both buttons
   and `choose-capital <id>`. Reusing an assigned capital must fail. For three players the choices are `venice`,
   `genoa` and `barcelona`.
6. The last capital choice leads directly to **Turn 1 — Order of Play**. Each player has its capital, one board
   marker, 24 stock markers, 36 stock tokens, zero pool tokens, zero misery and no ships. Capital-choice order stays
   visible and is distinct from the turn order being determined.

## Buy tokens and resolve tied positions

1. In Alice's tab, enter a purchase of 4. Check the preview: cost $4, four tokens and $24 remaining. Seal it, then
   replace it with `buy-tokens 5` in the command input. Her saved amount is private; other tabs show only submission
   status. The public cash and token counts must remain unchanged before the final submission.
2. Restart the server and refresh all tabs. Alice's saved purchase must still be 5. Bob and Carol must not see its
   amount in their responses, previews or history. Try unaffordable, fractional and malformed commands; each must be
   rejected without replacing the saved purchase.
3. Submit 5 for Bob and 5 for Carol. Purchases reveal together: cash becomes $23, $23 and $35. Every player now has
   five pool tokens and 31 stock tokens. Capital bids remain separately visible. History adds all three purchases
   together, with one revision increase and no turn-order dice.
4. Everyone is tied. The earliest capital chooser is placed provisionally. The second chooser gets buttons to go
   before or after that player. Choose after using `choose-turn-position 2`. A command from another player or an
   unavailable slot must fail without changing the sequence or cash.
5. Restart while Carol's decision is pending. The provisional sequence and required chooser must be identical.
   Carol, the last capital chooser, can insert before, between or after the two players. Choose the middle button.
   Its effect must match `choose-turn-position 2`; earlier players cannot act again.
6. Every tab now shows **Turn 1 — Draw Card** and the finalized order with boxes **2, 4, 6**. Resources are unchanged
   by tie choices. Each private hand still has only its original card. This walkthrough ends at that checkpoint; the repeatable engine continues when the first player
   uses `pass` to complete shortage/surplus cancellation and request the recorded draws.
7. Restart again. Purchases, resources, finalized order, boxes, cards and history must remain identical.

Repeat with 4–6 players. Paris, London and Hamburg become available in that order. Order boxes are **1,3,5,6** for
four players, **1–5** for five and **1–6** for six. The tie choices must also work for larger groups and for multiple
separate tied groups. Only one epoch is accepted. The agreed minimum-game scope defers additional decks and epoch transitions.

## Negative and over-stock purchases

Create another three-player game and submit capital bids of 0 for everyone. Complete the recorded dice and capital
choices normally; each player retains $40. Then submit:

| Player | Command | Cash after reveal | Pool tokens | Stock tokens | Order box |
| --- | --- | --- | --- | --- | --- |
| Alice | `buy-tokens -2` | $38 | 0 | 36 | 2 |
| Bob | `buy-tokens 0` | $40 | 0 | 36 | 4 |
| Carol | `buy-tokens 38` | $2 | 36 | 0 | 6 |

The order is Alice, Bob, Carol. A negative purchase pays its absolute amount and receives no tokens. Over-stock
purchases pay and rank by the full submitted amount but receive only available stock. Both the order and the pool
apply to this turn. No tie prompt is needed for distinct amounts.

## Commands and API

The command input accepts one command without an actor prefix. Forms generate the same commands:

```text
set-option epochs 2
start
bid-capital 12
choose-capital venice
buy-tokens -2
choose-turn-position 2
```

Only the creator can configure or start. Configuration freezes when setup starts. Capital bids must be whole numbers
from 0 through 40. Token purchases must be whole numbers between negative available cash and positive available cash.
Both types of sealed submission may be replaced until the final submission reveals their batch. Turn-position slots
are one-based insertion positions within the current tied group, not printed board boxes. System outcomes cannot be
submitted by clients. Unsupported options, actor prefixes and multiple commands are rejected.

| Endpoint | Request | Response |
| --- | --- | --- |
| `POST /api/games` | `{"players":["Alice","Bob","Carol"],"epochs":3}` | Game id and private relative seat links; 201 |
| `GET /api/games/{id}` | `Authorization: Bearer <token>` | Caller-specific state, prompts, legal choices, history and active revision |
| `POST /api/games/{id}/commands` | Same authorization; `{"expectedRevision":6,"command":"buy-tokens 5"}` | Updated caller-specific state |
| `GET /api/health` | None | `{"status":"ok"}` |

Extract the token from the seat link's `#token=…` fragment. It is sent in the authorization header and is not a query
parameter. Use the active revision from the latest snapshot when submitting a command. Unknown credentials return
401. Invalid commands return 422 with a stable `code` and entity ids; malformed
requests return 400. A stale active revision returns 409; the browser refreshes the state while preserving drafts,
then requires you to review and explicitly resubmit. Pending bid changes do not advance the committed revision.
Capital-bid reveal advances it once, including required dice; token-purchase reveal advances it once, including all
payments and allocations. Each subsequent turn-order choice advances it individually.

Each response hides other hands, unrevealed amounts and future deck order. Successful game responses and structured
errors use `Cache-Control: no-store`. The creator receives all seat links at creation but its own game view has no
extra access to other hands.

## Storage and automated checks

Games and pending sealed submissions survive server restarts. Replaying history uses recorded shuffles, deals and
rolls without generating randomness. Failed writes retain the preceding saved state. Unreadable, corrupt or
incompatible files return `STORAGE_UNAVAILABLE` and are never reset automatically. General history editing and undo
are deferred.

Only the current game format and rules version are supported. Every saved game must record its creator explicitly.
Files from the setup-only implementation are incompatible; use a fresh game for this walkthrough. No migration or
automatic deletion is performed.

Use a single server process for a data directory. Saved games contain private data; do not publish them. The default
`.aor-data/` directory is excluded from Git. Tests and disposable manual runs should use separate temporary directories.

```sh
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
npm run build
```

Core tests cover all player counts, signed purchase calculations, tie insertion, printed boxes, codec round trips,
replay equality, invalid commands and token conservation. Server tests cover private projections, authorization,
replacement, stale and concurrent requests, restart recovery, invalid saved histories and failed atomic writes.
