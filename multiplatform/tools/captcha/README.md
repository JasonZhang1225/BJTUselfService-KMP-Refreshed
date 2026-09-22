# 验证码模型转换

`convert_model.py` 从冻结 Android 参考模型读取原权重，重建确定性的
推理网络，并同时生成：

- `androidApp/src/main/assets/BJTUCaptcha.onnx`
- `iosApp/iosApp/BJTUCaptcha.mlpackage`

原模型导出时把 BatchNorm 与 Dropout 固化在训练模式，不能直接作为
Apple 推理基线。转换脚本先证明重建网络在相同训练模式、相同随机种子
下与原模型 logits 一致，再关闭 Dropout、使用 BatchNorm 运行统计，并将
旧图中由单图 BatchNorm 隐式完成的中心化明确固定为 `[0,1] → [-1,1]`，
最后将固定 8 时间步的双向 LSTM 展开后转换 ONNX 与 Core ML。Android
使用 ONNX Runtime，不再打包已停止更新的 PyTorch Mobile 2.1.0。

转换只在隔离 Python 环境中执行，Python 和转换工具不会打进应用。运行：

```bash
python3 -m venv /private/tmp/bjtu-coreml-venv
/private/tmp/bjtu-coreml-venv/bin/pip install -r tools/captcha/requirements.txt
/private/tmp/bjtu-coreml-venv/bin/python tools/captcha/convert_model.py
```

Apple 模型接收固定 `130×42` RGB 图像，`1/255` 与 CHW 转换由 Core ML
模型输入描述完成，`[-1,1]` 中心化烘焙在网络内；平台只负责把验证码
缩放到该固定像素尺寸。Android 仍传入 `[0,1]` RGB/CHW 张量。

验收输出必须包含：训练模式重建误差为 0、展开 LSTM 与 eval 模型误差、
ONNX/Core ML 原始 logits 误差，以及 `argmax_equal=True`。真实准确率另由本地
脱敏验证码测试集验证，不能用随机张量等价测试冒充。生成后还应记录
`BJTUCaptcha.onnx` 的 SHA-256；APK 签名保护最终随包资产的完整性。

## 固定真实样本验证

`validation_manifest.json` 固化真实样本的 SHA-256 与人工表达式标签，
但不提交验证码图片。将对应图片放在一个本地目录后，同时评测 Android
ONNX 与 Apple Core ML 模型：

```bash
/private/tmp/bjtu-coreml-venv/bin/python tools/captcha/evaluate_model.py \
  /private/tmp/bjtu-captcha-samples
```

脚本会拒绝哈希不匹配的图片，并报告两端表达式准确率、逐时间步 argmax
一致率、默认置信度阈值下的正确接受数和错误接受数。该 24 张集合只是迁移
冒烟基线，不代替后续扩大到至少 300 张的发布级测试集。

评测脚本默认调用 Desktop 构建生成的原生 Swift/Core ML helper，因而要先
运行 `./gradlew :desktopApp:compileMacCaptchaModel :desktopApp:compileMacCaptchaHelper`。
这条路径和 macOS 应用运行时一致，也避免让 Python 评测逻辑替代真正的
Apple 推理入口。

若需复核旧模型为何在训练态可用、直接切换 eval 却失效，可运行：

```bash
/private/tmp/bjtu-coreml-venv/bin/python tools/captcha/diagnose_legacy.py \
  /private/tmp/bjtu-captcha-samples --seeds 10 --verbose
```

它会比较旧 TorchScript 的单图 BatchNorm 行为以及多个候选输入归一化；
正式应用仍只使用转换脚本烘焙的确定性 `[0,1] → [-1,1]` 路径。

打包后可用只读诊断参数验证 Desktop 主进程能从 App Bundle 定位 helper
与模型（不会启动 UI、读取安全存储或请求学校网络）：

```bash
desktopApp/build/compose/binaries/main/app/BJTUselfServiceKMP.app/Contents/MacOS/BJTUselfServiceKMP \
  --verify-captcha-model=/private/tmp/bjtu-captcha-samples/01.png
```
