# Cancellation value diagnostic

`scripts/cancellation_conditional_value.py` consumes the fixed family61 TRAIN and VALIDATION
origins and the independently verified public cancellation quotes. TEST origins are rejected.
The 58 columns retain physical Gold/Ivory grouping, ordered marker occurrences and separate
own/rival payout amounts. Own physical commodity cards are counted with a zero-game
`CardEffectData` catalog export. Payout and removal rules remain with the native projection.

One Float64 ridge fit uses lambda10, TRAIN-only weighted normalization and equal origin weights.
The unchanged numerical owner is `acquisition_conditional_value.py`, retained byte-for-byte from
`18bad0283da75fffe39f8ebb481cc34c0cb44435` for reuse; its acquisition CLI is not invoked.
Every offered CANCEL target is its two-stream terminal reward difference from PASS. No winning
plan or action is supplied as a training label.

The fixed public policy proxy chooses the highest predicted CANCEL value only when positive;
otherwise it chooses PASS. Positive ties use canonical command order. Predictions and choices
are saved before semantic VALIDATION return processing. Reports average streams and actions
within each origin, then compare independent origins. Selection effects compare against both
PASS and the recorded normal incumbent action. These are first-action development proxies under
the original downstream policy, not evidence of an information-safe whole-game student gain.

Preparation and launch are separate. The frozen protocol pins inputs, projection proof, numerical
owner and runtime. One 600-second CPU1 attempt is allowed only after review. No TEST access,
threshold search, refit, native policy export or games are part of this diagnostic.
