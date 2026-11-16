'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const call = (name) => (...args) => ipcRenderer.invoke(name, ...args);

contextBridge.exposeInMainWorld('cutter', {
  pickFile: call('pickFile'),
  load: call('load'),
  preparePreview: call('preparePreview'),
  exportClip: call('export'),
  cancelExport: call('cancelExport'),
  history: call('history'),
  removeHistory: call('removeHistory'),
  clearHistory: call('clearHistory'),
  showInFolder: call('showInFolder'),
  showOutput: call('showOutput'),
  version: call('version'),
  // גרירת קובץ לחלון: הנתיב האמיתי של הקובץ
  pathForFile: (file) => { try { return webUtils.getPathForFile(file); } catch { return ''; } },
  onProgress: (fn) => ipcRenderer.on('progress', (_e, v) => fn(v)),
});
