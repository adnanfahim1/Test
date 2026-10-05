import test from 'node:test';
import assert from 'node:assert/strict';
import { slugify, resultFileName, batchFolderName, pickExtension, toCsv, redact } from '../src/lib/util.js';
import { classifyError, resultUrls, HiggsfieldClient } from '../src/lib/higgsfield.js';
import { validateModel, DEFAULT_MODELS } from '../src/lib/models.js';

test('slugify makes safe short names', () => {
  assert.equal(slugify('A Red Fox, at Dawn!'), 'a-red-fox-at-dawn');
  assert.equal(slugify('Café déjà vu'), 'cafe-deja-vu');
  assert.equal(slugify('!!!'), 'untitled');
  assert.ok(slugify('x'.repeat(100)).length <= 40);
});

test('file and folder names follow the agreed pattern', () => {
  assert.equal(resultFileName('Summer Shoes', 7, 'A shoe on sand', 'png'), 'summer-shoes_007_a-shoe-on-sand.png');
  assert.equal(resultFileName('b', 7, 'p', 'png', 1), 'b_007-2_p.png');
  assert.equal(batchFolderName('Summer Shoes', new Date(2026, 9, 5)), 'summer-shoes_2026-10-05');
});

test('extension detection', () => {
  assert.equal(pickExtension('image/jpeg', 'https://x/y', 'image'), 'jpg');
  assert.equal(pickExtension('', 'https://x/y/clip.MP4?sig=1', 'video'), 'mp4');
  assert.equal(pickExtension('application/octet-stream', 'https://x/y', 'video'), 'mp4');
});

test('csv escaping', () => {
  assert.equal(toCsv(['a', 'b'], [['x,y', 'he said "hi"\nok']]), 'a,b\r\n"x,y","he said ""hi""\nok"\r\n');
});

test('redact hides secrets', () => {
  assert.equal(redact('key sk-abcdef123 bad', ['sk-abcdef123']), 'key [hidden] bad');
});

test('higgsfield error classification', () => {
  assert.equal(classifyError(401), 'auth');
  assert.equal(classifyError(403), 'credits');
  assert.equal(classifyError(429), 'busy');
  assert.equal(classifyError(400, 'Concurrency limit reached'), 'busy');
  assert.equal(classifyError(400, 'prompt is required'), 'input');
  assert.equal(classifyError(422), 'input');
  assert.equal(classifyError(503), 'server');
});

test('result urls from documented response shape', () => {
  assert.deepEqual(resultUrls({ images: [{ url: 'a' }, { url: 'b' }] }), ['a', 'b']);
  assert.deepEqual(resultUrls({ video: { url: 'v' } }), ['v']);
  assert.deepEqual(resultUrls({}), []);
});

test('higgsfield client sends the documented auth header and never leaks the secret', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return new Response(JSON.stringify({ detail: 'bad SECRET123 here' }), { status: 400 });
  };
  const client = new HiggsfieldClient({ credentials: 'KID:SECRET123', baseURL: 'https://h.test', fetchImpl });
  await assert.rejects(client.submit('m/x', { prompt: 'p' }), (err) => {
    assert.equal(err.kind, 'input');
    assert.ok(!err.message.includes('SECRET123'));
    return true;
  });
  assert.equal(calls[0].url, 'https://h.test/m/x');
  assert.equal(calls[0].init.headers.Authorization, 'Key KID:SECRET123');
  assert.throws(() => new HiggsfieldClient({ credentials: 'nocolon' }));
});

test('model validation', () => {
  assert.equal(validateModel(DEFAULT_MODELS[0]), '');
  assert.match(validateModel({ ...DEFAULT_MODELS[0], path: 'https://api.higgsfield.ai/x' }), /path/);
  assert.match(validateModel({ ...DEFAULT_MODELS[0], type: 'audio' }), /type/);
});
