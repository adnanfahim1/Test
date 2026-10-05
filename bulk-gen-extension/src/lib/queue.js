// The bulk queue: sends prompts to Higgsfield a few at a time, checks on them,
// downloads finished files, retries failures, and keeps manifest.csv up to date.
//
// It is driven by a "tick" (called about once a second by the Studio page).
// Each tick it: checks running jobs, saves finished ones, and starts new ones
// if there is a free slot. All state lives on the `batch` object, which is
// saved to browser storage so a batch can be resumed after a restart.

import { HiggsfieldError, resultUrls } from './higgsfield.js';
import { DownloadError } from './files.js';
import { backoffDelay, jitter } from './util.js';

export const MAX_AUTO_RETRIES = 2; // failed generations are retried twice automatically
const MAX_TRANSIENT_ERRORS = 8; // per item: network/server/rate-limit errors before giving up
const MAX_RUN_MS = 60 * 60 * 1000; // a single generation taking over 1 hour is treated as failed
const POLL_MS = { image: 4000, video: 10000 };
const RECOVER_MS = 30000; // after a rate limit, add back one parallel job every 30s

const ACTIVE = new Set(['submitting', 'running', 'saving']);
const TERMINAL = new Set(['done', 'failed', 'canceled']);

/** Create the items list for a new batch. */
export function makeItems(prompts) {
  return prompts.map((prompt, i) => ({
    n: i + 1,
    prompt,
    status: 'queued',
    failures: 0,
    transient: 0,
    files: [],
    error: '',
  }));
}

/** Count items by status, for the progress bar. */
export function countByStatus(batch) {
  const counts = { queued: 0, running: 0, done: 0, failed: 0, canceled: 0 };
  for (const item of batch.items) {
    if (item.status === 'queued') counts.queued += 1;
    else if (ACTIVE.has(item.status)) counts.running += 1;
    else if (counts[item.status] !== undefined) counts[item.status] += 1;
  }
  return counts;
}

export class QueueEngine {
  /**
   * @param {object} deps
   * @param {object} deps.batch     the batch (items + settings), mutated in place
   * @param {object} deps.api       HiggsfieldClient (submit / status / cancel)
   * @param {object} deps.saver     BatchSaver (saveResult / writeManifest)
   * @param {(immediate?:boolean)=>Promise<void>|void} [deps.persist]  save batch to storage
   * @param {()=>void} [deps.onChange]        UI refresh
   * @param {(item, blob, index)=>void} [deps.onResult] thumbnail hook
   * @param {number} [deps.concurrency]
   * @param {()=>number} [deps.now]
   */
  constructor({ batch, api, saver, persist, onChange, onResult, concurrency = 2, now = Date.now }) {
    this.batch = batch;
    this.api = api;
    this.saver = saver;
    this.persist = persist || (() => {});
    this.onChange = onChange || (() => {});
    this.onResult = onResult || (() => {});
    this.now = now;
    this.maxConcurrency = Math.max(1, concurrency);
    this.concurrency = this.maxConcurrency;
    this.busy = new Set(); // item numbers with an operation in progress
    this.pending = new Set(); // promises of operations in progress (used by tests)
    this.backoffUntil = 0;
    this.backoffCount = 0;
    this.ticking = false;
    this.manifestTimer = null;

    // Coming back after a restart: a job caught mid-"submitting" never got a
    // request id saved, so it goes back in the queue. Running jobs are re-checked now.
    for (const item of batch.items) {
      if (item.status === 'submitting') item.status = 'queued';
      if (item.status === 'running') item.nextPollAt = 0;
    }
    batch.state = batch.state || 'idle';
  }

  get pollInterval() {
    return POLL_MS[this.batch.kind] || POLL_MS.image;
  }

  changed(immediate = false) {
    this.persist(immediate);
    this.onChange();
  }

  // ---- controls ---------------------------------------------------------

  start() {
    this.batch.state = 'running';
    this.batch.error = '';
    this.backoffUntil = 0;
    this.changed(true);
  }

  /** Stop starting new jobs. Jobs already at Higgsfield still finish and get saved. */
  pause() {
    if (this.batch.state === 'running') this.batch.state = 'paused';
    this.changed(true);
  }

  /** Cancel everything not yet finished. Jobs Higgsfield already started can't be cancelled; they are still saved. */
  async cancel() {
    this.batch.state = 'canceled';
    const cancels = [];
    for (const item of this.batch.items) {
      if (item.status === 'queued') {
        item.status = 'canceled';
      } else if (item.status === 'running' && item.requestId && item.hfStatus === 'queued') {
        cancels.push(
          this.api
            .cancel(item.requestId)
            .then(() => {
              item.status = 'canceled';
              item.error = 'Canceled before it started.';
            })
            .catch(() => {
              /* already started processing - it will finish and be saved */
            }),
        );
      }
    }
    await Promise.all(cancels);
    this.scheduleManifest(0);
    this.changed(true);
  }

  /** "Retry failed": failed downloads are re-downloaded (no new cost); failed generations go back in the queue. */
  retryFailed() {
    for (const item of this.batch.items) {
      if (item.status !== 'failed') continue;
      item.error = '';
      item.transient = 0;
      if (item.downloadFailed && item.resultUrls?.length) {
        item.status = 'saving';
      } else {
        item.status = 'queued';
        item.failures = 0;
        item.requestId = null;
      }
      item.downloadFailed = false;
    }
    this.batch.needsPermissionFor = '';
    this.start();
  }

  /** Retry just one item. */
  retryItem(n) {
    const item = this.batch.items.find((it) => it.n === n);
    if (!item || item.status !== 'failed') return;
    item.error = '';
    item.transient = 0;
    if (item.downloadFailed && item.resultUrls?.length) item.status = 'saving';
    else Object.assign(item, { status: 'queued', failures: 0, requestId: null });
    item.downloadFailed = false;
    if (this.batch.state !== 'running') this.start();
    else this.changed(true);
  }

  stopWith(message) {
    this.batch.state = 'stopped';
    this.batch.error = message;
    this.changed(true);
  }

  // ---- the heartbeat ---------------------------------------------------

  /** Called about once a second. Never throws. */
  tick() {
    if (this.ticking) return;
    this.ticking = true;
    try {
      const now = this.now();
      const items = this.batch.items;

      // 1) check on running jobs, and save finished ones (also while paused)
      for (const item of items) {
        if (this.busy.has(item.n)) continue;
        if (item.status === 'running' && (item.nextPollAt || 0) <= now && this.batch.state !== 'stopped') {
          this.run(item, () => this.poll(item));
        } else if (item.status === 'saving' && ['running', 'paused', 'canceled'].includes(this.batch.state)) {
          this.run(item, () => this.save(item));
        }
      }

      // 2) after slowing down for a rate limit, speed back up by one job every 30s without a new limit
      if (this.concurrency < this.maxConcurrency && now - Math.max(this.lastSlowdown ?? 0, this.lastSpeedup ?? 0) > RECOVER_MS) {
        this.concurrency += 1;
        this.lastSpeedup = now;
      }

      // 3) start new jobs if there is a free slot
      if (this.batch.state === 'running' && now >= this.backoffUntil) {
        let active = items.filter((it) => ACTIVE.has(it.status)).length;
        for (const item of items) {
          if (active >= this.concurrency) break;
          if (item.status !== 'queued' || this.busy.has(item.n)) continue;
          if (item.retryAt && item.retryAt > now) continue;
          item.status = 'submitting';
          active += 1;
          this.run(item, () => this.submit(item));
        }
      }

      // 4) finished?
      const allDone = items.every((it) => TERMINAL.has(it.status)) && this.busy.size === 0;
      if (allDone && ['running', 'paused'].includes(this.batch.state)) {
        this.batch.state = 'finished';
        this.batch.finishedAt = new Date().toISOString();
        this.batch.notice = '';
        this.scheduleManifest(0);
        this.changed(true);
      }
    } finally {
      this.ticking = false;
    }
  }

  /** Run one async operation for an item, making sure errors never escape. */
  run(item, fn) {
    this.busy.add(item.n);
    const promise = (async () => {
      try {
        await fn();
      } catch (err) {
        // Safety net: an unexpected bug fails just this item, not the whole batch.
        item.status = 'failed';
        item.error = `Unexpected error: ${err?.message || err}`;
        this.scheduleManifest();
      } finally {
        this.busy.delete(item.n);
        this.changed();
      }
    })();
    this.pending.add(promise);
    promise.finally(() => this.pending.delete(promise));
    return promise;
  }

  // ---- steps -------------------------------------------------------------

  async submit(item) {
    item.status = 'submitting';
    this.onChange();
    try {
      const params = { ...(this.batch.params || {}), prompt: item.prompt };
      const res = await this.api.submit(this.batch.modelPath, params);
      Object.assign(item, {
        requestId: res.request_id,
        hfStatus: res.status || 'queued',
        status: 'running',
        submittedAt: new Date().toISOString(),
        startedAtMs: this.now(),
        nextPollAt: this.now() + this.pollInterval,
        pollErrors: 0,
      });
      this.backoffCount = 0;
      this.batch.notice = '';
      this.changed(true); // save immediately so the request id survives a crash
    } catch (err) {
      this.handleSubmitError(item, err);
    }
  }

  handleSubmitError(item, err) {
    const kind = err instanceof HiggsfieldError ? err.kind : 'input';
    const message = err?.message || String(err);
    if (kind === 'auth' || kind === 'credits') {
      item.status = 'queued';
      this.stopWith(
        kind === 'auth'
          ? `Higgsfield rejected your key. Check it in Settings, then press Resume. (${message})`
          : `Higgsfield says there are not enough credits. Top up, then press Resume. (${message})`,
      );
      return;
    }
    if (kind === 'busy' || kind === 'server') {
      item.status = 'queued';
      item.transient = (item.transient || 0) + 1;
      if (kind === 'busy') {
        // Several jobs often hit the same limit at once: only slow down once per 10 seconds.
        if (this.now() - (this.lastSlowdown ?? -Infinity) > 10000) {
          this.concurrency = Math.max(1, this.concurrency - 1);
          this.lastSlowdown = this.now();
        }
      }
      const delay = err.retryAfterMs || backoffDelay(this.backoffCount);
      this.backoffCount += 1;
      this.backoffUntil = this.now() + delay;
      this.batch.notice = `${kind === 'busy' ? 'Higgsfield is busy (limit reached)' : 'Temporary problem'} - waiting ${Math.round(delay / 1000)}s before continuing.`;
      if (item.transient > MAX_TRANSIENT_ERRORS) {
        item.status = 'failed';
        item.error = `Gave up after repeated errors: ${message}`;
        this.scheduleManifest();
      }
      return;
    }
    // "input": Higgsfield rejected this prompt or the settings. Retrying the same thing won't help.
    item.status = 'failed';
    item.error = message;
    this.scheduleManifest();
  }

  async poll(item) {
    if (item.startedAtMs && this.now() - item.startedAtMs > MAX_RUN_MS) {
      this.generationFailed(item, 'Timed out (over 1 hour)');
      return;
    }
    let data;
    try {
      data = await this.api.status(item.requestId);
    } catch (err) {
      const kind = err instanceof HiggsfieldError ? err.kind : 'server';
      if (kind === 'auth' || kind === 'credits') {
        item.nextPollAt = this.now() + 30000;
        this.stopWith(`Higgsfield rejected your key while checking a job: ${err.message}`);
      } else if (kind === 'notfound') {
        this.generationFailed(item, 'Higgsfield no longer knows this request');
      } else {
        item.pollErrors = (item.pollErrors || 0) + 1;
        item.nextPollAt = this.now() + Math.max(err.retryAfterMs || 0, backoffDelay(item.pollErrors, 4000, 60000));
      }
      return;
    }

    item.hfStatus = data?.status;
    item.pollErrors = 0;
    switch (data?.status) {
      case 'completed': {
        const urls = resultUrls(data);
        if (!urls.length) {
          this.generationFailed(item, 'Higgsfield said "completed" but returned no files');
          return;
        }
        item.resultUrls = urls;
        item.status = 'saving';
        this.changed(true);
        await this.save(item);
        return;
      }
      case 'failed':
        this.generationFailed(item, 'Higgsfield reported the generation failed');
        return;
      case 'nsfw':
        this.generationFailed(item, 'Blocked by Higgsfield moderation (nsfw)');
        return;
      case 'canceled':
      case 'cancelled':
        if (this.batch.state === 'canceled') {
          item.status = 'canceled';
          this.scheduleManifest();
        } else {
          this.generationFailed(item, 'Canceled by Higgsfield');
        }
        return;
      default: // queued / in_progress: check again later
        item.nextPollAt = this.now() + this.pollInterval + jitter(2000);
    }
  }

  /** A generation did not produce a result: retry up to MAX_AUTO_RETRIES times. */
  generationFailed(item, reason) {
    item.failures = (item.failures || 0) + 1;
    item.requestId = null;
    item.resultUrls = null;
    if (item.failures <= MAX_AUTO_RETRIES && this.batch.state !== 'canceled') {
      item.status = 'queued';
      item.error = `${reason} - automatic retry ${item.failures} of ${MAX_AUTO_RETRIES}`;
    } else {
      item.status = this.batch.state === 'canceled' ? 'canceled' : 'failed';
      item.error = reason;
      this.scheduleManifest();
    }
    this.changed(true);
  }

  async save(item) {
    item.files = item.files || [];
    try {
      for (let i = 0; i < item.resultUrls.length; i += 1) {
        if (item.files[i]) continue; // already saved before a restart
        const { name, blob } = await this.saver.saveResult(item, item.resultUrls[i], i);
        item.files[i] = name;
        this.onResult(item, blob, i);
      }
      item.status = 'done';
      item.error = '';
      item.downloadFailed = false;
      item.finishedAt = new Date().toISOString();
    } catch (err) {
      item.status = 'failed';
      item.downloadFailed = true;
      item.error = err?.message || String(err);
      if (err instanceof DownloadError && err.maybePermission && err.origin) {
        this.batch.needsPermissionFor = err.origin;
      }
      if (err?.name === 'NotAllowedError' || err?.name === 'SecurityError') {
        this.stopWith('The browser no longer allows writing to your folder. Press Resume and allow access again.');
      }
    }
    this.scheduleManifest();
    this.changed(true);
  }

  // ---- manifest ---------------------------------------------------------

  /** Rewrite manifest.csv soon (several changes close together = one write). */
  scheduleManifest(delay = 1500) {
    clearTimeout(this.manifestTimer);
    this.manifestTimer = setTimeout(() => {
      this.writeManifestNow();
    }, delay);
  }

  async writeManifestNow() {
    clearTimeout(this.manifestTimer);
    try {
      await this.saver.writeManifest();
    } catch (err) {
      this.batch.notice = `Could not update manifest.csv: ${err?.message || err}`;
      this.onChange();
    }
  }

  /** For tests: wait until all in-flight operations finish. */
  async settle() {
    while (this.pending.size) await Promise.all([...this.pending]);
  }
}
