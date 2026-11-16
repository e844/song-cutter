'use strict';
/** לוגיקה טהורה של חותך השירים – בלי Electron, כדי שאפשר יהיה לבדוק אותה. */

const BITRATE = '320k';
const RINGTONE_IPHONE_MAX = 40; // אייפון לא מקבל רינגטון ארוך מ-40 שניות
const ENVELOPE_RATE = 100; // נקודות לשנייה בגל הקול (כל 10ms)

const FORMATS = {
  mp3: { ext: 'mp3', label: 'MP3 ‏320kbps' },
  'ringtone-android': { ext: 'mp3', label: 'רינגטון לאנדרואיד (MP3 ‏320kbps)' },
  'ringtone-iphone': { ext: 'm4r', label: 'רינגטון לאייפון (M4R)' },
};

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const round3 = (v) => Math.round(v * 1000) / 1000;

/** 75.3 → "1:15.3" ; 3725 → "1:02:05.0" */
function formatTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) sec = 0;
  const tenths = Math.round(sec * 10);
  const t = tenths % 10;
  const total = Math.floor(tenths / 10);
  const s = total % 60;
  const m = Math.floor(total / 60) % 60;
  const h = Math.floor(total / 3600);
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}.${t}` : `${m}:${ss}.${t}`;
}

/** "1:15.3" / "75.3" / "1:02:05" / "1,5" → שניות, או null אם לא תקין */
function parseTime(text) {
  const s = String(text ?? '').trim().replace(',', '.');
  if (!/^\d+(:\d{1,2}){0,2}(\.\d+)?$/.test(s)) return null;
  const parts = s.split(':');
  let sec = 0;
  for (const p of parts) sec = sec * 60 + Number(p);
  return Number.isFinite(sec) ? sec : null;
}

/** מתקן בחירה: בתוך גבולות השיר, התחלה לפני סוף, ומרווח מינימלי. */
function normalizeSelection(start, end, duration, minLen = 0.1) {
  const d = Math.max(0, duration);
  let a = clamp(Number(start) || 0, 0, d);
  let b = clamp(Number(end) || 0, 0, d);
  if (b < a) [a, b] = [b, a];
  if (b - a < minLen) {
    b = Math.min(d, a + minLen);
    a = Math.max(0, b - minLen);
  }
  return { start: round3(a), end: round3(b) };
}

/** הזזת סמן אחד בלי לעבור את השני. which = 'start' | 'end' */
function moveMarker(sel, which, value, duration, minLen = 0.1) {
  const v = clamp(Number(value) || 0, 0, duration);
  if (which === 'start') return { start: round3(Math.min(v, sel.end - minLen)), end: sel.end };
  return { start: sel.start, end: round3(Math.max(v, sel.start + minLen)) };
}

/** שם קובץ מוצע: "שיר (קטע 0:30.0-1:00.0).mp3" – בלי תווים אסורים ב-Windows */
function suggestName(sourceName, sel, format) {
  const base = String(sourceName || 'שיר').replace(/\.[^.]+$/, '').replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim() || 'שיר';
  const ext = (FORMATS[format] || FORMATS.mp3).ext;
  const tag = format && format.startsWith('ringtone') ? 'רינגטון' : 'קטע';
  const range = `${formatTime(sel.start)}-${formatTime(sel.end)}`.replace(/:/g, '.');
  return `${base} (${tag} ${range}).${ext}`;
}

/** פילטר עמעום: fade in בהתחלה, fade out בסוף הקטע */
function fadeFilter(length, fadeIn, fadeOut) {
  const f = [];
  const fi = Math.min(Math.max(0, fadeIn || 0), length / 2);
  const fo = Math.min(Math.max(0, fadeOut || 0), length / 2);
  if (fi > 0) f.push(`afade=t=in:st=0:d=${round3(fi)}`);
  if (fo > 0) f.push(`afade=t=out:st=${round3(length - fo)}:d=${round3(fo)}`);
  return f.join(',');
}

/** הארגומנטים ל-ffmpeg לשמירת הקטע. */
function exportArgs({ input, output, start, end, fadeIn = 0, fadeOut = 0, format = 'mp3', title }) {
  let length = end - start;
  if (format === 'ringtone-iphone') length = Math.min(length, RINGTONE_IPHONE_MAX);
  const args = ['-hide_banner', '-nostdin', '-y', '-ss', String(round3(start)), '-i', input, '-t', String(round3(length)),
    '-map', '0:a:0', '-vn', '-sn', '-dn', '-map_metadata', '0'];
  const af = fadeFilter(length, fadeIn, fadeOut);
  if (af) args.push('-af', af);
  if (format === 'ringtone-iphone') {
    args.push('-c:a', 'aac', '-b:a', '256k', '-ar', '44100', '-f', 'ipod');
  } else {
    // MP3 בקצב קבוע של 320kbps (CBR) – האיכות הגבוהה ביותר ש-MP3 מאפשר
    args.push('-c:a', 'libmp3lame', '-b:a', BITRATE, '-ar', '44100', '-id3v2_version', '3');
  }
  if (title) args.push('-metadata', `title=${title}`);
  args.push('-progress', 'pipe:1', '-nostats', output);
  return { args, length: round3(length) };
}

/** מפענח שורות "-progress" של ffmpeg → אחוז (0..1) */
function parseProgress(chunk, length) {
  const m = /out_time_(?:us|ms)=(\d+)/g;
  let last = null;
  let r;
  while ((r = m.exec(String(chunk)))) last = Number(r[1]) / 1e6;
  if (last === null || !(length > 0)) return null;
  return clamp(last / length, 0, 1);
}

/** פרטי הקובץ מתוך הפלט של ffmpeg -i */
function parseInfo(stderr) {
  const text = String(stderr);
  const d = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(text);
  const a = /Stream #\S+?: Audio: ([^,\n]+)(?:[^,\n]*)?, (\d+) Hz, ([^,\n]+)(?:, [^,\n]+)?(?:, (\d+) kb\/s)?/.exec(text);
  const br = /bitrate:\s*(\d+) kb\/s/.exec(text);
  return {
    duration: d ? Number(d[1]) * 3600 + Number(d[2]) * 60 + Number(d[3]) : null,
    hasAudio: !!a,
    codec: a ? a[1].trim().split(' ')[0] : null,
    sampleRate: a ? Number(a[2]) : null,
    channels: a ? a[3].trim() : null,
    bitrate: a && a[4] ? Number(a[4]) : br ? Number(br[1]) : null,
  };
}

/**
 * גל הקול: לכל 10ms – העוצמה המקסימלית (0..1), מתוך PCM 16bit מונו.
 * מקבל buffers בהמשכים, כדי לא לשמור את כל השיר בזיכרון.
 */
class EnvelopeBuilder {
  constructor(sampleRate) {
    this.block = Math.max(1, Math.round(sampleRate / ENVELOPE_RATE));
    this.sampleRate = sampleRate;
    this.out = [];
    this.cur = 0;
    this.n = 0;
    this.samples = 0;
    this.carry = null;
  }

  push(buf) {
    let b = buf;
    if (this.carry) { b = Buffer.concat([this.carry, buf]); this.carry = null; }
    const usable = b.length - (b.length % 2);
    if (usable < b.length) this.carry = b.subarray(usable);
    for (let i = 0; i < usable; i += 2) {
      const v = Math.abs(b.readInt16LE(i)) / 32768;
      if (v > this.cur) this.cur = v;
      if (++this.n === this.block) { this.out.push(this.cur); this.cur = 0; this.n = 0; }
    }
    this.samples += usable / 2;
  }

  finish() {
    if (this.n > 0) this.out.push(this.cur);
    return { envelope: Float32Array.from(this.out), rate: this.sampleRate / this.block, duration: this.samples / this.sampleRate };
  }
}

/** היסטוריית חיתוכים: החדש למעלה, עד 200 פריטים, בלי שדות לא צפויים */
function addHistory(list, item, max = 200) {
  const clean = {
    id: String(item.id || `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
    source: String(item.source || ''),
    sourceName: String(item.sourceName || ''),
    output: String(item.output || ''),
    start: Number(item.start) || 0,
    end: Number(item.end) || 0,
    format: FORMATS[item.format] ? item.format : 'mp3',
    fadeIn: Number(item.fadeIn) || 0,
    fadeOut: Number(item.fadeOut) || 0,
    at: Number(item.at) || Date.now(),
  };
  return [clean, ...(Array.isArray(list) ? list : []).filter((x) => x && x.id !== clean.id)].slice(0, max);
}

const logicApi = {
  BITRATE, RINGTONE_IPHONE_MAX, ENVELOPE_RATE, FORMATS,
  formatTime, parseTime, normalizeSelection, moveMarker, suggestName, fadeFilter,
  exportArgs, parseProgress, parseInfo, EnvelopeBuilder, addHistory,
};
// גם בממשק (בלי require)
if (typeof module !== 'undefined' && module.exports) module.exports = logicApi; else globalThis.CutterLogic = logicApi;
