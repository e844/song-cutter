'use strict';
/* ממשק חותך השירים */
const L = globalThis.CutterLogic;
const api = window.cutter;
const $ = (id) => document.getElementById(id);

const ERR = {
  'ffmpeg-missing': 'רכיב העיבוד (ffmpeg) חסר בתיקיית התוכנה. חלץ את הקובץ מחדש.',
  'no-audio': 'בקובץ הזה אין ערוץ שמע.',
  unreadable: 'לא הצלחתי לקרוא את הקובץ. ייתכן שהוא פגום או בפורמט לא נתמך.',
  missing: 'הקובץ לא נמצא.',
  'export-failed': 'השמירה נכשלה. נסה שם או תיקייה אחרים.',
  'same-file': 'אי אפשר לשמור על הקובץ המקורי. בחר שם אחר.',
  busy: 'שמירה אחרת עדיין בתהליך.',
};

const state = {
  file: null, // { name, path, duration, info }
  env: null, rate: 100,
  sel: { start: 0, end: 0 },
  view: { start: 0, dur: 1 },
  pos: 0,
  playingSel: false,
  drag: null,
  previewTried: false,
};

const player = $('player');
const canvas = $('wave');
const ctx = canvas.getContext('2d');

// ---------- עזר ----------
function snack(text, action, onAction) {
  $('snack-text').textContent = text;
  const b = $('snack-action');
  b.hidden = !action;
  if (action) { b.textContent = action; b.onclick = () => { onAction(); $('snackbar').hidden = true; }; }
  $('snackbar').hidden = false;
  clearTimeout(snack.t);
  snack.t = setTimeout(() => { $('snackbar').hidden = true; }, action ? 9000 : 4500);
}
/** שם בלי סיומת – כדי שהסיומת באנגלית לא תקפוץ לצד הלא נכון בטקסט עברי */
const baseName = (n) => String(n || '').replace(/\.[^.]+$/, '');
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

// ---------- טעינה ----------
async function openFile(path) {
  if (!path) return;
  stop();
  $('busy').hidden = false;
  const r = await api.load(path);
  $('busy').hidden = true;
  if (!r || r.error) { snack(ERR[r && r.error] || ERR.unreadable); return false; }
  const u8 = r.envelope instanceof Uint8Array ? r.envelope : new Uint8Array(r.envelope);
  state.env = new Float32Array(u8.slice().buffer);
  state.rate = r.rate;
  state.file = { name: r.name, path: r.path, duration: r.duration, info: r.info };
  state.sel = L.normalizeSelection(0, r.duration, r.duration);
  state.view = { start: 0, dur: Math.max(r.duration, 0.5) };
  state.pos = 0;
  state.previewTried = false;
  player.src = r.mediaUrl;
  player.load();

  $('file-name').textContent = baseName(r.name);
  const i = r.info || {};
  const bits = [L.formatTime(r.duration)];
  if (i.codec) bits.push(i.codec.toUpperCase());
  if (i.bitrate) bits.push(`${i.bitrate}kbps`);
  if (i.sampleRate) bits.push(`${(i.sampleRate / 1000).toFixed(1)}kHz`);
  if (i.channels) bits.push(/stereo|^2 channels/.test(i.channels) ? 'סטריאו' : /mono|^1 channels?/.test(i.channels) ? 'מונו' : i.channels);
  $('file-info').textContent = bits.join(' · ');
  $('total').textContent = L.formatTime(r.duration);
  $('empty').hidden = true;
  $('editor').hidden = false;
  document.title = `חותך שירים – ${r.name}`;
  resize();
  syncFields();
  return true;
}

// הדפדפן לא יודע לנגן את הפורמט – מכינים עותק השמעה
player.addEventListener('error', async () => {
  if (!state.file || state.previewTried) return;
  state.previewTried = true;
  snack('מכין את הקובץ להשמעה…');
  const r = await api.preparePreview();
  if (r && r.ok) { player.src = r.mediaUrl; player.load(); } else snack('לא ניתן להשמיע את הקובץ, אבל אפשר לחתוך ולשמור אותו.');
});

// ---------- גל קול ----------
function resize() {
  const dpr = window.devicePixelRatio || 1;
  const r = canvas.getBoundingClientRect();
  canvas.width = Math.max(1, Math.round(r.width * dpr));
  canvas.height = Math.max(1, Math.round(r.height * dpr));
  draw();
}
window.addEventListener('resize', resize);

const tToX = (t) => ((t - state.view.start) / state.view.dur) * canvas.width;
const xToT = (x) => state.view.start + (x / canvas.width) * state.view.dur;

function draw() {
  const W = canvas.width, H = canvas.height;
  const dpr = window.devicePixelRatio || 1;
  ctx.clearRect(0, 0, W, H);
  if (!state.env) return;
  const mid = (H - 22 * dpr) / 2;
  const amp = mid - 6 * dpr;
  const sx = tToX(state.sel.start), ex = tToX(state.sel.end);

  // אזור נבחר
  ctx.fillStyle = 'rgba(185,162,255,0.13)';
  ctx.fillRect(sx, 0, ex - sx, H - 22 * dpr);

  // גל
  const env = state.env, rate = state.rate;
  const colIn = css('--primary'), colOut = css('--muted');
  const step = Math.max(1, Math.round(dpr));
  for (let x = 0; x < W; x += step) {
    const t0 = xToT(x), t1 = xToT(x + step);
    let i0 = Math.floor(t0 * rate), i1 = Math.max(i0 + 1, Math.ceil(t1 * rate));
    if (i1 <= 0 || i0 >= env.length) continue;
    i0 = Math.max(0, i0); i1 = Math.min(env.length, i1);
    let m = 0;
    for (let i = i0; i < i1; i++) if (env[i] > m) m = env[i];
    const h = Math.max(1 * dpr, Math.sqrt(m) * amp); // sqrt – שקטים נראים טוב יותר
    const inside = x >= sx && x <= ex;
    ctx.fillStyle = inside ? colIn : colOut;
    ctx.globalAlpha = inside ? 1 : 0.45;
    ctx.fillRect(x, mid - h, step, h * 2);
  }
  ctx.globalAlpha = 1;

  // ציר זמן
  ctx.fillStyle = css('--muted');
  ctx.font = `${11 * dpr}px Segoe UI, sans-serif`;
  ctx.textBaseline = 'bottom';
  const target = W / (110 * dpr);
  const steps = [0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600];
  const tick = steps.find((s) => state.view.dur / s <= target) || 3600;
  for (let t = Math.ceil(state.view.start / tick) * tick; t <= state.view.start + state.view.dur; t += tick) {
    const x = tToX(t);
    ctx.fillRect(x, H - 20 * dpr, 1 * dpr, 5 * dpr);
    const label = tick < 1 ? L.formatTime(t) : L.formatTime(t).replace(/\.\d$/, '');
    ctx.fillText(label, x + 3 * dpr, H - 2 * dpr);
  }

  // סמנים
  drawMarker(sx, css('--start'), H, dpr, 'start');
  drawMarker(ex, css('--end'), H, dpr, 'end');

  // נקודת ניגון
  const px = tToX(state.pos);
  ctx.fillStyle = css('--on');
  ctx.fillRect(px - 0.75 * dpr, 0, 1.5 * dpr, H - 22 * dpr);
}

function drawMarker(x, color, H, dpr, which) {
  ctx.fillStyle = color;
  ctx.fillRect(x - 1.5 * dpr, 0, 3 * dpr, H - 22 * dpr);
  // ידית יהלום למעלה ולמטה
  const s = 9 * dpr;
  for (const y of [s + 2 * dpr, H - 22 * dpr - s - 2 * dpr]) {
    ctx.beginPath();
    ctx.moveTo(x, y - s); ctx.lineTo(x + s, y); ctx.lineTo(x, y + s); ctx.lineTo(x - s, y);
    ctx.closePath(); ctx.fill();
  }
  void which;
}

// ---------- גרירה ----------
function hit(xCss) {
  const dpr = window.devicePixelRatio || 1;
  const x = xCss * dpr;
  const ds = Math.abs(x - tToX(state.sel.start)), de = Math.abs(x - tToX(state.sel.end));
  const tol = 12 * dpr;
  if (ds <= tol || de <= tol) return ds <= de ? 'start' : 'end';
  return null;
}

canvas.addEventListener('pointerdown', (e) => {
  if (!state.env) return;
  canvas.setPointerCapture(e.pointerId);
  const r = canvas.getBoundingClientRect();
  const xCss = e.clientX - r.left;
  const which = hit(xCss);
  const t = xToT(xCss * (window.devicePixelRatio || 1));
  state.drag = which ? { which, moved: false } : { which: 'new', from: t, moved: false, x0: xCss };
});
canvas.addEventListener('pointermove', (e) => {
  const r = canvas.getBoundingClientRect();
  const xCss = e.clientX - r.left;
  if (!state.drag) { canvas.classList.toggle('grab', !!hit(xCss)); return; }
  const t = Math.min(Math.max(0, xToT(xCss * (window.devicePixelRatio || 1))), state.file.duration);
  const d = state.drag;
  if (d.which === 'new') {
    if (!d.moved && Math.abs(xCss - d.x0) < 4) return;
    d.moved = true;
    state.sel = L.normalizeSelection(Math.min(d.from, t), Math.max(d.from, t), state.file.duration);
  } else {
    d.moved = true;
    state.sel = L.moveMarker(state.sel, d.which, t, state.file.duration);
  }
  syncFields();
});
canvas.addEventListener('pointerup', (e) => {
  const d = state.drag;
  state.drag = null;
  if (d && d.which === 'new' && !d.moved) {
    // לחיצה בלי גרירה = מעבר לנקודה
    const r = canvas.getBoundingClientRect();
    seek(xToT((e.clientX - r.left) * (window.devicePixelRatio || 1)));
  }
});

// זום וגלילה בגלגלת
canvas.addEventListener('wheel', (e) => {
  if (!state.env) return;
  e.preventDefault();
  const r = canvas.getBoundingClientRect();
  if (e.ctrlKey || e.metaKey) {
    const at = xToT((e.clientX - r.left) * (window.devicePixelRatio || 1));
    zoomBy(e.deltaY > 0 ? 1.25 : 0.8, at);
  } else {
    setView(state.view.start + ((e.deltaY || e.deltaX) / 600) * state.view.dur, state.view.dur);
  }
}, { passive: false });

function setView(start, dur) {
  const D = Math.max(state.file.duration, 0.5);
  const d = Math.min(D, Math.max(0.5, dur));
  const s = Math.min(Math.max(0, start), D - d);
  state.view = { start: s, dur: d };
  const zoomed = d < D - 1e-6;
  $('scroll').hidden = !zoomed;
  $('scroll').value = zoomed ? Math.round((s / (D - d)) * 1000) : 0;
  $('zoom').value = Math.round((1 - Math.log(d / 0.5) / Math.log(D / 0.5 || 2)) * 100) || 0;
  draw();
}
function zoomBy(f, at) {
  const v = state.view;
  const center = at ?? v.start + v.dur / 2;
  const nd = v.dur * f;
  const ratio = (center - v.start) / v.dur;
  setView(center - ratio * nd, nd);
}
$('zoom-in').onclick = () => zoomBy(0.6);
$('zoom-out').onclick = () => zoomBy(1 / 0.6);
$('zoom-fit').onclick = () => setView(0, state.file.duration);
$('zoom-sel').onclick = () => {
  const len = state.sel.end - state.sel.start;
  setView(state.sel.start - len * 0.08, len * 1.16);
};
$('zoom').oninput = () => {
  const D = Math.max(state.file.duration, 0.5);
  const d = 0.5 * Math.pow(D / 0.5, 1 - $('zoom').value / 100);
  const c = state.view.start + state.view.dur / 2;
  setView(c - d / 2, d);
};
$('scroll').oninput = () => {
  const D = state.file.duration;
  setView(($('scroll').value / 1000) * (D - state.view.dur), state.view.dur);
};

// ---------- שדות זמן ----------
function syncFields() {
  if (document.activeElement !== $('start-time')) $('start-time').value = L.formatTime(state.sel.start);
  if (document.activeElement !== $('end-time')) $('end-time').value = L.formatTime(state.sel.end);
  $('start-time').classList.remove('bad');
  $('end-time').classList.remove('bad');
  $('sel-len').textContent = L.formatTime(state.sel.end - state.sel.start);
  draw();
}
for (const which of ['start', 'end']) {
  const input = $(`${which}-time`);
  const apply = () => {
    const v = L.parseTime(input.value);
    if (v === null) { input.classList.add('bad'); return; }
    state.sel = L.moveMarker(state.sel, which, v, state.file.duration);
    input.value = L.formatTime(state.sel[which]);
    syncFields();
  };
  input.addEventListener('change', apply);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { apply(); input.blur(); } });
}
document.querySelectorAll('[data-nudge]').forEach((b) => {
  b.onclick = () => {
    const which = b.dataset.nudge;
    state.sel = L.moveMarker(state.sel, which, state.sel[which] + Number(b.dataset.d), state.file.duration);
    syncFields();
    if (which === 'start') seek(state.sel.start); else seek(Math.max(state.sel.start, state.sel.end - 2));
  };
});
document.querySelectorAll('[data-here]').forEach((b) => {
  b.onclick = () => { state.sel = L.moveMarker(state.sel, b.dataset.here, state.pos, state.file.duration); syncFields(); };
});

// ---------- השמעה ----------
function seek(t) {
  state.pos = Math.min(Math.max(0, t), state.file.duration);
  try { player.currentTime = state.pos; } catch { /* עוד לא נטען */ }
  $('pos').textContent = L.formatTime(state.pos);
  draw();
}
function playSelection() {
  if (!state.file) return;
  if (!player.paused && state.playingSel) { pause(); return; }
  state.playingSel = true;
  seek(state.sel.start);
  player.volume = 1;
  player.play().catch(() => snack('לא ניתן להשמיע את הקובץ.'));
  $('play-sel').classList.add('playing');
  $('play-sel').textContent = '⏸';
}
function playFromPos() {
  if (!state.file) return;
  if (!player.paused) { pause(); return; }
  state.playingSel = false;
  player.volume = 1;
  player.play().catch(() => snack('לא ניתן להשמיע את הקובץ.'));
}
function pause() {
  player.pause();
  $('play-sel').classList.remove('playing');
  $('play-sel').textContent = '▶';
}
function stop() {
  pause();
  state.playingSel = false;
  if (state.file) seek(state.sel.start);
}
$('play-sel').onclick = playSelection;
$('play-pos').onclick = playFromPos;
$('stop').onclick = stop;
player.addEventListener('pause', () => { $('play-sel').classList.remove('playing'); $('play-sel').textContent = '▶'; });

// עדכון מיקום + עצירה בסוף הקטע + השמעת העמעום
(function tick() {
  if (state.file && !player.paused) {
    state.pos = player.currentTime;
    $('pos').textContent = L.formatTime(state.pos);
    if (state.playingSel) {
      const fi = Number($('fade-in').value), fo = Number($('fade-out').value);
      const into = state.pos - state.sel.start, left = state.sel.end - state.pos;
      let v = 1;
      if (fi > 0 && into < fi) v = Math.min(v, Math.max(0, into / fi));
      if (fo > 0 && left < fo) v = Math.min(v, Math.max(0, left / fo));
      player.volume = Math.min(1, Math.max(0, v));
      if (state.pos >= state.sel.end) { pause(); player.volume = 1; seek(state.sel.start); }
    }
    // הגל עוקב אחרי נקודת הניגון כשמוגדל
    if (state.view.dur < state.file.duration && (state.pos > state.view.start + state.view.dur || state.pos < state.view.start)) {
      setView(state.pos - state.view.dur * 0.1, state.view.dur);
    } else draw();
  }
  requestAnimationFrame(tick);
}());

// ---------- שמירה ----------
async function save(format) {
  if (!state.file) return;
  pause();
  $('progress-title').textContent = format.startsWith('ringtone') ? 'שומר רינגטון…' : 'שומר את הקטע…';
  $('progress-fill').style.width = '0%';
  $('progress-text').textContent = '0%';
  let opened = false;
  const openTimer = setTimeout(() => { opened = true; $('progress-dialog').showModal(); }, 150);
  const r = await api.exportClip({ start: state.sel.start, end: state.sel.end, fadeIn: Number($('fade-in').value), fadeOut: Number($('fade-out').value), format });
  clearTimeout(openTimer);
  if (opened || $('progress-dialog').open) $('progress-dialog').close();
  if (!r || r.canceled) return;
  if (r.error) { snack(ERR[r.error] || ERR['export-failed']); return; }
  const extra = r.clipped ? ` (קוצר ל-${L.RINGTONE_IPHONE_MAX} שניות – המקסימום באייפון)` : '';
  snack(`✓ נשמר: ${baseName(r.name)}${extra}`, 'הצג בתיקייה', () => api.showOutput(r.output));
  if (!$('history').hidden) renderHistory();
}
api.onProgress((v) => {
  if (!$('progress-dialog').open) $('progress-dialog').showModal();
  const p = Math.round(v * 100);
  $('progress-fill').style.width = `${p}%`;
  $('progress-text').textContent = `${p}%`;
});
$('progress-cancel').onclick = () => api.cancelExport();
$('save-btn').onclick = () => save('mp3');
$('ringtone-btn').onclick = () => {
  const len = state.sel.end - state.sel.start;
  $('ring-note').textContent = len > L.RINGTONE_IPHONE_MAX
    ? `הקטע ארוך מ-${L.RINGTONE_IPHONE_MAX} שניות – באייפון יישמרו רק ${L.RINGTONE_IPHONE_MAX} השניות הראשונות.`
    : 'טיפ: רינגטון טוב הוא 20–30 שניות, עם דעיכה בסוף.';
  $('ringtone-dialog').showModal();
};
document.querySelectorAll('[data-ring]').forEach((b) => { b.onclick = () => { $('ringtone-dialog').close(); save(b.dataset.ring); }; });
document.querySelectorAll('[data-close]').forEach((b) => { b.onclick = () => b.closest('dialog').close(); });

// ---------- היסטוריה ----------
const FORMAT_TAG = { mp3: 'MP3 ‏320kbps', 'ringtone-android': 'רינגטון אנדרואיד · MP3', 'ringtone-iphone': 'רינגטון אייפון · M4R' };
function fmtDate(ms) {
  const d = new Date(ms);
  return `${d.toLocaleDateString('he-IL')} ${d.toLocaleTimeString('he-IL', { hour: '2-digit', minute: '2-digit' })}`;
}
async function renderHistory() {
  const list = await api.history();
  const box = $('history-list');
  box.textContent = '';
  if (!list.length) {
    const e = document.createElement('div');
    e.className = 'h-empty';
    e.textContent = 'עדיין לא חתכת שירים. כל קטע שתשמור יופיע כאן.';
    box.appendChild(e);
    $('history-clear').hidden = true;
    return;
  }
  $('history-clear').hidden = false;
  for (const h of list) {
    const item = document.createElement('div');
    item.className = 'h-item';
    item.dataset.id = h.id;
    const name = document.createElement('div');
    name.className = 'h-name';
    name.textContent = baseName(h.output.split(/[\\/]/).pop());
    const meta = document.createElement('div');
    meta.className = 'h-meta';
    const tag = document.createElement('span'); tag.className = 'h-tag'; tag.textContent = FORMAT_TAG[h.format] || 'MP3';
    const range = document.createElement('span'); range.className = 'h-range'; range.textContent = `${L.formatTime(h.start)} – ${L.formatTime(h.end)}`;
    const when = document.createElement('span'); when.textContent = fmtDate(h.at);
    meta.append(tag, range, when);
    const src = document.createElement('div');
    src.className = 'h-meta';
    src.textContent = `מתוך: ${baseName(h.sourceName)}`;
    const actions = document.createElement('div');
    actions.className = 'h-actions';
    const mk = (label, fn, enabled = true) => { const b = document.createElement('button'); b.className = 'btn text small'; b.textContent = label; b.disabled = !enabled; b.onclick = fn; actions.appendChild(b); };
    mk('📁 הצג בתיקייה', () => api.showInFolder(h.id), h.outputExists);
    mk('✂️ חתוך שוב', async () => {
      closeHistory();
      if (await openFile(h.source)) {
        state.sel = L.normalizeSelection(h.start, h.end, state.file.duration);
        $('fade-in').value = String(h.fadeIn || 0);
        $('fade-out').value = String(h.fadeOut || 0);
        syncFields();
        seek(state.sel.start);
      }
    }, h.sourceExists);
    mk('הסר', async () => { await api.removeHistory(h.id); renderHistory(); });
    item.append(name, meta, src, actions);
    if (!h.outputExists) { const m = document.createElement('div'); m.className = 'h-missing'; m.textContent = 'הקובץ השמור נמחק או הועבר'; item.appendChild(m); }
    box.appendChild(item);
  }
}
function openHistory() { $('history').hidden = false; $('scrim').hidden = false; renderHistory(); }
function closeHistory() { $('history').hidden = true; $('scrim').hidden = true; }
$('history-btn').onclick = openHistory;
$('history-close').onclick = closeHistory;
$('scrim').onclick = closeHistory;
$('history-clear').onclick = async () => { await api.clearHistory(); renderHistory(); };

// ---------- פתיחה / גרירה ----------
const pick = async () => openFile(await api.pickFile());
$('open-btn').onclick = pick;
$('change-btn').onclick = pick;
$('empty').onclick = pick;
$('empty').addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') pick(); });
document.addEventListener('dragover', (e) => { e.preventDefault(); $('empty').classList.add('over'); });
document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) $('empty').classList.remove('over'); });
document.addEventListener('drop', (e) => {
  e.preventDefault();
  $('empty').classList.remove('over');
  const f = e.dataTransfer.files[0];
  if (f) openFile(api.pathForFile(f));
});

// מקלדת: רווח = השמעה, [ ] = קביעת התחלה/סוף בנקודת הניגון, Esc = סגירה
document.addEventListener('keydown', (e) => {
  if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement || !state.file) return;
  if (e.key === ' ') { e.preventDefault(); playSelection(); }
  else if (e.key === '[') { state.sel = L.moveMarker(state.sel, 'start', state.pos, state.file.duration); syncFields(); }
  else if (e.key === ']') { state.sel = L.moveMarker(state.sel, 'end', state.pos, state.file.duration); syncFields(); }
  else if (e.key === 'Escape') closeHistory();
});

api.version().then((v) => { $('version').textContent = `גרסה ${v}`; });
