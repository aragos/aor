# Visual decision coverage

Inventory follows engine prompts/decision frames and [canonical commands](commands.md). A rendered
control, deterministic UI fixture and live authorized game are different evidence levels. Fixtures
exercise the App and submission path but cannot establish domain validity, authorization or secrecy;
core/service tests own those contracts. Authority outcomes are never player controls.

The 2026-10-29 checks cover integrated core JVM, Kotlin/JS, server and formatting checks, web
build/unit tests, and the comprehensive browser suite, including private-seat, hosted and live
European-map flows. Presentation fixtures use production StrictMode. The live Crusades regression
first reproduced the lifecycle failure, then committed a map-selected province after its fix.
Historical long runs and separate history-editor evidence remain identified below. See
[README](../README.md) for commands rather than relying on frozen test counts.

## Decision inventory

| Prompt / state | Canonical actions and visual path | Evidence |
| --- | --- | --- |
| Creation/setup | Registration variants and undo policy; setup starts during creation | Service checks prove resolved options, automatic Start/outcomes and first private hand; live creation/keyboard/persisted defaults and no-Start assertions; gameplay fixtures check the compact table |
| Capital bids | `bid-capital`, `withdraw-bid`; private form and readiness | Live save/withdraw/reload/resubmit with an unsaved local draft; service file/PostgreSQL persistence, privacy, readiness/ETags/listing, stale reveal and concurrent final-submission tests |
| Capital choice | `choose-capital`; buttons/map and confirm | Live canonical selection and invalid-player/duplicate rejection |
| Token purchase | `buy-tokens`, `withdraw-bid`; signed amount, stock and exact preview | Live negative/over-stock purchase, private replacement/withdrawal/reload and quotes; unchanged balances before reveal; stale-quote/Chaos fixtures |
| Turn-order tie | `choose-turn-position`; visible insertion slots | Confirmed slot fixture; service tie groups and restart |
| Draw Card | `cancel-adjustment`, `pass`; priced adjustment buttons/confirmation | Live phase passage; gameplay fixture checks offer and confirmation cost |
| Buy Card | `buy-card`, `discard-card`, `pass`; optional hand actions | Purchase/discard fixtures; gameplay fixture queues Urban Ascendancy while waiting and proves one legal-turn submission without polling repeats |
| Card rounds / Final Card Play / War preparation | `play`, `pass`; hand and contextual form | Dual commodity, protection, targets, final-play and War-preparation fixtures |
| Commodity choice | `play … commodity`; choices and current payouts | Canonical dual-choice fixture; market fixture checks external payout-reference links and adjustment badges on hand/selected card |
| Player / area / province / category | `play … player/area/province/category`; finite choices and map | Canonical target fixtures; area-label selection is draft-only; StrictMode fixtures activate Crusades/Rebellion map targets; a live-engine Crusades regression fails before the lifecycle fix and commits a map-selected province after it |
| Pirates/Vikings | `play … provinces`; map and required target count | Gameplay fixture activates map; rapid-pick cap, invalid target, clear/correct and canonical-play fixtures |
| Leader | `play … protect`; protection payment | Invalid blank/negative/over-max values blocked; valid canonical payment; service activity labels protection/rebate |
| Shipping | `upgrade-ships`, `pass`; quote/blocker | Live phase passage; engine projection/execution agreement |
| Advances | `buy-advance`, `patronize`, `pass`; offers, claims and inspection | Browser quotes show printed/category/research/leader components and misery endpoints. Core tests match previews to actual payment, current-turn credit exclusion, unaffordable purchases, clamped relief and Chaos. Market/leader privacy evidence remains below. |
| Immediate Master Art | `discard-card`, `pass`; optional private discard | Select/confirm fixture; engine resumes same purchase activity |
| Stabilization | `stabilize cash/misery`; cost and destination box | Gameplay fixture displays destination; core preview/execution equality; live cash-payment baseline |
| Relief | `relieve-misery`, `pass`; spaces, cost and blocker | Disabled unaffordable choices and enabled Pass; core retains tier-based step including zero misery. Activity tests cover cash plus pooled current-turn MR, prior spending, cross-turn reset and Enlightenment rounding; browser receipt shows the cash expense once. |
| Expansion | `place`, `attack`, `reduce-marker`, `buy-expansion-card`, `pass`; province action | Live satellite/partial/full commitment; core omits impossible pool/shipping commitments and retains partial alternatives; stale/rejected drafts checked |
| Cathedral | `cathedral`, `pass`; interruption form | Canonical decision fixture and competition rules tests |
| Civil War | `civil-war-loss cash/tokens`; mandatory victim response | Cash-loss and changed-owner waiting fixtures |
| Event replacement | `replace-event-token pool/none`; replacement choice | Select/confirm fixture and resource-limit rules tests |
| Marker exhaustion | `move-marker`, `forgo-marker`; source map picker / forgo | Canonical move/forgo fixtures; core physical-marker rulings |
| War cession | `cede-war`; required count and overseas cap | Combined count/cap fixture and canonical cession |
| Renaissance | `swap-order`, `pass`; adjacent players | Select/confirm fixture and phase-opening rules |
| Wind/Watermill and trade | `windmill`, `pass`, `offer-card`; takeover/hand offer | Canonical choice/offer fixtures; service private random trade |
| Waiting / finished | No player action; responder/activity or final scores | Changed-recipient fixture blocks obsolete actions; live all-Chaos ending with scores; final-state fixture hides waiting indicators |
| History editing | `undo`, authorized suffix editor | Live restoration and stale-draft protection; service scopes, retained references, immutable audit and original randomness |
| History identifiers | Saved capitals/names/IDs preference | Browser switches/reloads modes without changing canonical edit references |

`!shuffle`, `!draw`, `!roll` and `!trade` belong to the authority. Fixtures for retired internal
handlers do not make those rules selectable in current game creation.

## Cross-cutting evidence

| Contract | Evidence and limits |
| --- | --- |
| Compact table and inspection | Gameplay fixture checks AoR/capital title, compact header and removed Board details. Desktop/phone visual inspection confirms six-player capital/handle rows, long-handle truncation and working detail expansion. Card reference/advance inspection is outside actions; inspecting an unrelated advance preserves the prepared Rebellion target and active picker. |
| Activity and results | Immutable entry-time identities; one rendering per action; bounded default-open activity with an inset icon toggle and no count. Browser cases name actor/non-actor cash receipts consistently, retain recorded identities and keep the control clear of entry text. Focus continuity, colored dice, red losses, local receipts through Cathedral interruptions and dismissal remain covered. Service tests derive draw reasons, market meanings, transitions and cash/misery facts without exposing private card locations. |
| Expansion resources | Market fixture checks each player's white markers including zero; contextual fixture identifies the acting pool. Core tests retain dark-token reach beneath a white marker, remove it on competition loss/capture, prevent resurrection and normalize at income. Both map graphs, reach tests and geometry validators exclude direct Smyrna–Taurus contact while retaining valid shipping via Adalia. Military tests own actual advantage rules. |
| Markets and leaders | Reference links expose all 120 payout boxes and 26 advance descriptions. App polling updates hand, inspection and markets after white-marker ownership changes/reclamation. Core/server tests exercise real competition before Income, zero values, Industry/adjustments, token/Chaos exclusions and unchanged colored-only actual payments/Famine. Leader labels identify Written Record; public counts and inspection catalogs retain hidden-card privacy. |
| Hand and spending context | Pointer dragging, outside-release cancellation, activation after dragging, contextual cash, inline descriptions, compact density and advance counts remain covered. Price fixtures show concise tile totals, optional named advance/IR/leader details, printed fallbacks and blocked estimates on desktop/phone. Core/serialization tests preserve exact charges, prior-turn source eligibility and purchase rejection despite visible estimates. |
| Misery consequences and automatic receipts | Card fixtures show all affected players, Chaos and target changes without commitment. Core/serialization tests cover Famine, Civil War, Crusades marker exhaustion, hidden-hand invariance and explicit War uncertainty. Holy Indulgence activity includes token-only pool/stock movements, separates the triggering action and excludes unrelated Chaos returns. |
| Draft lifetimes | Full-App polling/workspace/viewport preservation, rejection, stale refresh without retries, quote supersession and changed-owner fixtures. Activity inspection and closing either an explicit or implicit inspector preserve a prepared card target. Stable nested identity resets same-player/same-prompt replacement drafts. |
| Feasible expansion | Pool/shipping regressions omit destinations with no valid commitment; peaceful partial placement remains. Marker-stock/access blockers are still explainable in inspection. |
| Board and reductions | Live 3–6-player drawing, continental hit targets, mixed owners/ages, all twelve owner/age groups per province and combined rendered label/piece bounds. Dense provinces retain zoom/inspection fallback. |
| Reviewed topology | Catalog/shipping/geometry/browser checks cover separate standard Aegean/Central seas and European merger, Crete/Smyrna contacts, Belgrade/Durazzo, Danish/Baltic exclusions/support and British/Channel coasts. Core tests verify Fez's exact sea contacts; regenerated standard/European geometry passes validation, including more than 20 SVG units of Western Mediterranean frontage. The [connection inventory](connection-inventory.md) owns the graph. |
| Sea emphasis | Full connected-sea highlighting is distinct from coastal bands, retains screen-size hatch/perimeter during zoom, changes with province inspection and clears with area/background selection. Desktop/zoom/phone baseline screenshots were reviewed. |
| Dense labels | Copenhagen's earlier exception was removed. The user accepted Toulouse/Constantinople labels on 2026-10-29; their constrained geometry metadata remains descriptive. |
| Support arrows | Both geometry validators check catalogued directions and province endpoints. Desktop/phone visual review confirms short, straight Toulouse crossings to Montpellier and Barcelona on both maps, with accepted labels preserved. Both maps render every catalogued arrow even with a previously disabled browser preference; the visibility toggle is removed. |
| Resources and payouts | Core calculations distinguish ordinary/conditional income, current/projected payout, free/blocked offers and public zero/unavailable pools. Browser fixtures check those distinctions and legal partial alternatives. |
| Reference text and errors | Unit completeness for 18 events, 26 advances and supported variants; browser reference text, quantity interpolation and typed named diagnostics. Unknown object IDs are omitted. |
| Help and misery severity | Help removes the game-flow overview and consolidates shared commodity/leader rules. Desktop/phone review verifies all four undo policies and their common limits, plus the complete colored misery track. Player summaries, stabilization and final scores use the same track-position colors, including Chaos. The stabilization button shows current → target boxes; phone inspection verifies 50 → 80 and 1000 → Chaos, retaining the canonical payment command. Printed values remain visible; existing browser flows and unit checks pass. |
| Keyboard/touch/reflow | Keyboard picking, real touch zoom, reduced motion, 390 × 844 and 430 × 1100 layouts, phone sheet/action reachability, 200% pinch and equivalent desktop reflow. Full-session screen-reader, physical-phone and browser-chrome zoom review remain manual. |
| Accessible names/contrast | Browser accessibility-tree names and computed HTML text contrast checks. SVG, native controls, gradients and opacity-composited states require separate review. |
| Private-seat recovery | Viewer-specific saveable link, own credential only, saved private bids and reload. Service tests establish restart and secrecy; a restarted live browser session is separate evidence. |
| Background turn status | A live two-tab regression changes turns through real server commands while the game tab remains hidden and checks ready/waiting favicon changes within five seconds without animation frames. Virtual-time checks prove a return to 30-second cadence after two quiet minutes, renewed activity after changed snapshots and successful mutations, and no renewal for 304s or identical 200s. A same-revision private-readiness change also renews activity. Deterministic tests cover recent/idle intervals, foreground cadence, mutation pauses, read/auth/body deadlines, cancellation, retries, visibility coalescing, lifecycle/network wakeups and StrictMode cleanup. Browser-managed freezing remains outside a polling guarantee. |
| Hosted operation | Portal fixtures check unique handles without visible UIDs, single linked game rows, pending-action backgrounds, keyboard focus, phone reflow, creation, saved filters and administrative controls. Hosted authentication fixtures submit handle-only registration and verify a single account/My games/sign-out surface inside the game header on desktop and phone. Server tests cover handle uniqueness, live handle labels without history changes, and a V1-to-V2 PostgreSQL upgrade retaining account identity/permissions. Authentication, membership, game upgrades, purge and transactional behavior retain service coverage. Real Firebase delivery and Render deployment remain launch checks. |

## Historical long runs and reproduction

The live 2026-10-28 three-player run reached `FINISHED` through **469 canonical UI actions** over
22 turns and all three epochs. It exhausted the deck, visited Final Card Play and displayed three
final score rows. Creation and every decision used visible controls with the command dock closed;
read-only snapshots verified canonical actions and private-batch revisions. Fixtures and domain
tests cover choices the scenario did not make. The earlier 2026-10-25 run ended in Chaos before
Final Card Play; it remains separate evidence.

The 2026-10-27 six-seat epoch-3 profile measured 60 snapshot requests at p95 7.2 ms, maximum 145,955
bytes, and 18 workspace interactions at p95 92.3 ms. It used an older catalog and local Vite under
low conflict; see [baseline limits](ux-next-steps.md#execution-record-2026-10-27).

Run `npm run test:browser` from `web`. The runner owns an isolated server, Vite, Chrome profile and
game files; it cleans up on success/failure and prints the retained screenshot directory. Use
`CHROME_BIN` for Chrome, `AOR_BROWSER_ARTIFACTS` for artifacts or `AOR_BROWSER_SERVER` for an alternate
server launcher. Artifacts stay outside the repository and include desktop/phone/zoom captures.
The default focused suite has a three-minute budget. The longer live scenario is:

```sh
AOR_BROWSER_SUITE=fullgame.browser.mjs AOR_BROWSER_TIMEOUT_MS=1800000 npm run test:browser
```

It uses private-seat tabs and visual controls, checks actions against read-only snapshots, and writes
a trace/result screenshot. Record actual visited stages and keep historical and current runs separate.
