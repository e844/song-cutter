'use strict';
const { app, BrowserWindow, ipcMain, dialog, protocol, shell, session, Menu } = require('electron');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const L = require('./logic');

// בדיקות: תיקיית נתונים נפרדת
if (process.env.SC_USER_DATA) app.setPath('userData', process.env.SC_USER_DATA);
if (!app.requestSingleInstanceLock()) { app.exit(0); process.exit(0); }

protocol.registerSchemesAsPrivileged([
  { scheme: 'media', privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true } },
]);

/** ffmpeg: בגרסה הארוזה – מתוך התיקייה של התוכנה; בפיתוח – מהמערכת */
function ffmpegPath() {
  if (process.env.SC_FFMPEG) return process.env.SC_FFMPEG;
  if (app.isPackaged) return path.join(process.resourcesPath, 'bin', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg');
  return 'ffmpeg';
}

let win = null;
let current = null; // { path, name, duration, preview }
let running = null; // תהליך ffmpeg של שמירה

const historyFile = () => path.join(app.getPath('userData'), 'history.json');
function loadHistory() {
  try { const h = JSON.parse(fs.readFileSync(historyFile(), 'utf8')); return Array.isArray(h) ? h : []; } catch { return []; }
}
function saveHistory(h) {
  fs.mkdirSync(path.dirname(historyFile()), { recursive: true });
  fs.writeFileSync(historyFile(), JSON.stringify(h, null, 1));
}
const prefsFile = () => path.join(app.getPath('userData'), 'prefs.json');
function loadPrefs() { try { return JSON.parse(fs.readFileSync(prefsFile(), 'utf8')) || {}; } catch { return {}; } }
function savePrefs(p) { fs.mkdirSync(path.dirname(prefsFile()), { recursive: true }); fs.writeFileSync(prefsFile(), JSON.stringify(p)); }

const AUDIO_EXT = ['mp3', 'wav', 'flac', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wma', 'aiff', 'aif', 'ape', 'alac', 'amr', 'mka', 'webm'];
const VIDEO_EXT = ['mp4', 'mkv', 'mov', 'avi', 'wmv', 'm4v', 'flv', '3gp', 'ts', 'mpg', 'mpeg'];

const F = require('./ffmpeg');
const analyze = (file) => F.analyze(ffmpegPath(), file);
const makePreview = (file) => F.makePreview(ffmpegPath(), file, path.join(os.tmpdir(), `song-cutter-preview-${process.pid}.mp3`));

/** media://current – הקובץ שנטען, עם תמיכה ב-Range (כדי שאפשר יהיה לקפוץ בשיר) */
function serveFile(file, request) {
  const stat = fs.statSync(file);
  const ext = path.extname(file).slice(1).toLowerCase();
  const types = { mp3: 'audio/mpeg', wav: 'audio/wav', flac: 'audio/flac', m4a: 'audio/mp4', aac: 'audio/aac', ogg: 'audio/ogg', oga: 'audio/ogg', opus: 'audio/ogg', webm: 'audio/webm', mp4: 'video/mp4', m4v: 'video/mp4', mkv: 'video/x-matroska', mka: 'audio/x-matroska', mov: 'video/quicktime' };
  const headers = { 'content-type': types[ext] || 'application/octet-stream', 'accept-ranges': 'bytes' };
  const range = /bytes=(\d*)-(\d*)/.exec(request.headers.get('range') || '');
  if (range) {
    let start = range[1] ? Number(range[1]) : 0;
    let end = range[2] ? Number(range[2]) : stat.size - 1;
    if (!range[1] && range[2]) { start = Math.max(0, stat.size - Number(range[2])); end = stat.size - 1; }
    end = Math.min(end, stat.size - 1);
    if (start > end || start >= stat.size) return new Response(null, { status: 416, headers: { 'content-range': `bytes */${stat.size}` } });
    return new Response(Readable.toWeb(fs.createReadStream(file, { start, end })), {
      status: 206, headers: { ...headers, 'content-range': `bytes ${start}-${end}/${stat.size}`, 'content-length': String(end - start + 1) },
    });
  }
  return new Response(Readable.toWeb(fs.createReadStream(file)), { status: 200, headers: { ...headers, 'content-length': String(stat.size) } });
}

function createWindow() {
  win = new BrowserWindow({
    width: 1240, height: 820, minWidth: 880, minHeight: 620,
    title: 'חותך שירים',
    backgroundColor: '#111318',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, spellcheck: false },
  });
  win.once('ready-to-show', () => win.show());
  setTimeout(() => { if (win && !win.isVisible()) win.show(); }, 3000);
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
}

const handle = (name, fn) => ipcMain.handle(name, async (_e, ...args) => fn(...args));

handle('pickFile', async () => {
  if (process.env.SC_TEST_OPEN) return process.env.SC_TEST_OPEN;
  const prefs = loadPrefs();
  const r = await dialog.showOpenDialog(win, {
    title: 'בחר שיר או סרטון',
    defaultPath: prefs.lastOpenDir || app.getPath('music'),
    properties: ['openFile'],
    filters: [
      { name: 'קבצי שמע ווידאו', extensions: [...AUDIO_EXT, ...VIDEO_EXT] },
      { name: 'כל הקבצים', extensions: ['*'] },
    ],
  });
  if (r.canceled || !r.filePaths[0]) return null;
  savePrefs({ ...prefs, lastOpenDir: path.dirname(r.filePaths[0]) });
  return r.filePaths[0];
});

handle('load', async (file) => {
  if (typeof file !== 'string' || !file) return { error: 'bad' };
  try {
    if (!fs.statSync(file).isFile()) return { error: 'missing' };
  } catch { return { error: 'missing' }; }
  try {
    const r = await analyze(file);
    const duration = r.duration || r.info.duration || 0;
    current = { path: file, name: path.basename(file), duration, preview: null };
    return {
      ok: true, name: current.name, path: file, duration, info: r.info, rate: r.rate,
      envelope: Buffer.from(r.envelope.buffer, r.envelope.byteOffset, r.envelope.byteLength),
      mediaUrl: `media://current/${encodeURIComponent(current.name)}?v=${Date.now()}`,
    };
  } catch (e) {
    return { error: e.message };
  }
});

// הדפדפן לא הצליח לנגן את הקובץ המקורי – מכינים עותק השמעה זמני
handle('preparePreview', async () => {
  if (!current) return { error: 'none' };
  try {
    current.preview = await makePreview(current.path);
    return { ok: true, mediaUrl: `media://preview/x.mp3?v=${Date.now()}` };
  } catch { return { error: 'preview-failed' }; }
});

handle('export', async (opts) => {
  if (!current || running) return { error: running ? 'busy' : 'none' };
  const format = L.FORMATS[opts && opts.format] ? opts.format : 'mp3';
  const sel = L.normalizeSelection(opts.start, opts.end, current.duration);
  const prefs = loadPrefs();
  const suggested = L.suggestName(current.name, sel, format);
  const ext = L.FORMATS[format].ext;
  let output = process.env.SC_TEST_SAVE_DIR ? path.join(process.env.SC_TEST_SAVE_DIR, suggested) : null;
  if (!output) {
    // שואל איפה לשמור
    const r = await dialog.showSaveDialog(win, {
      title: format.startsWith('ringtone') ? 'איפה לשמור את הרינגטון?' : 'איפה לשמור את הקטע?',
      defaultPath: path.join(prefs.lastSaveDir || app.getPath('music'), suggested),
      filters: [{ name: ext === 'm4r' ? 'רינגטון לאייפון' : 'MP3', extensions: [ext] }],
    });
    if (r.canceled || !r.filePath) return { canceled: true };
    output = r.filePath.toLowerCase().endsWith(`.${ext}`) ? r.filePath : `${r.filePath}.${ext}`;
    savePrefs({ ...prefs, lastSaveDir: path.dirname(output) });
  }
  if (path.resolve(output) === path.resolve(current.path)) return { error: 'same-file' };

  const title = suggested.replace(/\.[^.]+$/, '');
  const { args, length } = L.exportArgs({ input: current.path, output, start: sel.start, end: sel.end, fadeIn: opts.fadeIn, fadeOut: opts.fadeOut, format, title });
  const job = F.runExport(ffmpegPath(), args, length, (v) => { if (win) win.webContents.send('progress', v); });
  running = job;
  const res = await job.done;
  running = null;
  if (!res.ok) {
    try { fs.unlinkSync(output); } catch { /* לא נוצר */ }
    return res.canceled ? { canceled: true } : { error: 'export-failed' };
  }
  const item = { source: current.path, sourceName: current.name, output, start: sel.start, end: Math.min(sel.end, sel.start + length), format, fadeIn: opts.fadeIn, fadeOut: opts.fadeOut };
  const history = L.addHistory(loadHistory(), item);
  saveHistory(history);
  return { ok: true, output, name: path.basename(output), length, clipped: length < sel.end - sel.start - 0.01, history };
});

handle('cancelExport', () => { if (running) running.cancel(); return { ok: true }; });

handle('history', () => loadHistory().map((h) => ({ ...h, outputExists: fs.existsSync(h.output), sourceExists: fs.existsSync(h.source) })));
handle('removeHistory', (id) => { const h = loadHistory().filter((x) => x.id !== id); saveHistory(h); return { ok: true }; });
handle('clearHistory', () => { saveHistory([]); return { ok: true }; });
// רק לקבצים שמופיעים בהיסטוריה – לא לכל נתיב שמגיע מהממשק
handle('showInFolder', (id) => {
  const h = loadHistory().find((x) => x.id === id);
  if (h && fs.existsSync(h.output)) shell.showItemInFolder(h.output);
  return { ok: !!h };
});
handle('showOutput', (file) => {
  const h = loadHistory().find((x) => x.output === file);
  if (h && fs.existsSync(h.output)) shell.showItemInFolder(h.output);
  return { ok: !!h };
});
handle('version', () => app.getVersion());

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  // שום חיבור לאינטרנט מהחלון
  session.defaultSession.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] }, (_d, cb) => cb({ cancel: true }));
  protocol.handle('media', (request) => {
    const u = new URL(request.url);
    const file = u.host === 'preview' ? current && current.preview : current && current.path;
    if (!file) return new Response(null, { status: 404 });
    try { return serveFile(file, request); } catch { return new Response(null, { status: 404 }); }
  });
  createWindow();
});

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } });
app.on('window-all-closed', () => {
  if (running) running.cancel();
  if (current && current.preview) { try { fs.unlinkSync(current.preview); } catch { /* ignore */ } }
  app.quit();
});
