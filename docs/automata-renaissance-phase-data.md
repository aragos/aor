# Renaissance phase data

`RenaissancePhaseMain` is an explicit data-only entry point. It assigns 512 independent origins
prospectively: seat `index % 4`, phase `(index / 4) % 5` among Draw, Buy, Play, Purchase and Expansion.
The controlled seat passes every earlier Renaissance window, including later phases of earlier
turns, until the first branching window of its assigned phase. Pass-only assigned windows and
missing roots remain recorded; there is no refill. Other choices use the frozen ordinary, expansion,
token and sampled purchase controllers.

Capture stops the prefix before its command. Every offered action receives two common future
chance streams and immediate normal continuation, with the original absolute player-decision
offset. No prefix intervention is installed in a tail controller. Canonical prefix/suffix replay
and fresh-controller incumbent parity are mandatory. Actor inputs contain only the ordinary
`GameView`/`BotObservation`; executor histories, streams and terminal labels are separate.

The ceiling is 3,072 terminal tails and 512 missing-origin terminal backbones, plus captured prefix
work. This is a conservative 3,584-terminal bound; captured prefixes are not complete games.
Reseeding does not resample the existing deck or hands. These conditional returns are development
evidence across independent origin worlds, not privileged winner labels, a learned public policy
or a playing-strength qualification. There is no fitting, automatic successor or activation.

This prospective intervention avoids treating the first post-acquisition Expansion window as
evidence about all phases. It may reduce coverage through ownership and remaining-horizon limits;
coverage must be reported by assigned phase before interpreting return differences.
