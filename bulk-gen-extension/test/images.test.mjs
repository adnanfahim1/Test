import test from 'node:test';
import assert from 'node:assert/strict';
import { imageParam, validateModel, DEFAULT_MODELS } from '../src/lib/models.js';
import { HiggsfieldClient } from '../src/lib/higgsfield.js';
import { QueueEngine, makeItems } from '../src/lib/queue.js';

test('image setting shapes match Higgsfield SDK examples', () => {
  assert.deepEqual(imageParam('input_images', 'list', 'U'), { input_images: [{ type: 'image_url', image_url: 'U' }] });
  assert.deepEqual(imageParam('image_reference', 'object', 'U'), { image_reference: { type: 'image_url', image_url: 'U' } });
  assert.deepEqual(imageParam('image_url', 'url', 'U'), { image_url: 'U' });
  assert.deepEqual(imageParam('images', 'url_list', 'U'), { images: ['U'] });
  assert.deepEqual(imageParam('', 'url', 'U'), {});
  assert.deepEqual(imageParam('x', 'url', ''), {});
});

test('model image settings are validated', () => {
  assert.equal(validateModel({ ...DEFAULT_MODELS[0], imageField: 'input_images', imageFormat: 'list' }), '');
  assert.match(validateModel({ ...DEFAULT_MODELS[0], imageField: 'bad field', imageFormat: 'list' }), /image field/);
  assert.match(validateModel({ ...DEFAULT_MODELS[0], imageField: 'x', imageFormat: 'nope' }), /image format/);
});

test('upload follows the SDK flow: get upload link, PUT bytes without the API key', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (url.endsWith('/files/generate-upload-url')) {
      return new Response(JSON.stringify({ public_url: 'https://cdn.test/p.png', upload_url: 'https://up.test/put', upload_headers: { 'Content-Type': 'image/png' } }));
    }
    return new Response('', { status: 200 });
  };
  const client = new HiggsfieldClient({ credentials: 'K:S', baseURL: 'https://h.test', fetchImpl });
  const url = await client.uploadImage(new Blob(['x'], { type: 'image/png' }));
  assert.equal(url, 'https://cdn.test/p.png');
  assert.equal(JSON.parse(calls[0].init.body).content_type, 'image/png');
  assert.equal(calls[1].init.method, 'PUT');
  assert.equal(calls[1].init.headers.Authorization, undefined);
});

test('upload network failure reports the upload site (for the permission button)', async () => {
  const fetchImpl = async (url) => {
    if (url.endsWith('/files/generate-upload-url')) return new Response(JSON.stringify({ public_url: 'p', upload_url: 'https://up.test/put' }));
    throw new TypeError('Failed to fetch');
  };
  const client = new HiggsfieldClient({ credentials: 'K:S', baseURL: 'https://h.test', fetchImpl });
  await assert.rejects(client.uploadImage(new Blob(['x'])), (err) => err.origin === 'https://up.test');
});

test('queue sends each item with its own reference image', async () => {
  const sent = [];
  const api = {
    async submit(path, params) {
      sent.push(params);
      return { request_id: `r${sent.length}`, status: 'queued' };
    },
    async status() {
      return { status: 'completed', video: { url: 'https://cdn.test/v.mp4' } };
    },
    async cancel() {},
  };
  const saver = { async saveResult(item) { return { name: `f${item.n}.mp4`, blob: null }; }, async writeManifest() {} };
  const batch = {
    kind: 'video', modelPath: 'm', params: { duration: 5 }, imageField: 'input_images', imageFormat: 'list',
    items: makeItems(['a', 'b', 'c'], [{ name: 'one.png', url: 'U1' }, { name: 'two.png', url: 'U2' }, null]),
  };
  let t = 0;
  const engine = new QueueEngine({ batch, api, saver, concurrency: 3, now: () => t });
  engine.start();
  for (let i = 0; i < 30 && batch.state !== 'finished'; i += 1) {
    engine.tick();
    await engine.settle();
    t += 5000;
  }
  assert.equal(batch.state, 'finished');
  assert.deepEqual(sent[0], { duration: 5, input_images: [{ type: 'image_url', image_url: 'U1' }], prompt: 'a' });
  assert.deepEqual(sent[1].input_images[0].image_url, 'U2');
  assert.equal(sent[2].input_images, undefined);
  assert.equal(batch.items[0].imageName, 'one.png');
});

test('"no text prompt" models are sent without a prompt (Genjutsu motion transfer shape)', async () => {
  const sent = [];
  const api = {
    async submit(path, params) { sent.push({ path, params }); return { request_id: 'r1', status: 'queued' }; },
    async status() { return { status: 'completed', video: { url: 'https://cdn.test/v.mp4' } }; },
    async cancel() {},
  };
  const saver = { async saveResult(item) { return { name: `f${item.n}.mp4`, blob: null }; }, async writeManifest() {} };
  const batch = {
    kind: 'video', modelPath: 'higgsfield/genjutsu/motion-transfer/v1.0', noPrompt: true,
    params: { video_url: 'https://example.com/input.mp4' }, imageField: 'image_urls', imageFormat: 'url_list',
    items: makeItems(['label only'], [{ name: 'me.jpg', url: 'https://example.com/input.jpg' }]),
  };
  let t = 0;
  const engine = new QueueEngine({ batch, api, saver, concurrency: 1, now: () => t });
  engine.start();
  for (let i = 0; i < 20 && batch.state !== 'finished'; i += 1) { engine.tick(); await engine.settle(); t += 10000; }
  // exactly the body from Higgsfield's docs example
  assert.deepEqual(sent[0].params, { video_url: 'https://example.com/input.mp4', image_urls: ['https://example.com/input.jpg'] });
  assert.equal(batch.state, 'finished');
});

test('retries after a timeout reuse the same Idempotency-Key; a failed generation gets a new one', async () => {
  const keys = [];
  let calls = 0;
  const { HiggsfieldError } = await import('../src/lib/higgsfield.js');
  const api = {
    async submit(path, params, opts) {
      keys.push(opts.idempotencyKey);
      calls += 1;
      if (calls === 1) throw new HiggsfieldError('timeout', { kind: 'server' });
      return { request_id: `r${calls}`, status: 'queued' };
    },
    async status(id) { return id === 'r2' ? { status: 'failed' } : { status: 'completed', images: [{ url: 'u' }] }; },
    async cancel() {},
  };
  const saver = { async saveResult(item) { return { name: 'f.png', blob: null }; }, async writeManifest() {} };
  const batch = { kind: 'image', modelPath: 'm', params: {}, items: makeItems(['a']) };
  let t = 0;
  const engine = new QueueEngine({ batch, api, saver, concurrency: 1, now: () => t });
  engine.start();
  for (let i = 0; i < 60 && batch.state !== 'finished'; i += 1) { engine.tick(); await engine.settle(); t += 10000; }
  assert.equal(batch.state, 'finished');
  assert.equal(keys.length, 3);
  assert.ok(keys[0]);
  assert.equal(keys[0], keys[1]); // retry after timeout: same key
  assert.notEqual(keys[1], keys[2]); // after a failed generation: new key
});

test('client sends the Idempotency-Key header', async () => {
  let headers;
  const client = new HiggsfieldClient({ credentials: 'K:S', baseURL: 'https://h.test', fetchImpl: async (u, init) => { headers = init.headers; return new Response(JSON.stringify({ request_id: 'x' })); } });
  await client.submit('alibaba/qwen-image-3/edit', { prompt: 'p' }, { idempotencyKey: 'abc' });
  assert.equal(headers['Idempotency-Key'], 'abc');
});

test('built-in models: always listed, your edits win, deleted ones stay hidden, your own are kept', async () => {
  const { mergeModels, DEFAULT_MODELS: D, validateModel: v } = await import('../src/lib/models.js');
  for (const m of D) assert.equal(v(m), '', m.id);
  const ids = mergeModels().map((m) => m.id);
  assert.ok(ids.includes('qwen-image-3-edit') && ids.includes('genjutsu-motion'));
  const edited = mergeModels([{ id: 'qwen-image-3-edit', price: 0.02 }]);
  assert.equal(edited.find((m) => m.id === 'qwen-image-3-edit').price, 0.02);
  assert.equal(edited.find((m) => m.id === 'qwen-image-3-edit').path, 'alibaba/qwen-image-3/edit');
  const hidden = mergeModels([], ['genjutsu-motion']);
  assert.ok(!hidden.some((m) => m.id === 'genjutsu-motion'));
  const mine = mergeModels([{ id: 'my-model', name: 'Mine', type: 'image', path: 'a/b' }]);
  assert.ok(mine.some((m) => m.id === 'my-model' && !m.builtIn));
});

test('Qwen edit built-in sends exactly the documented request body', () => {
  const q = DEFAULT_MODELS.find((m) => m.id === 'qwen-image-3-edit');
  const body = { ...q.fixed, aspect_ratio: q.options.aspect_ratio[0], resolution: q.options.resolution[0], ...imageParam(q.imageField, q.imageFormat, 'https://example.com/input-image.jpg'), prompt: 'Change the vase to matte blue while keeping the composition.' };
  assert.deepEqual(body, { prompt: 'Change the vase to matte blue while keeping the composition.', image_urls: ['https://example.com/input-image.jpg'], resolution: '1k', aspect_ratio: '1:1' });
});
