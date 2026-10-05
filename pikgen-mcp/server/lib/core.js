// PikGen's PC-side work for Claude Desktop:
//  - list the pictures in a folder
//  - upload pictures to the upload links Higgsfield's connector gives Claude
//  - create a batch folder, remember which job is which, download finished files
//  - keep manifest.csv up to date
//
// Generation itself is done by Higgsfield's official connector in Claude Desktop
// (it uses your Higgsfield plan credits). PikGen never sees your Higgsfield or Claude login.

import fs from 'node:fs/promises';
import path from 'node:path';
import { dateStamp, pickExtension, resultFileName, slugify, toCsv, TYPE_BY_EXT } from './util.js';

export const BATCH_FILE = 'pikgen-batch.json';
export const MANIFEST_HEADER = ['number', 'final_prompt', 'model', 'settings', 'status', 'file_name', 'error', 'time', 'reference_image', 'job_id'];
const IMAGE_RE = /\.(png|jpe?g|webp)$/i;
const MAX_DOWNLOAD_BYTES = 500 * 1024 * 1024;

/** Only Higgsfield's own upload servers may receive your files. */
export function isHiggsfieldUploadUrl(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' && (u.hostname === 'higgsfield.ai' || u.hostname.endsWith('.higgsfield.ai'));
  } catch {
    return false;
  }
}

/** Run async work a few at a time. */
async function pool(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i], i);
      }
    }),
  );
  return out;
}

// One batch file is updated by one operation at a time.
const locks = new Map();
function withLock(key, fn) {
  const prev = locks.get(key) || Promise.resolve();
  const run = prev.then(fn, fn);
  locks.set(key, run.catch(() => {}));
  return run;
}

export class PikGen {
  /** @param {{ folders: import('./folders.js').Folders, fetchImpl?: typeof fetch }} deps */
  constructor({ folders, fetchImpl }) {
    this.folders = folders;
    this.fetch = fetchImpl || globalThis.fetch.bind(globalThis);
  }

  // ---------------------------------------------------------------- pictures

  async listImages({ folder, recursive = false, limit = 500 }) {
    const root = await this.folders.check(folder);
    const found = [];
    const walk = async (dir) => {
      const entries = await fs.readdir(dir, { withFileTypes: true });
      entries.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
      for (const e of entries) {
        if (found.length >= limit) return;
        const full = path.join(dir, e.name);
        if (e.isDirectory() && recursive) await walk(full);
        else if (e.isFile() && IMAGE_RE.test(e.name)) {
          const st = await fs.stat(full);
          const ext = path.extname(e.name).slice(1).toLowerCase();
          found.push({ path: full, name: e.name, size_kb: Math.round(st.size / 1024), content_type: TYPE_BY_EXT[ext] });
        }
      }
    };
    await walk(root);
    return { folder: root, count: found.length, images: found };
  }

  /**
   * PUT local files to Higgsfield upload links (from the connector's media_upload tool).
   * Follows the connector's own instructions: PUT with Content-Type. If the storage
   * server insists on the If-None-Match header it signed, we retry once with it.
   */
  async uploadFiles({ uploads }) {
    if (!Array.isArray(uploads) || !uploads.length) throw new Error('Nothing to upload.');
    if (uploads.length > 20) throw new Error('Upload at most 20 files per call (that is the most media_upload gives at once).');
    const results = await pool(uploads, 4, async (u) => {
      try {
        if (!isHiggsfieldUploadUrl(u.upload_url)) throw new Error('For safety PikGen only uploads to Higgsfield (https://*.higgsfield.ai) links.');
        const file = await this.folders.check(u.path);
        const bytes = await fs.readFile(file);
        const ext = path.extname(file).slice(1).toLowerCase();
        const contentType = u.content_type || TYPE_BY_EXT[ext] || 'application/octet-stream';
        const put = (extra = {}) =>
          this.fetch(u.upload_url, { method: 'PUT', headers: { 'Content-Type': contentType, ...extra }, body: bytes });
        let res = await put();
        if (res.status === 403 || res.status === 412) res = await put({ 'If-None-Match': '*' });
        if (!res.ok) throw new Error(`Upload failed with HTTP ${res.status}`);
        return { path: u.path, ok: true, media_id: u.media_id || null, bytes: bytes.length };
      } catch (err) {
        return { path: u.path, ok: false, media_id: u.media_id || null, error: err.message };
      }
    });
    const ok = results.filter((r) => r.ok).length;
    return {
      uploaded: ok,
      failed: results.length - ok,
      results,
      next_step: ok ? 'Call Higgsfield media_confirm with the media_ids of the files that uploaded OK.' : 'Fix the errors and try again.',
    };
  }

  // ---------------------------------------------------------------- batches

  async readBatch(batchFolder) {
    const dir = await this.folders.check(batchFolder);
    const raw = await fs.readFile(path.join(dir, BATCH_FILE), 'utf8').catch(() => {
      throw new Error(`No PikGen batch in "${batchFolder}". Use pikgen_start_batch first, or pikgen_list_batches to find it.`);
    });
    return { dir, batch: JSON.parse(raw) };
  }

  async writeBatch(dir, batch) {
    batch.updated_at = new Date().toISOString();
    const tmp = path.join(dir, `${BATCH_FILE}.tmp`);
    await fs.writeFile(tmp, JSON.stringify(batch, null, 2));
    await fs.rename(tmp, path.join(dir, BATCH_FILE));
    const settings = JSON.stringify(batch.settings || {});
    const rows = batch.items.map((it) => [
      it.number, it.prompt, batch.model, settings, it.status, (it.files || []).join(' | '), it.error || '', it.time || '', it.reference_image || '', it.job_id || '',
    ]);
    // "﻿" makes Excel open the file as UTF-8
    await fs.writeFile(path.join(dir, 'manifest.csv'), '﻿' + toCsv(MANIFEST_HEADER, rows));
  }

  async startBatch({ output_folder, name, kind = 'image', model, settings = {}, items }) {
    if (!['image', 'video'].includes(kind)) throw new Error('kind must be "image" or "video".');
    if (!model) throw new Error('model is required (the Higgsfield model id you will use).');
    if (!Array.isArray(items) || !items.length) throw new Error('items must list at least one prompt.');
    if (items.length > 1000) throw new Error('At most 1000 items per batch.');
    const out = await this.folders.check(output_folder);
    await fs.mkdir(out, { recursive: true });
    const base = `${slugify(name || items[0].prompt || 'batch', 30)}_${dateStamp()}`;
    let folderName = base;
    for (let i = 2; ; i += 1) {
      try {
        await fs.access(path.join(out, folderName));
        folderName = `${base}-${i}`; // don't mix two batches in one folder
      } catch {
        break;
      }
    }
    const dir = path.join(out, folderName);
    await fs.mkdir(path.join(dir, kind === 'video' ? 'videos' : 'images'), { recursive: true });
    const batch = {
      version: 1,
      name: slugify(name || items[0].prompt || 'batch', 30),
      kind,
      model,
      settings,
      created_at: new Date().toISOString(),
      items: items.map((it, i) => ({
        number: it.number ?? i + 1,
        prompt: String(it.prompt ?? ''),
        reference_image: it.reference_image || '',
        status: 'pending',
        files: [],
      })),
    };
    const numbers = new Set(batch.items.map((it) => it.number));
    if (numbers.size !== batch.items.length) throw new Error('Item numbers must be unique.');
    await this.writeBatch(dir, batch);
    return { batch_folder: dir, items: batch.items.length, kind, next_step: 'Submit jobs with Higgsfield (12 at a time), then call pikgen_record_jobs with the job ids.' };
  }

  async recordJobs({ batch_folder, jobs }) {
    return withLock(batch_folder, async () => {
      const { dir, batch } = await this.readBatch(batch_folder);
      let recorded = 0;
      for (const j of jobs || []) {
        const item = batch.items.find((it) => it.number === j.number);
        if (!item) continue;
        if (j.job_id) {
          item.job_id = j.job_id;
          item.status = 'submitted';
          item.error = '';
          recorded += 1;
        } else if (j.error) {
          item.status = 'failed';
          item.error = String(j.error);
        }
        item.time = new Date().toISOString();
      }
      await this.writeBatch(dir, batch);
      return { recorded, summary: summarize(batch) };
    });
  }

  async saveResults({ batch_folder, results }) {
    return withLock(batch_folder, async () => {
      const { dir, batch } = await this.readBatch(batch_folder);
      const mediaDir = path.join(dir, batch.kind === 'video' ? 'videos' : 'images');
      await fs.mkdir(mediaDir, { recursive: true });
      const report = await pool(results || [], 4, async (r) => {
        const item = batch.items.find((it) => it.number === r.number) || batch.items.find((it) => r.job_id && it.job_id === r.job_id);
        if (!item) return { number: r.number, ok: false, error: 'No such item number in this batch.' };
        if (r.job_id) item.job_id = r.job_id;
        item.time = new Date().toISOString();
        const status = String(r.status || 'completed').toLowerCase();
        if (status !== 'completed') {
          item.status = 'failed';
          item.error = r.error || `Higgsfield status: ${status}`;
          return { number: item.number, ok: false, error: item.error };
        }
        const urls = (r.urls || (r.url ? [r.url] : [])).filter(Boolean);
        if (!urls.length) {
          item.status = 'failed';
          item.error = 'Completed but no result URL was given.';
          return { number: item.number, ok: false, error: item.error };
        }
        try {
          item.files = item.files || [];
          for (let i = 0; i < urls.length; i += 1) {
            if (item.files[i]) continue; // saved before
            const url = urls[i];
            if (!/^https:\/\//i.test(url)) throw new Error('Result URL must start with https://');
            const res = await this.fetch(url);
            if (!res.ok) throw new Error(`Download failed with HTTP ${res.status} (the link may have expired).`);
            const size = Number(res.headers.get('content-length') || 0);
            if (size > MAX_DOWNLOAD_BYTES) throw new Error('File is larger than 500 MB.');
            const bytes = Buffer.from(await res.arrayBuffer());
            const ext = pickExtension(res.headers.get('content-type'), url, batch.kind);
            const name = resultFileName(batch.name, item.number, item.prompt, ext, i);
            await fs.writeFile(path.join(mediaDir, name), bytes);
            item.files[i] = name;
          }
          item.status = 'done';
          item.error = '';
          return { number: item.number, ok: true, files: item.files };
        } catch (err) {
          item.status = 'failed';
          item.error = err.message;
          item.result_urls = urls; // kept so the download can be retried without regenerating
          return { number: item.number, ok: false, error: err.message };
        }
      });
      await this.writeBatch(dir, batch);
      return { saved: report.filter((r) => r.ok).length, failed: report.filter((r) => !r.ok).length, report, summary: summarize(batch), folder: mediaDir };
    });
  }

  async batchStatus({ batch_folder }) {
    const { dir, batch } = await this.readBatch(batch_folder);
    return { batch_folder: dir, name: batch.name, kind: batch.kind, model: batch.model, settings: batch.settings, summary: summarize(batch), ...details(batch) };
  }

  async listBatches({ output_folder }) {
    const out = await this.folders.check(output_folder);
    const entries = await fs.readdir(out, { withFileTypes: true }).catch(() => []);
    const batches = [];
    for (const e of entries) {
      if (!e.isDirectory()) continue;
      try {
        const batch = JSON.parse(await fs.readFile(path.join(out, e.name, BATCH_FILE), 'utf8'));
        batches.push({ batch_folder: path.join(out, e.name), name: batch.name, kind: batch.kind, model: batch.model, created_at: batch.created_at, summary: summarize(batch) });
      } catch {
        /* not a PikGen batch */
      }
    }
    batches.sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)));
    return { output_folder: out, batches };
  }
}

export function summarize(batch) {
  const s = { total: batch.items.length, pending: 0, submitted: 0, done: 0, failed: 0 };
  for (const it of batch.items) s[it.status] = (s[it.status] || 0) + 1;
  return s;
}

function details(batch) {
  return {
    pending: batch.items.filter((it) => it.status === 'pending').map((it) => ({ number: it.number, prompt: it.prompt, reference_image: it.reference_image || undefined })),
    submitted_not_saved: batch.items.filter((it) => it.status === 'submitted').map((it) => ({ number: it.number, job_id: it.job_id })),
    failed: batch.items.filter((it) => it.status === 'failed').map((it) => ({ number: it.number, error: it.error, job_id: it.job_id, can_redownload: Boolean(it.result_urls?.length) })),
  };
}
