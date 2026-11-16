'use strict';
/** הפעלת ffmpeg: פענוח, השמעה זמנית ושמירה. בלי Electron – כדי שאפשר יהיה לבדוק. */
const { spawn } = require('node:child_process');
const L = require('./logic');

/** פרטי הקובץ + גל קול (PCM מונו 8kHz, בהמשכים – בלי לשמור את כל השיר בזיכרון) */
function analyze(ffmpeg, file) {
  return new Promise((resolve, reject) => {
    const rate = 8000;
    const p = spawn(ffmpeg, ['-hide_banner', '-nostdin', '-i', file, '-map', '0:a:0', '-vn', '-ac', '1', '-ar', String(rate), '-f', 's16le', 'pipe:1'], { windowsHide: true });
    const env = new L.EnvelopeBuilder(rate);
    let err = '';
    p.stdout.on('data', (d) => env.push(d));
    p.stderr.on('data', (d) => { if (err.length < 200_000) err += d; });
    p.on('error', (e) => reject(new Error(e.code === 'ENOENT' ? 'ffmpeg-missing' : 'unreadable')));
    p.on('close', (code) => {
      const info = L.parseInfo(err);
      if (!info.hasAudio) { reject(new Error(/No such file|Permission denied/.test(err) ? 'missing' : 'no-audio')); return; }
      const res = env.finish();
      if (code !== 0 && res.duration === 0) { reject(new Error('unreadable')); return; }
      resolve({ info, ...res });
    });
  });
}

/** עותק להשמעה (כשהממשק לא יודע לנגן את הפורמט המקורי, למשל WMA או וידאו עם AC3) */
function makePreview(ffmpeg, file, out) {
  return new Promise((resolve, reject) => {
    const p = spawn(ffmpeg, ['-hide_banner', '-nostdin', '-y', '-i', file, '-map', '0:a:0', '-vn', '-c:a', 'libmp3lame', '-b:a', '160k', '-ar', '44100', out], { windowsHide: true });
    p.on('error', reject);
    p.on('close', (code) => (code === 0 ? resolve(out) : reject(new Error('preview-failed'))));
  });
}

/** שמירת הקטע. מחזיר { done: Promise<boolean>, cancel() } */
function runExport(ffmpeg, args, length, onProgress) {
  const p = spawn(ffmpeg, args, { windowsHide: true });
  let canceled = false;
  const done = new Promise((resolve) => {
    p.stdout.on('data', (d) => {
      const v = L.parseProgress(d, length);
      if (v !== null && onProgress) onProgress(v);
    });
    p.stderr.on('data', () => { /* לא נשמר */ });
    p.on('error', () => resolve({ ok: false, canceled }));
    p.on('close', (code) => resolve({ ok: code === 0 && !canceled, canceled }));
  });
  return { done, cancel: () => { canceled = true; p.kill(); } };
}

module.exports = { analyze, makePreview, runExport };
