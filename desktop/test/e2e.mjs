// בדיקה אמיתית של התוכנה: פתיחה, גרירת סמנים, השמעה, שמירה ב-320kbps, רינגטון והיסטוריה.
import { _electron as electron } from 'playwright-core';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const FF = process.env.SC_FFMPEG || 'ffmpeg';
const shots = process.env.SC_SHOTS || path.join(os.tmpdir(), 'sc-shots');
fs.mkdirSync(shots, { recursive: true });
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sc-e2e-'));
const saveDir = path.join(work, 'שמורים');
fs.mkdirSync(saveDir);
const step = async (name, fn) => { await fn(); process.stdout.write(`  ✓ ${name}\n`); };

// "שיר" לבדיקה: 60 שניות, עוצמה משתנה – כדי שגל הקול ייראה כמו מוזיקה
const song = path.join(work, 'שיר לדוגמה.mp3');
spawnSync(FF, ['-hide_banner', '-y', '-f', 'lavfi', '-i',
  "aevalsrc='0.6*sin(2*PI*220*t)*(0.35+0.65*abs(sin(PI*t/3)))*(0.6+0.4*sin(2*PI*2*t))|0.6*sin(2*PI*330*t)*(0.35+0.65*abs(sin(PI*t/3)))':s=44100:d=60",
  '-c:a', 'libmp3lame', '-b:a', '192k', song]);
const probe = (f) => {
  const s = spawnSync(FF, ['-hide_banner', '-i', f], { encoding: 'utf8' }).stderr;
  const d = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(s);
  return { text: s, duration: d ? +d[1] * 3600 + +d[2] * 60 + +d[3] : 0 };
};

const app = await electron.launch({
  args: ['--no-sandbox', root],
  env: { ...process.env, SC_USER_DATA: path.join(work, 'data'), SC_TEST_OPEN: song, SC_TEST_SAVE_DIR: saveDir },
});
const win = await app.firstWindow();
await win.setViewportSize({ width: 1240, height: 800 });
await win.waitForSelector('#empty:not([hidden])');
const shot = (n) => win.screenshot({ path: path.join(shots, `${n}.png`) });

await step('start screen in Hebrew, credit "נוצר ע״י king400"', async () => {
  assert.equal(await win.getAttribute('html', 'dir'), 'rtl');
  assert.match(await win.textContent('.credit'), /נוצר ע״י\s*king400/);
  assert.match(await win.textContent('.footer'), /king400/);
  await shot('1-start');
});

await step('open a song → waveform, duration, info', async () => {
  await win.click('#empty');
  await win.waitForSelector('#editor:not([hidden])');
  assert.equal(await win.textContent('#file-name'), 'שיר לדוגמה');
  assert.match(await win.textContent('#file-info'), /^1:00\.0 · MP3 · 192kbps/);
  assert.equal(await win.inputValue('#start-time'), '0:00.0');
  assert.equal(await win.inputValue('#end-time'), '1:00.0');
});

const box = await win.locator('#wave').boundingBox();
const xAt = (sec) => box.x + (sec / 60) * box.width;

await step('drag the start and end markers with the mouse', async () => {
  await win.mouse.move(xAt(0) + 2, box.y + 20);
  await win.mouse.down();
  await win.mouse.move(xAt(15), box.y + 20, { steps: 12 });
  await win.mouse.up();
  await win.mouse.move(xAt(60) - 2, box.y + 20);
  await win.mouse.down();
  await win.mouse.move(xAt(42), box.y + 20, { steps: 12 });
  await win.mouse.up();
  const s = L(await win.inputValue('#start-time'));
  const e = L(await win.inputValue('#end-time'));
  assert.ok(Math.abs(s - 15) < 0.5, `start ${s}`);
  assert.ok(Math.abs(e - 42) < 0.5, `end ${e}`);
  await shot('2-selected');
});

await step('exact times: type + nudge ±0.1', async () => {
  await win.fill('#start-time', '0:12.5');
  await win.press('#start-time', 'Enter');
  await win.fill('#end-time', '37');
  await win.press('#end-time', 'Enter');
  await win.click('[data-nudge="end"][data-d="0.1"]');
  assert.equal(await win.inputValue('#start-time'), '0:12.5');
  assert.equal(await win.inputValue('#end-time'), '0:37.1');
  assert.equal(await win.textContent('#sel-len'), '0:24.6');
  // התחלה אחרי הסוף – לא מתקבל
  await win.fill('#start-time', '0:50');
  await win.press('#start-time', 'Enter');
  assert.equal(await win.inputValue('#start-time'), '0:37.0');
  await win.fill('#start-time', '0:12.5');
  await win.press('#start-time', 'Enter');
});

await step('play the selection (starts at the start marker)', async () => {
  await win.click('#play-sel');
  await win.waitForTimeout(900);
  const st = await win.evaluate(() => ({ paused: document.getElementById('player').paused, t: document.getElementById('player').currentTime }));
  assert.equal(st.paused, false);
  assert.ok(st.t >= 12.5 && st.t < 14.5, `t=${st.t}`);
  await shot('3-playing');
  await win.click('#stop');
});

await step('save → MP3 320kbps, exact length, fades', async () => {
  await win.selectOption('#fade-in', '1');
  await win.selectOption('#fade-out', '2');
  await win.click('#save-btn');
  await win.waitForSelector('#snackbar:not([hidden])', { timeout: 20000 });
  assert.match(await win.textContent('#snack-text'), /נשמר: שיר לדוגמה \(קטע/);
  const f = fs.readdirSync(saveDir).find((x) => x.endsWith('.mp3'));
  assert.ok(f, 'no file');
  assert.match(f, /^שיר לדוגמה \(קטע 0\.12\.5-0\.37\.1\)\.mp3$/);
  const p = probe(path.join(saveDir, f));
  assert.match(p.text, /Audio: mp3.*44100 Hz.*320 kb\/s/);
  assert.ok(Math.abs(p.duration - 24.6) < 0.1, `duration ${p.duration}`);
  await shot('4-saved');
});

await step('ringtone for iPhone → M4R', async () => {
  await win.click('#ringtone-btn');
  await win.waitForSelector('#ringtone-dialog[open]');
  await shot('5-ringtone');
  await win.click('[data-ring="ringtone-iphone"]');
  await win.waitForFunction(() => /רינגטון/.test(document.getElementById('snack-text').textContent), null, { timeout: 20000 });
  const f = fs.readdirSync(saveDir).find((x) => x.endsWith('.m4r'));
  const p = probe(path.join(saveDir, f));
  assert.match(p.text, /Audio: aac/);
  assert.ok(Math.abs(p.duration - 24.6) < 0.1);
});

await step('history: both cuts listed, "חתוך שוב" restores the range', async () => {
  await win.click('#start-time');
  await win.fill('#start-time', '0:01');
  await win.press('#start-time', 'Enter');
  await win.click('#history-btn');
  await win.waitForSelector('#history:not([hidden]) .h-item');
  assert.equal(await win.locator('.h-item').count(), 2);
  assert.match(await win.textContent('.h-item >> nth=0'), /רינגטון אייפון/);
  assert.match(await win.textContent('.h-item >> nth=1'), /MP3/);
  await win.waitForTimeout(400);
  await shot('6-history');
  await win.click('.h-item >> nth=1 >> text=חתוך שוב');
  await win.waitForSelector('#history', { state: 'hidden' });
  await win.waitForFunction(() => document.getElementById('start-time').value === '0:12.5');
  assert.equal(await win.inputValue('#end-time'), '0:37.1');
  assert.equal(await win.inputValue('#fade-out'), '2');
});

await step('zoom on the selection', async () => {
  await win.click('#zoom-sel');
  assert.equal(await win.isVisible('#scroll'), true);
  await shot('7-zoom');
  await win.click('#zoom-fit');
  assert.equal(await win.isVisible('#scroll'), false);
});

await step('history survives a restart', async () => {
  await app.close();
  const app2 = await electron.launch({ args: ['--no-sandbox', root], env: { ...process.env, SC_USER_DATA: path.join(work, 'data') } });
  const w2 = await app2.firstWindow();
  await w2.click('#history-btn');
  await w2.waitForSelector('.h-item');
  assert.equal(await w2.locator('.h-item').count(), 2);
  await app2.close();
});

function L(t) { const [m, s] = t.split(':'); return Number(m) * 60 + Number(s); }
process.stdout.write('\nall desktop checks passed\n');
