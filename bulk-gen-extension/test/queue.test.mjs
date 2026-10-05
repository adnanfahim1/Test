import test from 'node:test';
import assert from 'node:assert/strict';
import { QueueEngine, makeItems, countByStatus } from '../src/lib/queue.js';
import { HiggsfieldError } from '../src/lib/higgsfield.js';
import { DownloadError } from '../src/lib/files.js';

// A fake Higgsfield: each request completes after `pollsUntilDone` status checks.
function fakeApi({ pollsUntilDone = 1, failPrompts = {}, submitErrors = [] } = {}) {
  let id = 0;
  const jobs = new Map();
  const api = {
    submitted: 0,
    maxInFlight: 0,
    inFlight: 0,
    async submit(path, params) {
      if (submitErrors.length) throw submitErrors.shift();
      api.submitted += 1;
      const rid = `r${++id}`;
      jobs.set(rid, { polls: 0, prompt: params.prompt });
      api.inFlight += 1;
      api.maxInFlight = Math.max(api.maxInFlight, api.inFlight);
      return { request_id: rid, status: 'queued' };
    },
    async status(rid) {
      const job = jobs.get(rid);
      job.polls += 1;
      if (job.polls < pollsUntilDone) return { status: 'in_progress' };
      api.inFlight -= 1;
      const fail = failPrompts[job.prompt];
      if (fail && fail.count > 0) {
        fail.count -= 1;
        return { status: fail.status || 'failed' };
      }
      return { status: 'completed', images: [{ url: `https://cdn.test/${rid}.png` }] };
    },
    async cancel() {},
  };
  return api;
}

function fakeSaver({ failDownloads = 0 } = {}) {
  return {
    saved: [],
    manifests: 0,
    async saveResult(item, url, i) {
      if (failDownloads > 0) {
        failDownloads -= 1;
        throw new DownloadError('blocked', { origin: 'https://cdn.test', maybePermission: true });
      }
      const name = `f${item.n}-${i}.png`;
      this.saved.push(name);
      return { name, blob: null };
    },
    async writeManifest() {
      this.manifests += 1;
    },
  };
}

// Drive the engine with a fake clock until the batch finishes.
async function runToEnd(engine, clock, maxTicks = 5000) {
  for (let i = 0; i < maxTicks; i += 1) {
    engine.tick();
    await engine.settle();
    if (['finished', 'stopped', 'canceled'].includes(engine.batch.state) && engine.busy.size === 0) {
      if (engine.batch.state !== 'canceled' || engine.batch.items.every((it) => ['done', 'failed', 'canceled'].includes(it.status))) break;
    }
    clock.t += 1000;
  }
}

function setup(n, apiOpts, saverOpts, concurrency = 3) {
  const clock = { t: 0 };
  const batch = { id: 'b', name: 'test', kind: 'image', modelPath: 'm/x', params: { aspect_ratio: '16:9' }, items: makeItems(Array.from({ length: n }, (_, i) => `prompt ${i + 1}`)) };
  const api = fakeApi(apiOpts);
  const saver = fakeSaver(saverOpts);
  const engine = new QueueEngine({ batch, api, saver, concurrency, now: () => clock.t });
  return { clock, batch, api, saver, engine };
}

test('runs a batch of 5 to completion within the concurrency limit', async () => {
  const { clock, batch, api, saver, engine } = setup(5, { pollsUntilDone: 3 }, {}, 2);
  engine.start();
  await runToEnd(engine, clock);
  assert.equal(batch.state, 'finished');
  assert.equal(countByStatus(batch).done, 5);
  assert.equal(saver.saved.length, 5);
  assert.ok(api.maxInFlight <= 2, `max in flight ${api.maxInFlight}`);
  engine.writeManifestNow();
});

test('runs a batch of 100', async () => {
  const { clock, batch, api, engine } = setup(100, { pollsUntilDone: 2 }, {}, 4);
  engine.start();
  await runToEnd(engine, clock, 20000);
  assert.equal(countByStatus(batch).done, 100);
  assert.equal(api.submitted, 100);
  assert.ok(api.maxInFlight <= 4);
});

test('failed generations retry twice automatically, then fail', async () => {
  const { clock, batch, api, engine } = setup(2, { failPrompts: { 'prompt 1': { count: 5 }, 'prompt 2': { count: 1, status: 'nsfw' } } });
  engine.start();
  await runToEnd(engine, clock);
  const [a, b] = batch.items;
  assert.equal(a.status, 'failed');
  assert.equal(a.failures, 3); // 1 try + 2 retries
  assert.equal(b.status, 'done');
  assert.equal(api.submitted, 3 + 2);
});

test('rate-limit errors back off and lower concurrency, without failing items', async () => {
  const busy = () => new HiggsfieldError('429', { status: 429, kind: 'busy', retryAfterMs: 7000 });
  const { clock, batch, engine } = setup(3, { submitErrors: [busy(), busy()] }, {}, 3);
  engine.start();
  engine.tick();
  await engine.settle();
  assert.ok(engine.backoffUntil >= 7000);
  assert.ok(engine.concurrency < 3);
  await runToEnd(engine, clock);
  assert.equal(countByStatus(batch).done, 3);
});

test('bad key stops the batch; resume continues', async () => {
  const auth = new HiggsfieldError('401', { status: 401, kind: 'auth' });
  const { clock, batch, engine } = setup(2, { submitErrors: [auth] });
  engine.start();
  engine.tick();
  await engine.settle();
  assert.equal(batch.state, 'stopped');
  assert.match(batch.error, /key/);
  engine.start();
  await runToEnd(engine, clock);
  assert.equal(countByStatus(batch).done, 2);
});

test('rejected input fails just that item', async () => {
  const bad = new HiggsfieldError('400 prompt too long', { status: 400, kind: 'input' });
  const { clock, batch, engine } = setup(2, { submitErrors: [bad] });
  engine.start();
  await runToEnd(engine, clock);
  const c = countByStatus(batch);
  assert.equal(c.failed, 1);
  assert.equal(c.done, 1);
});

test('failed download is retried without paying for a new generation', async () => {
  const { clock, batch, api, saver, engine } = setup(1, {}, { failDownloads: 1 });
  engine.start();
  await runToEnd(engine, clock);
  assert.equal(batch.items[0].status, 'failed');
  assert.equal(batch.needsPermissionFor, 'https://cdn.test');
  engine.retryFailed();
  await runToEnd(engine, clock);
  assert.equal(batch.items[0].status, 'done');
  assert.equal(api.submitted, 1);
  assert.equal(saver.saved.length, 1);
});

test('pause stops new submissions but lets running jobs finish', async () => {
  const { clock, batch, api, engine } = setup(6, { pollsUntilDone: 3 }, {}, 2);
  engine.start();
  engine.tick();
  await engine.settle();
  engine.pause();
  for (let i = 0; i < 20; i += 1) {
    clock.t += 1000;
    engine.tick();
    await engine.settle();
  }
  assert.equal(api.submitted, 2);
  assert.equal(countByStatus(batch).done, 2);
  assert.equal(countByStatus(batch).queued, 4);
});

test('cancel marks queued items canceled', async () => {
  const { clock, batch, engine } = setup(5, { pollsUntilDone: 2 }, {}, 1);
  engine.start();
  engine.tick();
  await engine.settle();
  await engine.cancel();
  await runToEnd(engine, clock);
  const c = countByStatus(batch);
  assert.equal(c.canceled + c.done, 5);
  assert.ok(c.canceled >= 4);
});

test('resume after restart re-checks running jobs instead of resubmitting', async () => {
  const { clock, batch, api, saver } = setup(2, { pollsUntilDone: 5 }, {}, 2);
  const first = new QueueEngine({ batch, api, saver, concurrency: 2, now: () => clock.t });
  first.start();
  first.tick();
  await first.settle();
  assert.equal(api.submitted, 2);
  // "browser restart": a new engine from the saved batch
  const saved = JSON.parse(JSON.stringify(batch));
  const second = new QueueEngine({ batch: saved, api, saver, concurrency: 2, now: () => clock.t });
  second.start();
  await runToEnd(second, clock);
  assert.equal(api.submitted, 2);
  assert.equal(countByStatus(saved).done, 2);
});
