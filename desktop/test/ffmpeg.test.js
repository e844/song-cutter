'use strict';
// חיתוך אמיתי עם ffmpeg: הקובץ שנשמר הוא MP3 ב-320kbps ובאורך הנכון
const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const L = require('../src/logic');
const F = require('../src/ffmpeg');

const FF = process.env.SC_FFMPEG || 'ffmpeg';
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sc-'));
const src = path.join(dir, 'שיר בדיקה.wav');
spawnSync(FF, ['-hide_banner', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=12:sample_rate=48000', '-ac', '2', src]);
const probe = (f) => L.parseInfo(spawnSync(FF, ['-hide_banner', '-i', f], { encoding: 'utf8' }).stderr);

test('analyze: duration and waveform', async () => {
  const r = await F.analyze(FF, src);
  assert.ok(Math.abs(r.duration - 12) < 0.05, r.duration);
  assert.ok(Math.abs(r.envelope.length - 1200) <= 2);
  assert.ok(r.envelope[500] > 0.05); // יש שמע (sine של ffmpeg בעוצמה 1/8)
  assert.equal(r.info.sampleRate, 48000);
});

test('analyze: missing / not audio', async () => {
  await assert.rejects(F.analyze(FF, path.join(dir, 'nope.mp3')));
  const txt = path.join(dir, 'x.txt');
  fs.writeFileSync(txt, 'hello');
  await assert.rejects(F.analyze(FF, txt));
});

test('export MP3 320kbps, correct length, fades, progress', async () => {
  const out = path.join(dir, 'קטע.mp3');
  const { args, length } = L.exportArgs({ input: src, output: out, start: 2, end: 7.5, fadeIn: 1, fadeOut: 1, title: 'קטע' });
  const seen = [];
  const r = await F.runExport(FF, args, length, (v) => seen.push(v)).done;
  assert.equal(r.ok, true);
  const info = probe(out);
  assert.equal(info.codec, 'mp3');
  assert.equal(info.bitrate, 320);
  assert.equal(info.sampleRate, 44100);
  assert.ok(Math.abs(info.duration - 5.5) < 0.1, info.duration);
  assert.ok(seen.length > 0 && seen[seen.length - 1] >= 0.9);
  // העמעום עובד: ההתחלה שקטה יותר מהאמצע
  const e = (await F.analyze(FF, out)).envelope;
  assert.ok(e[2] < e[250] * 0.3, `${e[2]} vs ${e[250]}`);
});

test('iPhone ringtone: M4R (AAC), max 40 seconds', async () => {
  const long = path.join(dir, 'long.wav');
  spawnSync(FF, ['-hide_banner', '-y', '-f', 'lavfi', '-i', 'sine=frequency=330:duration=50', long]);
  const out = path.join(dir, 'ring.m4r');
  const { args, length } = L.exportArgs({ input: long, output: out, start: 0, end: 50, format: 'ringtone-iphone' });
  assert.equal(length, 40);
  const r = await F.runExport(FF, args, length).done;
  assert.equal(r.ok, true);
  const info = probe(out);
  assert.equal(info.codec, 'aac');
  assert.ok(Math.abs(info.duration - 40) < 0.1, info.duration);
});

test('cancel stops and reports canceled', async () => {
  const out = path.join(dir, 'cancel.mp3');
  const { args, length } = L.exportArgs({ input: src, output: out, start: 0, end: 12 });
  const job = F.runExport(FF, ['-re', ...args], length);
  setTimeout(() => job.cancel(), 300);
  const r = await job.done;
  assert.equal(r.ok, false);
  assert.equal(r.canceled, true);
});
