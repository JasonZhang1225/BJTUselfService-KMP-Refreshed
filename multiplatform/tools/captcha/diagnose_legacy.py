#!/usr/bin/env python3
"""Compare the frozen legacy graph under plausible preprocessing variants."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from evaluate_model import decode


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SOURCE = REPOSITORY_ROOT / "app/src/main/assets/model.pt"
DEPLOYMENT = REPOSITORY_ROOT / "app/src/main/assets/model.pt"
MANIFEST = Path(__file__).with_name("validation_manifest.json")


def tensors(image: Image.Image) -> dict[str, torch.Tensor]:
    rgb = np.asarray(image.convert("RGB"), dtype=np.float32)
    variants = {
        "rgb_0_1": rgb / 255.0,
        "bgr_0_1": rgb[:, :, ::-1].copy() / 255.0,
        "rgb_0_255": rgb,
        "rgb_inverted": 1.0 - rgb / 255.0,
        "rgb_minus1_1": rgb / 127.5 - 1.0,
    }
    return {
        name: torch.from_numpy(value.transpose(2, 0, 1)).unsqueeze(0)
        for name, value in variants.items()
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image_directory", type=Path)
    parser.add_argument("--seeds", type=int, default=50)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    samples = json.loads(MANIFEST.read_text(encoding="utf-8"))["samples"]
    legacy = torch.jit.load(str(SOURCE), map_location="cpu")
    deployment = torch.jit.load(str(DEPLOYMENT), map_location="cpu").eval()
    names = list(tensors(Image.open(args.image_directory / samples[0]["file"])).keys())

    for name in names:
        deployment_correct = 0
        for sample in samples:
            tensor = tensors(Image.open(args.image_directory / sample["file"]))[name]
            with torch.no_grad():
                result = decode(deployment(tensor).numpy())
            deployment_correct += int(result.expression == sample["expression"])

        counts: list[int] = []
        valid_counts: list[int] = []
        for seed in range(args.seeds):
            correct = 0
            valid = 0
            for sample in samples:
                tensor = tensors(Image.open(args.image_directory / sample["file"]))[name]
                torch.manual_seed(seed)
                with torch.no_grad():
                    result = decode(legacy(tensor).numpy())
                correct += int(result.expression == sample["expression"])
                valid += int(result.expression.endswith("=") and len(result.expression) >= 4)
            counts.append(correct)
            valid_counts.append(valid)
        print(
            f"{name}: eval={deployment_correct}/{len(samples)}, "
            f"legacy_best={max(counts)}/{len(samples)}, legacy_mean={np.mean(counts):.2f}, "
            f"legacy_valid_best={max(valid_counts)}/{len(samples)}"
        )
        if args.verbose and name == "rgb_0_1":
            for sample in samples:
                tensor = tensors(Image.open(args.image_directory / sample["file"]))[name]
                with torch.no_grad():
                    result = decode(deployment(tensor).numpy())
                marker = "PASS" if result.expression == sample["expression"] else "FAIL"
                print(
                    f"  {sample['file']} expected={sample['expression']} "
                    f"predicted={result.expression} confidence={result.confidence:.3f} {marker}"
                )


if __name__ == "__main__":
    main()
