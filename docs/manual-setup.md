# Manual setup walkthrough

## Run locally

Use the JDK and Node requirements in [README](../README.md). From the repository root:

```sh
npm install
./gradlew :server:run
```

In a second terminal, run `npm start` and open <http://localhost:5173>. Vite proxies `/api` to the local server on
port 8080. The server is required even after `npm run build`; the production bundle does not host an API.

Create a game with Alice, Bob and Carol, leaving the epoch setting at 3. Save all three private seat links and open
each in its own tab. Links identify players independently of cookies, so the tabs may share a browser profile.
The first player, Alice, is the creator and can change the epoch setting or start setup. The links grant access to
their respective seats; share only the intended player's link.

## Complete the setup

1. In Alice's tab, click **Start setup**. Each tab should show one private card, $40, 36 tokens, 25 markers, no ships
   and zero misery. No other player's card or remaining deck order should appear in its network responses.
2. Seal Alice's bid at 10. Replace it with 12 using the text input: `bid-capital 12`. Her tab shows her pending bid;
   the others show only that she submitted. Cash remains $40 and the public history has no bid entry yet.
3. Stop the server with Ctrl+C, start it again with `./gradlew :server:run`, and refresh all tabs. Cards and Alice's
   pending bid must be unchanged. If you changed `AOR_DATA_DIR`, reuse that directory on restart.
4. Submit Bob's bid of 12 through the form and Carol's bid of 0 through the text input. All bids reveal together.
   Cash must become $28, $28 and $40. The history must include tie rolls for Alice and Bob; higher dice results
   choose first, with equal rolls repeated. Carol chooses last.
5. Try selecting a capital from the wrong player's command input. Expect a validation error, no history change and
   the command still in the input so it can be corrected.
   Choose capitals in the displayed order using both buttons and `choose-capital <id>` commands. Try reusing an
   assigned capital; it must be rejected. For three players the choices are `venice`, `genoa` and `barcelona`.
6. After the third selection, every tab shows **Setup complete**. Each player has its chosen capital, one marker
   on the board, 24 markers in stock, 36 stock tokens, zero pool tokens, zero misery and no ships. Capital choice
   order remains visible. No Order of Play actions run yet.
7. Restart once more. All cards, bids, rolls, assignments and history must remain identical.

Repeat creation with 4–6 players to check that Paris, London and Hamburg become available in that order. Epochs
1–3 are accepted and recorded, but subsequent turn play and epoch transitions are outside this milestone.

## Commands and API

The command input accepts one command without an actor prefix. Forms generate the same commands:

```text
set-option epochs 2
start
bid-capital 12
choose-capital venice
```

Only the creator can configure or start. Configuration freezes when setup starts. Bids must be whole numbers from
0 through 40 and may be replaced until the final submission reveals the batch. System outcomes cannot be submitted
by clients. Unsupported options, actor prefixes and multiple commands are rejected.

| Endpoint                        | Request                                                                 | Response                                                                   |
|---------------------------------|-------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `POST /api/games`               | `{"players":["Alice","Bob","Carol"],"epochs":3}`                        | Game id and private relative seat links; 201                               |
| `GET /api/games/{id}`           | `Authorization: Bearer <token>`                                         | Caller-specific state, prompts, legal choices, history and active revision |
| `POST /api/games/{id}/commands` | Same authorization; `{"expectedRevision":2,"command":"bid-capital 12"}` | Updated caller-specific state                                              |
| `GET /api/health`               | None                                                                    | `{"status":"ok"}`                                                          |

Extract the token from the seat link's `#token=…` fragment. It is sent in the authorization header and is not a query
parameter. Unknown credentials return 401. Invalid commands return 422 with a stable `code` and entity ids; malformed
requests return 400. A stale active revision returns 409; the browser refreshes the state while preserving drafts,
then requires you to review and explicitly resubmit. Pending bid changes
do not advance the committed revision. The final bid batch advances it once, together with all tie outcomes.

Each response hides other hands, unrevealed amounts and future deck order. Successful game responses and structured
errors use `Cache-Control: no-store`. The creator receives all seat links at creation but its own game view has no
extra access to other hands.

## Storage and automated checks

One versioned JSON file per game contains the header, credential hashes, immutable append-only revisions with text-log
entries, and pending sealed bids. State is derived by replay. Recorded shuffles, deals and rolls are authority
outcomes; reading a game never calls randomness. Files are replaced atomically after complete validation. Unreadable,
corrupt or incompatible files return `STORAGE_UNAVAILABLE` and are never reset automatically. Temporary `.game-*.tmp`
files left by an interrupted process are ignored. General history editing and undo are deferred.

Only the current game file format is supported. Every saved game must record its creator explicitly.

Use a single server process for a data directory. The files contain private game data; do not publish them. The
default `.aor-data/` directory is excluded from Git. Tests use separate temporary directories.

```sh
./gradlew :core:jvmTest :core:compileKotlinJs :server:test
npm run build
```

Core tests cover every player count, codec round trips, replay equality, invalid commands/outcomes, resource counts
and repeated tie subgroups. Service tests cover private projections, authorization, bid replacement, stale requests,
concurrent submissions, recovery without randomness, corruption and rejected writes that preserve the saved revision.
