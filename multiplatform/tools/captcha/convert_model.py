#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import shutil
from collections import OrderedDict
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as functional


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_SOURCE = REPOSITORY_ROOT / "app/src/main/assets/model.pt"
DEFAULT_ANDROID_ONNX_OUTPUT = (
    REPOSITORY_ROOT / "multiplatform/androidApp/src/main/assets/BJTUCaptcha.onnx"
)
DEFAULT_APPLE_OUTPUT = (
    REPOSITORY_ROOT / "multiplatform/iosApp/iosApp/BJTUCaptcha.mlpackage"
)


def build_cnn() -> nn.Sequential:
    layers: OrderedDict[str, nn.Module] = OrderedDict()
    block_channels = ((3, 32), (32, 64), (64, 128), (128, 256), (256, 256))
    for block, (input_channels, output_channels) in enumerate(block_channels, start=1):
        names = (
            (f"conv{block}1", input_channels, output_channels),
            (f"conv{block}1_2", output_channels, output_channels),
            (f"conv{block}2", output_channels, output_channels),
            (f"conv{block}2_2", output_channels, output_channels),
        )
        for conv_name, in_channels, out_channels in names:
            suffix = conv_name.removeprefix("conv")
            layers[conv_name] = nn.Conv2d(
                in_channels,
                out_channels,
                kernel_size=3,
                stride=1,
                padding=1,
                bias=True,
            )
            layers[f"bn{suffix}"] = nn.BatchNorm2d(out_channels)
            layers[f"relu{suffix}"] = nn.ReLU(inplace=True)
        pool_size = (2, 1) if block == 5 else (2, 2)
        layers[f"pool{block}"] = nn.MaxPool2d(pool_size, pool_size)
    layers["dropout"] = nn.Dropout(0.25)
    return nn.Sequential(layers)


class CaptchaModel(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.cnn = build_cnn()
        self.lstm = nn.LSTM(
            input_size=256,
            hidden_size=128,
            num_layers=2,
            bidirectional=True,
        )
        self.fc = nn.Linear(256, 15)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        features = self.cnn(image)
        sequence = features.reshape(1, 256, 8).permute(2, 0, 1)
        sequence, _ = self.lstm(sequence)
        return self.fc(sequence)


class DeploymentCaptchaModel(CaptchaModel):
    """Deterministic graph with explicit input normalization and unrolled LSTM."""

    def run_direction(
        self,
        sequence: torch.Tensor,
        layer: int,
        reverse: bool,
    ) -> torch.Tensor:
        suffix = f"_l{layer}" + ("_reverse" if reverse else "")
        weight_ih = getattr(self.lstm, f"weight_ih{suffix}")
        weight_hh = getattr(self.lstm, f"weight_hh{suffix}")
        bias_ih = getattr(self.lstm, f"bias_ih{suffix}")
        bias_hh = getattr(self.lstm, f"bias_hh{suffix}")
        hidden = sequence.new_zeros((1, 128))
        cell = sequence.new_zeros((1, 128))
        outputs = [sequence[0].new_zeros((1, 128)) for _ in range(8)]
        indices = range(7, -1, -1) if reverse else range(8)
        for index in indices:
            gates = functional.linear(sequence[index], weight_ih, bias_ih)
            gates = gates + functional.linear(hidden, weight_hh, bias_hh)
            input_gate, forget_gate, candidate, output_gate = gates.chunk(4, dim=1)
            input_gate = torch.sigmoid(input_gate)
            forget_gate = torch.sigmoid(forget_gate)
            candidate = torch.tanh(candidate)
            output_gate = torch.sigmoid(output_gate)
            cell = forget_gate * cell + input_gate * candidate
            hidden = output_gate * torch.tanh(cell)
            outputs[index] = hidden
        return torch.stack(outputs, dim=0)

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        # The legacy graph runs BatchNorm in training mode even after loading.
        # A single-image batch therefore normalizes the captcha dynamically.
        # The stored running statistics expect centered input in deterministic
        # eval mode, so make that implicit behavior explicit and reproducible.
        image = image * 2.0 - 1.0
        features = self.cnn(image)
        sequence = features.reshape(1, 256, 8).permute(2, 0, 1)
        for layer in range(2):
            forward = self.run_direction(sequence, layer, reverse=False)
            backward = self.run_direction(sequence, layer, reverse=True)
            sequence = torch.cat((forward, backward), dim=2)
        return self.fc(sequence)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--android-onnx-output", type=Path, default=DEFAULT_ANDROID_ONNX_OUTPUT)
    parser.add_argument("--apple-output", type=Path, default=DEFAULT_APPLE_OUTPUT)
    parser.add_argument("--skip-apple", action="store_true")
    args = parser.parse_args()

    source = torch.jit.load(str(args.source), map_location="cpu")
    weights = {name: value.detach().clone() for name, value in source.state_dict().items()}
    random = np.random.default_rng(20260801)
    example_pixels = random.integers(0, 256, size=(42, 130, 3), dtype=np.uint8)
    example = torch.from_numpy(
        example_pixels.transpose(2, 0, 1).astype(np.float32) / 255.0,
    ).unsqueeze(0)

    legacy_equivalent = CaptchaModel().train()
    legacy_equivalent.load_state_dict(weights, strict=True)
    torch.manual_seed(20260801)
    with torch.no_grad():
        source_logits = source(example).numpy()
    torch.manual_seed(20260801)
    with torch.no_grad():
        rebuilt_training_logits = legacy_equivalent(example).numpy()
    training_delta = float(np.abs(source_logits - rebuilt_training_logits).max())
    if training_delta != 0.0:
        raise RuntimeError(f"reconstructed training graph differs: {training_delta}")

    reference = CaptchaModel().eval()
    reference.load_state_dict(weights, strict=True)
    deployment = DeploymentCaptchaModel().eval()
    deployment.load_state_dict(weights, strict=True)
    with torch.no_grad():
        expected = reference(example * 2.0 - 1.0).numpy()
        deployment_logits = deployment(example).numpy()
    unrolled_delta = float(np.abs(expected - deployment_logits).max())

    traced = torch.jit.freeze(torch.jit.trace(deployment, example, strict=True).eval())
    with torch.no_grad():
        traced_logits = traced(example).numpy()
    trace_delta = float(np.abs(expected - traced_logits).max())

    args.android_onnx_output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        deployment,
        (example,),
        str(args.android_onnx_output),
        input_names=["captcha"],
        output_names=["logits"],
        opset_version=18,
        do_constant_folding=True,
        dynamo=False,
    )

    import onnx
    import onnxruntime as ort

    onnx.checker.check_model(onnx.load(str(args.android_onnx_output)))
    onnx_session = ort.InferenceSession(
        str(args.android_onnx_output),
        providers=["CPUExecutionProvider"],
    )
    onnx_logits = onnx_session.run(
        ["logits"],
        {"captcha": example.numpy()},
    )[0]
    onnx_delta = np.abs(expected - onnx_logits)
    if not np.array_equal(expected.argmax(-1), onnx_logits.argmax(-1)):
        raise RuntimeError("ONNX argmax sequence differs from PyTorch")

    core_ml_delta = None
    if not args.skip_apple:
        import coremltools as ct
        from PIL import Image

        core_ml = ct.convert(
            traced,
            inputs=[
                ct.ImageType(
                    name="captcha",
                    shape=example.shape,
                    scale=1.0 / 255.0,
                    color_layout=ct.colorlayout.RGB,
                ),
            ],
            outputs=[ct.TensorType(name="logits", dtype=np.float32)],
            convert_to="mlprogram",
            minimum_deployment_target=ct.target.iOS15,
            compute_precision=ct.precision.FLOAT32,
        )
        core_ml.author = "BJTUselfService Contributors"
        core_ml.short_description = "本地识别北交大 CAS 算术验证码"
        core_ml.version = "1.0"
        core_ml.input_description["captcha"] = "130×42 RGB 像素；模型内转换为 CHW [0,1] 后再中心化到 [-1,1]"
        core_ml.output_description["logits"] = "CTC 时间优先 logits [8,1,15]"
        core_ml.user_defined_metadata["source_sha256"] = sha256(args.source)
        core_ml.user_defined_metadata["charset"] = " <blank>,0,1,2,3,4,5,6,7,8,9,+,-,*,="
        core_ml.user_defined_metadata["normalization"] = "RGB/255 then x*2-1"
        if args.apple_output.exists():
            shutil.rmtree(args.apple_output)
        args.apple_output.parent.mkdir(parents=True, exist_ok=True)
        core_ml.save(str(args.apple_output))

        core_ml_logits = core_ml.predict({"captcha": Image.fromarray(example_pixels, mode="RGB")})["logits"]
        core_ml_delta = np.abs(expected - core_ml_logits)
        if not np.array_equal(expected.argmax(-1), core_ml_logits.argmax(-1)):
            raise RuntimeError("Core ML argmax sequence differs from PyTorch")

    print(f"source_sha256={sha256(args.source)}")
    print(f"training_mode_rebuild_max_abs={training_delta}")
    print(f"unrolled_lstm_max_abs={unrolled_delta}")
    print(f"eval_trace_max_abs={trace_delta}")
    print(f"onnx_max_abs={float(onnx_delta.max())}")
    print(f"onnx_mean_abs={float(onnx_delta.mean())}")
    if core_ml_delta is not None:
        print(f"coreml_max_abs={float(core_ml_delta.max())}")
        print(f"coreml_mean_abs={float(core_ml_delta.mean())}")
    print("argmax_equal=True")
    print(f"android_onnx_model={args.android_onnx_output}")
    if not args.skip_apple:
        print(f"apple_model={args.apple_output}")


if __name__ == "__main__":
    main()
