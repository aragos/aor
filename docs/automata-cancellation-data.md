# Cancellation counterfactual data

`CancellationMain` is a standalone, data-only installed-classpath entry point. Its explicit source
contains exactly 256 independent origin seeds and two distinct future chance streams per origin.
`prepare` parses and snapshots these inputs without playing; `collect` uses one bounded attempt.

Origin `index` targets epoch `1 + index % 3`. Normal frozen policies advance the origin until the
first branching `cancel-adjustment` window in that epoch. The evaluated seat is the actual first
player at that window, recorded explicitly. All four seats use identical ordinary, expansion, token
and sampled purchase roles, including the purchase actor's separate prior; changing the evaluated
seat cannot change the ecology. Missing windows remain terminal origins, without replacement.

Every offered command, including PASS, receives both paired full continuations. Fresh complete
controllers resume immediately after the root command at the original absolute **player-command**
index, not the canonical entry count or the evaluated seat's decision count. Prefixes and suffixes
are retained and canonically replayed. Collection stores each completed origin before returning only
its compact receipt to the worker pool.

Actor inputs contain only the existing `GameView` and `BotObservation`. The view retains typed marker
queues, cancellation cost offers and current public commodity payout quotes. No rules or runtime
features change. `ActivityPlanning.preview` does not admit cancellation; this adapter does not extend
it or manufacture after-cancellation quotes. Canonical authority histories and terminal returns are
separate evidence, never actor inputs or privileged winner labels.

Reseeding a fork changes future chance but preserves existing hands and deck order. The independent
statistical unit is the origin, not its actions or two tails. The dataset is development evidence;
collection neither fits a model nor qualifies or activates a policy. Source/parser/routing tests in
`CancellationInputTest` and the existing `PurchaseSequenceTeacherTest` cover the reused offset owner.
