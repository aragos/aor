"""Measure public bid predictability after four prior games against the same policy."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path


def public_series(stage, prefix, role):
    games = {}
    for path in stage.glob(f"{prefix}-*/worlds.jsonl"):
        for line in path.open():
            world = json.loads(line)
            index = world["index"]
            if index in games:
                raise ValueError(f"The fixture includes duplicate world index {index}.")
            counter = world["diagnostics"][role]["publicTokenCounter"]
            if counter is None:
                raise ValueError("The selected fixture requires public counter diagnostic evidence.")
            games[index] = {
                proof["turn"]: proof["actualTargetBid"]
                for proof in counter["decisionProofs"] if proof["phase"] == "order-revealed"
            }
    return [game for _, game in sorted(games.items())]


def modal(counts):
    return max(counts, key=lambda bid: (counts[bid], bid))


def compare(games):
    estimates = defaultdict(Counter)
    test_games = 0
    for start in range(0, len(games) - 4, 5):
        training = games[start:start + 4]
        test = games[start + 4]
        if not all(training) or not test:
            continue
        test_games += 1
        by_turn = defaultdict(Counter)
        by_previous = defaultdict(Counter)
        all_bids = Counter()
        for game in training:
            for turn, bid in game.items():
                all_bids[bid] += 1
                by_turn[turn][bid] += 1
                by_previous[turn, game.get(turn - 1)][bid] += 1
        for turn, actual in test.items():
            recent = by_previous[turn, test.get(turn - 1)]
            forecast = modal(recent if sum(recent.values()) >= 2 else by_turn[turn] or all_bids)
            estimates["global"][actual == modal(all_bids)] += 1
            estimates["roundAndPrevious"][actual == forecast] += 1
            if turn == 1:
                estimates["opening"][actual == forecast] += 1
    openings = Counter(game[1] for game in games if 1 in game)
    return {"priorGamesPerForecast": 4, "testGames": test_games, "openingBidCounts": openings,
            "accuracy": {
        kind: {"hits": results[True], "predictions": results.total(),
               "fraction": results[True] / results.total()}
        for kind, results in estimates.items()
    }}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--role", choices=("CANDIDATE", "CONTROL"), default="CANDIDATE")
    args = parser.parse_args()
    games = public_series(args.stage, args.prefix, args.role)
    if len(games) < 5:
        raise SystemExit("At least five games with public bid proofs are required.")
    print(json.dumps(compare(games), indent=2))
