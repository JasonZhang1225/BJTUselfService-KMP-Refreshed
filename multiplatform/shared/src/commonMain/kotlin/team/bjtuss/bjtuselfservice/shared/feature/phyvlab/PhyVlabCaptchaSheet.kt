package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCaptchaProof
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabManualLogin
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSessionResult
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet

internal const val PHYVLAB_CAPTCHA_REQUIRED_MESSAGE = "请完成物理在线的人机验证。"
internal const val PHYVLAB_ACCOUNT_REJECTED_MESSAGE = "物理在线账号或密码错误，请在设置中检查。"
internal const val PHYVLAB_LOGIN_NETWORK_MESSAGE = "无法登录物理在线，请检查校园网后重试。"
internal const val PHYVLAB_LOGIN_TIMEOUT_MESSAGE = "物理在线登录超时，请重试。"

/** 提交验证结果后等待学校响应的上限；超过就结束本次尝试，把控制权还给用户。 */
private const val PHYVLAB_SUBMIT_TIMEOUT_MS = 60_000L

@Composable
internal fun PhyVlabCaptchaSheet(
    account: Credentials,
    prepare: suspend () -> PhyVlabManualLogin,
    onSuccess: suspend () -> Boolean,
    onRejected: () -> Unit,
    onDismiss: () -> Unit,
) {
    var attemptNumber by remember { mutableIntStateOf(0) }
    var captchaId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var credentialsRejected by remember { mutableStateOf(false) }
    val proof = remember(attemptNumber) { CompletableDeferred<PhyVlabCaptchaProof?>() }
    LaunchedEffect(attemptNumber) {
        var attempt: PhyVlabManualLogin? = null
        try {
            attempt = prepare()
            captchaId = attempt.captchaId
            loading = false
            val answer = proof.await()
            if (answer == null) {
                captchaId = null
                error = "无法读取验证码结果，请重试。"
                return@LaunchedEffect
            }
            submitting = true
            captchaId = null
            // 学校没有响应时不能一直停在“正在登录”，超时后把重试入口还给用户。
            val outcome = withTimeoutOrNull(PHYVLAB_SUBMIT_TIMEOUT_MS) { attempt.complete(account, answer) }
            error = if (outcome == null) PHYVLAB_LOGIN_TIMEOUT_MESSAGE else when (outcome) {
                is PhyVlabSessionResult.Ready ->
                    if (onSuccess()) null else "登录成功，但保存账号失败。请关闭后查看设置页的提示。"
                PhyVlabSessionResult.CaptchaRequired,
                PhyVlabSessionResult.CaptchaRejected,
                -> "验证码未通过，请重新验证。"
                PhyVlabSessionResult.AccountRequired,
                PhyVlabSessionResult.CredentialsRejected,
                -> {
                    credentialsRejected = true
                    onRejected()
                    PHYVLAB_ACCOUNT_REJECTED_MESSAGE
                }
                is PhyVlabSessionResult.Failed -> PHYVLAB_LOGIN_NETWORK_MESSAGE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = PHYVLAB_LOGIN_NETWORK_MESSAGE
        } finally {
            attempt?.close?.invoke()
            loading = false
            submitting = false
        }
    }
    AppleSheet(
        title = "物理在线验证码",
        fitContentHeight = true,
        onDismissRequest = onDismiss,
        dismissible = !submitting,
        dismissEnabled = !submitting,
        confirmLabel = if (error != null && !credentialsRejected) "重试" else null,
        onConfirm = {
            error = null
            loading = true
            attemptNumber++
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val message = error
            if (loading || submitting) {
                CircularProgressIndicator()
                Text(if (submitting) "正在登录物理在线…" else "正在加载验证码…")
            } else if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            captchaId?.let { id ->
                key(attemptNumber) {
                    PhyVlabCaptchaWeb(
                        id,
                        onProof = { proof.complete(it) },
                        onUnavailable = { proof.complete(null) },
                    )
                }
            }
        }
    }
}

@Composable
internal expect fun PhyVlabCaptchaWeb(
    captchaId: String,
    onProof: (PhyVlabCaptchaProof) -> Unit,
    onUnavailable: () -> Unit,
)

// Synthetic origin keeps this document separate from every website cookie jar.
internal const val PHYVLAB_CAPTCHA_BASE_URL = "https://app-captcha.invalid/"

internal const val PHYVLAB_CAPTCHA_RESULT_PROMISE = "phyVlabCaptchaResult"

/** 验证结果交给宿主的通道。每个平台只有一种，避免页面里出现用不上的分支。 */
internal enum class PhyVlabCaptchaDelivery {
    /** Android：写入页面变量，由宿主读取 WebView 的求值结果。 */
    WindowVariable,

    /** iOS：WKWebView 在主文档中等待验证结果。 */
    WebViewPromise,

    /** 桌面：POST 回本机一次性回环地址。 */
    LoopbackFetch,
}

/** Official challenge widget. This document receives neither account data nor Moodle cookies. */
internal fun phyVlabCaptchaHtml(
    captchaId: String,
    delivery: PhyVlabCaptchaDelivery = PhyVlabCaptchaDelivery.WindowVariable,
): String {
    require(captchaId.matches(Regex("[a-fA-F0-9]{32}")))
    val send = when (delivery) {
        PhyVlabCaptchaDelivery.WindowVariable -> "window.phyVlabCaptchaProof = result;"
        PhyVlabCaptchaDelivery.WebViewPromise -> "resolveProof(result);"
        PhyVlabCaptchaDelivery.LoopbackFetch -> """
            fetch(location.pathname, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: result})
              .then(response => {
                if (!response.ok) throw new Error();
                document.getElementById('message').textContent = '已完成验证，可以返回交大自由行。';
              }).catch(() => {
                document.getElementById('message').textContent = '请返回交大自由行重新打开验证码。';
              });
        """
    }
    val setup = if (delivery == PhyVlabCaptchaDelivery.WebViewPromise) """
        let resolveProof;
        window.$PHYVLAB_CAPTCHA_RESULT_PROMISE = new Promise(resolve => { resolveProof = resolve; });
    """.trimIndent() else ""
    val noResponse = if (delivery == PhyVlabCaptchaDelivery.WebViewPromise) """
        message.textContent = '已完成验证，但应用没有收到结果。请关闭后重新打开验证码。';
    """.trimIndent() else """
        message.textContent = '已完成验证，但应用没有收到结果。请点「重新加载」再试一次。';
        verify.disabled = false;
        reload.disabled = false;
    """.trimIndent()
    return """
        <!doctype html>
        <html lang="zh-CN">
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; form-action 'none';
          script-src 'unsafe-inline' https://phyvlab.bjtu.edu.cn/login/ct4.js https://*.alicaptcha.com;
          connect-src 'self' https://*.alicaptcha.com https://*.geetest.com;
          img-src https://*.alicaptcha.com https://*.geetest.com data: blob:;
          style-src 'unsafe-inline' https://*.alicaptcha.com; font-src https://*.alicaptcha.com data:">
        <title>物理在线验证码</title>
        <style>
          :root { color-scheme: light dark; }
          body { font: 16px system-ui, sans-serif; margin: 16px; }
          button { font: inherit; padding: 10px 16px; }
        </style>
        <body>
        <p id="message" role="status">正在加载学校验证码…</p>
        <button id="verify" disabled>开始验证</button>
        <button id="reload">重新加载</button>
        <script>
        const message = document.getElementById('message');
        const verify = document.getElementById('verify');
        const reload = document.getElementById('reload');
        let widget;
        let timeout;
        $setup
        function failed() {
          clearTimeout(timeout);
          message.textContent = '学校验证码加载失败，请重新加载。';
          reload.disabled = false;
        }
        // 结果已经交给应用却一直没有反应时，把重试入口放回来，避免停在“正在提交验证结果…”。
        function armNoResponseHint() {
          setTimeout(() => {
            if (message.textContent !== '正在提交验证结果…') return;
            $noResponse
          }, 15000);
        }
        function start() {
          message.textContent = '正在加载学校验证码…';
          verify.disabled = true;
          reload.disabled = true;
          timeout = setTimeout(failed, 12000);
          const initialize = () => {
            if (typeof initAlicom4 !== 'function') {
              failed();
              return;
            }
            initAlicom4({captchaId: '$captchaId', product: 'bind', https: true}, captcha => {
              widget = captcha;
              const ready = () => {
                clearTimeout(timeout);
                message.textContent = '按学校提示手动完成点选。';
                verify.disabled = false;
                reload.disabled = false;
              };
              const readyHook = captcha.onReady || captcha.onNextReady;
              if (typeof readyHook === 'function') readyHook.call(captcha, ready);
              else ready();
              captcha.onError(() => {
                message.textContent = '学校验证码出现异常，请重新加载。';
              });
              captcha.onSuccess(() => {
                const value = captcha.getValidate();
                if (!value) {
                  message.textContent = '未收到验证结果，请重试。';
                  captcha.reset();
                  return;
                }
                const result = JSON.stringify({
                  lot_number: String(value.lot_number),
                  captcha_output: String(value.captcha_output),
                  pass_token: String(value.pass_token),
                  gen_time: String(value.gen_time)
                });
                verify.disabled = true;
                reload.disabled = true;
                message.textContent = '正在提交验证结果…';
                $send
                armNoResponseHint();
              });
            });
          };
          if (typeof initAlicom4 === 'function') initialize();
          else {
            const script = document.createElement('script');
            script.src = 'https://phyvlab.bjtu.edu.cn/login/ct4.js';
            script.onload = initialize;
            script.onerror = failed;
            document.head.append(script);
          }
        }
        verify.onclick = () => widget?.showCaptcha();
        reload.onclick = () => {
          if (!widget) start();
          else {
            widget.reset();
            message.textContent = '按学校提示手动完成点选。';
          }
        };
        start();
        </script>
        </body></html>
    """.trimIndent()
}
