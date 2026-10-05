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
  minify: false, // keep it readable
  legalComments: 'inline',
});
console.log('Built ./extension - load this folder in chrome://extensions or opera://extensions');
