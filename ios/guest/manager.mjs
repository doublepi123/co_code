#!/usr/bin/env node
// allow: SIZE_OK -- The guest contract requires a single-file manager with no npm dependencies.
import { spawn } from 'node:child_process';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { constants, readlinkSync } from 'node:fs';
import { access, copyFile, mkdir, mkdtemp, open, readFile, readdir, rename, rm, symlink, writeFile, chmod } from 'node:fs/promises';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';

const definitions = [
  { id: 'cox', name: 'Cox', kind: 'server', required: true, pkg: '@doublepi123/cox', bin: 'cox' },
  { id: 'omo', name: 'OMO Native', kind: 'agent', required: false, pkg: 'omo-ai', bin: 'omo' },
  { id: 'omp', name: 'Oh My Pi', kind: 'agent', required: false, bin: 'omp' },
  { id: 'codex', name: 'Codex', kind: 'agent', required: false, pkg: '@openai/codex', bin: 'codex' },
  { id: 'claude', name: 'Claude Code', kind: 'agent', required: false, pkg: '@anthropic-ai/claude-code', bin: 'claude' },
  { id: 'opencode', name: 'OpenCode', kind: 'agent', required: false, pkg: '@opencode/cli', bin: 'opencode' },
  { id: 'grok', name: 'Grok', kind: 'agent', required: false, pkg: '@xai-official/grok', bin: 'grok' },
  { id: 'pi', name: 'Pi', kind: 'agent', required: false, pkg: '@earendil-works/pi-coding-agent', bin: 'pi' },
];
const byId = new Map(definitions.map(component => [component.id, component]));
const root = resolve(process.env.COCODE_ROOT ?? '/opt/cocode');
const seedRoot = resolve(process.env.COCODE_SEED_ROOT ?? '/usr/lib/cocode/seed');
const binRoot = join(root, 'bin');
const consolePath = process.env.COCODE_CONSOLE ?? '/dev/console';
const state = { cox: 'starting', checking: false, latest: new Map(), job: null, coxChild: null, coxRestart: null };
let stoppingCox = false;

function log(message) { process.stdout.write('CoCode: ' + message + '\n'); }
function componentPath(id) { return join(root, 'components', id); }
function currentVersion(id) { return readlinkVersion(join(componentPath(id), 'current')); }
async function readlinkVersion(path) {
  try { return await (await import('node:fs/promises')).readlink(path); }
  catch (error) { if (error?.code === 'ENOENT' || error?.code === 'EINVAL') return null; throw error; }
}
async function exists(path) { try { await access(path, constants.F_OK); return true; } catch (error) { if (error?.code === 'ENOENT') return false; throw error; } }
function run(command, args, options = {}) {
  const timeout = options.timeout ?? 120000;
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { cwd: options.cwd, env: options.env ?? process.env, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = ''; let stderr = ''; let settled = false;
    child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
    const timer = setTimeout(() => { child.kill('SIGKILL'); finish(new Error(command + ' timed out after ' + timeout + 'ms\n' + tail(stderr))); }, timeout);
    function finish(error) {
      if (settled) return; settled = true; clearTimeout(timer);
      if (error) reject(error); else resolvePromise({ stdout, stderr });
    }
    child.on('error', error => finish(new Error(error.message + '\n' + tail(stderr))));
    child.on('close', code => code === 0 ? finish(null) : finish(new Error(command + ' exited ' + code + '\n' + tail(stderr))));
  });
}
function tail(text) { return text.trim().split('\n').slice(-8).join('\n'); }
function validVersion(version) { return /^(latest|[0-9A-Za-z][0-9A-Za-z.+-]{0,63})$/.test(version); }
async function ompRelease(version = 'latest') {
  const endpoint = version === 'latest'
    ? 'latest'
    : 'tags/v' + encodeURIComponent(version);
  const release = await fetchJson('https://api.github.com/repos/can1357/oh-my-pi/releases/' + endpoint);
  if (release.draft || release.prerelease || typeof release.tag_name !== 'string') throw new Error('OMP release is unavailable');
  return release;
}
async function resolveVersion(component, requested) {
  if (component.id === 'omp') {
    const release = await ompRelease(requested);
    const version = release.tag_name.replace(/^v/, '');
    if (!validVersion(version) || (requested !== 'latest' && requested !== version)) throw new Error('Invalid OMP release tag');
    return version;
  }
  if (requested !== 'latest') return requested;
  const result = await run('npm', ['view', component.pkg + '@latest', 'version'], { timeout: 120000 });
  const version = result.stdout.trim();
  if (!validVersion(version) || version === 'latest') throw new Error('npm returned an invalid version');
  return version;
}
async function fetchJson(url) {
  const response = await fetch(url, { headers: { 'user-agent': 'CoCode-component-manager', accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(120000) });
  if (!response.ok) throw new Error('GET ' + url + ' returned HTTP ' + response.status);
  return await response.json();
}
async function switchCurrent(id, version) { return await switchCurrentAt(componentPath(id), version); }
async function switchCurrentAt(dir, version) {
  const linkTmp = join(dir, '.current-' + process.pid + '-' + randomBytes(4).toString('hex'));
  await symlink(version, linkTmp);
  await rename(linkTmp, join(dir, 'current'));
}
async function refreshBin(component) {
  await mkdir(binRoot, { recursive: true });
  const linkPath = join(binRoot, component.bin);
  await rm(linkPath, { force: true });
  const version = await currentVersion(component.id);
  if (version !== null) await symlink('../components/' + component.id + '/current/bin/' + component.bin, linkPath);
}
async function install(component, requested, announce = true, targetDir = componentPath(component.id)) {
  const version = await resolveVersion(component, requested);
  const dir = targetDir === componentPath(component.id) ? targetDir : dirname(targetDir);
  await mkdir(dir, { recursive: true });
  if (targetDir === componentPath(component.id) && await currentVersion(component.id) === version) return version;
  const staging = await mkdtemp(join(dir, '.staging-'));
  const cache = join(staging, '.npm-cache');
  try {
    if (announce) log('installing ' + component.name + ' ' + version);
    if (component.id === 'omp') await installOmp(component, version, staging);
    else {
      await run('npm', ['install', '--global', '--prefix', staging, '--no-fund', '--no-audit', '--no-update-notifier', '--cache', cache, component.pkg + '@' + version], { timeout: 1800000, env: { ...process.env, npm_config_nodedir: '/usr' } });
      if (component.id === 'cox') {
        const pty = join(staging, 'lib/node_modules/@doublepi123/cox/node_modules/node-pty');
        await run('npx', ['--yes', 'node-gyp', 'rebuild', '--nodedir=/usr'], { cwd: pty, timeout: 1800000 });
        if (!(await exists(join(pty, 'build/Release/pty.node')))) throw new Error('node-pty rebuild did not produce pty.node');
        await rm(join(pty, 'prebuilds'), { recursive: true, force: true });
      }
      const packageJson = JSON.parse(await readFile(join(staging, 'lib/node_modules', component.pkg, 'package.json'), 'utf8'));
      if (packageJson.version !== version) throw new Error('installed package version ' + packageJson.version + ' does not match ' + version);
    }
    const executable = join(staging, 'bin', component.bin);
    if (!(await exists(executable))) throw new Error('missing executable ' + component.bin);
    await rm(cache, { recursive: true, force: true });
    const destination = targetDir === componentPath(component.id) ? join(dir, version) : targetDir;
    if (await exists(destination)) await rm(destination, { recursive: true, force: true });
    await rename(staging, destination);
    if (targetDir === componentPath(component.id)) {
      await switchCurrent(component.id, version);
      await refreshBin(component);
      if (component.id !== 'cox') await removeOldVersions(component.id, version);
    }
    if (announce) log(component.name + ' ' + version + ' installed');
    return version;
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    throw error;
  }
}
async function installOmp(component, version, staging) {
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64';
  if (process.arch !== 'arm64' && process.arch !== 'x64') throw new Error('Unsupported OMP architecture: ' + process.arch);
  const release = await ompRelease(version);
  const assetName = 'omp-linux-musl-' + arch;
  const sumsAsset = release.assets?.find(asset => asset.name === 'SHA256SUMS.txt');
  const binaryAsset = release.assets?.find(asset => asset.name === assetName);
  if (!sumsAsset || !binaryAsset || release.tag_name.replace(/^v/, '') !== version) throw new Error('OMP release assets are incomplete');
  const sumsResponse = await fetch(sumsAsset.browser_download_url, { signal: AbortSignal.timeout(120000) });
  const binaryResponse = await fetch(binaryAsset.browser_download_url, { signal: AbortSignal.timeout(120000) });
  if (!sumsResponse.ok || !binaryResponse.ok) throw new Error('OMP asset download failed');
  const sums = await sumsResponse.text();
  const entry = sums.split('\n').find(line => line.trim().endsWith('  ' + assetName) || line.trim().endsWith(' *' + assetName));
  const expected = entry?.trim().split(/\s+/)[0];
  const bytes = Buffer.from(await binaryResponse.arrayBuffer());
  if (!expected || createHash('sha256').update(bytes).digest('hex') !== expected) throw new Error('OMP SHA-256 verification failed');
  await mkdir(join(staging, 'bin'), { recursive: true });
  const executable = join(staging, 'bin', component.bin);
  await writeFile(executable, bytes, { mode: 0o755 });
  await chmod(executable, 0o755);
}
async function removeOldVersions(id, keep) {
  const dir = componentPath(id);
  for (const item of await readdir(dir)) if (item !== keep && item !== 'current' && !item.startsWith('.')) await rm(join(dir, item), { recursive: true, force: true });
}
async function adoptSeeds() {
  for (const component of definitions) {
    await mkdir(componentPath(component.id), { recursive: true });
    if (await currentVersion(component.id) !== null) { await refreshBin(component); continue; }
    const seedDir = join(seedRoot, component.id);
    if (!(await exists(seedDir))) continue;
    const versions = (await readdir(seedDir, { withFileTypes: true })).filter(item => item.isDirectory()).map(item => item.name).sort();
    if (versions.length === 0) continue;
    const version = versions.at(-1);
    const destination = join(componentPath(component.id), version);
    const staging = await mkdtemp(join(componentPath(component.id), '.adopt-'));
    await rm(staging, { recursive: true, force: true });
    await copyTree(join(seedDir, version), staging);
    await rename(staging, destination);
    await switchCurrent(component.id, version);
    await refreshBin(component);
  }
}
async function copyTree(from, to) {
  await mkdir(to, { recursive: true });
  for (const entry of await readdir(from, { withFileTypes: true })) {
    const src = join(from, entry.name); const dst = join(to, entry.name);
    if (entry.isDirectory()) await copyTree(src, dst);
    else if (entry.isSymbolicLink()) await symlink(await (await import('node:fs/promises')).readlink(src), dst);
    else await copyFile(src, dst);
  }
}
async function removeComponent(component) {
  await rm(join(binRoot, component.bin), { force: true });
  await rm(componentPath(component.id), { recursive: true, force: true });
}
async function ensurePassword() {
  const passwordFile = '/root/.cocode-password';
  await mkdir('/root', { recursive: true });
  let password = null;
  try { password = (await readFile(passwordFile, 'utf8')).trim(); }
  catch (error) { if (error?.code !== 'ENOENT') throw error; }
  // iOS can end the app, and with it the guest, right after the first boot;
  // a file renamed into place before its data reached the disk comes back
  // empty, so the password is synced and an unreadable one is replaced.
  if (!/^[0-9a-f]{32,128}$/.test(password ?? '')) {
    if (password !== null) log('the saved local password was unreadable; creating a new one');
    password = randomBytes(24).toString('hex');
    const temporary = passwordFile + '.' + process.pid;
    const file = await open(temporary, 'w', 0o600);
    try { await file.writeFile(password + '\n'); await file.sync(); } finally { await file.close(); }
    await rename(temporary, passwordFile);
    const directory = await open('/root', 'r');
    try { await directory.sync(); } finally { await directory.close(); }
  }
  await announcePassword(password);
  return password;
}
async function announcePassword(password) {
  await writeFile(consolePath, '\nCoCode local password: ' + password + '\n\n');
}
async function prepareHome() {
  const coxHome = '/root/.cox';
  await mkdir('/root/projects', { recursive: true });
  await mkdir(join(coxHome, 'userdata'), { recursive: true });
  const settings = join(coxHome, 'userdata/settings.json');
  if (!(await exists(settings))) await writeFile(settings, '{"providerInstances":{"omo":{"driver":"omo","enabled":true,"config":{}}}}\n', { mode: 0o600 });
}
function runtimeEnv(password) {
  return { ...process.env, PATH: '/opt/cocode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', HOME: '/root', COX_HOME: '/root/.cox', COX_AUTH_PASSWORD: password, COX_UPDATES_MANAGED_BY: 'CoCode', T3CODE_HOST: '0.0.0.0', T3CODE_PORT: '3773', T3CODE_NO_BROWSER: 'true' };
}
async function probeCox() {
  try { await fetch('http://127.0.0.1:3773/.well-known/t3/environment', { signal: AbortSignal.timeout(1200) }); return true; }
  catch { return false; }
}
async function startCox(password) {
  let delay = 2000;
  while (!stoppingCox) {
    const version = await currentVersion('cox');
    if (version === null) {
      try { await install(byId.get('cox'), 'latest'); }
      catch (error) { log('Cox install failed: ' + error.message); await new Promise(resolvePromise => setTimeout(resolvePromise, 60000)); continue; }
    }
    await rm('/root/.cox/userdata/server-runtime.json', { force: true });
    const child = spawn('/opt/cocode/bin/cox', [], { cwd: '/root', env: runtimeEnv(password), stdio: 'inherit' });
    state.coxChild = child;
    state.cox = 'starting';
    const exited = new Promise(resolvePromise => child.once('exit', (code, signal) => resolvePromise({ code, signal })));
    while (state.coxChild === child && !stoppingCox) {
      if (await probeCox()) {
        state.cox = 'running';
        // The host signs in as soon as Cox answers; repeating the line then
        // spares it from depending on the boot-time copy alone.
        await announcePassword(password).catch(error => log('cannot write the password line: ' + error.message));
        break;
      }
      await new Promise(resolvePromise => setTimeout(resolvePromise, 500));
    }
    if (state.cox === 'running') delay = 2000;
    const result = await exited;
    if (state.coxChild !== child || stoppingCox) return;
    state.coxChild = null; state.cox = 'restarting';
    log('Cox exited (' + (result.code ?? result.signal) + '); restarting');
    await new Promise(resolvePromise => setTimeout(resolvePromise, delay));
    delay = Math.min(delay * 2, 60000);
  }
  state.cox = 'stopped';
}
async function stopCox() {
  const child = state.coxChild;
  if (!child) return;
  stoppingCox = true;
  state.cox = 'restarting';
  child.kill('SIGTERM');
  await Promise.race([new Promise(resolvePromise => child.once('exit', resolvePromise)), new Promise(resolvePromise => setTimeout(resolvePromise, 20000))]);
  if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  state.coxChild = null;
  stoppingCox = false;
}
async function latestFor(component) {
  if (component.id === 'omp') return await resolveVersion(component, 'latest');
  return (await run('npm', ['view', component.pkg + '@latest', 'version'], { timeout: 120000 })).stdout.trim();
}
function stateBody() {
  return { cox: { state: state.cox }, checking: state.checking, components: definitions.map(component => ({ id: component.id, name: component.name, kind: component.kind, required: component.required, installed: currentVersionSync(component.id), latest: state.latest.get(component.id) ?? null })), job: state.job };
}
function currentVersionSync(id) {
  try { return requireSyncReadlink(join(componentPath(id), 'current')); }
  catch { return null; }
}
function requireSyncReadlink(path) {
  return readlinkSync(path);
}
function json(response, status, data) {
  const body = JSON.stringify(data);
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
  response.end(body);
}
async function bodyJson(request) {
  const chunks = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4096) throw new Error('body too large');
    chunks.push(chunk);
  }
  if (size === 0) return {};
  const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid JSON body');
  return value;
}
function authorized(request, password) {
  const match = /^Bearer (.+)$/.exec(request.headers.authorization ?? '');
  if (!match) return false;
  const supplied = Buffer.from(match[1]); const expected = Buffer.from(password);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
function beginJob(component, action, version) {
  const startedAt = new Date().toISOString();
  const job = { id: randomBytes(12).toString('hex'), component: component.id, action, version, state: 'running', message: 'running', startedAt, finishedAt: null };
  state.job = job;
  void (async () => {
    try {
      if (action === 'install') {
        const installed = await install(component, version ?? 'latest');
        if (component.id === 'cox') {
          // Cox starts slowly in the iOS guest; the job ends once the new
          // version is in place and restarting, and the state reports when it answers.
          await stopCox();
          await removeOldVersions('cox', installed);
          void startCox(passwordForJobs);
        }
        job.version = installed; job.message = installed + ' installed';
      } else await removeComponent(component);
      job.state = 'succeeded';
      log(component.name + ' ' + (action === 'install' ? job.version + ' installed' : 'removed'));
    } catch (error) {
      job.state = 'failed'; job.message = error instanceof Error ? tail(error.message) : String(error);
      log(component.name + ' ' + action + ' failed: ' + job.message.replaceAll('\n', ' | '));
    } finally { job.finishedAt = new Date().toISOString(); }
  })();
  return job;
}
let passwordForJobs = '';
function createApi(password) {
  passwordForJobs = password;
  return createServer(async (request, response) => {
    if (!authorized(request, password)) return json(response, 401, { error: 'unauthorized' });
    const url = new URL(request.url ?? '/', 'http://localhost');
    if (request.method === 'GET' && url.pathname === '/v1/state') return json(response, 200, stateBody());
    if (request.method === 'POST' && url.pathname === '/v1/check') {
      if (state.checking) return json(response, 202, stateBody());
      state.checking = true;
      void Promise.all(definitions.map(async component => { try { state.latest.set(component.id, await latestFor(component)); } catch { state.latest.set(component.id, null); } })).finally(() => { state.checking = false; });
      return json(response, 202, stateBody());
    }
    const match = /^\/v1\/components\/([^/]+)\/(install|remove)$/.exec(url.pathname);
    if (request.method === 'POST' && match) {
      const component = byId.get(match[1]);
      if (!component) return json(response, 404, { error: 'unknown component' });
      if (match[2] === 'remove' && component.required) return json(response, 409, { error: 'component is required' });
      let body;
      try { body = await bodyJson(request); }
      catch (error) { return json(response, error.message === 'body too large' ? 413 : 400, { error: 'invalid request body' }); }
      const version = match[2] === 'install' ? body.version ?? 'latest' : null;
      if (match[2] === 'install' && (typeof version !== 'string' || !validVersion(version))) return json(response, 400, { error: 'invalid version' });
      if (state.job?.state === 'running') return json(response, 409, { error: 'job already running' });
      if (match[2] === 'install') {
        const job = beginJob(component, 'install', version);
        return json(response, 202, { job });
      }
      const job = beginJob(component, 'remove', null);
      return json(response, 202, { job });
    }
    return json(response, 404, { error: 'not found' });
  });
}
async function serve() {
  process.umask(0o077);
  const password = await ensurePassword();
  await prepareHome();
  await mkdir(root, { recursive: true }); await mkdir(binRoot, { recursive: true });
  await adoptSeeds();
  void startCox(password);
  const port = Number(process.env.COCODE_MANAGER_PORT ?? 3774);
  const server = createApi(password);
  server.listen(port, '0.0.0.0', () => log('manager listening on port ' + port));
  const healthTimer = setInterval(async () => { if (state.coxChild && await probeCox()) state.cox = 'running'; else if (state.coxChild && state.cox === 'running') state.cox = 'restarting'; }, 1500);
  process.on('SIGTERM', async () => { clearInterval(healthTimer); stoppingCox = true; await stopCox(); server.close(); process.exit(0); });
}
async function installSeed(id, version, prefix) {
  const component = byId.get(id);
  if (!component || !validVersion(version) || version === 'latest') throw new Error('install-seed requires a known component and exact version');
  const target = resolve(prefix);
  const parent = dirname(target);
  await mkdir(parent, { recursive: true });
  await install(component, version, false, target);
}

if (process.argv[2] === 'install-seed') {
  try { await installSeed(process.argv[3], process.argv[4], process.argv[5]); }
  catch (error) { process.stderr.write('CoCode: seed install failed: ' + (error instanceof Error ? error.message : String(error)) + '\n'); process.exitCode = 1; }
} else if (process.argv[2] === undefined || process.argv[2] === 'serve') await serve();
else { process.stderr.write('Usage: node manager.mjs [serve | install-seed <id> <version> <prefix>]\n'); process.exitCode = 2; }
