// Saving files into the folder you picked, using the browser's File System Access API.
//
// Layout:
//   <your folder>/<batch-name>_<date>/images/<batch>_<001>_<prompt-slug>.png
//   <your folder>/<batch-name>_<date>/videos/<batch>_<001>_<prompt-slug>.mp4
//   <your folder>/<batch-name>_<date>/manifest.csv

import { pickExtension, resultFileName, toCsv } from './util.js';

export const MANIFEST_HEADER = ['number', 'final_prompt', 'model', 'settings', 'status', 'file_name', 'error', 'time'];

/** Is the folder still allowed? Returns "granted", "prompt" or "denied". */
export async function folderPermission(handle) {
  if (!handle?.queryPermission) return 'granted'; // e.g. test folders that need no permission
  return handle.queryPermission({ mode: 'readwrite' });
}

/** Ask for permission again (must be called from a button click). */
export async function requestFolderPermission(handle) {
  if (!handle?.requestPermission) return 'granted';
  return handle.requestPermission({ mode: 'readwrite' });
}

/** Write a Blob or string to a file inside a directory handle. */
export async function writeFile(dirHandle, name, data) {
  const fileHandle = await dirHandle.getFileHandle(name, { create: true });
  const writable = await fileHandle.createWritable();
  try {
    await writable.write(data);
    await writable.close();
  } catch (err) {
    await writable.abort?.().catch(() => {});
    throw err;
  }
}

/** A "download failed" error that says whether a permission is the likely cause. */
export class DownloadError extends Error {
  constructor(message, { origin, maybePermission = false } = {}) {
    super(message);
    this.name = 'DownloadError';
    this.origin = origin;
    this.maybePermission = maybePermission;
  }
}

export class BatchSaver {
  /**
   * @param {FileSystemDirectoryHandle} rootHandle  the folder you picked
   * @param {object} batch
   * @param {typeof fetch} [fetchImpl]
   */
  constructor(rootHandle, batch, fetchImpl) {
    this.root = rootHandle;
    this.batch = batch;
    this.fetch = fetchImpl || globalThis.fetch.bind(globalThis);
    this.batchDir = null;
    this.mediaDir = null;
  }

  async ensureDirs() {
    if (this.mediaDir) return;
    this.batchDir = await this.root.getDirectoryHandle(this.batch.folderName, { create: true });
    const sub = this.batch.kind === 'video' ? 'videos' : 'images';
    this.mediaDir = await this.batchDir.getDirectoryHandle(sub, { create: true });
  }

  /** Download one result URL and save it. Returns the saved file name. */
  async saveResult(item, url, index = 0) {
    await this.ensureDirs();
    let response;
    try {
      response = await this.fetch(url);
    } catch (err) {
      let origin;
      try {
        origin = new URL(url).origin;
      } catch {
        origin = undefined;
      }
      // A browser "TypeError: Failed to fetch" here usually means the file server
      // needs the extension to be allowed to download from it.
      throw new DownloadError(`Could not download the result (${err?.message || 'network error'}).`, {
        origin,
        maybePermission: true,
      });
    }
    if (!response.ok) {
      throw new DownloadError(`Download failed with HTTP ${response.status}. The link may have expired.`);
    }
    const blob = await response.blob();
    const ext = pickExtension(response.headers.get('content-type'), url, this.batch.kind);
    const name = resultFileName(this.batch.name, item.n, item.prompt, ext, index);
    await writeFile(this.mediaDir, name, blob);
    return { name, blob };
  }

  /** Read back a saved file (used to show thumbnails after a restart). */
  async readResult(name) {
    await this.ensureDirs();
    const handle = await this.mediaDir.getFileHandle(name);
    return handle.getFile();
  }

  /** Rewrite manifest.csv with the current state of every item. */
  async writeManifest() {
    await this.ensureDirs();
    const settings = JSON.stringify(this.batch.params || {});
    const rows = this.batch.items.map((item) => [
      item.n,
      item.prompt,
      this.batch.modelName || this.batch.modelPath,
      settings,
      item.status,
      (item.files || []).join(' | '),
      item.error || '',
      item.finishedAt || item.submittedAt || '',
    ]);
    // The "﻿" at the start makes Excel open the file as UTF-8.
    await writeFile(this.batchDir, 'manifest.csv', '﻿' + toCsv(MANIFEST_HEADER, rows));
  }
}
