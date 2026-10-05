import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Folders } from '../server/lib/folders.js';
import { PikGen, isHiggsfieldUploadUrl } from '../server/lib/core.js';

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

async function setup(fetchImpl) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'pikgen-'));
  const pics = path.join(root, 'pics');
  await fs.mkdir(pics);
  for (const n of ['b10.png', 'b2.jpg', 'notes.txt', 'a1.webp']) await fs.writeFile(path.join(pics, n), PNG);
  const pg = new PikGen({ folders: new Folders([root]), fetchImpl });
  return { root, pics, pg };
}

test('lists only pictures, in natural order', async () => {
  const { pics, pg } = await setup();
  const r = await pg.listImages({ folder: pics });
  assert.deepEqual(r.images.map((i) => i.name), ['a1.webp', 'b2.jpg', 'b10.png']);
  assert.equal(r.images[1].content_type, 'image/jpeg');
});

test('refuses folders outside the allowed ones', async () => {
  const { pg } = await setup();
  await assert.rejects(pg.listImages({ folder: os.homedir() === '/' ? '/etc' : path.dirname(os.tmpdir()) }), /outside the folders/);
  const none = new PikGen({ folders: new Folders([]) });
  await assert.rejects(none.listImages({ folder: '/tmp' }), /No folders are allowed/);
});

test('refuses ../ tricks', async () => {
  const { root, pg } = await setup();
  await assert.rejects(pg.listImages({ folder: path.join(root, '..', '..') }), /outside/);
});

test('uploads only to higgsfield links, with Content-Type, retrying with If-None-Match if refused', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, headers: init.headers, size: init.body.length });
    return new Response('', { status: init.headers['If-None-Match'] ? 200 : 403 });
  };
  const { pics, pg } = await setup(fetchImpl);
  const r = await pg.uploadFiles({
    uploads: [
      { path: path.join(pics, 'b2.jpg'), upload_url: 'https://upload.higgsfield.ai/u/x.jpg?sig=1', media_id: 'm1' },
      { path: path.join(pics, 'b10.png'), upload_url: 'https://evil.example.com/steal', media_id: 'm2' },
    ],
  });
  assert.equal(r.uploaded, 1);
  assert.equal(r.results[1].ok, false);
  assert.match(r.results[1].error, /only uploads to Higgsfield/);
  assert.equal(calls.length, 2); // 403 then retry, nothing sent to evil.example.com
  assert.equal(calls[0].headers['Content-Type'], 'image/jpeg');
  assert.equal(calls[1].headers['If-None-Match'], '*');
  assert.equal(calls[0].size, PNG.length);
  assert.ok(isHiggsfieldUploadUrl('https://upload.higgsfield.ai/a'));
  assert.ok(!isHiggsfieldUploadUrl('https://higgsfield.ai.evil.com/a'));
  assert.ok(!isHiggsfieldUploadUrl('http://upload.higgsfield.ai/a'));
});

test('full batch: start, record jobs, save results, status, manifest, resume-safe', async () => {
  const fetchImpl = async (url) => {
    if (url.includes('expired')) return new Response('', { status: 403 });
    return new Response(PNG, { status: 200, headers: { 'content-type': 'image/png' } });
  };
  const { root, pics, pg } = await setup(fetchImpl);
  const out = path.join(root, 'out');
  const s = await pg.startBatch({
    output_folder: out, name: 'Mens Sweater', kind: 'image', model: 'qwen_image_edit', settings: { aspect_ratio: '1:1' },
    items: [
      { prompt: 'Blue sweater on white', reference_image: path.join(pics, 'b2.jpg') },
      { prompt: 'Red sweater, studio light' },
      { prompt: 'Green sweater outdoors' },
    ],
  });
  assert.match(path.basename(s.batch_folder), /^mens-sweater_\d{4}-\d{2}-\d{2}$/);
  const again = await pg.startBatch({ output_folder: out, name: 'Mens Sweater', kind: 'image', model: 'm', items: [{ prompt: 'x' }] });
  assert.match(path.basename(again.batch_folder), /-2$/); // never mixes two batches

  await pg.recordJobs({ batch_folder: s.batch_folder, jobs: [{ number: 1, job_id: 'j1' }, { number: 2, job_id: 'j2' }, { number: 3, error: 'rejected' }] });
  let st = await pg.batchStatus({ batch_folder: s.batch_folder });
  assert.deepEqual(st.summary, { total: 3, pending: 0, submitted: 2, done: 0, failed: 1 });
  assert.deepEqual(st.submitted_not_saved.map((x) => x.job_id), ['j1', 'j2']);

  const saved = await pg.saveResults({
    batch_folder: s.batch_folder,
    results: [
      { number: 1, status: 'completed', urls: ['https://cdn.test/a.png'] },
      { number: 2, status: 'completed', urls: ['https://cdn.test/expired.png'] },
    ],
  });
  assert.equal(saved.saved, 1);
  const files = await fs.readdir(path.join(s.batch_folder, 'images'));
  assert.deepEqual(files, ['mens-sweater_001_blue-sweater-on-white.png']);
  st = await pg.batchStatus({ batch_folder: s.batch_folder });
  assert.equal(st.failed.find((f) => f.number === 2).can_redownload, true);

  const csv = await fs.readFile(path.join(s.batch_folder, 'manifest.csv'), 'utf8');
  assert.ok(csv.startsWith('﻿number,final_prompt,model,settings,status,file_name,error,time,reference_image,job_id'));
  assert.match(csv, /1,Blue sweater on white,qwen_image_edit,.*done,mens-sweater_001_blue-sweater-on-white\.png/);
  assert.match(csv, /b2\.jpg,j1/);

  const list = await pg.listBatches({ output_folder: out });
  assert.equal(list.batches.length, 2);
});

test('saving twice does not download twice', async () => {
  let downloads = 0;
  const fetchImpl = async () => {
    downloads += 1;
    return new Response(PNG, { headers: { 'content-type': 'image/png' } });
  };
  const { root, pg } = await setup(fetchImpl);
  const s = await pg.startBatch({ output_folder: path.join(root, 'o'), name: 'x', kind: 'video', model: 'm', items: [{ prompt: 'p' }] });
  const r = { number: 1, status: 'completed', urls: ['https://cdn.test/v.mp4'] };
  await pg.saveResults({ batch_folder: s.batch_folder, results: [r] });
  await pg.saveResults({ batch_folder: s.batch_folder, results: [r] });
  assert.equal(downloads, 1);
  assert.deepEqual(await fs.readdir(path.join(s.batch_folder, 'videos')), ['x_001_p.png']);
});
