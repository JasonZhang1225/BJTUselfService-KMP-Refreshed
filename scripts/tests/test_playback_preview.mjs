import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// Execute the actual script shipped to WKWebView with controllable media events.
const source = readFileSync(new URL('../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/feature/courseware/PlaybackPlayerPage.kt', import.meta.url), 'utf8');
const template = source.split('private val PREVIEW_TEMPLATE = """')[1].split('""".trimIndent()')[0];
const script = template.match(/<script>([\s\S]*?)<\/script>/)[1].replace('__URL__', '"https://lmt1.bjtu.edu.cn:8880/example.m3u8"');
function preview(modern = true) {
  const events = {}, frames = [], paints = [];
  const video = {
    duration: 7200, currentTime: 0, seeking: false, readyState: 2,
    pauses: 0, plays: 0,
    addEventListener(name, callback) { events[name] = callback; },
    play() { this.plays++; return Promise.resolve(); },
    pause() { this.pauses++; },
  };
  if (modern) video.requestVideoFrameCallback = callback => frames.push(callback);
  runInNewContext(script, {
    document: { querySelector: () => video },
    requestAnimationFrame: callback => paints.push(callback),
  });
  return {
    video, frames, paints,
    emit(name) { events[name]?.(); },
    frame(mediaTime) { assert.ok(frames.length); frames.shift()(0, { mediaTime }); },
  };
}

test('iOS preview starts muted and pauses only after the target frame is presented', () => {
  const p = preview();
  assert.equal(p.video.muted, true);
  assert.equal(p.video.defaultMuted, true);
  assert.equal(p.video.plays, 1);
  p.emit('loadedmetadata');
  assert.equal(p.video.currentTime, 600);
  p.emit('seeked');
  p.emit('playing');
  assert.equal(p.video.pauses, 0, 'playing may fire before WebKit paints a frame');
  p.frame(0);
  assert.equal(p.video.pauses, 0, 'ignore a queued frame from before seeking');
  p.frame(600.04);
  assert.equal(p.video.pauses, 1);
});

test('short recording uses its midpoint', () => {
  const p = preview();
  p.video.duration = 60;
  p.emit('loadedmetadata');
  assert.equal(p.video.currentTime, 30);
  p.emit('seeked');
  p.frame(30);
  assert.equal(p.video.pauses, 1);
});

test('older WebKit waits for time advancement and a paint before pausing', () => {
  const p = preview(false);
  p.emit('loadedmetadata');
  p.emit('seeked');
  p.emit('playing');
  p.emit('timeupdate');
  assert.equal(p.video.pauses, 0);
  assert.equal(p.paints.length, 0);
  p.video.currentTime = 600.1;
  p.emit('timeupdate');
  p.paints.shift()();
  assert.equal(p.video.pauses, 0);
  p.paints.shift()();
  assert.equal(p.video.pauses, 1);
});
