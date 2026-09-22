#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
import subprocess
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_ANDROID_MODEL = (
    REPOSITORY_ROOT / "multiplatform/androidApp/src/main/assets/BJTUCaptcha.onnx"
)
DEFAULT_APPLE_MODEL = (
    REPOSITORY_ROOT
    / "multiplatform/desktopApp/build/generated/captcha/model/BJTUCaptcha.mlmodelc"
)
DEFAULT_APPLE_HELPER = (
    REPOSITORY_ROOT
    / "multiplatform/desktopApp/build/generated/captcha/BJTUCaptchaHelper"
)
DEFAULT_MANIFEST = Path(__file__).with_name("validation_manifest.json")
CHARSET = " 0123456789+-*="


@dataclass(frozen=True)
class Decoded:
    expression: str
    confidence: float


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def decode(logits: np.ndarray) -> Decoded:
    values = np.asarray(logits, dtype=np.float32).reshape(8, 15)
    classes = values.argmax(axis=1)
    selected: list[int] = []
    probabilities: list[float] = []
    for step, class_index in enumerate(classes):
        if class_index == 0 or (step > 0 and class_index == classes[step - 1]):
            continue
        selected.append(int(class_index))
        row = values[step].astype(np.float64)
        row -= row.max()
        probabilities.append(float(math.exp(row[class_index]) / np.exp(row).sum()))
    return Decoded(
        expression="".join(CHARSET[index] for index in selected),
        confidence=min(probabilities, default=0.0),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image_directory", type=Path)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--android-model", type=Path, default=DEFAULT_ANDROID_MODEL)
    parser.add_argument("--apple-compiled-model", type=Path, default=DEFAULT_APPLE_MODEL)
    parser.add_argument("--apple-helper", type=Path, default=DEFAULT_APPLE_HELPER)
    parser.add_argument("--minimum-confidence", type=float, default=0.55)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    onnx_model = ort.InferenceSession(
        str(args.android_model),
        providers=["CPUExecutionProvider"],
    )

    onnx_correct = 0
    apple_correct = 0
    accepted_correct = 0
    false_accepts = 0
    argmax_equal = 0
    maximum_delta = 0.0

    print("file  expected  onnx(confidence)  coreml(confidence)  result")
    for sample in manifest["samples"]:
        path = args.image_directory / sample["file"]
        actual_hash = sha256(path)
        if actual_hash != sample["sha256"]:
            raise RuntimeError(f"hash mismatch for {path}: {actual_hash}")
        image = Image.open(path).convert("RGB")
        if image.size != (130, 42):
            image = image.resize((130, 42), Image.Resampling.BILINEAR)
        pixels = np.asarray(image, dtype=np.float32)
        tensor = np.expand_dims(pixels.transpose(2, 0, 1) / 255.0, axis=0)
        onnx_logits = onnx_model.run(["logits"], {"captcha": tensor})[0]
        helper = subprocess.run(
            [str(args.apple_helper), str(args.apple_compiled_model)],
            input=path.read_bytes(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
            timeout=20,
        )
        apple_logits = np.fromstring(helper.stdout.decode("utf-8"), sep=",", dtype=np.float32)
        if apple_logits.size != 120:
            raise RuntimeError(
                f"invalid Core ML helper output for {path}: {helper.stderr.decode('utf-8')}"
            )
        onnx_result = decode(onnx_logits)
        apple_result = decode(apple_logits)
        expected = sample["expression"]
        onnx_ok = onnx_result.expression == expected
        apple_ok = apple_result.expression == expected
        onnx_correct += int(onnx_ok)
        apple_correct += int(apple_ok)
        accepted = apple_result.confidence >= args.minimum_confidence
        accepted_correct += int(accepted and apple_ok)
        false_accepts += int(accepted and not apple_ok)
        same_classes = np.array_equal(
            np.asarray(onnx_logits).reshape(8, 15).argmax(axis=1),
            np.asarray(apple_logits).reshape(8, 15).argmax(axis=1),
        )
        argmax_equal += int(same_classes)
        maximum_delta = max(
            maximum_delta,
            float(
                np.abs(
                    np.asarray(onnx_logits).reshape(8, 15)
                    - np.asarray(apple_logits).reshape(8, 15)
                ).max()
            ),
        )
        result = "PASS" if onnx_ok and apple_ok and same_classes else "FAIL"
        print(
            f"{sample['file']:>4}  {expected:<8}  "
            f"{onnx_result.expression:<8}({onnx_result.confidence:.3f})  "
            f"{apple_result.expression:<8}({apple_result.confidence:.3f})  {result}"
        )

    total = len(manifest["samples"])
    print()
    print(f"samples={total}")
    print(f"onnx_expression_accuracy={onnx_correct / total:.4f} ({onnx_correct}/{total})")
    print(f"coreml_expression_accuracy={apple_correct / total:.4f} ({apple_correct}/{total})")
    print(f"backend_argmax_equal={argmax_equal / total:.4f} ({argmax_equal}/{total})")
    print(f"backend_max_abs={maximum_delta}")
    print(f"minimum_confidence={args.minimum_confidence}")
    print(f"accepted_correct={accepted_correct}")
    print(f"false_accepts={false_accepts}")


if __name__ == "__main__":
    main()
