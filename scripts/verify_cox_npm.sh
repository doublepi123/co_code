#!/bin/sh
set -eu

version=${1:?Usage: sh verify_cox_npm.sh VERSION (inside a disposable Alpine guest)}
case "$version" in
    *[!0-9.]* | '' ) echo 'Expected a stable numeric version' >&2; exit 2 ;;
esac

apk add --no-cache nodejs npm python3 make g++ linux-headers gcompat git
npm i -g --no-fund --no-audit "@doublepi123/cox@$version"
module=/usr/local/lib/node_modules/@doublepi123/cox
cd "$module/node_modules/node-pty"
npx --yes node-gyp rebuild
test -f build/Release/pty.node
rm -rf prebuilds
export COX_SMOKE_VERSION="$version"
node <<'NODE'
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { randomBytes } = require('node:crypto');
const { spawn } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');

const moduleRoot = '/usr/local/lib/node_modules/@doublepi123/cox';
const load = createRequire(moduleRoot + '/package.json');
const home = mkdtempSync(join(tmpdir(), 'cox-npm-smoke-'));
const password = randomBytes(24).toString('hex');
const base = 'http://127.0.0.1:4773';
let server;
let serverLog = '';
const deadline = setTimeout(() => { server?.kill('SIGKILL'); process.exit(1); }, 120000);

async function main() {
    assert.equal(load('./package.json').version, process.env.COX_SMOKE_VERSION);
    load('@napi-rs/keyring');
    load('@ff-labs/fff-node');
    const vendor = moduleRoot + '/node_modules/@cursor/sdk-linux-' + process.arch + '/vendor/';
    require(vendor + 'tree-sitter');
    require(vendor + 'tree-sitter-bash');
    console.log('NATIVE_OK');

    await new Promise((resolve, reject) => {
        const terminal = load('node-pty').spawn('/bin/sh', ['-c', 'echo PTYOK'], {
            cols: 80, rows: 24, cwd: home, env: process.env,
        });
        let output = '';
        terminal.onData(data => { output += data; });
        terminal.onExit(({ exitCode }) => {
            if (exitCode === 0 && output.includes('PTYOK')) resolve();
            else reject(new Error('PTY smoke failed'));
        });
    });
    console.log('PTY_OK');

    process.env.HOME = home;
    process.env.XDG_CONFIG_HOME = join(home, 'config');
    process.env.XDG_DATA_HOME = join(home, 'data');
    process.env.XDG_CACHE_HOME = join(home, 'cache');
    process.env.XDG_STATE_HOME = join(home, 'state');
    process.chdir(home);
    const { createAgentPlatform } = load('@cursor/sdk');
    for (const enabled of [true, false, true]) {
        const platform = await createAgentPlatform({ workspaceRef: home });
        const release = await platform.prewarmLocalWorkspace({
            local: { cwd: home, settingSources: [], sandboxOptions: { enabled } },
        });
        await release();
    }
    console.log('CURSOR_SDK_OFFLINE_OK');

    server = spawn(process.execPath, [moduleRoot + '/dist/bin.mjs'], {
        cwd: home,
        env: {
            ...process.env,
            COX_HOME: join(home, '.cox'),
            T3CODE_HOST: '127.0.0.1',
            T3CODE_PORT: '4773',
            T3CODE_NO_BROWSER: 'true',
            COX_AUTH_PASSWORD: password,
            T3CODE_AUTH_PASSWORD: password,
            NODE_OPTIONS: '--require /repo/app/src/main/assets/android-shim.cjs',
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    server.stdout.on('data', data => { serverLog += data; });
    server.stderr.on('data', data => { serverLog += data; });
    server.on('error', error => { serverLog += String(error); });
    let environment;
    for (let attempt = 0; attempt < 100; attempt++) {
        if (server.exitCode !== null) throw new Error('Cox exited before readiness');
        try {
            const response = await fetch(base + '/.well-known/t3/environment', { signal: AbortSignal.timeout(1000) });
            if (response.ok) { environment = await response.json(); break; }
        } catch {}
        await delay(300);
    }
    assert.equal(environment?.serverVersion, process.env.COX_SMOKE_VERSION);
    assert.ok(environment.environmentId);
    console.log('SERVER_HEALTH_OK');

    const login = credential => fetch(base + '/api/auth/browser-session', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ credential }),
        signal: AbortSignal.timeout(10000),
    });
    const denied = await login('not-the-password');
    assert.equal(denied.status, 401);
    const response = await login(password);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).authenticated, true);
    const cookie = response.headers.get('set-cookie');
    assert.ok(cookie);
    const page = await fetch(base + '/', { headers: { cookie: cookie.split(';')[0] } });
    assert.equal(page.status, 200);
    assert.match(await page.text(), /<html/i);
    console.log('AUTH_AND_UI_OK');
}

main().catch(error => {
    console.error(error);
    console.error(serverLog.replaceAll(password, '[redacted]'));
    process.exitCode = 1;
}).finally(async () => {
    clearTimeout(deadline);
    if (server && server.exitCode === null) {
        const exited = new Promise(resolve => server.once('exit', resolve));
        server.kill('SIGTERM');
        await Promise.race([exited, delay(3000)]);
        if (server.exitCode === null) { server.kill('SIGKILL'); await exited; }
    }
    process.chdir(tmpdir());
    rmSync(home, { recursive: true, force: true });
});
NODE
