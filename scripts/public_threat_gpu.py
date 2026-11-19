"""Train a nonlinear public threat predictor independently of native game simulation."""

import argparse
from pathlib import Path

import numpy as np

from initiative_campaign import read, sha, write
from public_threat_campaign import dataset, grouped_interval, loss, observations


VERSION = "public-seat-threat-network-1"
SPLITS = ("train", "tune", "test", "transfer")
TRAIN_SEED = 5023671


def prepare(lab, output):
    linear = read(lab / "result.json")
    count = linear["worldsPerPopulation"]
    source = ("positions-ordinary", "positions-adaptive", "positions-unseen")
    data = [observations(lab / phase, count) for phase in source]
    ordinary, adaptive, transfer = [worlds for worlds, _ in data]
    columns = data[0][1]
    if columns != linear["columns"] or any(cols != columns for _, cols in data):
        raise ValueError("The nonlinear fit must preserve the exact linear benchmark column order.")
    development = ordinary + adaptive
    splits = {
        "train": [row for row in development if (row["index"] // 4) % 6 < 4],
        "tune": [row for row in development if (row["index"] // 4) % 6 == 4],
        "test": [row for row in development if (row["index"] // 4) % 6 == 5],
        "transfer": transfer,
    }
    seeds = [row["seed"] for split in splits.values() for row in split]
    if len(seeds) != len(set(seeds)):
        raise ValueError("All fit, tuning and transfer games must be independent.")
    output.mkdir()
    arrays = {}
    for name, worlds in splits.items():
        arrays.update({f"{name}_{key}": value for key, value in
                       dataset(worlds, tuple(range(len(columns)))).items()})
    archive = output / "positions.npz"
    np.savez_compressed(archive, **arrays)
    write(output / "data.json", {
        "kind": VERSION, "columns": columns, "worlds": {k: len(v) for k, v in splits.items()},
        "inputs": {phase: sha(lab / phase / "method.json") for phase in source},
        "linearResultSha256": sha(lab / "result.json"), "linearWeights": linear["weights"],
        "positionsSha256": sha(archive),
    })


def linear_probabilities(split, coefficients):
    logits = np.where(split["active"], split["x"] @ coefficients, -1e10)
    exp = np.exp(logits - logits.max(axis=1, keepdims=True))
    return exp / exp.sum(axis=1, keepdims=True)


def compare(split, nonlinear, linear):
    y, groups = split["y"], split["group"]
    rows = np.arange(len(y))
    network_choice = nonlinear.argmax(axis=1)
    linear_choice = linear.argmax(axis=1)
    return {
        "worlds": len(set(groups)),
        "positions": len(y),
        "changedTopThreat": int(np.sum(network_choice != linear_choice)),
        "logLossReduction": grouped_interval(groups, loss(y, linear) - loss(y, nonlinear)),
        "topThreatWinShareDelta": grouped_interval(groups,
                                                   y[rows, network_choice] - y[rows, linear_choice]),
    }


def fit(folder, output):
    import torch
    from torch import nn

    if not torch.cuda.is_available():
        raise RuntimeError("The requested fit requires the verified CUDA worker.")
    torch.use_deterministic_algorithms(True)
    torch.set_num_threads(1)
    data = read(folder / "data.json")
    archive = folder / "positions.npz"
    if data["kind"] != VERSION or sha(archive) != data["positionsSha256"]:
        raise ValueError("The simulated public dataset must match its audited transfer digest.")
    with np.load(archive, allow_pickle=False) as arrays:
        cases = {name: {key.removeprefix(name + "_"): arrays[key] for key in arrays
                        if key.startswith(name + "_")} for name in SPLITS}
    train = cases["train"]
    active = train["x"][train["active"]]
    center = active.mean(axis=0)
    scale = np.maximum(active.std(axis=0), .05)
    dev = torch.device("cuda")
    tensors = {name: (
        torch.as_tensor((rows["x"] - center) / scale, dtype=torch.float32, device=dev),
        torch.as_tensor(rows["active"], dtype=torch.bool, device=dev),
        torch.as_tensor(rows["y"], dtype=torch.float32, device=dev),
        torch.as_tensor(rows["weight"], dtype=torch.float32, device=dev),
    ) for name, rows in cases.items()}

    class SeatNetwork(nn.Module):
        def __init__(self, features, width):
            super().__init__()
            self.model = nn.Sequential(nn.Linear(features * 3, width), nn.Tanh(),
                                       nn.Linear(width, width // 2), nn.Tanh(),
                                       nn.Linear(width // 2, 1))

        def forward(self, observed, live):
            active = live.unsqueeze(-1)
            average = observed.masked_fill(~active, 0).sum(1, keepdim=True) / active.sum(
                1, keepdim=True).clamp_min(1)
            largest = observed.masked_fill(~active, -1e6).amax(1, keepdim=True)
            common = torch.cat((observed, average.expand_as(observed), largest - observed), dim=-1)
            logits = self.model(common).squeeze(-1).masked_fill(~live, -1e6)
            return logits.log_softmax(dim=-1)

    def train_network(width, decay):
        torch.manual_seed(TRAIN_SEED)
        features, live, labels, group_weight = tensors["train"]
        net = SeatNetwork(features.shape[-1], width).to(dev)
        optimizer = torch.optim.AdamW(net.parameters(), lr=.002, weight_decay=decay)
        for _ in range(36):
            order = torch.randperm(len(labels), device=dev)
            for batch in order.split(256):
                logged = net(features[batch], live[batch])
                costs = -(labels[batch] * logged).sum(-1)
                objective = (costs * group_weight[batch]).sum() / group_weight[batch].sum()
                optimizer.zero_grad(set_to_none=True)
                objective.backward()
                optimizer.step()
        return net

    def predict(model, split):
        features, live, _, _ = tensors[split]
        with torch.inference_mode():
            return model(features, live).exp().cpu().numpy().astype(float)

    linear = {name: linear_probabilities(rows, np.asarray(data["linearWeights"]))
              for name, rows in cases.items()}
    candidate_configs = (("small", 24, .035), ("wide", 64, .07))
    tuning, models = {}, {}
    for label, width, decay in candidate_configs:
        model = train_network(width, decay)
        models[label] = (width, model)
        tuning[label] = compare(cases["tune"], predict(model, "tune"), linear["tune"])
    selected = max(tuning, key=lambda name: tuning[name]["logLossReduction"]["mean"])
    width, model = models[selected]
    heldout = {name: compare(cases[name], predict(model, name), linear[name])
               for name in ("test", "transfer")}
    model_dict = {name: values.detach().cpu().tolist()
                  for name, values in model.state_dict().items()}
    output.mkdir()
    write(output / "model.json", {
        "kind": VERSION, "columns": data["columns"], "width": width, "turnOrigin": 5,
        "turnScale": 4, "centers": center.tolist(), "scales": scale.tolist(), "network": model_dict,
        "datasetSha256": data["positionsSha256"], "linearResultSha256": data["linearResultSha256"],
    })
    write(output / "result.json", {
        "kind": VERSION, "device": torch.cuda.get_device_name(dev), "torchVersion": str(torch.__version__),
        "choice": selected, "tuning": tuning, "heldout": heldout,
        "modelSha256": sha(output / "model.json"),
        "passed": all(row["logLossReduction"]["lower95"] > 0
                      and row["topThreatWinShareDelta"]["mean"] >= 0 for row in heldout.values()),
    })


if __name__ == "__main__":
    top = argparse.ArgumentParser(description=__doc__)
    modes = top.add_subparsers(dest="mode", required=True)
    material = modes.add_parser("prepare")
    material.add_argument("--lab", required=True, type=Path)
    material.add_argument("--output", required=True, type=Path)
    fitting = modes.add_parser("fit")
    fitting.add_argument("--data", required=True, type=Path)
    fitting.add_argument("--output", required=True, type=Path)
    args = top.parse_args()
    if args.mode == "prepare":
        prepare(args.lab.resolve(), args.output.resolve())
    else:
        fit(args.data.resolve(), args.output.resolve())
