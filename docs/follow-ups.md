# Follow-up decisions and checks

Current behavior is specified in [requirements](requirements.md), [UI contracts](ui-design.md) and
[rules](rules.md). This file keeps open decisions and the defaults chosen without blocking work.

## Defaults from the gameplay improvements

| Item | Implemented default | Follow-up decision |
| --- | --- | --- |
| Fez | Add a direct Western Mediterranean contact on both maps, retaining Canary Basin access | Confirm this interpretation of the map correction |
| Unplayed leaders | Count matching leaders by printed epoch minus public plays; include hands, future decks and private discards | Keep this privacy-preserving public meaning rather than exposing card availability |
| Urban Ascendancy | A cancelable private browser draft buys once when legal; requires the view to stay open and never retries a rejected purchase | Decide whether reload-safe or server-saved preregistration is useful |
| Competition results | Clear current notices locally; polling preserves dismissal, new results appear, activity retains every result | Decide whether dismissal should survive reload |
| Dice without a printed role | War initiator uses white and opponent black; independent capital-tie rolls use white | Treat these as display conventions; outcomes are unchanged |
| Misery relief | A completed tier opens the decision even when relief is unaffordable or misery is zero; explain and offer Pass | No further decision required |

## Needed before public launch

- The deployment setup is complete and the application runs successfully on Render. Google is
  enabled in Firebase; verify Google sign-in on the deployed application after this change ships.
- Complete a real sign-up/verification/reset flow, three-account privacy check, restart and backup
  restore on Render. Check actual memory, traffic, database size and concurrent-player load. The
  $13.30/month app plus managed PostgreSQL base is a starting configuration, not a measured capacity
  promise. CI verifies real PostgreSQL and production-container startup; live provider credentials,
  email delivery and restore procedures still need deployment checks.
- If preserving an existing file deployment, stop it, retain a private backup, import its current-format
  directory into an empty database, and verify player views and archives before switching traffic.
- Enable GitHub's required `verify` check, Dependabot security alerts and optional patch auto-merge.
  Supply the Render CI credentials if weekly runtime refreshes are wanted. Set billing alerts and
  decide the private backup retention period.

## Product decisions

- Review the remaining constrained label space in Toulouse and Constantinople on the map. All
  supported connections remain checked; these shapes still limit comfortable label placement.
- Perform the human screen-reader and physical-phone review recorded in the UI requirements.

## Later maintenance

Some unreachable retired-rule handlers remain internally. Remove them incrementally with focused
behavioral evidence; preserve baseline mechanics and the supported variants. Strategy controllers
and private plans remain deferred.
