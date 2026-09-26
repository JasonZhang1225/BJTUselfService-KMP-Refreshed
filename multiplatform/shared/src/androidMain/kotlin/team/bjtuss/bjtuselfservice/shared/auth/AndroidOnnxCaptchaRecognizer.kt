package team.bjtuss.bjtuselfservice.shared.auth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MODEL_ASSET = "BJTUCaptcha.onnx"
private const val CAPTCHA_WIDTH = 130
private const val CAPTCHA_HEIGHT = 42

/** Android captcha inference without the abandoned PyTorch Mobile runtime. */
class AndroidOnnxCaptchaRecognizer(
    context: Context,
    private val minimumConfidence: Float = DEFAULT_AUTO_CAPTCHA_CONFIDENCE,
) : CaptchaRecognizer {
    private val applicationContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val model = applicationContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        OrtSession.SessionOptions().use { options ->
            environment.createSession(model, options)
        }
    }

    override suspend fun recognize(imageBytes: ByteArray): CaptchaRecognitionResult =
        withContext(Dispatchers.Default) {
            runCatching {
                val tensorData = preprocess(imageBytes)
                    ?: return@withContext CaptchaRecognitionResult.Failed(
                        CaptchaRecognitionFailure.IMAGE_DECODE_FAILED,
                    )
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(tensorData),
                    longArrayOf(1, 3, CAPTCHA_HEIGHT.toLong(), CAPTCHA_WIDTH.toLong()),
                ).use { input ->
                    synchronized(session) {
                        session.run(mapOf(session.inputNames.single() to input)).use { result ->
                            val output = result[0] as? OnnxTensor
                                ?: return@synchronized CaptchaRecognitionResult.Failed(
                                    CaptchaRecognitionFailure.INVALID_OUTPUT,
                                )
                            if (!output.info.shape.contentEquals(longArrayOf(8, 1, 15))) {
                                return@synchronized CaptchaRecognitionResult.Failed(
                                    CaptchaRecognitionFailure.INVALID_OUTPUT,
                                )
                            }
                            val buffer = output.floatBuffer
                            val logits = FloatArray(buffer.remaining())
                            buffer.get(logits)
                            decodeCaptchaLogits(logits, minimumConfidence)
                        }
                    }
                }
            }.getOrElse {
                CaptchaRecognitionResult.Failed(CaptchaRecognitionFailure.INFERENCE_FAILED)
            }
        }

    private fun preprocess(imageBytes: ByteArray): FloatArray? {
        val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, CAPTCHA_WIDTH, CAPTCHA_HEIGHT, true)
        if (scaled !== decoded) decoded.recycle()
        return try {
            val pixels = IntArray(CAPTCHA_WIDTH * CAPTCHA_HEIGHT)
            scaled.getPixels(pixels, 0, CAPTCHA_WIDTH, 0, 0, CAPTCHA_WIDTH, CAPTCHA_HEIGHT)
            FloatArray(3 * CAPTCHA_WIDTH * CAPTCHA_HEIGHT).also { output ->
                val plane = CAPTCHA_WIDTH * CAPTCHA_HEIGHT
                pixels.forEachIndexed { index, pixel ->
                    output[index] = ((pixel ushr 16) and 0xFF) / 255f
                    output[plane + index] = ((pixel ushr 8) and 0xFF) / 255f
                    output[2 * plane + index] = (pixel and 0xFF) / 255f
                }
            }
        } finally {
            scaled.recycle()
        }
    }
}
