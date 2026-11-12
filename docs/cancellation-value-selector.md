# Experimental public cancellation value

The opt-in arena `cancellationValue: {candidateModel: "model.json"}` intercepts only the
candidate seat's cancellation windows. Incumbent, rivals, card/purchase actors, token selector and
expansion overlay retain their original routing. Omission preserves legacy identities and metadata.
The model bytes are snapshotted and bound into replay identity; a side must be explicit.

The common `CancellationValuePolicy` computes the fixed 58 original-unit features from the actual
first player's public quote context, four-player turn order and own card definitions. It delegates
all queue removal and payouts to `AdjustmentCancellationProjection`. Physical Gold/Ivory cards
count once, rivals retain separate owner slots, and only current conditional payouts enter inputs.
The frozen TRAIN means/scales and coefficients predict CANCEL minus PASS terminal reward. Zero
TRAIN-variance columns contribute zero. PASS has value zero; a cancellation must exceed zero,
with canonical-command ordering for exact positive ties. There is no sampling or manual correction.

The standalone production-JS inference export is for parity verification, not browser activation.
It rejects undeclared fields and malformed dictionaries/models. Neither the server nor ordinary
bot dispatcher installs this policy. The native overlay accepts only four-player configurations.

The intended diagnostic compares this unchanged fixed model against the qualified G3 controller
on fresh complete games. Earlier labels concerned a first action followed by old ordinary
cancellation decisions; deployment would choose at every window with G3 continuation. Retained
conditional proxies do not establish that this distribution change improves strength. No automatic
qualification, refitting or activation follows from this source prerequisite.
