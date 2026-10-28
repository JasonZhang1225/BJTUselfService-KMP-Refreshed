package team.bjtuss.bjtuselfservice.shared.feature.courseware

import team.bjtuss.bjtuselfservice.shared.data.homework.isSchoolPlaybackStreamUrl
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream

/**
 * 回放播放页（纯本地 HTML，不加载任何外部脚本）。
 *
 * 学校网页播放器把“教师画面”和“屏幕/课件”两路 HLS 并排同步播放；系统播放器一次只能放一路，
 * 只放屏幕那一路就会丢掉左边的教室画面。这里用两个原生 `<video>` 复刻：第一路为主时钟，
 * 第二路跟随（偏差超过 0.3 秒就对齐）；“声音”切换哪一路出声；“画面”切换并排/只看某一路。
 * iOS WKWebView、Safari 与 Chrome/Edge 使用此页；Android 使用独立的系统 MediaPlayer。
 */
fun buildPlaybackPlayerHtml(detail: PlaybackDetail, streams: List<PlaybackStream> = detail.defaultStreams): String {
    val safeStreams = streams.filter { isSchoolPlaybackStreamUrl(it.hlsUrl) }.take(2)
    val title = detail.lesson.title.ifBlank { "课程回放" }
    val sources = safeStreams.joinToString(",", prefix = "[", postfix = "]") { stream ->
        "{\"label\":${jsString(stream.kind.label)},\"url\":${jsString(stream.hlsUrl)}}"
    }
    return PLAYER_TEMPLATE
        .replace("__TITLE_HTML__", htmlEscape(title))
        .replace("__SUBTITLE_HTML__", htmlEscape(listOf(detail.lesson.timeRange, detail.lesson.classroom).filter(String::isNotBlank).joinToString(" · ")))
        .replace("__SOURCES__", sources)
}

private fun htmlEscape(value: String): String = buildString {
    value.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/** 预览页：静音、无控件，跳到第 10 分钟（短视频取中点）停住。 */
internal fun buildPlaybackPreviewHtml(url: String): String = PREVIEW_TEMPLATE.replace("__URL__", jsString(url))

private val PREVIEW_TEMPLATE = """
<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<meta name="referrer" content="no-referrer">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}video{width:100%;height:100%;object-fit:cover;pointer-events:none}</style>
</head><body>
<video muted playsinline webkit-playsinline preload="auto"></video>
<script>
(function () {
  var v = document.querySelector('video'); var done = false;
  v.src = __URL__;
  v.addEventListener('loadedmetadata', function () {
    var d = v.duration; v.currentTime = isFinite(d) && d > 0 ? Math.min(600, d / 2) : 600;
  });
  // iOS 不播放就不渲染帧：静音播一下，出第一帧后立刻暂停。
  v.addEventListener('seeked', function () { if (!done) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } });
  v.addEventListener('playing', function () { done = true; v.pause(); });
})();
</script></body></html>
""".trimIndent()

/** JSON 字符串字面量；额外转义 `<`，避免 `</script>` 提前结束脚本。 */
private fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '<' -> append("\\u003c")
            c == ' ' -> append("\\u2028")
            c == ' ' -> append("\\u2029")
            c.code < 0x20 -> append("\\u" + c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}

private val PLAYER_TEMPLATE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<meta name="referrer" content="no-referrer">
<title>__TITLE_HTML__</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { margin: 0; height: 100%; background: #000; color: #f2f2f7;
    font: 15px -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif; }
  #player { display: flex; flex-direction: column; width: 100%; height: 100%; background: #000;
    padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left); }
  #player.fullscreen header { display: none; }
  #player.fullscreen { height: 100%; height: 100dvh; }
  header { padding: 10px 14px 6px; }
  header h1 { margin: 0; font-size: 16px; font-weight: 600; }
  header p { margin: 2px 0 0; font-size: 13px; color: #a1a1a6; }
  #stage { flex: 1; min-height: 0; display: flex; gap: 2px; align-items: center; justify-content: center; }
  #stage.stack { flex-direction: column; }
  .pane { position: relative; flex: 1 1 0; min-width: 0; min-height: 0; height: 100%; display: flex; align-items: center; justify-content: center; }
  .pane.hidden { display: none; }
  video { width: 100%; height: 100%; object-fit: contain; background: #000; }
  .tag { position: absolute; left: 8px; top: 8px; padding: 2px 8px; border-radius: 10px; font-size: 12px;
    background: rgba(0,0,0,.55); color: #fff; pointer-events: none; }
  .tag.audio::after { content: " · 声音"; }
  #controls { padding: 8px 12px 12px; display: flex; flex-direction: column; gap: 8px; background: #111; }
  .row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
  input[type=range] { flex: 1; min-width: 120px; accent-color: #0a84ff; height: 28px; }
  button, select { min-height: 36px; padding: 0 14px; border-radius: 18px; border: 0; background: #2c2c2e; color: #f2f2f7; font: inherit; }
  button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid #0a84ff; outline-offset: 2px; }
  #play { min-width: 64px; background: #0a84ff; color: #fff; font-weight: 600; }
  #time { font-variant-numeric: tabular-nums; color: #d1d1d6; font-size: 13px; }
  #status { color: #ff9f0a; font-size: 13px; }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<main id="player">
<header><h1>__TITLE_HTML__</h1><p>__SUBTITLE_HTML__</p></header>
<div id="stage"></div>
<div id="controls">
  <div class="row">
    <button id="play" aria-label="播放">播放</button>
    <button id="fullscreen" aria-label="全屏" aria-pressed="false">全屏</button>
    <input id="seek" type="range" min="0" max="1000" value="0" aria-label="播放进度">
    <span id="time">00:00:00 / --:--:--</span>
  </div>
  <div class="row">
    <label>倍速 <select id="rate" aria-label="倍速">
      <option value="0.75">0.75×</option><option value="1" selected>1×</option><option value="1.25">1.25×</option>
      <option value="1.5">1.5×</option><option value="2">2×</option></select></label>
    <label id="audioWrap">声音 <select id="audio" aria-label="声音来源"></select></label>
    <label id="layoutWrap">画面 <select id="layout" aria-label="画面布局"></select></label>
    <button id="back" aria-label="后退 15 秒">−15 秒</button>
    <button id="fwd" aria-label="前进 15 秒">+15 秒</button>
    <span id="status" role="status"></span>
  </div>
</div>
</main>
<script>
(function () {
  var sources = __SOURCES__;
  var stage = document.getElementById('stage');
  var status = document.getElementById('status');
  var player = document.getElementById('player'), fullscreenBtn = document.getElementById('fullscreen');
  function fullscreenElement() { return document.fullscreenElement || document.webkitFullscreenElement; }
  function syncFullscreen() {
    var active = !!fullscreenElement();
    player.classList.toggle('fullscreen', active);
    fullscreenBtn.textContent = active ? '退出全屏' : '全屏';
    fullscreenBtn.setAttribute('aria-label', fullscreenBtn.textContent);
    fullscreenBtn.setAttribute('aria-pressed', String(active));
    applyLayout();
  }
  fullscreenBtn.onclick = function () {
    try {
      var result;
      if (fullscreenElement()) {
        var exit = document.exitFullscreen || document.webkitExitFullscreen;
        if (exit) result = exit.call(document);
      } else {
        // 请求整个双路容器；对单个 video 调用会丢掉另一半画面。
        var enter = player.requestFullscreen || player.webkitRequestFullscreen;
        if (!enter) { status.textContent = '此浏览器暂不支持双路全屏，请使用 Safari、Chrome 或 Edge'; return; }
        result = enter.call(player);
      }
      if (result && result.catch) result.catch(function () { status.textContent = '无法进入全屏，请再点一次全屏按钮'; });
    } catch (_) { status.textContent = '无法进入全屏，请再点一次全屏按钮'; }
  };
  document.addEventListener('fullscreenchange', syncFullscreen);
  document.addEventListener('webkitfullscreenchange', syncFullscreen);
  if (!sources.length) { status.textContent = '没有可播放的画面'; return; }
  var videos = sources.map(function (s, i) {
    var pane = document.createElement('div'); pane.className = 'pane';
    var v = document.createElement('video');
    v.setAttribute('playsinline', ''); v.setAttribute('webkit-playsinline', ''); v.preload = 'metadata';
    v.src = s.url; v.muted = i !== 0;
    var tag = document.createElement('span'); tag.className = 'tag' + (i === 0 ? ' audio' : ''); tag.textContent = s.label;
    pane.appendChild(v); pane.appendChild(tag); stage.appendChild(pane);
    v.addEventListener('error', function () { status.textContent = s.label + '加载失败，请检查网络'; });
    v.addEventListener('waiting', function () { status.textContent = '缓冲中…'; });
    v.addEventListener('playing', function () { status.textContent = ''; });
    return { video: v, pane: pane, tag: tag };
  });
  var audioIndex = 0;
  var master = function () { return videos[0].video; };
  var playBtn = document.getElementById('play'), seek = document.getElementById('seek'), time = document.getElementById('time');
  var rate = document.getElementById('rate'), audio = document.getElementById('audio'), layout = document.getElementById('layout');
  function fmt(t) { if (!isFinite(t)) return '--:--:--'; t = Math.max(0, Math.floor(t));
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), s = t % 60;
    return [h, m, s].map(function (x) { return (x < 10 ? '0' : '') + x; }).join(':'); }
  function each(fn) { videos.forEach(function (x) { fn(x.video); }); }
  function setPlaying(on) {
    if (on) { each(function (v) { var p = v.play(); if (p && p.catch) p.catch(function () { status.textContent = '点“播放”开始'; }); }); }
    else { each(function (v) { v.pause(); }); }
  }
  playBtn.onclick = function () { setPlaying(master().paused); };
  function syncButton() { var paused = master().paused; playBtn.textContent = paused ? '播放' : '暂停'; playBtn.setAttribute('aria-label', paused ? '播放' : '暂停'); }
  master().addEventListener('play', syncButton); master().addEventListener('pause', syncButton);
  master().addEventListener('ended', function () { setPlaying(false); });
  var dragging = false;
  seek.addEventListener('input', function () { dragging = true; var d = master().duration;
    if (isFinite(d)) time.textContent = fmt(d * seek.value / 1000) + ' / ' + fmt(d); });
  seek.addEventListener('change', function () { dragging = false; var d = master().duration;
    if (isFinite(d)) { var t = d * seek.value / 1000; each(function (v) { v.currentTime = t; }); } });
  function jump(delta) { var t = Math.max(0, master().currentTime + delta); each(function (v) { v.currentTime = t; }); }
  document.getElementById('back').onclick = function () { jump(-15); };
  document.getElementById('fwd').onclick = function () { jump(15); };
  rate.onchange = function () { each(function (v) { v.playbackRate = parseFloat(rate.value); }); };
  sources.forEach(function (s, i) { var o = document.createElement('option'); o.value = i; o.textContent = s.label; audio.appendChild(o); });
  audio.onchange = function () { audioIndex = parseInt(audio.value, 10);
    videos.forEach(function (x, i) { x.video.muted = i !== audioIndex; x.tag.className = 'tag' + (i === audioIndex ? ' audio' : ''); }); };
  var layouts = [{ v: 'all', t: sources.length > 1 ? '两路并排' : sources[0].label }];
  if (sources.length > 1) sources.forEach(function (s, i) { layouts.push({ v: String(i), t: '只看' + s.label }); });
  layouts.forEach(function (l) { var o = document.createElement('option'); o.value = l.v; o.textContent = l.t; layout.appendChild(o); });
  if (sources.length < 2) { document.getElementById('audioWrap').style.display = 'none'; document.getElementById('layoutWrap').style.display = 'none'; }
  function applyLayout() {
    var v = layout.value;
    videos.forEach(function (x, i) { x.pane.className = 'pane' + (v === 'all' || v === String(i) ? '' : ' hidden'); });
    // 竖屏手机放不下两路 16:9 并排时上下排。
    stage.className = (v === 'all' && window.innerHeight > window.innerWidth * 1.1) ? 'stack' : '';
  }
  layout.onchange = applyLayout; window.addEventListener('resize', applyLayout); applyLayout();
  setInterval(function () {
    var m = master(), d = m.duration;
    if (!dragging) { time.textContent = fmt(m.currentTime) + ' / ' + fmt(d); if (isFinite(d) && d > 0) seek.value = Math.round(m.currentTime / d * 1000); }
    for (var i = 1; i < videos.length; i++) {
      var f = videos[i].video;
      if (Math.abs(f.currentTime - m.currentTime) > 0.3 && f.readyState > 0) f.currentTime = m.currentTime;
      if (m.paused !== f.paused) { if (m.paused) f.pause(); else { var p = f.play(); if (p && p.catch) p.catch(function () {}); } }
    }
  }, 500);
  document.addEventListener('keydown', function (e) {
    if (e.target && (e.target.tagName === 'SELECT' || e.target.tagName === 'INPUT')) return;
    if (e.key === ' ') { e.preventDefault(); setPlaying(master().paused); }
    else if (e.key === 'ArrowLeft') jump(-15); else if (e.key === 'ArrowRight') jump(15);
    else if (e.key.toLowerCase() === 'f') fullscreenBtn.click();
  });
})();
</script>
</body>
</html>
""".trimIndent()
