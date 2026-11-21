"""Fit a bounded expansion correction on independent simulated origins using CPU or CUDA."""

import json
import sys

import torch


def fit(origins, device):
    # Entire origins (all alternatives and paired futures) share a split.
    groups = [[alt for alt in r["alternatives"]] for r in origins if r["alternatives"]]
    if len(groups) < 4:
        return {"device": str(device), "origins": len(groups), "admitted": False}
    training, validation, test = [], [], []
    for idx, group in enumerate(groups):
        (test if idx % 5 == 0 else validation if idx % 5 == 1 else training).append(group)

    def tensors(groups):
        rows = [alt for group in groups for alt in group]
        return (
            torch.tensor([a["inputs"] for a in rows], dtype=torch.float64, device=device),
            torch.tensor([a["gain"] for a in rows], dtype=torch.float64, device=device),
        )

    x, y = tensors(training)
    penalties = torch.tensor([0.001, 0.01, 0.1, 1.0], dtype=x.dtype, device=device)
    eye = torch.eye(x.size(1), device=device, dtype=x.dtype)
    weights = (
        torch.linalg.solve(
            x.T @ x + (len(x) * penalties[:, None, None]) * eye,
            (x.T @ y)[None, :, None].expand(len(penalties), -1, -1),
        )
        .squeeze(-1)
        .clamp(-4, 4)
    )

    def yields(weights, groups):
        x, y = tensors(groups)
        scores = x @ weights.T
        outcomes = []
        offset = 0
        for group in groups:
            end = offset + len(group)
            values, indexes = scores[offset:end].max(dim=0)
            outcomes.append(torch.where(values > 0.02, y[offset + indexes], 0.0))
            offset = end
        return torch.stack(outcomes)

    performance = yields(weights, validation).mean(dim=0)
    best = int(performance.argmax())
    held = yields(weights[best : best + 1], test)[:, 0]
    gain = float(held.mean())
    se = float(held.std() / len(held) ** 0.5) if len(held) > 1 else 1.0
    return dict(
        device=str(device),
        gpu=torch.cuda.get_device_name() if device == "cuda" else None,
        origins=len(groups),
        heldOutOrigins=len(test),
        gain=gain,
        lower95=gain - 1.96 * se,
        admitted=gain - 1.96 * se > 0,
        expansion=weights[best].tolist(),
    )


if __name__ == "__main__":
    torch.set_num_threads(1)
    origins = json.load(sys.stdin)
    # Stable order independent of JVM worker completion and shard placement.
    print(json.dumps(fit(sorted(origins, key=lambda r: r["seed"]), sys.argv[1]), allow_nan=False))
