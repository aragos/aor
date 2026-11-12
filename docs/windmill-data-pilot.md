# Windmill acquisition/disposal data pilot

`WindmillMain` is a data-only installed-classpath entry point with separate `prepare` and
single-attempt `collect` modes. The 256 prospective origins assign controlled seat `index % 4`
and epoch `1 + index % 3`. The first controlled-seat Windmill opportunity in that epoch is captured;
missing opportunities remain missing. Prefixes use normal policies, without forced purchases,
advance acquisition or prior Windmill decisions.

Every seat uses the same sampled G3 card/purchase bundle with explicit raw M8, E, fixed T and F0
roles. Every offered ATTEMPT/PASS action has four common future chance streams and freshly composed
normal continuations at the original absolute player-command offset. Existing hidden hands/deck
remain fixed across a root's forks. The independent statistical unit is the prospective origin,
not its dependent tails; no best-action labels or learned policy are produced.

When the forced root ATTEMPT immediately succeeds, the actor-safe offer-card observation and normal
command actually executed are retained. The choice is neither changed nor branched, and later
Windmill offers are not attributed to the original attempt. Canonical prefix/suffix histories and
terminal returns stay separate from actor inputs and reproduce every completed branch.

Collection is bounded to ten workers, two hours and at most 2,304 terminal executions: 2,048 tails
plus 256 potentially missing full prefixes. Preparation performs no simulations. All prospective
origins and missing cases are retained; there is no refill, resume, retry, fitting or activation.

Version two requires an explicit `rootProfile`. `ASSIGNED_EPOCH` preserves the original
epoch schedule. `FIRST_BURDEN_WINDMILL` captures the first controlled-seat Windmill window
at any epoch with nonempty actor-safe `burdenReferences`. Earlier choices remain exact
normal choices; no forced PASS or continuation change occurs. Version two records the
profile, nullable target epoch, and each window's own-burden eligibility. Version one
inputs, request metadata and behavior remain unchanged. Oversampled burden coverage is
development data, not a runtime-average sampling claim.
