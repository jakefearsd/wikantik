// Runs `npm ci --ignore-scripts` unless node_modules was already installed from exactly this package-lock.json.
//
// The WAR build calls this instead of `npm ci` directly. `npm ci` always deletes node_modules first, so every Maven
// build used to reinstall ~400 packages and reset the developer's vitest/coverage tooling mid-session. The skip is
// keyed on a SHA-256 of package-lock.json recorded after a successful `npm ci`: any lockfile change (an `npm install`,
// a dependency bump, a checkout) reinstalls. Set FORCE_NPM_CI=1 to reinstall regardless. A fresh checkout (CI, the
// release workflow) has no node_modules and so always runs `npm ci`, which keeps the shipped bundle reproducible.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const lockfile = path.join(root, 'package-lock.json');
const stamp = path.join(root, 'node_modules', '.wikantik-lock-sha256');
const digest = createHash('sha256').update(readFileSync(lockfile)).digest('hex');

if (process.env.FORCE_NPM_CI !== '1' && existsSync(stamp) && readFileSync(stamp, 'utf8').trim() === digest) {
  console.log('[ensure-deps] node_modules was installed from this package-lock.json; skipping npm ci (FORCE_NPM_CI=1 to force)');
  process.exit(0);
}

const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const result = spawnSync(npm, ['ci', '--no-audit', '--no-fund', '--ignore-scripts'], { cwd: root, stdio: 'inherit' });
if (result.status !== 0) {
  console.error(`[ensure-deps] npm ci failed (exit ${result.status ?? result.signal})`);
  process.exit(result.status ?? 1);
}
writeFileSync(stamp, `${digest}\n`);
