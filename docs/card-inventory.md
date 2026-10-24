# History-card inventory

The catalog contains 64 physical cards: 29 in epoch one, 22 in epoch two, 13 in epoch three.
Physical IDs identify copies; a commodity choice identifies the shared effect. All leaders are
removed after use; a circle returns only when it is already discarded at the next epoch shuffle.

Sources: the [AH errata and reference bundle](https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf)
contains official 7 May 1998 errata on pages 1–3, the EuroGames differences on page 4, and the
complete named-card list near its end. Printed discounts are transcribed in [rules §11.3](rules.md#113-leader-cards).

The user supplied Chris Conboy (Liumas), *Age Of Renaissance Cards Reference*, in
`AOR_Cards_Reference_by_Liumas_2008-09.pdf`. Checked on 2026-10-24 by text extraction and visual
inspection of both catalog pages:

- **PDF page 2:** all 18 events, their epochs, effects and recycling arrows. Armor, Stirrups,
  Long Bow, Gunpowder and Mongol Armies have no recycling arrow; the other 13 events recycle.
- **PDF page 3:** all 27 commodity instances recycle; none of the 19 leaders recycle. The commodity
  quantities per epoch and every leader's discounts match the implemented catalog, including
  Walter's 20/30 condition and Marco Polo's 20/40 condition.
- **Result:** all 64 recycling statuses and the 29/22/13 epoch counts match. There are 40 recyclable
  cards and 24 single-use cards. No catalog data correction was needed; Q7 is resolved from this
  reference. The aid uses recycling arrows rather than reproducing each physical card's symbol.

The source PDF remains outside the repository. Its SHA-256 is
`714d64ec362447c5c2fd6d8f63e20adeda0da16e2815fe010a1432c5599497f4`.
Its PDF metadata identifies Chris Conboy as author and records creation on 2008-10-07;
the filename carries the 2008-09 label. The rows below map its recycling status to the engine's
circle/square classification: leaders and the five events above are squares; the rest are circles.

| Epoch | Physical ID | Kind | Symbol |
| --- | --- | --- | --- |
| 1 | `stone-1` | commodity | circle |
| 1 | `stone-2` | commodity | circle |
| 1 | `wool-1` | commodity | circle |
| 1 | `wool-2` | commodity | circle |
| 1 | `timber` | commodity | circle |
| 1 | `cloth-wine` | commodity | circle |
| 1 | `ivory-gold` | commodity | circle |
| 1 | `metal` | commodity | circle |
| 1 | `fur` | commodity | circle |
| 1 | `silk` | commodity | circle |
| 1 | `spice` | commodity | circle |
| 1 | `alchemists-gold` | event | circle |
| 1 | `armor` | event | square |
| 1 | `stirrups` | event | square |
| 1 | `civil-war` | event | circle |
| 1 | `enlightened-ruler` | event | circle |
| 1 | `famine` | event | circle |
| 1 | `mysticism-abounds` | event | circle |
| 1 | `papal-decree` | event | circle |
| 1 | `pirates-vikings` | event | circle |
| 1 | `rebellion` | event | circle |
| 1 | `revolutionary-uprisings` | event | circle |
| 1 | `the-crusades` | event | circle |
| 1 | `war` | event | circle |
| 1 | `charlemagne` | leader | square |
| 1 | `dionysus-exiguus` | leader | square |
| 1 | `rashid-ad-din` | leader | square |
| 1 | `st-benedict` | leader | square |
| 1 | `walter-the-penniless` | leader | square |
| 2 | `timber-2-1` | commodity | circle |
| 2 | `timber-2-2` | commodity | circle |
| 2 | `grain-2-1` | commodity | circle |
| 2 | `grain-2-2` | commodity | circle |
| 2 | `cloth-2` | commodity | circle |
| 2 | `wine-2` | commodity | circle |
| 2 | `metal-2` | commodity | circle |
| 2 | `silk-2` | commodity | circle |
| 2 | `spice-2` | commodity | circle |
| 2 | `black-death` | event | circle |
| 2 | `gunpowder` | event | square |
| 2 | `long-bow` | event | square |
| 2 | `mongol-armies` | event | square |
| 2 | `religious-strife` | event | circle |
| 2 | `christopher-columbus` | leader | square |
| 2 | `desiderius-erasmus` | leader | square |
| 2 | `ibn-majid` | leader | square |
| 2 | `johann-gutenberg` | leader | square |
| 2 | `marco-polo` | leader | square |
| 2 | `nicolaus-copernicus` | leader | square |
| 2 | `prince-henry` | leader | square |
| 2 | `william-caxton` | leader | square |
| 3 | `cloth-3` | commodity | circle |
| 3 | `wine-3` | commodity | circle |
| 3 | `metal-3` | commodity | circle |
| 3 | `fur-3` | commodity | circle |
| 3 | `silk-3` | commodity | circle |
| 3 | `spice-3` | commodity | circle |
| 3 | `gold-3` | commodity | circle |
| 3 | `andreas-vesalius` | leader | square |
| 3 | `bartolome-de-las-casas` | leader | square |
| 3 | `galileo-galilei` | leader | square |
| 3 | `henry-oldenburg` | leader | square |
| 3 | `leonardo-da-vinci` | leader | square |
| 3 | `sir-isaac-newton` | leader | square |

The effect catalog includes all 27 commodity instances, 18 events and 19 leaders. The later
leaders use the advance identities and amounts recorded in rules §11.3. Duplicate commodities
remain different physical cards even when their choices are equal.

Timing and interpretation:

- Official errata §9.32 preserves an already-played older military card against third parties.
  Its benefits are suppressed against the newer military card’s player. The unofficial FAQ
  reproduced later in the bundle describes broader cancellation and does not override that erratum.
- Mongol Armies permanently voids Crusades. Marco Polo captures that condition at play time,
  so a later Mongol Armies does not retroactively double earlier credits or rebates.
- Strife cancels Papal Decree throughout the same turn. Only Strife played in epoch three
  permanently turns Papal Decree into a burden.
- Black Death returns all square tokens in the selected area before replacing markers. Its
  replacements use the ordinary event replacement rule in official errata §9.32: stock first,
  otherwise an optional pool token or an empty province.
- Pirates uses the current epoch. The engine reads “reduce 1/2/3” as a requirement to select
  that many distinct available coastal markers, or all available markers if fewer exist.
  The official errata confirms overseas targeting but does not explicitly resolve fewer targets;
  the user approved this selection obligation as a project rule on 2026-10-24.
- Alternate Patronage uses boxes 1/2/3 with 0/1/2 other claimants respectively; subsequent leaders
  allow unrestricted claims. The box is fixed by play order, and protection money is unavailable.
- The EuroGames summary specifies the last-moving Wind/Watermill owner, after the first player’s
  buy-off opportunity. The engine selects the last active owner and offers a single neutral-to-
  shortage/surplus or shortage/surplus-to-neutral adjustment. Clearing a double marker clears its
  complete commodity status; the user approved that behavior on 2026-10-24.
