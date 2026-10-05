// Build: bundles the Studio page (with Anthropic's SDK) into the "extension" folder,
// which is the folder you load into Chrome/Opera. Run: npm run build
import { build } from 'esbuild';
import { cpSync, mkdirSync, rmSync } from 'node:fs';

const out = 'extension';
rmSync(out, { recursive: true, force: true });
mkdirSync(`${out}/ui`, { recursive: true });

cpSync('src/manifest.json', `${out}/manifest.json`);
cpSync('src/background.js', `${out}/background.js`);
cpSync('src/icons', `${out}/icons`, { recursive: true });
for (const f of ['studio.html', 'studio.css', 'ticker.js']) cpSync(`src/ui/${f}`, `${out}/ui/${f}`);

await build({
  entryPoints: ['src/ui/studio.js'],
  outfile: `${out}/ui/studio.js`,
  bundle: true,
  format: 'esm',
  target: 'chrome110',
  minify: true, // smaller, faster to load (the readable code is in src/)
  legalComments: 'linked',
});
// A second manifest in the outer folder, so loading EITHER folder in the browser works.
import { readFileSync, writeFileSync } from 'node:fs';
const m = JSON.parse(readFileSync('src/manifest.json', 'utf8'));
const pre = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, `extension/${v}`]));
m.action.default_icon = pre(m.action.default_icon);
m.icons = pre(m.icons);
m.background.service_worker = 'extension/background.js';
writeFileSync('manifest.json', JSON.stringify(m, null, 2) + '\n');
console.log('Built ./extension - load this folder in chrome://extensions or opera://extensions');
