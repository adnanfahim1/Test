// PikGen page: the main screen of the extension.
// It connects the form, Claude (prompt writing), Higgsfield (generation),
// the queue and your output folder.

import { loadSettings, saveSettings } from '../lib/settings.js';
import { kvGet, kvSet, saveBatch, listBatches, deleteBatch } from '../lib/db.js';
import { CLAUDE_MODELS, expandPrompts, testClaude } from '../lib/claude.js';
import { HiggsfieldClient } from '../lib/higgsfield.js';
import { BatchSaver, folderPermission, requestFolderPermission } from '../lib/files.js';
import { QueueEngine, makeItems, countByStatus } from '../lib/queue.js';
import { validateModel } from '../lib/models.js';
import { batchFolderName, slugify } from '../lib/util.js';

const $ = (id) => document.getElementById(id);
const MAX_QUANTITY = 500;
// Automated tests open the page with ?test=1 and use a private test folder instead of the folder picker.
const TEST_MODE = new URLSearchParams(location.search).get('test') === '1';

const state = {
  settings: null,
  kind: 'image',
  prompts: [],
  folder: null, // FileSystemDirectoryHandle
  batch: null,
  engine: null,
  tiles: new Map(), // item number -> tile element
  thumbs: new Map(), // item number -> object URL
  previewAbort: null,
  renderQueued: false,
};

// ------------------------------------------------------------------ helpers

function toast(message, ms = 4000) {
  const el = $('toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (el.hidden = true), ms);
}

function errText(err) {
  // Never show API keys, even if a library put one in an error message.
  let text = err?.message || String(err);
  for (const secret of [state.settings?.anthropicKey, state.settings?.higgsfieldKey]) {
    if (secret && secret.length > 6) text = text.split(secret).join('[hidden]');
  }
  return text;
}

function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  Object.assign(node, props);
  for (const child of children) node.append(child);
  return node;
}

function modelsOfKind(kind) {
  return (state.settings.models || []).filter((m) => m.type === kind);
}

function currentModel() {
  return (state.settings.models || []).find((m) => m.id === $('modelSelect').value) || null;
}

function money(value) {
  return value < 1 ? `$${value.toFixed(3)}` : `$${value.toFixed(2)}`;
}

// ------------------------------------------------------------------ start gate

$('startBtn').addEventListener('click', async () => {
  $('startBtn').disabled = true;
  try {
    state.settings = await loadSettings();
    await loadFolder(true);
    $('gate').hidden = true;
    $('app').hidden = false;
    renderGenerateForm();
    renderSettings();
    await resumeInterruptedBatch();
    startTicker();
  } catch (err) {
    $('startBtn').disabled = false;
    toast(`Could not start: ${errText(err)}`);
  }
});

function startTicker() {
  // A tiny background worker sends a "tick" every second. Workers keep ticking even
  // when this tab is in the background (normal page timers get slowed down there).
  const worker = new Worker('ticker.js');
  worker.onmessage = () => state.engine?.tick();
}

window.addEventListener('beforeunload', (event) => {
  if (state.batch?.state === 'running') {
    event.preventDefault();
    event.returnValue = '';
  }
});

// ------------------------------------------------------------------ tabs

function showTab(name) {
  for (const tab of document.querySelectorAll('.tab')) tab.classList.toggle('active', tab.dataset.tab === name);
  for (const panel of ['generate', 'batches', 'settings']) $(`tab-${panel}`).hidden = panel !== name;
  if (name === 'batches') renderBatchList();
}
document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => showTab(tab.dataset.tab)));
document.querySelectorAll('[data-goto]').forEach((link) =>
  link.addEventListener('click', (e) => {
    e.preventDefault();
    showTab(link.dataset.goto);
  }),
);

// ------------------------------------------------------------------ folder

async function loadFolder(fromClick) {
  if (TEST_MODE) {
    state.folder = await navigator.storage.getDirectory();
  } else {
    state.folder = (await kvGet('outputFolder')) || null;
    if (state.folder && fromClick) {
      // Ask the browser to confirm access again if it forgot (needs a button click).
      if ((await folderPermission(state.folder)) !== 'granted') {
        await requestFolderPermission(state.folder).catch(() => {});
      }
    }
  }
  await renderFolder();
}

async function renderFolder() {
  if (!state.folder) {
    $('folderName').textContent = 'No folder chosen';
  } else {
    const perm = await folderPermission(state.folder).catch(() => 'prompt');
    $('folderName').textContent =
      perm === 'granted' ? `Saving to: ${state.folder.name}` : `${state.folder.name} (click "Choose output folder" or Resume to allow access)`;
  }
  updateGenerateButton();
}

$('pickFolder').addEventListener('click', async () => {
  if (TEST_MODE) return;
  if (!window.showDirectoryPicker) {
    toast('This browser does not support choosing a folder.');
    return;
  }
  try {
    const handle = await window.showDirectoryPicker({ id: 'bulk-output', mode: 'readwrite' });
    state.folder = handle;
    await kvSet('outputFolder', handle);
    await renderFolder();
  } catch (err) {
    if (err?.name !== 'AbortError') toast(`Could not use that folder: ${errText(err)}`);
  }
});

async function ensureFolderAccess() {
  if (!state.folder) throw new Error('Choose an output folder first.');
  if ((await folderPermission(state.folder)) === 'granted') return;
  const result = await requestFolderPermission(state.folder);
  await renderFolder();
  if (result !== 'granted') throw new Error('The browser did not allow access to your output folder.');
}

// ------------------------------------------------------------------ generate form

function renderGenerateForm() {
  document.querySelectorAll('.seg-btn').forEach((btn) => btn.classList.toggle('active', btn.dataset.kind === state.kind));
  const select = $('modelSelect');
  const models = modelsOfKind(state.kind);
  const previous = select.value;
  select.replaceChildren(...models.map((m) => el('option', { value: m.id, textContent: m.name })));
  const preferred = state.kind === 'image' ? state.settings.defaultImageModel : state.settings.defaultVideoModel;
  if (models.some((m) => m.id === previous)) select.value = previous;
  else if (models.some((m) => m.id === preferred)) select.value = preferred;
  $('noModelHint').hidden = models.length > 0;
  renderOptions();
  renderPrompts();
}

document.querySelectorAll('.seg-btn').forEach((btn) =>
  btn.addEventListener('click', () => {
    state.kind = btn.dataset.kind;
    renderGenerateForm();
  }),
);
$('modelSelect').addEventListener('change', () => {
  renderOptions();
  renderCost();
});

/** One dropdown per setting the model profile lists (aspect ratio, resolution, duration...). */
function renderOptions() {
  const box = $('optionsBox');
  box.replaceChildren();
  const model = currentModel();
  for (const [key, values] of Object.entries(model?.options || {})) {
    const select = el(
      'select',
      {},
      values.map((value, i) => el('option', { value: String(i), textContent: String(value) })),
    );
    select.dataset.key = key;
    select.addEventListener('change', renderCost);
    box.append(el('label', { textContent: key.replace(/_/g, ' ') }, [select]));
  }
}

/** The settings sent to Higgsfield with every prompt. */
function currentParams() {
  const model = currentModel();
  const params = { ...(model?.fixed || {}) };
  for (const select of $('optionsBox').querySelectorAll('select')) {
    const key = select.dataset.key;
    params[key] = model.options[key][Number(select.value)];
  }
  return params;
}

$('quantity').addEventListener('change', () => {
  const q = Math.round(Number($('quantity').value) || 1);
  $('quantity').value = Math.min(MAX_QUANTITY, Math.max(1, q));
});
for (const id of ['batchName', 'useLines']) $(id).addEventListener('input', updateGenerateButton);

// ------------------------------------------------------------------ prompt preview

$('previewBtn').addEventListener('click', async () => {
  const base = $('basePrompt').value.trim();
  if (!base) return toast('Type or paste a prompt first.');
  if ($('useLines').checked) {
    state.prompts = base.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).slice(0, MAX_QUANTITY);
    renderPrompts();
    return;
  }
  const count = Math.min(MAX_QUANTITY, Math.max(1, Number($('quantity').value) || 1));
  const abort = new AbortController();
  state.previewAbort = abort;
  $('previewBtn').disabled = true;
  $('stopPreviewBtn').hidden = false;
  $('previewStatus').textContent = `Claude is writing prompts... 0 / ${count}`;
  try {
    state.prompts = await expandPrompts({
      apiKey: state.settings.anthropicKey,
      baseURL: state.settings.anthropicBaseUrl,
      model: state.settings.claudeModel,
      kind: state.kind,
      basePrompt: base,
      style: $('variation').value,
      rules: $('rules').value.trim(),
      count,
      signal: abort.signal,
      onProgress: (done, total) => {
        $('previewStatus').textContent = `Claude is writing prompts... ${done} / ${total}`;
      },
    });
    $('previewStatus').textContent = `${state.prompts.length} prompts ready. Edit anything below.`;
  } catch (err) {
    $('previewStatus').textContent = '';
    toast(`Prompt writing failed: ${errText(err)}`, 8000);
  } finally {
    state.previewAbort = null;
    $('previewBtn').disabled = false;
    $('stopPreviewBtn').hidden = true;
    renderPrompts();
  }
});

$('stopPreviewBtn').addEventListener('click', () => state.previewAbort?.abort());
$('clearPrompts').addEventListener('click', () => {
  state.prompts = [];
  renderPrompts();
});

function renderPrompts() {
  const list = $('promptList');
  list.replaceChildren(
    ...state.prompts.map((prompt, i) => {
      const text = el('textarea', { value: prompt, rows: 2 });
      text.addEventListener('input', () => (state.prompts[i] = text.value));
      const regen = el('button', { className: 'icon-btn', title: 'Write a new version of this prompt', textContent: '↻' });
      regen.addEventListener('click', () => regenerateOne(i, regen));
      const remove = el('button', { className: 'icon-btn', title: 'Delete this prompt', textContent: '✕' });
      remove.addEventListener('click', () => {
        state.prompts.splice(i, 1);
        renderPrompts();
      });
      return el('li', {}, [el('div', { className: 'prompt-item' }, [text, regen, remove])]);
    }),
  );
  $('promptCount').textContent = state.prompts.length ? `(${state.prompts.length})` : '';
  $('clearPrompts').hidden = !state.prompts.length;
  $('promptHint').hidden = state.prompts.length > 0;
  renderCost();
  updateGenerateButton();
}

async function regenerateOne(index, button) {
  button.disabled = true;
  try {
    const [fresh] = await expandPrompts({
      apiKey: state.settings.anthropicKey,
      baseURL: state.settings.anthropicBaseUrl,
      model: state.settings.claudeModel,
      kind: state.kind,
      basePrompt: $('basePrompt').value.trim() || state.prompts[index],
      style: $('variation').value,
      rules: $('rules').value.trim(),
      count: 1,
      existing: state.prompts,
    });
    state.prompts[index] = fresh;
    renderPrompts();
  } catch (err) {
    toast(`Could not regenerate: ${errText(err)}`);
  } finally {
    button.disabled = false;
  }
}

function renderCost() {
  const box = $('costBox');
  const model = currentModel();
  const n = state.prompts.length;
  if (!n || !model) {
    box.textContent = '';
    return;
  }
  if (model.price == null || model.price === '') {
    box.textContent = `${n} generations. No price set for "${model.name}" - add one in Settings to see an estimated cost.`;
    return;
  }
  const total = n * Number(model.price);
  box.textContent = `Estimated cost: about ${money(total)} (${n} × ${money(Number(model.price))}). This is an ESTIMATE from the price you entered, not a quote from Higgsfield.`;
}

function updateGenerateButton() {
  const busy = state.batch && ['running', 'paused', 'stopped'].includes(state.batch.state);
  $('generateBtn').disabled = !state.prompts.length || !currentModel() || !state.folder || busy;
  $('generateBtn').title = busy ? 'Finish, cancel or close the current batch first.' : '';
}

// ------------------------------------------------------------------ start a batch

$('generateBtn').addEventListener('click', async () => {
  const model = currentModel();
  const prompts = state.prompts.map((p) => p.trim()).filter(Boolean);
  if (!model || !prompts.length) return;
  if (!state.settings.higgsfieldKey) return toast('Add your Higgsfield key in Settings first.');
  const cost = model.price != null && model.price !== '' ? ` Estimated cost: about ${money(prompts.length * Number(model.price))}.` : '';
  if (!confirm(`Start ${prompts.length} ${state.kind} generations with "${model.name}"?${cost}`)) return;

  try {
    await ensureFolderAccess();
    const name = slugify($('batchName').value.trim() || $('basePrompt').value.trim() || 'batch', 30);
    const batch = {
      id: crypto.randomUUID(),
      name,
      folderName: batchFolderName(name),
      rootFolder: state.folder.name,
      kind: state.kind,
      modelId: model.id,
      modelName: model.name,
      modelPath: model.path,
      params: currentParams(),
      basePrompt: $('basePrompt').value.trim(),
      items: makeItems(prompts),
      createdAt: new Date().toISOString(),
      state: 'idle',
    };
    await saveBatch(batch);
    openBatch(batch);
    state.engine.start();
    await state.engine.writeManifestNow();
    state.prompts = [];
    renderPrompts();
  } catch (err) {
    toast(`Could not start: ${errText(err)}`, 8000);
  }
});

// ------------------------------------------------------------------ batch view

let persistTimer = null;
function persist(immediate) {
  if (!state.batch) return;
  const batch = state.batch;
  const doSave = () => saveBatch(batch).catch((err) => toast(`Could not save progress: ${errText(err)}`));
  if (immediate) {
    clearTimeout(persistTimer);
    persistTimer = null;
    doSave();
  } else if (!persistTimer) {
    persistTimer = setTimeout(() => {
      persistTimer = null;
      doSave();
    }, 1000);
  }
}

function openBatch(batch) {
  for (const url of state.thumbs.values()) URL.revokeObjectURL(url);
  state.thumbs.clear();
  state.tiles.clear();
  $('thumbs').replaceChildren();

  state.batch = batch;
  const api = new HiggsfieldClient({
    credentials: state.settings.higgsfieldKey,
    baseURL: state.settings.higgsfieldBaseUrl || undefined,
  });
  const saver = new BatchSaver(state.folder, batch);
  state.engine = new QueueEngine({
    batch,
    api,
    saver,
    concurrency: Number(state.settings.concurrency) || 2,
    persist,
    onChange: scheduleRender,
    onResult: (item, blob, index) => {
      if (index === 0 && !state.thumbs.has(item.n)) setThumb(item.n, blob, saver, item.files[0]);
    },
  });
  $('batchView').hidden = false;
  showTab('generate');
  renderBatch();
  loadSavedThumbs(saver);
  $('batchView').scrollIntoView({ behavior: 'smooth' });
}

/** After a restart, show thumbnails for files already in the folder. */
async function loadSavedThumbs(saver) {
  const batch = state.batch;
  for (const item of batch.items) {
    if (state.batch !== batch) return; // a different batch was opened
    if (item.status !== 'done' || !item.files?.[0] || state.thumbs.has(item.n)) continue;
    try {
      await setThumb(item.n, null, saver, item.files[0]);
    } catch {
      // file moved or deleted - just no thumbnail
    }
  }
}

// Thumbnails are kept SMALL so a 500-item batch doesn't eat gigabytes of memory:
//  - images: shrunk to a ~280px JPEG (a few KB each)
//  - videos: shown straight from the saved file on disk (not held in memory)
const THUMB_PX = 280;

async function makeImageThumb(blob) {
  try {
    const bitmap = await createImageBitmap(blob, { resizeWidth: THUMB_PX, resizeQuality: 'medium' });
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    canvas.getContext('2d').drawImage(bitmap, 0, 0);
    bitmap.close();
    return await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.8 });
  } catch {
    return blob; // unusual format: fall back to the original
  }
}

async function setThumb(n, blob, saver, fileName) {
  const batch = state.batch;
  try {
    let source = blob;
    if (!source || batch.kind === 'video') source = await saver.readResult(fileName); // disk-backed, not in RAM
    const thumb = batch.kind === 'video' ? source : await makeImageThumb(source);
    if (state.batch !== batch || state.thumbs.has(n)) return;
    state.thumbs.set(n, URL.createObjectURL(thumb));
    scheduleRender();
  } catch {
    // file moved or unreadable - just no thumbnail
  }
}

function scheduleRender() {
  if (state.renderQueued) return;
  state.renderQueued = true;
  requestAnimationFrame(() => {
    state.renderQueued = false;
    renderBatch();
  });
}

const STATUS_LABEL = {
  queued: 'Queued',
  submitting: 'Sending',
  running: 'Generating',
  saving: 'Saving',
  done: 'Done',
  failed: 'Failed',
  canceled: 'Canceled',
};

function renderBatch() {
  const batch = state.batch;
  if (!batch) return;
  const c = countByStatus(batch);
  const total = batch.items.length;
  $('batchTitle').textContent = `${batch.name} - ${batch.modelName} - ${STATE_LABEL[batch.state] || batch.state}`;
  $('progressBar').style.width = `${(100 * (c.done + c.failed + c.canceled)) / total}%`;
  $('counts').replaceChildren(
    ...[
      ['Queued', c.queued],
      ['Running', c.running],
      ['Done', c.done],
      ['Failed', c.failed],
      ...(c.canceled ? [['Canceled', c.canceled]] : []),
      ['Total', total],
    ].map(([label, n]) => el('span', {}, [`${label}: `, el('b', { textContent: String(n) })])),
  );

  const s = batch.state;
  $('pauseBtn').hidden = s !== 'running';
  $('resumeBtn').hidden = !['paused', 'stopped', 'idle'].includes(s) || c.queued + c.running === 0;
  $('retryBtn').hidden = c.failed === 0 || s === 'running';
  $('cancelBtn').hidden = !['running', 'paused', 'stopped', 'idle'].includes(s) || c.queued + c.running === 0;
  $('closeBatchBtn').hidden = s === 'running';

  $('batchNotice').hidden = !batch.notice;
  $('batchNotice').textContent = batch.notice || '';
  $('batchError').hidden = !batch.error;
  $('batchError').textContent = batch.error || '';
  $('permBox').hidden = !batch.needsPermissionFor;
  $('permOrigin').textContent = batch.needsPermissionFor || '';

  for (const item of batch.items) updateTile(item);
  updateGenerateButton();
}

const STATE_LABEL = {
  idle: 'ready',
  running: 'running',
  paused: 'paused',
  stopped: 'stopped - see message',
  canceled: 'canceled',
  finished: 'finished',
};

/** Create or update the small card for one item in the results grid. */
function updateTile(item) {
  let tile = state.tiles.get(item.n);
  if (!tile) {
    tile = el('div', { className: 'thumb' });
    tile.media = el('div', { className: 'media' });
    tile.label = el('span');
    tile.status = el('span', { className: 'muted' });
    tile.err = el('div', { className: 'err' });
    tile.append(tile.media, el('div', { className: 'meta' }, [tile.label, tile.status]), tile.err);
    tile.label.textContent = `#${item.n}`;
    tile.title = item.prompt;
    state.tiles.set(item.n, tile);
    $('thumbs').append(tile);
  }
  const key = `${item.status}|${state.thumbs.has(item.n)}|${item.error}`;
  if (tile.key === key) return;
  tile.key = key;
  tile.classList.toggle('failed', item.status === 'failed');
  tile.status.textContent = STATUS_LABEL[item.status] || item.status;
  tile.err.textContent = item.error || '';
  tile.err.hidden = !item.error;

  const url = state.thumbs.get(item.n);
  if (url && tile.mediaUrl !== url) {
    tile.mediaUrl = url;
    const media =
      state.batch.kind === 'video'
        ? el('video', { src: url, muted: true, loop: true, preload: 'metadata', controls: true })
        : el('img', { src: url, alt: item.prompt, loading: 'lazy' });
    tile.media.replaceChildren(media);
  } else if (!url) {
    tile.media.textContent = STATUS_LABEL[item.status] || '';
  }
}

$('pauseBtn').addEventListener('click', () => state.engine?.pause());
$('resumeBtn').addEventListener('click', async () => {
  try {
    await ensureFolderAccess();
    state.engine?.start();
  } catch (err) {
    toast(errText(err));
  }
});
$('retryBtn').addEventListener('click', async () => {
  try {
    await ensureFolderAccess();
    state.engine?.retryFailed();
  } catch (err) {
    toast(errText(err));
  }
});
$('cancelBtn').addEventListener('click', async () => {
  if (!confirm('Cancel this batch? Jobs Higgsfield has already started will still finish and be saved.')) return;
  await state.engine?.cancel();
});
$('closeBatchBtn').addEventListener('click', () => {
  state.engine = null;
  state.batch = null;
  $('batchView').hidden = true;
  updateGenerateButton();
});
$('permBtn').addEventListener('click', async () => {
  const origin = state.batch?.needsPermissionFor;
  if (!origin) return;
  try {
    const granted = await chrome.permissions.request({ origins: [`${origin}/*`] });
    if (granted) {
      toast('Allowed. Retrying the downloads (no new generations needed).');
      await ensureFolderAccess();
      state.engine?.retryFailed();
    } else {
      toast('Permission was not granted.');
    }
  } catch (err) {
    toast(errText(err));
  }
});

/** On Start: if a batch was interrupted (tab closed, browser restarted), show it paused. */
async function resumeInterruptedBatch() {
  const batches = await listBatches();
  const open = batches.find(
    (b) => ['running', 'paused', 'stopped', 'idle'].includes(b.state) && b.items.some((it) => !['done', 'failed', 'canceled'].includes(it.status)),
  );
  if (!open) return;
  if (open.state === 'running' || open.state === 'idle') open.state = 'paused';
  open.notice = 'This batch was interrupted. Press Resume to continue it.';
  if (!state.folder) {
    toast('An unfinished batch was found. Choose your output folder, then open it from the Batches tab.', 8000);
    return;
  }
  openBatch(open);
}

// ------------------------------------------------------------------ batches tab

async function renderBatchList() {
  const batches = await listBatches();
  if (!batches.length) {
    $('batchList').replaceChildren(el('p', { className: 'muted', textContent: 'No batches yet.' }));
    return;
  }
  const rows = batches.map((b) => {
    const c = countByStatus(b);
    const openBtn = el('button', { className: 'btn small', textContent: 'Open' });
    openBtn.addEventListener('click', () => {
      if (state.batch?.state === 'running') return toast('Pause or finish the current batch first.');
      if (!state.folder) return toast('Choose your output folder first (Generate tab).');
      if (b.state === 'running') b.state = 'paused';
      openBatch(b);
    });
    const delBtn = el('button', { className: 'btn small danger', textContent: 'Remove from list' });
    delBtn.addEventListener('click', async () => {
      if (state.batch?.id === b.id) return toast('Close this batch first.');
      if (!confirm('Remove this batch from the list? Files in your folder are NOT deleted.')) return;
      await deleteBatch(b.id);
      renderBatchList();
    });
    return el('tr', {}, [
      el('td', { textContent: b.name }),
      el('td', { textContent: new Date(b.createdAt).toLocaleString() }),
      el('td', { textContent: `${b.kind} - ${b.modelName}` }),
      el('td', { textContent: `${c.done} done / ${c.failed} failed / ${b.items.length} total` }),
      el('td', { textContent: b.state }),
      el('td', { textContent: `${b.rootFolder || ''}/${b.folderName}` }),
      el('td', {}, [openBtn, ' ', delBtn]),
    ]);
  });
  const head = el('tr', {}, ['Name', 'Created', 'Model', 'Progress', 'State', 'Folder', ''].map((h) => el('th', { textContent: h })));
  $('batchList').replaceChildren(el('table', {}, [el('thead', {}, [head]), el('tbody', {}, rows)]));
}

// ------------------------------------------------------------------ settings tab

function fillModelSelect(select, kind, value, allowNone) {
  const options = modelsOfKind(kind).map((m) => el('option', { value: m.id, textContent: m.name }));
  if (allowNone) options.unshift(el('option', { value: '', textContent: '(none)' }));
  select.replaceChildren(...options);
  select.value = value || '';
}

function renderSettings() {
  const s = state.settings;
  $('anthropicKey').value = s.anthropicKey;
  $('higgsfieldKey').value = s.higgsfieldKey;
  $('claudeModel').replaceChildren(...CLAUDE_MODELS.map((m) => el('option', { value: m.id, textContent: m.label })));
  $('claudeModel').value = s.claudeModel;
  fillModelSelect($('defaultImageModel'), 'image', s.defaultImageModel, true);
  fillModelSelect($('defaultVideoModel'), 'video', s.defaultVideoModel, true);
  $('concurrency').value = s.concurrency;
  renderModelTable();
}

$('saveSettings').addEventListener('click', async () => {
  state.settings = await saveSettings({
    anthropicKey: $('anthropicKey').value.trim(),
    higgsfieldKey: $('higgsfieldKey').value.trim(),
    claudeModel: $('claudeModel').value,
    defaultImageModel: $('defaultImageModel').value,
    defaultVideoModel: $('defaultVideoModel').value,
    concurrency: Math.min(10, Math.max(1, Math.round(Number($('concurrency').value) || 2))),
  });
  renderSettings();
  renderGenerateForm();
  toast('Settings saved.');
});

$('testClaude').addEventListener('click', async () => {
  const out = $('testClaudeResult');
  out.textContent = 'Testing...';
  try {
    await testClaude({ apiKey: $('anthropicKey').value.trim(), baseURL: state.settings.anthropicBaseUrl });
    out.textContent = '✓ Connected to Claude';
  } catch (err) {
    out.textContent = `✗ ${errText(err)}`;
  }
});

$('testHiggs').addEventListener('click', async () => {
  const out = $('testHiggsResult');
  out.textContent = 'Testing...';
  try {
    const client = new HiggsfieldClient({
      credentials: $('higgsfieldKey').value.trim(),
      baseURL: state.settings.higgsfieldBaseUrl || undefined,
    });
    await client.testConnection();
    out.textContent = '✓ Higgsfield accepted the key (no generation was started)';
  } catch (err) {
    out.textContent = `✗ ${errText(err)}`;
  }
});

function renderModelTable() {
  const models = state.settings.models || [];
  if (!models.length) {
    $('modelTable').replaceChildren(el('p', { className: 'muted', textContent: 'No models yet.' }));
    return;
  }
  const rows = models.map((m) => {
    const edit = el('button', { className: 'btn small', textContent: 'Edit' });
    edit.addEventListener('click', () => {
      $('mId').value = m.id;
      $('mName').value = m.name;
      $('mType').value = m.type;
      $('mPath').value = m.path;
      $('mOptions').value = JSON.stringify(m.options || {});
      $('mFixed').value = JSON.stringify(m.fixed || {});
      $('mPrice').value = m.price ?? '';
    });
    const del = el('button', { className: 'btn small danger', textContent: 'Delete' });
    del.addEventListener('click', async () => {
      if (!confirm(`Delete model "${m.name}"?`)) return;
      state.settings = await saveSettings({ models: models.filter((x) => x.id !== m.id) });
      renderSettings();
      renderGenerateForm();
    });
    return el('tr', {}, [
      el('td', { textContent: m.name }),
      el('td', { textContent: m.type }),
      el('td', {}, [el('code', { textContent: m.path })]),
      el('td', { textContent: m.price != null && m.price !== '' ? `$${m.price}` : '-' }),
      el('td', {}, [edit, ' ', del]),
    ]);
  });
  const head = el('tr', {}, ['Name', 'Type', 'Path', 'Price', ''].map((h) => el('th', { textContent: h })));
  $('modelTable').replaceChildren(el('table', {}, [el('thead', {}, [head]), el('tbody', {}, rows)]));
}

$('saveModel').addEventListener('click', async () => {
  $('modelError').textContent = '';
  let options;
  let fixed;
  try {
    options = JSON.parse($('mOptions').value || '{}');
    fixed = JSON.parse($('mFixed').value || '{}');
  } catch {
    $('modelError').textContent = 'Options / Always send must be valid JSON, e.g. {"aspect_ratio": ["16:9"]}';
    return;
  }
  const priceText = $('mPrice').value.trim();
  const model = {
    id: $('mId').value.trim(),
    name: $('mName').value.trim(),
    type: $('mType').value,
    path: $('mPath').value.trim().replace(/^\/+/, ''),
    options,
    fixed,
    price: priceText === '' ? null : Number(priceText),
  };
  const problem = validateModel(model);
  if (problem) {
    $('modelError').textContent = problem;
    return;
  }
  const others = (state.settings.models || []).filter((m) => m.id !== model.id);
  state.settings = await saveSettings({ models: [...others, model] });
  renderSettings();
  renderGenerateForm();
  toast(`Model "${model.name}" saved.`);
});
