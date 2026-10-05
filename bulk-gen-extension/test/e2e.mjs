// End-to-end test: loads the real built extension into Chromium and runs a batch
// through the actual UI, against a FAKE Claude + FAKE Higgsfield server on localhost.
// No real API keys, no real credits. Run: node test/e2e.mjs [count]
//
// Needs Playwright (set PLAYWRIGHT_PATH if it's not resolvable).

import http from 'node:http';
import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');

const IMAGES = Number(process.env.IMAGES || 0); // use N reference images (one generation each)
const COUNT = IMAGES || Number(process.argv[2] || 5);
const CONCURRENCY = Number(process.argv[3] || 3);
const CHAOS = !!process.env.CHAOS;
const T0 = Date.now();
const VIDEO = !!process.env.VIDEO || !!process.env.MOTION;
// BUILTIN=1: no hand-made models, use PikGen's built-in Qwen edit. MOTION=1: built-in Genjutsu motion transfer.
const BUILTIN = !!process.env.BUILTIN || !!process.env.MOTION;
const MOTION = !!process.env.MOTION; // run in Video mode instead of Image mode

// ---------- fake servers ----------
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const stats = { messages: 0, submits: 0, polls: 0, maxActive: 0, active: 0, badAuth: 0 };
const jobs = new Map();
let nextId = 1;

const server = http.createServer(async (req, res) => {
  let body = '';
  for await (const chunk of req) body += chunk;
  const url = new URL(req.url, 'http://x');
  const json = (status, data) => {
    res.writeHead(status, {
      'content-type': 'application/json',
      'access-control-allow-origin': '*',
      'access-control-allow-headers': '*',
    });
    res.end(JSON.stringify(data));
  };
  if (req.method === 'OPTIONS') return json(204, {});

  // --- fake Anthropic ---
  if (url.pathname === '/v1/models') return json(200, { data: [], has_more: false });
  if (url.pathname === '/v1/messages') {
    stats.messages += 1;
    const params = JSON.parse(body);
    const content = params.messages[0].content;
    const text = typeof content === 'string' ? content : content.find((b) => b.type === 'text').text;
    if (Array.isArray(content) && content.some((b) => b.type === 'image' && b.source?.data?.length > 10)) stats.visionCalls = (stats.visionCalls || 0) + 1;
    const k = Number(/Write exactly (\d+)/.exec(text)[1]);
    const tag = Array.isArray(content) ? 'from-picture ' : '';
    const offset = (text.match(/^- /gm) || []).length;
    const prompts = Array.from({ length: k }, (_, i) => `Fake prompt ${tag}${offset + i + 1}: a red sneaker, angle ${offset + i + 1}`);
    return json(200, {
      id: 'msg_1', type: 'message', role: 'assistant', model: params.model,
      content: [{ type: 'text', text: JSON.stringify({ prompts }) }],
      stop_reason: 'end_turn', stop_details: null, usage: { input_tokens: 1, output_tokens: 1 },
    });
  }

  // --- fake Higgsfield ---
  if (req.headers.authorization && req.headers.authorization !== 'Key KID:SECRET') {
    stats.badAuth += 1;
    return json(401, { detail: 'Invalid credentials' });
  }
  if (url.pathname === '/files/generate-upload-url') {
    const n = (stats.uploadLinks = (stats.uploadLinks || 0) + 1);
    return json(200, { public_url: `${base}/public/${n}.png`, upload_url: `${base}/upload/${n}`, upload_headers: { 'Content-Type': JSON.parse(body).content_type } });
  }
  if (req.method === 'PUT' && url.pathname.startsWith('/upload/')) {
    stats.uploadedBytes = (stats.uploadedBytes || 0) + body.length;
    stats.uploads = (stats.uploads || 0) + 1;
    return json(200, {});
  }
  if (req.method === 'POST' && /^\/(fake\/model\/text-to-(image|video)|alibaba\/qwen-image-3\/edit|higgsfield\/genjutsu\/motion-transfer\/v1\.0)$/.test(url.pathname)) {
    // CHAOS: the fake server allows only 3 jobs at a time (429 above that), and every 11th submit gets a server error
    stats.submitAttempts = (stats.submitAttempts || 0) + 1;
    if (process.env.TRACE) console.log('submit', ((Date.now() - T0) / 1000).toFixed(1));
    if (CHAOS && stats.active >= 3) {
      stats.rateLimited = (stats.rateLimited || 0) + 1;
      res.writeHead(429, { 'content-type': 'application/json', 'retry-after': '2' });
      return res.end(JSON.stringify({ detail: 'Too many requests' }));
    }
    if (CHAOS && stats.submitAttempts % 11 === 0) return json(503, { detail: 'temporarily unavailable' });
    stats.submits += 1;
    stats.active += 1;
    stats.maxActive = Math.max(stats.maxActive, stats.active);
    const id = `req-${nextId++}`;
    const sent = JSON.parse(body);
    if (sent.input_images?.[0]?.image_url?.includes('/public/') || sent.image_urls?.[0]?.includes('/public/')) stats.withImage = (stats.withImage || 0) + 1;
    stats.lastBody = sent;
    stats.lastPath = url.pathname;
    jobs.set(id, { polls: 0, params: sent, video: url.pathname.endsWith('video') || url.pathname.includes('genjutsu') });
    return json(200, { request_id: id, status: 'queued', status_url: '', cancel_url: '' });
  }
  const m = /^\/requests\/([^/]+)\/status$/.exec(url.pathname);
  if (m) {
    stats.polls += 1;
    if (process.env.TRACE) console.log('poll', m[1], ((Date.now() - T0) / 1000).toFixed(1));
    const job = jobs.get(m[1]);
    job.polls += 1;
    if (job.polls < 2) return json(200, { status: 'in_progress', request_id: m[1] });
    if (!job.done) {
      job.done = true;
      stats.active -= 1;
    }
    // CHAOS: every 5th job fails once at Higgsfield (should be retried automatically)
    if (CHAOS && Number(m[1].split('-')[1]) % 5 === 0) {
      stats.genFailed = (stats.genFailed || 0) + 1;
      return json(200, { status: 'failed', request_id: m[1] });
    }
    if (job.video) return json(200, { status: 'completed', request_id: m[1], video: { url: `http://127.0.0.1:${port}/cdn/${m[1]}.mp4` } });
    return json(200, { status: 'completed', request_id: m[1], images: [{ url: `http://127.0.0.1:${port}/cdn/${m[1]}.png` }] });
  }
  if (url.pathname.startsWith('/cdn/')) {
    if (url.pathname.endsWith('.mp4')) {
      res.writeHead(200, { 'content-type': 'video/mp4' });
      return res.end(Buffer.alloc(2048, 1)); // not a playable video, just bytes to save
    }
    res.writeHead(200, { 'content-type': 'image/png' }); // no CORS header on purpose
    return res.end(PNG);
  }
  json(404, { detail: 'not found' });
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const base = `http://127.0.0.1:${port}`;

// ---------- extension copy with permission for the fake server ----------
const extDir = mkdtempSync(join(tmpdir(), 'ext-'));
cpSync('extension', extDir, { recursive: true });
const manifest = JSON.parse(readFileSync(join(extDir, 'manifest.json'), 'utf8'));
manifest.host_permissions.push('http://127.0.0.1/*');
writeFileSync(join(extDir, 'manifest.json'), JSON.stringify(manifest));

const context = await chromium.launchPersistentContext('', {
  channel: 'chromium',
  headless: true,
  args: [`--disable-extensions-except=${extDir}`, `--load-extension=${extDir}`],
});
let [worker] = context.serviceWorkers();
if (!worker) worker = await context.waitForEvent('serviceworker');
const extId = new URL(worker.url()).host;

const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
// The browser logs every non-200 response; injected 429/503s are expected, not code errors.
page.on('console', (msg) => msg.type() === 'error' && !msg.text().startsWith('Failed to load resource') && errors.push(msg.text()));
page.on('dialog', (d) => d.accept());
await page.goto(`chrome-extension://${extId}/ui/studio.html?test=1`);

// Settings pointing at the fake server (as if typed into the Settings tab).
await page.evaluate(
  ({ base, concurrency, BUILTIN }) =>
    chrome.storage.local.set({
      settings: {
        anthropicKey: 'sk-ant-fake',
        higgsfieldKey: 'KID:SECRET',
        anthropicBaseUrl: base,
        higgsfieldBaseUrl: base,
        concurrency,
        ...(BUILTIN ? {} : { defaultImageModel: 'fake-img',
        defaultVideoModel: 'fake-vid',
        models: [
          { id: 'fake-vid', name: 'Fake Video', type: 'video', path: 'fake/model/text-to-video', options: { aspect_ratio: ['16:9', '9:16'], duration: [5, 10] }, fixed: {}, price: 0.2, imageField: 'input_images', imageFormat: 'list' },
          { id: 'fake-img', name: 'Fake Image', type: 'image', path: 'fake/model/text-to-image', options: { aspect_ratio: ['16:9', '9:16'] }, fixed: { resolution: '2K' }, price: 0.01, imageField: 'input_images', imageFormat: 'list' },
        ] }),
      },
    }),
  { base, concurrency: CONCURRENCY, BUILTIN },
);

const t0 = Date.now();
await page.click('#startBtn');
await page.waitForSelector('#app:not([hidden])');

// Settings tab: test both connections
await page.click('[data-tab=settings]');
await page.click('#testClaude');
await page.waitForFunction(() => /Connected|✗/.test(document.querySelector('#testClaudeResult').textContent));
await page.click('#testHiggs');
await page.waitForFunction(() => /accepted|✗/.test(document.querySelector('#testHiggsResult').textContent));
const tests = await page.evaluate(() => [testClaudeResult.textContent, testHiggsResult.textContent]);
console.log('connection tests:', tests);
await page.click('[data-tab=generate]');

// Generate tab
if (VIDEO) await page.click('.seg-btn[data-kind=video]');
if (!MOTION) await page.fill('#basePrompt', 'A red sneaker on a sand dune');
if (IMAGES) {
  // make N small real PNG files and pick them with "Choose images"
  const files = Array.from({ length: IMAGES }, (_, i) => ({ name: `product-${i + 1}.png`, mimeType: 'image/png', buffer: PNG }));
  await page.setInputFiles('#imageFiles', files);
  await page.waitForFunction((n) => document.querySelectorAll('#refStrip img').length === n, IMAGES);
  if (BUILTIN) console.log('model after loading pictures:', await page.$eval('#modelSelect', (s) => s.selectedOptions[0].textContent));
  if (MOTION) await page.setInputFiles('#motionFile', { name: 'dance.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(4096, 7) });
  console.log('quantity box (auto):', await page.inputValue('#quantity'));
  if (process.env.SHOT_REF) { await page.setViewportSize({ width: 1280, height: 1100 }); await page.locator('.ref-box').screenshot({ path: process.env.SHOT_REF }); }
} else {
  await page.fill('#quantity', String(COUNT));
}
if (!BUILTIN) await page.selectOption('#optionsBox select', { label: '9:16' });
await page.fill('#batchName', 'E2E Test');
await page.click('#previewBtn');
await page.waitForFunction((n) => document.querySelectorAll('#promptList li').length === n, COUNT, { timeout: 60000 });
const costText = await page.textContent('#costBox');
console.log('cost box:', costText);

// edit prompt #1 and delete prompt #2 to check the editable list
await page.fill('#promptList li:nth-child(1) textarea', 'EDITED first prompt');
if (COUNT > 2) {
  await page.click('#promptList li:nth-child(2) button[title^="Delete"]');
}
const expected = COUNT > 2 ? COUNT - 1 : COUNT;

await page.click('#generateBtn');
if (process.env.RELOAD) {
  // simulate closing the tab / restarting mid-batch
  await page.waitForTimeout(3000);
  await page.reload();
  await page.click('#startBtn');
  await page.waitForSelector('#resumeBtn:not([hidden])');
  const notice = await page.textContent('#batchNotice');
  console.log('after reload:', notice);
  await page.click('#resumeBtn');
}
await page.waitForFunction(
  (n) => {
    const done = [...document.querySelectorAll('.thumb')].filter((t) => t.querySelector('img, video')).length;
    return done === n;
  },
  expected,
  { timeout: 15 * 60 * 1000, polling: 1000 },
);
// let the manifest debounce finish
await page.waitForTimeout(2500);

const result = await page.evaluate(async (VIDEO) => {
  const root = await navigator.storage.getDirectory();
  const out = {};
  for await (const [name, handle] of root.entries()) {
    if (handle.kind !== 'directory') continue;
    const images = await handle.getDirectoryHandle(VIDEO ? 'videos' : 'images');
    const files = [];
    for await (const [f] of images.entries()) files.push(f);
    const manifest = await (await (await handle.getFileHandle('manifest.csv')).getFile()).text();
    out[name] = { files: files.sort(), manifest };
  }
  return { out, title: document.querySelector('#batchTitle').textContent };
}, VIDEO);

console.log(JSON.stringify({ seconds: Math.round((Date.now() - t0) / 1000), stats, title: result.title }, null, 1));
const [folder] = Object.keys(result.out);
const { files, manifest: csv } = result.out[folder];
console.log('folder:', folder, '| files:', files.length, '| first:', files.slice(0, 2));
console.log('manifest head:\n' + csv.split('\r\n').slice(0, 3).join('\n'));

// ---------- assertions ----------
const fail = (msg) => {
  console.error('FAIL:', msg);
  process.exitCode = 1;
};
if (!tests[0].includes('Connected') || !tests[1].includes('accepted')) fail('connection tests');
if (files.length !== expected) fail(`expected ${expected} files, got ${files.length}`);
if (!/^e2e-test_\d{4}-\d{2}-\d{2}$/.test(folder)) fail(`folder name ${folder}`);
if (!files[0].startsWith('e2e-test_001_edited-first-prompt') || !files[0].endsWith(VIDEO ? '.mp4' : '.png')) fail(`file name ${files[0]}`);
if (!csv.includes('number,final_prompt,model,settings,status,file_name,error,time')) fail('manifest header');
if ((csv.match(/,done,/g) || []).length !== expected) fail('manifest done rows');
if (!BUILTIN && (!csv.includes('9:16') || !csv.includes(VIDEO ? 'duration' : '2K'))) fail('settings in manifest');
if (BUILTIN && !MOTION && !(csv.includes('1:1') && csv.includes('1k'))) fail('qwen settings in manifest');
if (BUILTIN) console.log('last request:', stats.lastPath, JSON.stringify(stats.lastBody));
if (CHAOS ? stats.submits < expected : stats.submits !== expected) fail(`submits ${stats.submits}`);
if (stats.maxActive > CONCURRENCY) fail(`concurrency exceeded: ${stats.maxActive}`);
if (!result.title.includes('finished')) fail(`title ${result.title}`);
if (errors.length) fail(`page errors: ${errors.join(' | ')}`);
if (IMAGES) {
  if (stats.uploads !== expected + (MOTION ? 1 : 0)) fail(`uploads ${stats.uploads}, expected ${expected} (one per picture actually used)`);
  if (stats.withImage !== stats.submits) fail(`only ${stats.withImage} of ${stats.submits} requests had the reference image`);
  if (!MOTION && !stats.visionCalls) fail('Claude never saw the images');
  if (MOTION && (stats.lastBody.prompt !== undefined || !stats.lastBody.video_url?.includes('/public/'))) fail('genjutsu body wrong');
  if (BUILTIN && !MOTION && stats.lastPath !== '/alibaba/qwen-image-3/edit') fail('did not use Qwen edit');
  if (MOTION && !stats.lastPath.includes('genjutsu')) fail('did not use Genjutsu');
  if (!csv.includes('product-1.png')) fail('reference image missing from manifest');
}
if (!process.exitCode) console.log('E2E PASS');

if (process.env.SHOT) { await page.setViewportSize({width:1280,height:900}); await page.screenshot({path: process.env.SHOT + '/main.png'}); await page.goto('chrome-extension://' + extId + '/ui/studio.html'); await page.screenshot({path: process.env.SHOT + '/gate.png'}); }
await context.close();
server.close();
