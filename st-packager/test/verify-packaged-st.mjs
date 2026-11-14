#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

function parseArgs(argv) {
    let appDir = '';
    for (let i = 2; i < argv.length; i++) {
        if (argv[i] === '--app-dir' && argv[i + 1]) {
            appDir = path.resolve(argv[++i]);
        }
    }
    if (!appDir) {
        throw new Error('Usage: verify-packaged-st.mjs --app-dir <packaged_st_app_dir>');
    }
    return { appDir };
}

function snapshotTree(dir) {
    const entries = new Map();
    function walk(curr) {
        for (const ent of fs.readdirSync(curr, { withFileTypes: true })) {
            const full = path.join(curr, ent.name);
            const rel = path.relative(dir, full);
            if (ent.isDirectory()) {
                entries.set(rel, 'dir');
                walk(full);
            } else if (ent.isFile()) {
                const st = fs.statSync(full);
                entries.set(rel, `${st.size}:${Math.trunc(st.mtimeMs)}`);
            }
        }
    }
    walk(dir);
    return entries;
}

class CookieJar {
    constructor() {
        this.cookies = new Map();
    }
    ingest(headers) {
        const setCookie = headers.getSetCookie ? headers.getSetCookie() : [];
        for (const raw of setCookie) {
            const first = raw.split(';')[0];
            const eq = first.indexOf('=');
            if (eq > 0) {
                this.cookies.set(first.slice(0, eq).trim(), first.slice(eq + 1).trim());
            }
        }
    }
    headerValue() {
        return Array.from(this.cookies.entries())
            .map(([k, v]) => `${k}=${v}`)
            .join('; ');
    }
}

async function testPureJsGitShim(appDir, tempRoot) {
    console.log('[verify] Testing pure-JS simple-git shim (clone/branch/fetch/log/pull/checkout)...');
    const shimUrl = pathToFileURL(path.join(appDir, 'node_modules', 'simple-git', 'index.js')).href;
    const { default: simpleGit, CheckRepoActions } = await import(shimUrl);

    // Create a local source git repo with 2 commits + 2 branches, served via a tiny Node HTTP git smart server or git daemon?
    // Even simpler if system git exists in test environment: create a real git repo + use `git http-backend` over Node http!
    const hasSystemGit = (() => {
        try {
            execFileSync('/usr/bin/git', ['--version'], { stdio: 'ignore' });
            return true;
        } catch {
            return false;
        }
    })();
    if (!hasSystemGit) {
        console.log('[verify] /usr/bin/git not present in VM; skipping HTTP git-backend roundtrip test.');
        return;
    }

    const reposRoot = path.join(tempRoot, 'git-repos');
    const seedWork = path.join(tempRoot, 'git-seed');
    const bareRepo = path.join(reposRoot, 'ext-test.git');
    const clonedDir = path.join(tempRoot, 'git-cloned');
    fs.mkdirSync(reposRoot, { recursive: true });
    fs.mkdirSync(seedWork, { recursive: true });

    execFileSync('/usr/bin/git', ['init', '-b', 'main', seedWork]);
    execFileSync('/usr/bin/git', ['-C', seedWork, 'config', 'user.name', 'Tester']);
    execFileSync('/usr/bin/git', ['-C', seedWork, 'config', 'user.email', 'tester@example.com']);
    fs.writeFileSync(path.join(seedWork, 'manifest.json'), JSON.stringify({
        display_name: 'Test Ext',
        author: 'CI',
        version: '1.0.0',
    }));
    execFileSync('/usr/bin/git', ['-C', seedWork, 'add', '.']);
    execFileSync('/usr/bin/git', ['-C', seedWork, 'commit', '-m', 'Initial commit']);
    execFileSync('/usr/bin/git', ['-C', seedWork, 'checkout', '-b', 'dev']);
    fs.writeFileSync(path.join(seedWork, 'dev.txt'), 'dev branch');
    execFileSync('/usr/bin/git', ['-C', seedWork, 'add', '.']);
    execFileSync('/usr/bin/git', ['-C', seedWork, 'commit', '-m', 'Dev commit']);
    execFileSync('/usr/bin/git', ['-C', seedWork, 'checkout', 'main']);

    execFileSync('/usr/bin/git', ['clone', '--bare', seedWork, bareRepo]);
    execFileSync('/usr/bin/git', ['-C', bareRepo, 'update-server-info']);

    // Spin up a minimal CGI bridge to `git http-backend` so isomorphic-git talks real HTTP git protocol
    const server = http.createServer((req, res) => {
        const url = new URL(req.url, 'http://127.0.0.1');
        const chunks = [];
        req.on('data', c => chunks.push(c));
        req.on('end', () => {
            const body = Buffer.concat(chunks);
            const env = {
                ...process.env,
                GIT_PROJECT_ROOT: reposRoot,
                GIT_HTTP_EXPORT_ALL: '1',
                PATH_INFO: url.pathname,
                QUERY_STRING: url.search.slice(1),
                REQUEST_METHOD: req.method,
                CONTENT_TYPE: req.headers['content-type'] || '',
                CONTENT_LENGTH: String(body.length),
                REMOTE_ADDR: '127.0.0.1',
            };
            const child = spawn('/usr/bin/git', ['http-backend'], { env });
            const out = [];
            child.stdout.on('data', d => out.push(d));
            child.stderr.on('data', d => process.stderr.write(d));
            child.on('close', () => {
                const buf = Buffer.concat(out);
                const sep = buf.indexOf('\r\n\r\n');
                const sepAlt = sep >= 0 ? sep : buf.indexOf('\n\n');
                const sepLen = sep >= 0 ? 4 : 2;
                const rawHeaders = buf.slice(0, sepAlt).toString('utf8');
                const payload = buf.slice(sepAlt + sepLen);
                let status = 200;
                for (const line of rawHeaders.split(/\r?\n/)) {
                    const idx = line.indexOf(':');
                    if (idx === -1) continue;
                    const k = line.slice(0, idx).trim();
                    const v = line.slice(idx + 1).trim();
                    if (k.toLowerCase() === 'status') {
                        status = parseInt(v, 10) || 200;
                    } else {
                        res.setHeader(k, v);
                    }
                }
                res.writeHead(status);
                res.end(payload);
            });
            child.stdin.end(body);
        });
    });

    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const addr = server.address();
    const repoUrl = `http://127.0.0.1:${addr.port}/ext-test.git`;

    try {
        const client = simpleGit();
        await client.clone(repoUrl, clonedDir, { '--depth': 1 });
        const repoGit = simpleGit({ baseDir: clonedDir });
        assert.equal(await repoGit.checkIsRepo(CheckRepoActions.IS_REPO_ROOT), true);
        const curBranch = await repoGit.branch();
        assert.equal(curBranch.current, 'main');
        const head1 = await repoGit.revparse(['HEAD']);
        assert.match(head1, /^[0-9a-f]{40}$/);

        // Push a new commit to bare repo, then test fetch/log/pull via pure JS
        fs.writeFileSync(path.join(seedWork, 'update.txt'), 'v2');
        execFileSync('/usr/bin/git', ['-C', seedWork, 'add', '.']);
        execFileSync('/usr/bin/git', ['-C', seedWork, 'commit', '-m', 'Update commit']);
        execFileSync('/usr/bin/git', ['-C', seedWork, 'push', bareRepo, 'main']);

        await repoGit.fetch('origin');
        const aheadLog = await repoGit.log({ from: head1, to: 'origin/main' });
        assert.equal(aheadLog.total, 1, 'Expected 1 commit ahead before pull');

        await repoGit.pull('origin', 'main');
        const head2 = await repoGit.revparse(['HEAD']);
        assert.notEqual(head2, head1);
        assert.equal(fs.readFileSync(path.join(clonedDir, 'update.txt'), 'utf8'), 'v2');

        // Unshallow + list all branches + checkout remote branch
        await repoGit.remote(['set-branches', 'origin', '*']);
        await repoGit.fetch('origin', ['--unshallow']);
        await repoGit.fetch('origin');
        const remoteBranches = await repoGit.branch(['-r', '--list', 'origin/*']);
        assert.ok(remoteBranches.all.includes('origin/dev'), `Expected origin/dev in ${remoteBranches.all}`);
        await repoGit.checkoutBranch('dev', 'origin/dev');
        assert.equal((await repoGit.branch()).current, 'dev');
        assert.equal(fs.readFileSync(path.join(clonedDir, 'dev.txt'), 'utf8'), 'dev branch');
        console.log('[verify] Pure-JS simple-git shim tests PASSED.');
    } finally {
        server.close();
    }
}

async function testTransformersImport(appDir) {
    console.log('[verify] Verifying sillytavern-transformers module import...');
    const transformersPath = pathToFileURL(path.join(appDir, 'src', 'transformers.js')).href;
    const mod = await import(transformersPath);
    assert.equal(typeof mod.getPipeline, 'function');
    assert.equal(typeof mod.getRawImage, 'function');
    console.log('[verify] sillytavern-transformers import PASSED.');
}

async function testServerRuntime(appDir, tempRoot) {
    console.log('[verify] Starting packaged SillyTavern with fake failing git in PATH & isolated DATA_ROOT...');
    const fakeBin = path.join(tempRoot, 'fake-bin');
    fs.mkdirSync(fakeBin, { recursive: true });
    const fakeGit = path.join(fakeBin, 'git');
    fs.writeFileSync(fakeGit, '#!/bin/sh\necho "ERROR: external git binary must never be called" >&2\nexit 99\n');
    fs.chmodSync(fakeGit, 0o755);

    const dataRoot = path.join(tempRoot, 'st_data');
    const configDir = path.join(tempRoot, 'st_config');
    const configPath = path.join(configDir, 'config.yaml');
    const appToken = crypto.randomBytes(24).toString('hex');

    const beforeTree = snapshotTree(appDir);

    const bootstrapPath = path.join(appDir, 'android-bootstrap.mjs');
    const env = {
        ...process.env,
        PATH: `${fakeBin}:${process.env.PATH || ''}`,
        NODE_ENV: 'production',
        ST_ANDROID_APP_DIR: appDir,
        ST_ANDROID_DATA_ROOT: dataRoot,
        ST_ANDROID_CONFIG_PATH: configPath,
        ST_ANDROID_PORT: '0',
        ST_ANDROID_APP_TOKEN: appToken,
    };

    const child = spawn(process.execPath, [bootstrapPath], {
        cwd: appDir,
        env,
        stdio: ['ignore', 'pipe', 'pipe'],
    });

    let logs = '';
    let boundPort = 0;

    const readyPromise = new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            reject(new Error(`Server failed to start within 30s. Logs:\n${logs}`));
        }, 30_000);

        const onChunk = (chunk) => {
            const text = chunk.toString('utf8');
            logs += text;
            const m = text.match(/Go to:\s*http:\/\/127\.0\.0\.1:(\d+)/);
            if (m) {
                boundPort = Number(m[1]);
                clearTimeout(timeout);
                resolve();
            }
        };

        child.stdout.on('data', onChunk);
        child.stderr.on('data', onChunk);
        child.on('exit', (code) => {
            clearTimeout(timeout);
            reject(new Error(`Server exited prematurely with code ${code}. Logs:\n${logs}`));
        });
    });

    try {
        await readyPromise;
        assert.ok(boundPort > 0, `Expected ephemeral port > 0, got ${boundPort}`);
        const baseUrl = `http://127.0.0.1:${boundPort}`;
        console.log(`[verify] Server ready on ${baseUrl}`);

        // 1. Unauthorized requests must fail with 403
        const unauthRoot = await fetch(`${baseUrl}/`);
        assert.equal(unauthRoot.status, 403, 'Unauthenticated GET / must be blocked');
        const unauthHealth = await fetch(`${baseUrl}/_app/health`);
        assert.equal(unauthHealth.status, 403, 'Unauthenticated health check must be blocked');
        const wrongToken = await fetch(`${baseUrl}/_app/bootstrap`, {
            headers: { 'X-App-Token': 'wrong-token' },
            redirect: 'manual',
        });
        assert.equal(wrongToken.status, 403, 'Wrong token bootstrap must be blocked');

        // 2. Authorized bootstrap sets HttpOnly session cookie & redirects to /
        const jar = new CookieJar();
        const bootRes = await fetch(`${baseUrl}/_app/bootstrap`, {
            headers: { 'X-App-Token': appToken },
            redirect: 'manual',
        });
        assert.equal(bootRes.status, 302);
        assert.equal(bootRes.headers.get('location'), '/');
        jar.ingest(bootRes.headers);
        assert.ok(jar.cookies.has('__st_app_session'), 'Session cookie must be issued');

        const authFetch = async (urlPath, init = {}) => {
            const headers = new Headers(init.headers || {});
            const cookieStr = jar.headerValue();
            if (cookieStr) headers.set('Cookie', cookieStr);
            const res = await fetch(`${baseUrl}${urlPath}`, { ...init, headers });
            jar.ingest(res.headers);
            return res;
        };

        // 3. Frontend index.html + bridge script + prebuilt lib.js
        const idxRes = await authFetch('/');
        assert.equal(idxRes.status, 200);
        const idxHtml = await idxRes.text();
        assert.ok(idxHtml.includes('/_app/android-bridge.js'), 'index.html must include android-bridge.js');

        const bridgeRes = await authFetch('/_app/android-bridge.js');
        assert.equal(bridgeRes.status, 200);
        assert.ok((await bridgeRes.text()).includes('STAndroidBridge'));

        const libRes = await authFetch('/lib.js');
        assert.equal(libRes.status, 200);
        const libText = await libRes.text();
        assert.ok(libText.length > 50_000, 'Prebuilt lib.js should be non-trivial');

        // 4. CSRF token + Version + Extensions discovery
        const csrfRes = await authFetch('/csrf-token');
        assert.equal(csrfRes.status, 200);
        const { token: csrfToken } = await csrfRes.json();
        assert.ok(csrfToken && csrfToken !== 'disabled');

        const verRes = await authFetch('/version');
        assert.equal(verRes.status, 200);
        const verJson = await verRes.json();
        assert.ok(verJson.pkgVersion && verJson.pkgVersion !== 'UNKNOWN');

        const extRes = await authFetch('/api/extensions/discover');
        assert.equal(extRes.status, 200);
        const extList = await extRes.json();
        assert.ok(Array.isArray(extList) && extList.length > 0);

        // 5. Character create -> list -> export roundtrip (verifies DATA_ROOT persistence)
        const formData = new FormData();
        formData.append('ch_name', 'AndroidVerifyChar');
        formData.append('description', 'Self-built Android embedded SillyTavern test');
        formData.append('personality', 'Helpful');
        formData.append('scenario', 'Unit Test');
        formData.append('first_mes', 'Hello from Android sandbox!');
        formData.append('mes_example', '');

        const createRes = await authFetch('/api/characters/create', {
            method: 'POST',
            headers: { 'X-CSRF-Token': csrfToken },
            body: formData,
        });
        assert.equal(createRes.status, 200, `Character create failed: ${await createRes.clone().text()}`);
        const avatarFile = await createRes.text();
        assert.ok(avatarFile.endsWith('.png'));

        const charsRes = await authFetch('/api/characters/all', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: '{}',
        });
        assert.equal(charsRes.status, 200);
        const chars = await charsRes.json();
        assert.ok(chars.some(c => c.name === 'AndroidVerifyChar'));

        const exportCharRes = await authFetch('/api/characters/export', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: JSON.stringify({ format: 'json', avatar_url: avatarFile }),
        });
        assert.equal(exportCharRes.status, 200);
        const exportedCard = await exportCharRes.json();
        assert.equal(exportedCard.data.name, 'AndroidVerifyChar');

        const exportPngRes = await authFetch('/api/characters/export', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: JSON.stringify({ format: 'png', avatar_url: avatarFile }),
        });
        assert.equal(exportPngRes.status, 200);
        const pngBuf = Buffer.from(await exportPngRes.arrayBuffer());
        assert.equal(pngBuf.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', 'Exported card must be valid PNG');

        const tokenizeRes = await authFetch('/api/tokenizers/openai/encode?model=gpt-4o', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: JSON.stringify({ text: 'Hello SillyTavern on Android!' }),
        });
        assert.equal(tokenizeRes.status, 200);
        const tokenData = await tokenizeRes.json();
        assert.ok(Array.isArray(tokenData.ids) && tokenData.count > 0, 'OpenAI tiktoken encode must return tokens');

        const claudeTokRes = await authFetch('/api/tokenizers/claude/encode', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: JSON.stringify({ text: 'Hello SillyTavern on Android!' }),
        });
        assert.equal(claudeTokRes.status, 200);
        const claudeTokData = await claudeTokRes.json();
        assert.ok(Array.isArray(claudeTokData.ids) && claudeTokData.count > 0, 'Claude WebTokenizer encode must return tokens');

        // 6. Chat save -> get -> export roundtrip
        const saveChatRes = await authFetch('/api/chats/save', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: JSON.stringify({
                ch_name: 'AndroidVerifyChar',
                avatar_url: avatarFile,
                file_name: 'verify_chat',
                chat: [
                    { user_name: 'User', character_name: 'AndroidVerifyChar' },
                    { name: 'AndroidVerifyChar', is_user: false, mes: 'Hello from Android sandbox!' },
                ],
            }),
        });
        assert.equal(saveChatRes.status, 200);

        const exportChatRes = await authFetch('/api/chats/export', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': csrfToken,
            },
            body: JSON.stringify({
                file: 'verify_chat.jsonl',
                avatar_url: avatarFile,
                is_group: false,
                exportfilename: 'verify_chat.jsonl',
            }),
        });
        assert.equal(exportChatRes.status, 200);
        const chatExportJson = await exportChatRes.json();
        assert.ok(String(chatExportJson.result).includes('Hello from Android sandbox!'));

        // 7. Verify st_app was NOT mutated at all (all state lives in DATA_ROOT + configPath)
        const afterTree = snapshotTree(appDir);
        const diffKeys = [];
        for (const [k, v] of afterTree.entries()) {
            if (beforeTree.get(k) !== v) diffKeys.push(`added/changed:${k}`);
        }
        for (const k of beforeTree.keys()) {
            if (!afterTree.has(k)) diffKeys.push(`removed:${k}`);
        }
        assert.deepEqual(diffKeys, [], `st_app must stay immutable, found diffs: ${diffKeys.join(', ')}`);
        assert.ok(fs.existsSync(path.join(dataRoot, '_global_extensions')));
        assert.ok(fs.existsSync(path.join(dataRoot, '_public_backups')));
        assert.ok(fs.existsSync(configPath));

        console.log('[verify] All runtime & sandbox isolation assertions PASSED!');
    } finally {
        child.kill('SIGTERM');
        await new Promise((resolve) => {
            child.once('exit', resolve);
            setTimeout(() => {
                child.kill('SIGKILL');
                resolve();
            }, 3000);
        });
    }
}

async function main() {
    const { appDir } = parseArgs(process.argv);
    const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'st-verify-'));
    try {
        await testPureJsGitShim(appDir, tempRoot);
        await testTransformersImport(appDir);
        await testServerRuntime(appDir, tempRoot);
    } finally {
        fs.rmSync(tempRoot, { recursive: true, force: true });
    }
}

main().catch((err) => {
    console.error('[verify] FAILED:', err);
    process.exit(1);
});
