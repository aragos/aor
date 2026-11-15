# Offline finalization of a qualified local preview

This step copies an existing successful certificate. It creates no qualification claim, reruns no
timed audit, and starts no service. The original scientific attempt and its audit clocks stay closed.
Run it only after both original watcher audits have passed, every owned process group (including
watchers) has independently exited, input/output hashes have been checked, and the independent root
review confirms both complete 4,096-pair win99 gates. A stopped, failed or negative run has no preview.

The Desktop runner already publishes `execution/run/study/qualification.json`. Its exact raw subject
must equal the Desktop terminal's `qualification` and sealed pure audit's `eligibleQualifiedUnit`.
Copy that original file; do not call the qualification publisher again. Preserve both banks'
`complete.json` and `report/report.json` at their certificate-relative paths. Retain original
`report/inputs.json`, `report/finished.json`, compact world tables and shard receipts separately for
the independent scientific review. Full archives and tensors remain in their original location.

Both hosts' original `dispatch/completion-audit.json` bind `dispatch/terminal-evidence.json`. The
Desktop pure audit is global: 8,192 worlds, 16,384 games and 16 reproductions, totaling 16,400 terminal
executions. Mac's 3,072 worlds / 6,152 executions are an included subset. Do not add the counts.
Do not rerun `execution.verify` or a timed pure auditor after its original deadline. The offline
packager only rehashes retained subjects from those already sealed audits. The root release review
is an explicitly supplied hash-pinned approval of the exact package inputs; the packager does not
itself check live processes or independently recompute game statistics.

## Request and package

Use a new private destination. Each request subject has exactly `path` and `sha256`; source paths
may point at verified compact copies. Their raw hashes must remain those of the original files.
The package preserves descriptor-relative inference, nomination and certificate paths. Copied bank
evidence is opaque provenance: its full archive dependencies remain at the scientific source.

After the independent review, root writes a small release receipt with kind
`coordinated-win-preview-release-review-1`, `passed: true`, `complete: true`, the original
`protocolSha256` and `clockSha256`, `allOwnedProcessesExitedIndependently: true`, and
`packagingAuthorized: true`. Its `subjects` map binds the seven raw hashes named `inferenceAsset`,
`nomination`, `qualification`, `desktopWatcher`, `desktopEvidence`, `macWatcher`, and `macEvidence`.
It may retain the independent terminal review as further provenance. An unrelated passed source
review cannot authorize packaging. This receipt is not server activation authorization.

Then write a request containing:

```json
{
  "kind": "coordinated-win-preview-package-request-1",
  "protocolSha256": "<original protocol raw SHA256>",
  "clockSha256": "<original shared clock raw SHA256>",
  "inferenceAsset": {"path": "<frozen inference-asset.json>", "sha256": "<frozen hash>"},
  "nomination": {"path": "<root nomination.json>", "sha256": "<frozen hash>"},
  "qualification": {"path": "<Desktop study/qualification.json>", "sha256": "<audited hash>"},
  "desktopWatcher": {"path": "<Desktop completion-audit.json>", "sha256": "<audited hash>"},
  "desktopEvidence": {"path": "<Desktop terminal-evidence.json>", "sha256": "<audited hash>"},
  "macWatcher": {"path": "<Mac completion-audit.json>", "sha256": "<audited hash>"},
  "macEvidence": {"path": "<Mac terminal-evidence.json>", "sha256": "<audited hash>"},
  "rootReleaseReview": {"path": "<independent root terminal review>", "sha256": "<approved hash>"}
}
```

Then run once from the reviewed finalization checkout:

```sh
python3 -B scripts/package_qualified_preview.py \
  --request /absolute/path/package-request.json \
  --output /absolute/new/private/preview-asset
```

The script preserves all model and certificate bytes and writes only `manifest.json`, a package
receipt, and exact copied subjects. It requires at least 16 GiB free after copying. The resulting
outer manifest hash is the new stored asset identity; never overwrite that directory after a game
has used it. The `TESTONLY-loader` directory and its invented certificate are never inputs here.

## Decode without serving

Using the already reviewed server runtime and JDK21, run the small source-mode helper:

```sh
"$PREVIEW_JAVA" -XX:ActiveProcessorCount=1 -Xmx2048m \
  --class-path "$PREVIEW_RUNTIME/server/lib/*" \
  scripts/VerifyCoordinatedPreview.java /absolute/new/private/preview-asset/manifest.json
```

This calls the actual cached reader and prints its profile and manifest hash. It performs no policy
forward, game action or server start. It must report `aor-coordinated-win-support-1` and the package's
exact manifest SHA. A decoding failure stops finalization; it never falls back to another model.

## Separate local server, after activation authorization

Check port18776 is unused and create a fresh private directory with mode700. Never point this server
at another preview's storage or change port18775. Use the immutable server and web runtime from the
reviewed preview source; no build is required when source and UI are unchanged.

```sh
set -eu
umask 077
mkdir /absolute/new/private/preview-session
env AOR_AUTH_MODE=local AOR_STORAGE=file AOR_HTTP_HOST=127.0.0.1 AOR_HTTP_PORT=18776 \
  AOR_DATA_DIR=/absolute/new/private/preview-session/data \
  AOR_WEB_DIR="$PREVIEW_RUNTIME/web" \
  AOR_COORDINATED_BOT_ASSET=/absolute/new/private/preview-asset/manifest.json \
  "$PREVIEW_JAVA" -XX:ActiveProcessorCount=2 -Xmx2048m \
  -cp "$PREVIEW_RUNTIME/server/lib/*" dev.aragos.aor.ApplicationKt \
  > /absolute/new/private/preview-session/server.log 2>&1
```

The command is deliberately foreground. An approved local process owner may detach it and record
the exact PID/group/log. Check `/api/health` and `/`, then verify the new stored profile/asset identity
on the first approved compatible HARD game. Only new current-rules four-seat qualified setups use
this profile. Existing G3 games, saves, services and ports retain their current behavior.
