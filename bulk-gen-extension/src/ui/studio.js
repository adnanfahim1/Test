// PikGen page: the main screen of the extension.
// It connects the form, Claude (prompt writing), Higgsfield (generation),
// the queue and your output folder.

import { loadSettings, saveSettings } from '../lib/settings.js';
import { kvGet, kvSet, saveBatch, listBatches, deleteBatch } from '../lib/db.js';
import { CLAUDE_MODELS, expandPrompts, promptsForImage, testClaude } from '../lib/claude.js';
import { HiggsfieldClient } from '../lib/higgsfield.js';
import { BatchSaver, folderPermission, requestFolderPermission } from '../lib/files.js';
import { QueueEngine, makeItems, countByStatus } from '../lib/queue.js';
import { validateModel, acceptsImage, IMAGE_FORMATS } from '../lib/models.js';
import { batchFolderName, slugify } from '../lib/util.js';

const $ = (id) => document.getElementById(id);
const MAX_QUANTITY = 500;
// Automated tests open the page with ?test=1 and use a private test folder instead of the folder picker.
const TEST_MODE = new URLSearchParams(location.search).get('test') === '1';

const state = {
  settings: null,
  kind: 'image',
  prompts: [],
  promptImages: [], // for each prompt: index into images, or null
  images: [], // reference images: { name, file, thumbUrl }
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
  // Plain-language versions of common account problems
  if (/credit balance is too low/i.test(text)) {
    return 'Your Claude API account has no credit. Buy API credits at platform.claude.com → Settings → Billing (a Claude.ai Pro/Max subscription does not cover the API). Or tick "Use my lines exactly" to skip Claude.';
  }
  if (/invalid x-api-key|authentication_error/i.test(text)) {
    return 'Claude rejected your API key. Check it in Settings (it starts with sk-ant-).';
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
  renderImages();
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
  renderImages();
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

// ------------------------------------------------------------------ reference images
// Your own pictures, used as the starting point for each generation
// (image-to-image or image-to-video). Pick files, or a whole folder.

const IMAGE_RE = /\.(png|jpe?g|webp)$/i;

function setImages(files) {
  for (const img of state.images) URL.revokeObjectURL(img.thumbUrl);
  const list = files
    .filter((f) => IMAGE_RE.test(f.name))
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
    .slice(0, MAX_QUANTITY);
  state.images = list.map((file) => ({ name: file.name, file, thumbUrl: URL.createObjectURL(file) }));
  if (files.length && !list.length) toast('No PNG, JPG or WEBP images found there.');
  state.prompts = [];
  state.promptImages = [];
  renderImages();
  renderPrompts();
}

$('pickImages').addEventListener('click', () => $('imageFiles').click());
$('imageFiles').addEventListener('change', () => {
  setImages([...$('imageFiles').files]);
  $('imageFiles').value = '';
});
$('pickImageFolder').addEventListener('click', async () => {
  if (!window.showDirectoryPicker) return toast('This browser does not support choosing a folder. Use "Choose images" instead.');
  try {
    const dir = await window.showDirectoryPicker({ id: 'bulk-input', mode: 'read' });
    const files = [];
    for await (const [name, handle] of dir.entries()) {
      if (handle.kind === 'file' && IMAGE_RE.test(name)) files.push(await handle.getFile());
    }
    setImages(files);
    if (files.length) toast(`Loaded ${state.images.length} images from "${dir.name}".`);
  } catch (err) {
    if (err?.name !== 'AbortError') toast(`Could not read that folder: ${errText(err)}`);
  }
});
$('clearImages').addEventListener('click', () => setImages([]));
for (const id of ['refMode', 'perImage', 'oneImage']) {
  $(id).addEventListener('change', () => {
    state.prompts = [];
    state.promptImages = [];
    renderImages();
    renderPrompts();
  });
}

function usingImages() {
  return state.images.length > 0;
}

function perImageCount() {
  return Math.min(20, Math.max(1, Math.round(Number($('perImage').value) || 1)));
}

function renderImages() {
  const has = usingImages();
  const model = currentModel();
  const mode = $('refMode').value;
  $('refCount').textContent = has ? `${state.images.length} image${state.images.length === 1 ? '' : 's'}` : 'optional';
  $('clearImages').hidden = !has;
  $('refOptions').hidden = !has;
  $('refUnsupported').hidden = !has || !model || acceptsImage(model);
  $('perImageLabel').hidden = mode !== 'each';
  $('oneImageLabel').hidden = mode !== 'one';
  // "How many" is worked out automatically in "each image" mode
  const auto = has && mode === 'each';
  $('quantity').disabled = auto;
  if (auto) $('quantity').value = Math.min(MAX_QUANTITY, state.images.length * perImageCount());

  const previous = $('oneImage').value;
  $('oneImage').replaceChildren(...state.images.map((img, i) => el('option', { value: String(i), textContent: img.name })));
  if (previous && Number(previous) < state.images.length) $('oneImage').value = previous;

  const shown = state.images.slice(0, 40).map((img) => el('img', { src: img.thumbUrl, alt: img.name, title: img.name, loading: 'lazy' }));
  if (state.images.length > 40) shown.push(el('span', { className: 'muted small', textContent: `+${state.images.length - 40} more` }));
  $('refStrip').replaceChildren(...shown);
}

/** Shrink a picture to max 1024px and return it as base64 JPEG for Claude to look at. */
async function imageForClaude(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 1024 / Math.max(bitmap.width, bitmap.height));
  const canvas = new OffscreenCanvas(Math.round(bitmap.width * scale), Math.round(bitmap.height * scale));
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return { mediaType: 'image/jpeg', data: btoa(binary) };
}

function claudeOptions() {
  return {
    apiKey: state.settings.anthropicKey,
    baseURL: state.settings.anthropicBaseUrl,
    model: state.settings.claudeModel,
    kind: state.kind,
    basePrompt: $('basePrompt').value.trim(),
    style: $('variation').value,
    rules: $('rules').value.trim(),
  };
}

/** Run async jobs a few at a time. */
async function runPool(items, limit, fn) {
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      await fn(items[i], i);
    }
  });
  await Promise.all(workers);
}

// ------------------------------------------------------------------ prompt preview

$('previewBtn').addEventListener('click', async () => {
  const base = $('basePrompt').value.trim();
  const images = usingImages();
  const mode = $('refMode').value;
  const claudeSees = images && $('claudeSees').checked;
  if (!base && !claudeSees) return toast('Type or paste a prompt first.');

  // Which image goes with each prompt (null = no image)
  let slots;
  if (images && mode === 'each') {
    const k = perImageCount();
    slots = state.images.flatMap((_, i) => Array(k).fill(i)).slice(0, MAX_QUANTITY);
  } else {
    const count = Math.min(MAX_QUANTITY, Math.max(1, Number($('quantity').value) || 1));
    slots = Array(count).fill(images ? Number($('oneImage').value || 0) : null);
  }

  if ($('useLines').checked) {
    const lines = base.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).slice(0, MAX_QUANTITY);
    if (!images) slots = Array(lines.length).fill(null);
    // with images: lines are reused in order if there are fewer lines than images
    state.prompts = slots.map((_, i) => lines[i % lines.length]);
    state.promptImages = slots;
    renderPrompts();
    return;
  }

  const abort = new AbortController();
  state.previewAbort = abort;
  $('previewBtn').disabled = true;
  $('stopPreviewBtn').hidden = false;
  const status = (done) => ($('previewStatus').textContent = `Claude is writing prompts... ${done} / ${slots.length}`);
  status(0);
  try {
    if (claudeSees) {
      // Claude looks at each picture and writes prompts that fit it.
      const prompts = Array(slots.length).fill('');
      const groups = new Map(); // image index -> positions that use it
      slots.forEach((img, pos) => groups.set(img, [...(groups.get(img) || []), pos]));
      let done = 0;
      await runPool([...groups.entries()], 3, async ([imgIndex, positions]) => {
        if (abort.signal.aborted) return;
        const picture = await imageForClaude(state.images[imgIndex].file);
        for (let start = 0; start < positions.length; start += 25) {
          if (abort.signal.aborted) return;
          const chunk = positions.slice(start, start + 25);
          const written = await promptsForImage({
            ...claudeOptions(),
            count: chunk.length,
            existing: chunk.length > 1 || start > 0 ? prompts.filter(Boolean).slice(-50) : [],
            image: picture,
          });
          chunk.forEach((pos, j) => (prompts[pos] = written[j] || written[0]));
          done += chunk.length;
          status(done);
        }
      });
      if (abort.signal.aborted) throw new Error('Stopped.');
      state.prompts = prompts;
    } else {
      state.prompts = await expandPrompts({
        ...claudeOptions(),
        count: slots.length,
        signal: abort.signal,
        onProgress: (done) => status(done),
      });
    }
    state.promptImages = slots;
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
  state.promptImages = [];
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
        state.promptImages.splice(i, 1);
        renderPrompts();
      });
      const parts = [text, regen, remove];
      const img = state.images[state.promptImages[i]];
      if (img) parts.unshift(el('img', { className: 'prompt-ref', src: img.thumbUrl, title: img.name, alt: img.name }));
      return el('li', {}, [el('div', { className: 'prompt-item' }, parts)]);
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
    const img = state.images[state.promptImages[index]];
    let fresh;
    if (img && $('claudeSees').checked) {
      [fresh] = await promptsForImage({ ...claudeOptions(), count: 1, existing: state.prompts, image: await imageForClaude(img.file) });
    } else {
      [fresh] = await expandPrompts({
        ...claudeOptions(),
        basePrompt: $('basePrompt').value.trim() || state.prompts[index],
        count: 1,
        existing: state.prompts,
      });
    }
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

/** Upload each reference image used by this batch once. Returns name -> public URL. */
async function uploadImages(api, indexes) {
  const urls = new Map();
  let done = 0;
  $('uploadPermBox').hidden = true;
  $('previewStatus').textContent = `Uploading reference images... 0 / ${indexes.length}`;
  await runPool(indexes, 3, async (i) => {
    const img = state.images[i];
    urls.set(i, await api.uploadImage(img.file));
    done += 1;
    $('previewStatus').textContent = `Uploading reference images... ${done} / ${indexes.length}`;
  });
  $('previewStatus').textContent = '';
  return urls;
}

$('uploadPermBtn').addEventListener('click', async () => {
  const origin = $('uploadPermBtn').dataset.origin;
  if (!origin) return;
  const granted = await chrome.permissions.request({ origins: [`${origin}/*`] }).catch(() => false);
  $('uploadPermBox').hidden = granted;
  toast(granted ? 'Allowed. Press Generate again.' : 'Permission was not granted.');
});

$('generateBtn').addEventListener('click', async () => {
  const model = currentModel();
  // keep each prompt paired with its image, dropping empty prompts
  const pairs = state.prompts
    .map((p, i) => ({ prompt: p.trim(), img: state.promptImages[i] ?? null }))
    .filter((pair) => pair.prompt);
  if (!model || !pairs.length) return;
  if (!state.settings.higgsfieldKey) return toast('Add your Higgsfield key in Settings first.');
  const withImages = pairs.some((p) => p.img !== null);
  if (withImages && !acceptsImage(model)) {
    return toast(`"${model.name}" has no image setting. Set its "Image field" in Settings, or clear the reference images.`, 8000);
  }
  const cost = model.price != null && model.price !== '' ? ` Estimated cost: about ${money(pairs.length * Number(model.price))}.` : '';
  const imgNote = withImages ? ` Uses ${new Set(pairs.map((p) => p.img)).size} reference image(s).` : '';
  if (!confirm(`Start ${pairs.length} ${state.kind} generations with "${model.name}"?${imgNote}${cost}`)) return;

  try {
    await ensureFolderAccess();
    let imageUrls = new Map();
    if (withImages) {
      const api = new HiggsfieldClient({
        credentials: state.settings.higgsfieldKey,
        baseURL: state.settings.higgsfieldBaseUrl || undefined,
      });
      $('generateBtn').disabled = true;
      try {
        imageUrls = await uploadImages(api, [...new Set(pairs.map((p) => p.img))]);
      } catch (err) {
        if (err.origin) {
          $('uploadPermBtn').dataset.origin = err.origin;
          $('uploadPermOrigin').textContent = err.origin;
          $('uploadPermBox').hidden = false;
        }
        throw err;
      } finally {
        updateGenerateButton();
      }
    }
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
      imageField: withImages ? model.imageField : '',
      imageFormat: withImages ? model.imageFormat : '',
      noPrompt: Boolean(model.noPrompt),
      basePrompt: $('basePrompt').value.trim(),
      items: makeItems(
        pairs.map((p) => p.prompt),
        pairs.map((p) => (p.img === null ? null : { name: state.images[p.img].name, url: imageUrls.get(p.img) })),
      ),
      createdAt: new Date().toISOString(),
      state: 'idle',
    };
    await saveBatch(batch);
    openBatch(batch);
    state.engine.start();
    await state.engine.writeManifestNow();
    state.prompts = [];
    state.promptImages = [];
    renderPrompts();
  } catch (err) {
    $('previewStatus').textContent = '';
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
  $('mImageFormat').replaceChildren(...Object.entries(IMAGE_FORMATS).map(([k, label]) => el('option', { value: k, textContent: label })));
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
      $('mImageField').value = m.imageField || '';
      $('mImageFormat').value = m.imageFormat || 'url';
      $('mNoPrompt').checked = Boolean(m.noPrompt);
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
      el('td', { textContent: m.imageField ? `${m.imageField} (${m.imageFormat})` : '-' }),
      el('td', {}, [edit, ' ', del]),
    ]);
  });
  const head = el('tr', {}, ['Name', 'Type', 'Path', 'Price', 'Image', ''].map((h) => el('th', { textContent: h })));
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
    imageField: $('mImageField').value.trim(),
    imageFormat: $('mImageField').value.trim() ? $('mImageFormat').value : '',
    noPrompt: $('mNoPrompt').checked,
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
