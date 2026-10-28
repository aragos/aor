# Decisions and checks after the task batch

The deployment implementation and intact CI workflows are now published on `main`. The GitHub
publication credential issue was resolved using the configured SSH authentication.

## Needed before public launch

- Create/choose the Firebase and Render projects, supply the service-account secret and initial
  admin UID, and choose the hostname under the Squarespace domain. Follow [deployment](deployment.md).
- Complete a real sign-up/verification/reset flow, three-account privacy check, restart and backup
  restore on Render. Confirm the first CI Docker build and memory/traffic/disk usage. Docker and live
  hosting credentials were unavailable in this workspace; the $7.25 base is a starting configuration,
  not a measured capacity promise.
- Enable GitHub's required `verify` check, Dependabot security alerts and optional patch auto-merge.
  Supply the Render CI credentials if weekly runtime refreshes are wanted. Set billing alerts and
  decide the private backup retention period.

## Product decisions

- Specify the European map's added/removed provinces, area membership, land/coastal/sea connections
  and satellite supports. The option is visible but disabled until those rules and geometry exist.
- Review the remaining constrained label space in Toulouse and Constantinople on the map. All
  supported connections remain checked; these shapes still limit comfortable label placement.
- Perform the human screen-reader and physical-phone review recorded in the UI requirements.

## Later maintenance

The supported variant surface is simplified and rejects retired choices. Some unreachable retired
handlers remain internally. Automatic approval review rejected the proposed bulk removal because
it could remove baseline mechanics; that broad deletion was not executed. A separate reviewed,
incremental cleanup can remove those handlers with focused behavioral evidence.

The cleanup instead consolidated requirements/document ownership, static catalog ownership,
parsing and related calculations, and corrected stale descriptions. Strategy controllers and
private plans remain explicitly deferred.
