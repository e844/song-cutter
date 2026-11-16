'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const L = require('../src/logic');

test('time format and parse', () => {
  assert.equal(L.formatTime(0), '0:00.0');
  assert.equal(L.formatTime(75.34), '1:15.3');
  assert.equal(L.formatTime(3725), '1:02:05.0');
  assert.equal(L.formatTime(59.96), '1:00.0');
  assert.equal(L.parseTime('1:15.3'), 75.3);
  assert.equal(L.parseTime('75,5'), 75.5);
  assert.equal(L.parseTime('1:02:05'), 3725);
  assert.equal(L.parseTime('abc'), null);
  assert.equal(L.parseTime(''), null);
  assert.equal(L.parseTime('1:2:3:4'), null);
});

test('selection stays valid', () => {
  assert.deepEqual(L.normalizeSelection(50, 10, 30), { start: 10, end: 30 });
  assert.deepEqual(L.normalizeSelection(-5, 5, 30), { start: 0, end: 5 });
  assert.deepEqual(L.normalizeSelection(30, 30, 30), { start: 29.9, end: 30 });
  const s = { start: 10, end: 20 };
  assert.deepEqual(L.moveMarker(s, 'start', 25, 60), { start: 19.9, end: 20 }); // לא עובר את הסוף
  assert.deepEqual(L.moveMarker(s, 'end', 5, 60), { start: 10, end: 10.1 });
  assert.deepEqual(L.moveMarker(s, 'end', 99, 60), { start: 10, end: 60 });
});

test('suggested file name is safe for Windows', () => {
  assert.equal(L.suggestName('שיר: "טוב".flac', { start: 30, end: 60 }, 'mp3'), 'שיר טוב (קטע 0.30.0-1.00.0).mp3');
  assert.equal(L.suggestName('song.mp3', { start: 0, end: 25 }, 'ringtone-iphone'), 'song (רינגטון 0.00.0-0.25.0).m4r');
});

test('export args: MP3 320k CBR, fades, iPhone ringtone max 40s', () => {
  const a = L.exportArgs({ input: 'in.wav', output: 'out.mp3', start: 10, end: 40, fadeIn: 2, fadeOut: 3 });
  assert.equal(a.length, 30);
  const s = a.args.join(' ');
  assert.match(s, /-ss 10 -i in\.wav -t 30/);
  assert.match(s, /-c:a libmp3lame -b:a 320k/);
  assert.match(s, /afade=t=in:st=0:d=2,afade=t=out:st=27:d=3/);
  assert.equal(a.args[a.args.length - 1], 'out.mp3');
  const r = L.exportArgs({ input: 'in', output: 'o.m4r', start: 0, end: 90, format: 'ringtone-iphone' });
  assert.equal(r.length, 40);
  assert.match(r.args.join(' '), /-c:a aac .* -f ipod/);
  assert.equal(L.fadeFilter(4, 5, 5), 'afade=t=in:st=0:d=2,afade=t=out:st=2:d=2'); // עמעום לא ארוך מחצי קטע
});

test('progress and info parsing', () => {
  assert.equal(L.parseProgress('out_time_us=5000000\nprogress=continue', 10), 0.5);
  assert.equal(L.parseProgress('nothing', 10), null);
  const info = L.parseInfo(`Input #0, mp3, from 'a.mp3':\n  Duration: 00:03:25.12, start: 0.0, bitrate: 192 kb/s\n  Stream #0:0: Audio: mp3 (mp3float), 44100 Hz, stereo, fltp, 192 kb/s`);
  assert.equal(info.duration, 205.12);
  assert.equal(info.codec, 'mp3');
  assert.equal(info.sampleRate, 44100);
  assert.equal(info.bitrate, 192);
  assert.equal(L.parseInfo('Stream #0:0: Video: h264').hasAudio, false);
});

test('envelope builder: 10ms blocks, odd buffer split', () => {
  const e = new L.EnvelopeBuilder(8000);
  const b = Buffer.alloc(8000 * 2); // שנייה
  for (let i = 0; i < 8000; i++) b.writeInt16LE(i < 4000 ? 16384 : 0, i * 2);
  e.push(b.subarray(0, 1001));
  e.push(b.subarray(1001));
  const r = e.finish();
  assert.equal(r.duration, 1);
  assert.equal(r.envelope.length, 100);
  assert.equal(r.envelope[0], 0.5);
  assert.equal(r.envelope[99], 0);
});

test('history: newest first, capped, sanitized', () => {
  let h = [];
  for (let i = 0; i < 205; i++) h = L.addHistory(h, { id: String(i), output: `o${i}.mp3`, start: i, end: i + 1, format: 'weird', extra: 'x' });
  assert.equal(h.length, 200);
  assert.equal(h[0].id, '204');
  assert.equal(h[0].format, 'mp3');
  assert.equal('extra' in h[0], false);
});
