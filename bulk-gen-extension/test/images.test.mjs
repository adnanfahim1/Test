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
