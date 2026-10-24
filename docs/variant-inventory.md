# Functional rule variants

All games use three epochs. The default functional choices reproduce the reviewed AH1996 table;
showing cards and bribery are outside the application rules.

`set-option preset ah1996`, `set-option preset second-edition` and `set-option preset tournament`
are input conveniences. Durable histories expand a preset into the eleven explicit options below.
The creator may then override individual choices until `start`; after starting, they are frozen.
The existing `rebuilt-marker-bonus` option remains independent.

| Option | Values (first is the AH1996 default) |
| --- | --- |
| `setup` | `standard`, `improved` |
| `initial-card` | `any`, `commodity-only` |
| `commerce` | `ah1996`, `second-edition` |
| `wind-watermill` | `takeover-trade`, `market-adjustment` |
| `written-record` | `leader-bonus`, `takeover-trade` |
| `income-relief` | `new-world`, `enlightenment` |
| `credits` | `ah1996`, `second-edition` |
| `misery-relief` | `purchased`, `category-completion` |
| `patronage` | `protection-money`, `three-box-display` |
| `immediate-master-art-discard` | `disabled`, `enabled` |
| `st-gall-support` | `disabled`, `enabled` |

The second-edition preset selects improved setup, the alternate commerce and credit tables,
market-adjustment Wind/Watermill, takeover Written Record, Enlightenment income relief,
category-completion relief, three-box Patronage, and an immediate Master Art discard. The tournament
preset selects commodity-only initial dealing, immediate Master Art discard, and St. Gall support.
These mechanics are independently configurable, subject to the user's 2026-10-24 compatibility
ruling: "get three, choose two" is incompatible with reduced initial-deal decks for now. Improved
setup combined with commodity-only initial dealing is rejected at start. The sources do not
define that combination; its exclusion is now an explicit project rule.

## Selected tables and identity

Advance identities are permanent. The alternate commerce table changes Improved Agriculture to J
($40, requires Caravan) and Wind/Watermill to K ($50, requires Improved Agriculture). Interest &
Profit and Middle Class require the advance printed K in the selected table. Names, card leader
references and stored acquisitions retain their stable identities; quotes, command letters, displayed
letters and scoring resolve the selected definitions.

Second-edition Communication credits are 30/20/10/0, Civics credits are 20/30/40/50/60, and
Proselytism gives 30. Credits still become usable only in the turn after acquisition. Written
Record's leader bonus exists only with `written-record leader-bonus`.

With category-completion relief, crossing two, three, and four owned advances in each of Science,
Commerce and Communication awards one, two, and three additional misery spaces respectively.
Each threshold is earned once. Acquisition history establishes which thresholds have already been
earned, including thresholds reached while misery is zero. The award is applied on acquisition,
so it precedes stabilization. Cash misery relief is unavailable in this mode. Chaos remains final:
players already in Chaos cannot acquire advances or receive relief.

## Source evidence and preserved decisions

The [reference compilation](https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf)
contains the EuroGames 2nd Edition Rules supplement on PDF page 4, and the second-edition printed
Advance Log on PDF page 17. The local reviewed copy is the same compilation used by the baseline
catalog inventory. These references supply the table changes and functional options above.

- Page 4 and page 17 both give Proselytism 30 credits. This adds the credit omitted from the old
  short §13 summary without changing the AH1996 table.
- Patronage has conflicting values: page 4 says 20, while page 17 prints 10. The implementation
  preserves the reviewed baseline 20 in both tables, following the explicit supplement rather than
  silently changing the established catalog. The user approved 20 on 2026-10-24.
- Page 4 calls the final relief threshold “all” advances, while page 17 explicitly prints four
  in each category. The implementation follows the explicit 2/3/4 thresholds already recorded in
  the reviewed rules and plan. Commerce contains five advances; its fifth does not earn extra relief.
  The user approved the four-per-category final threshold on 2026-10-24.
- The user also approved awarding relief immediately when an acquisition crosses a threshold.
  Each threshold grants 1/2/3 additional spaces once; it is not recurring Income.
  The source's wording “two more” / “three additional” is also present in the full Euro-rules
  rewrite's Purchase section. No extra player choice or cash purchase is introduced.
- Page 4 says the last-moving **owner** of Wind/Watermill adjusts one of Grain, Cloth, Wine or Metal
  after the first player's buy-off opportunity. A shortage or surplus may become neutral, or a
  neutral commodity may gain a shortage or surplus; a direct shortage-to-surplus flip is excluded.
  An owner already in Chaos has no advance abilities under the ordinary Chaos rule and is skipped.
- The second-edition printed log states Written Record is once per turn after a successful attack
  against a non-owner. The supplement describes the simplified takeover trade; the log establishes
  its limit. The baseline paid Wind/Watermill trade is disabled in market-adjustment mode.
- The original [Avalon Hill errata](https://www.grognard.com/ah/errata/aore.html), map question on
  St. Gall, confirms it does not support Nuremberg in the printed baseline. The optional arrow is
  added to the selected board configuration without changing the reviewed printed support graph.

`RuleOptionsTest` compares both tables, identity/letter resolution, canonical option snapshots,
configuration permissions and selected support. `AlternateEconomyTest` compares purchase pricing,
old-credit timing, Written Record bonuses, score values, threshold relief and Income ownership.
