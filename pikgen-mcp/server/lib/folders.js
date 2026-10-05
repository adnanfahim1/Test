// Safety: PikGen may only read and write inside the folders you allowed when installing it.
import fs from 'node:fs/promises';
import path from 'node:path';

const same = (a, b) => (process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b);

function inside(child, parent) {
  const rel = path.relative(parent, child);
  return same(child, parent) || (rel && !rel.startsWith('..') && !path.isAbsolute(rel));
}

/** Resolve a real path, following links, even if the last parts don't exist yet. */
async function realish(p) {
  const abs = path.resolve(p);
  const missing = [];
  let cur = abs;
  for (;;) {
    try {
      const real = await fs.realpath(cur);
      return path.join(real, ...missing.reverse());
    } catch {
      const parent = path.dirname(cur);
      if (parent === cur) return abs;
      missing.push(path.basename(cur));
      cur = parent;
    }
  }
}

export class Folders {
  constructor(allowed = []) {
    this.allowed = allowed.filter(Boolean).map((p) => path.resolve(p));
  }

  async check(p) {
    if (!p || typeof p !== 'string') throw new Error('A folder or file path is required.');
    if (!this.allowed.length) {
      throw new Error('No folders are allowed yet. In Claude Desktop: Settings -> Extensions -> PikGen -> add your folders.');
    }
    const real = await realish(p);
    for (const root of this.allowed) {
      if (inside(real, await realish(root))) return real;
    }
    throw new Error(`"${p}" is outside the folders PikGen may use (${this.allowed.join(', ')}). Add it in Claude Desktop: Settings -> Extensions -> PikGen.`);
  }
}
