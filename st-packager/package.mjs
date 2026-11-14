#!/usr/bin/env node
import { execFileSync, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const OVERLAY_DIR = path.join(__dirname, 'overlay');

function parseArgs(argv) {
    const args = {
        source: '',
        repo: 'https://github.com/SillyTavern/SillyTavern.git',
        ref: 'release',
        workDir: path.resolve(__dirname, '.work'),
        outDir: path.resolve(__dirname, 'dist'),
        skipTests: false,
    };

    for (let i = 2; i < argv.length; i++) {
        const key = argv[i];
        const next = argv[i + 1];
        switch (key) {
            case '--source':
                args.source = path.resolve(next);
                i++;
                break;
            case '--repo':
                args.repo = next;
                i++;
                break;
            case '--ref':
                args.ref = next;
                i++;
                break;
            case '--work':
                args.workDir = path.resolve(next);
                i++;
                break;
            case '--out':
                args.outDir = path.resolve(next);
                i++;
                break;
            case '--skip-tests':
                args.skipTests = true;
                break;
            default:
                throw new Error(`Unknown argument: ${key}`);
        }
    }
    return args;
}

function logStep(msg) {
    console.log(`\n[st-packager] ${msg}`);
}

function run(cmd, args, cwd, env = process.env) {
    const res = spawnSync(cmd, args, {
        cwd,
        env,
        stdio: 'inherit',
    });
    if (res.status !== 0) {
        throw new Error(`Command failed (${res.status}): ${cmd} ${args.join(' ')}`);
    }
}

/**
 * Replaces an exact substring and fails loudly if not found so upstream drifts
 * never go unnoticed.
 */
function replaceExact(content, needle, replacement, label) {
    const idx = content.indexOf(needle);
    if (idx === -1) {
        throw new Error(`Patch assertion failed (${label}): pattern not found:\n${needle}`);
    }
    const secondIdx = content.indexOf(needle, idx + needle.length);
    if (secondIdx !== -1) {
        throw new Error(`Patch assertion failed (${label}): pattern matched multiple times`);
    }
    return content.slice(0, idx) + replacement + content.slice(idx + needle.length);
}

/**
 * Computes reachable production packages from package-lock.json excluding
 * packages removed/shimmed for the embedded mobile runtime.
 */
function pruneUnreachableModules(stageDir, excludedRootDeps) {
    const pkg = JSON.parse(fs.readFileSync(path.join(stageDir, 'package.json'), 'utf8'));
    const lock = JSON.parse(fs.readFileSync(path.join(stageDir, 'package-lock.json'), 'utf8'));
    const packages = lock.packages || {};

    const keepRelPaths = new Set();
    const queue = [];

    const rootDeps = { ...(pkg.dependencies || {}) };
    for (const ex of excludedRootDeps) {
        delete rootDeps[ex];
    }

    function enqueuePkg(depName, fromRelPath) {
        // Walk up node_modules scopes just like Node resolution
        let dir = fromRelPath;
        while (true) {
            const candidate = dir ? `${dir}/node_modules/${depName}` : `node_modules/${depName}`;
            if (packages[candidate]) {
                if (!keepRelPaths.has(candidate)) {
                    keepRelPaths.add(candidate);
                    queue.push(candidate);
                }
                return;
            }
            if (!dir) break;
            const lastNm = dir.lastIndexOf('/node_modules/');
            if (lastNm >= 0) {
                dir = dir.slice(0, lastNm);
            } else if (dir.startsWith('node_modules/')) {
                dir = '';
            } else {
                break;
            }
        }
    }

    for (const dep of Object.keys(rootDeps)) {
        enqueuePkg(dep, '');
    }

    while (queue.length > 0) {
        const rel = queue.shift();
        const meta = packages[rel];
        if (!meta) continue;
        const deps = {
            ...(meta.dependencies || {}),
            ...(meta.optionalDependencies || {}),
        };
        for (const dep of Object.keys(deps)) {
            enqueuePkg(dep, rel);
        }
    }

    let removedCount = 0;

    function walkNodeModules(nmDir, relPrefix) {
        if (!fs.existsSync(nmDir)) return;
        for (const entry of fs.readdirSync(nmDir, { withFileTypes: true })) {
            if (!entry.isDirectory() && !entry.isSymbolicLink()) continue;
            if (entry.name === '.bin') {
                fs.rmSync(path.join(nmDir, entry.name), { recursive: true, force: true });
                continue;
            }
            if (entry.name.startsWith('@')) {
                const scopeDir = path.join(nmDir, entry.name);
                for (const sub of fs.readdirSync(scopeDir, { withFileTypes: true })) {
                    if (!sub.isDirectory() && !sub.isSymbolicLink()) continue;
                    const pkgRel = `${relPrefix}/${entry.name}/${sub.name}`;
                    const pkgAbs = path.join(scopeDir, sub.name);
                    if (!keepRelPaths.has(pkgRel)) {
                        fs.rmSync(pkgAbs, { recursive: true, force: true });
                        removedCount++;
                    } else {
                        walkNodeModules(path.join(pkgAbs, 'node_modules'), `${pkgRel}/node_modules`);
                    }
                }
                if (fs.existsSync(scopeDir) && fs.readdirSync(scopeDir).length === 0) {
                    fs.rmdirSync(scopeDir);
                }
            } else {
                const pkgRel = `${relPrefix}/${entry.name}`;
                const pkgAbs = path.join(nmDir, entry.name);
                if (!keepRelPaths.has(pkgRel)) {
                    fs.rmSync(pkgAbs, { recursive: true, force: true });
                    removedCount++;
                } else {
                    walkNodeModules(path.join(pkgAbs, 'node_modules'), `${pkgRel}/node_modules`);
                }
            }
        }
    }

    walkNodeModules(path.join(stageDir, 'node_modules'), 'node_modules');
    console.log(`[st-packager] Tree-shaken ${removedCount} unreachable packages (kept ${keepRelPaths.size}).`);
}

function pruneBloatFiles(nodeModulesDir) {
    let bytesSaved = 0;
    let filesRemoved = 0;

    const removablePackageDirs = new Set(['test', 'tests', '__tests__', 'docs', 'example', 'examples', '.github']);
    const removableExts = ['.map', '.d.ts', '.d.mts', '.d.cts'];
    const removableNames = new Set(['.eslintrc', '.eslintrc.js', '.prettierrc', 'tsconfig.json']);

    function isSubdirReferencedInPkgJson(pkgRoot, dirName) {
        try {
            const pkgPath = path.join(pkgRoot, 'package.json');
            if (!fs.existsSync(pkgPath)) return false;
            const raw = fs.readFileSync(pkgPath, 'utf8');
            const pkg = JSON.parse(raw);
            const needleRegex = new RegExp(`(^|[/\\\\"'])${dirName}([/\\\\"']|$)`);
            const fields = [pkg.main, pkg.module, pkg.browser, pkg.exports, pkg.bin];
            return needleRegex.test(JSON.stringify(fields));
        } catch {
            return true;
        }
    }

    function cleanPackageFiles(dir) {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                if (entry.name === 'node_modules') {
                    walkNodeModules(full);
                    continue;
                }
                cleanPackageFiles(full);
            } else if (entry.isFile()) {
                const lower = entry.name.toLowerCase();
                if (removableExts.some(ext => lower.endsWith(ext)) || removableNames.has(lower)) {
                    const st = fs.statSync(full);
                    bytesSaved += st.size;
                    filesRemoved++;
                    fs.rmSync(full, { force: true });
                }
            }
        }
    }

    function prunePackageRoot(pkgRoot, pkgName) {
        if (pkgName !== 'tiktoken') {
            for (const entry of fs.readdirSync(pkgRoot, { withFileTypes: true })) {
                if (!entry.isDirectory()) continue;
                const lower = entry.name.toLowerCase();
                if (removablePackageDirs.has(lower) && !isSubdirReferencedInPkgJson(pkgRoot, entry.name)) {
                    fs.rmSync(path.join(pkgRoot, entry.name), { recursive: true, force: true });
                    filesRemoved++;
                }
            }
        }
        cleanPackageFiles(pkgRoot);
        const nestedNm = path.join(pkgRoot, 'node_modules');
        if (fs.existsSync(nestedNm)) {
            walkNodeModules(nestedNm);
        }
    }

    function walkNodeModules(nmDir) {
        if (!fs.existsSync(nmDir)) return;
        for (const entry of fs.readdirSync(nmDir, { withFileTypes: true })) {
            if (!entry.isDirectory()) continue;
            if (entry.name.startsWith('.')) continue;
            if (entry.name.startsWith('@')) {
                const scopeDir = path.join(nmDir, entry.name);
                for (const sub of fs.readdirSync(scopeDir, { withFileTypes: true })) {
                    if (!sub.isDirectory()) continue;
                    prunePackageRoot(path.join(scopeDir, sub.name), `${entry.name}/${sub.name}`);
                }
            } else {
                prunePackageRoot(path.join(nmDir, entry.name), entry.name);
            }
        }
    }

    walkNodeModules(nodeModulesDir);

    // Prune redundant threaded/duplicate ONNX WASMs (src/transformers.js forces numThreads = 1
    // and points wasmPaths to sillytavern-transformers/dist/).
    const stTransDist = path.join(nodeModulesDir, 'sillytavern-transformers', 'dist');
    for (const redundant of [
        'ort-wasm-threaded.wasm',
        'ort-wasm-simd-threaded.wasm',
        'transformers.js',
        'transformers.min.js',
    ]) {
        const p = path.join(stTransDist, redundant);
        if (fs.existsSync(p)) {
            bytesSaved += fs.statSync(p).size;
            fs.rmSync(p, { force: true });
            filesRemoved++;
        }
    }

    const ortWebDist = path.join(nodeModulesDir, 'onnxruntime-web', 'dist');
    if (fs.existsSync(ortWebDist)) {
        for (const file of fs.readdirSync(ortWebDist)) {
            if (file.endsWith('.wasm') || file.endsWith('.es5.min.js') || file.endsWith('.es6.min.js') || file === 'ort.js' || file === 'ort-web.js') {
                const p = path.join(ortWebDist, file);
                bytesSaved += fs.statSync(p).size;
                fs.rmSync(p, { force: true });
                filesRemoved++;
            }
        }
    }

    console.log(`[st-packager] Pruned ${filesRemoved} bloat files (~${(bytesSaved / (1024 * 1024)).toFixed(1)} MB saved).`);
}

function patchStage(stageDir) {
    // 1. Install Android overlay files
    fs.mkdirSync(path.join(stageDir, '_app'), { recursive: true });
    fs.copyFileSync(
        path.join(OVERLAY_DIR, 'android-bridge.js'),
        path.join(stageDir, '_app', 'android-bridge.js'),
    );
    fs.copyFileSync(
        path.join(OVERLAY_DIR, 'android-app-middleware.mjs'),
        path.join(stageDir, 'android-app-middleware.mjs'),
    );
    fs.copyFileSync(
        path.join(OVERLAY_DIR, 'android-bootstrap.mjs'),
        path.join(stageDir, 'android-bootstrap.mjs'),
    );

    // 2. Replace webpack-serve middleware with prebuilt dist/lib.js server
    fs.copyFileSync(
        path.join(OVERLAY_DIR, 'webpack-serve-prebuilt.js'),
        path.join(stageDir, 'src', 'middleware', 'webpack-serve.js'),
    );

    // 3. Install pure-JS shims for simple-git and command-exists
    const simpleGitTarget = path.join(stageDir, 'node_modules', 'simple-git');
    fs.rmSync(simpleGitTarget, { recursive: true, force: true });
    fs.cpSync(path.join(OVERLAY_DIR, 'shims', 'simple-git'), simpleGitTarget, { recursive: true });

    const commandExistsTarget = path.join(stageDir, 'node_modules', 'command-exists');
    fs.rmSync(commandExistsTarget, { recursive: true, force: true });
    fs.cpSync(path.join(OVERLAY_DIR, 'shims', 'command-exists'), commandExistsTarget, { recursive: true });

    // 4. Patch src/constants.js so backups & globalExtensions live inside DATA_ROOT
    const constantsPath = path.join(stageDir, 'src', 'constants.js');
    let constantsSrc = fs.readFileSync(constantsPath, 'utf8');
    constantsSrc = replaceExact(
        constantsSrc,
        `export const PUBLIC_DIRECTORIES = {
    images: 'public/img/',
    backups: 'backups/',
    sounds: 'public/sounds',
    extensions: 'public/scripts/extensions',
    globalExtensions: 'public/scripts/extensions/third-party',
};`,
        `import path from 'node:path';
import process from 'node:process';

const _androidDataRoot = globalThis.DATA_ROOT || process.env.ST_ANDROID_DATA_ROOT || '';

export const PUBLIC_DIRECTORIES = {
    images: 'public/img/',
    backups: _androidDataRoot ? path.join(_androidDataRoot, '_public_backups') : 'backups/',
    sounds: 'public/sounds',
    extensions: 'public/scripts/extensions',
    globalExtensions: _androidDataRoot ? path.join(_androidDataRoot, '_global_extensions') : 'public/scripts/extensions/third-party',
};`,
        'src/constants.js PUBLIC_DIRECTORIES',
    );
    fs.writeFileSync(constantsPath, constantsSrc, 'utf8');

    // 5. Patch src/users.js backup path resolution (path.join -> path.resolve for absolute PUBLIC_DIRECTORIES.backups)
    const usersPath = path.join(stageDir, 'src', 'users.js');
    let usersSrc = fs.readFileSync(usersPath, 'utf8');
    usersSrc = replaceExact(
        usersSrc,
        `const backupDirectory = path.join(process.cwd(), PUBLIC_DIRECTORIES.backups, '_migration', currentDate);`,
        `const backupDirectory = path.join(path.resolve(process.cwd(), PUBLIC_DIRECTORIES.backups), '_migration', currentDate);`,
        'src/users.js migrateUserData backupDirectory',
    );
    fs.writeFileSync(usersPath, usersSrc, 'utf8');

    // 6. Patch src/server-startup.js so ephemeral port 0 updates cliArgs.port when bound
    const startupPath = path.join(stageDir, 'src', 'server-startup.js');
    let startupSrc = fs.readFileSync(startupPath, 'utf8');
    startupSrc = replaceExact(
        startupSrc,
        `            server.on('error', reject);
            server.on('listening', resolve);

            let host = url.hostname;
            if (ipVersion === 6) host = urlHostnameToIPv6(url.hostname);
            server.listen({
                host: host,
                port: Number(url.port || 80),`,
        `            server.on('error', reject);
            server.on('listening', () => {
                const address = server.address();
                if (address && typeof address === 'object' && address.port) {
                    this.cliArgs.port = address.port;
                }
                resolve();
            });

            let host = url.hostname;
            if (ipVersion === 6) host = urlHostnameToIPv6(url.hostname);
            server.listen({
                host: host,
                port: url.port === '' ? 80 : Number(url.port),`,
        'src/server-startup.js #createHttpServer port 0 support',
    );
    fs.writeFileSync(startupPath, startupSrc, 'utf8');

    // 7. Patch src/server-main.js:
    //    a) Mount androidAppMiddleware right after const app = express();
    //    b) Store server plugins inside DATA_ROOT/_plugins rather than immutable st_app
    //    c) Include port in SERVER_STARTED event payload
    const mainPath = path.join(stageDir, 'src', 'server-main.js');
    let mainSrc = fs.readFileSync(mainPath, 'utf8');
    mainSrc = replaceExact(
        mainSrc,
        `import { router as usersPublicRouter } from './endpoints/users-public.js';`,
        `import { createAndroidAppMiddleware } from '../android-app-middleware.mjs';
import { router as usersPublicRouter } from './endpoints/users-public.js';`,
        'src/server-main.js import androidAppMiddleware',
    );
    mainSrc = replaceExact(
        mainSrc,
        `const app = express();`,
        `const app = express();
app.use(createAndroidAppMiddleware());`,
        'src/server-main.js mount androidAppMiddleware',
    );
    mainSrc = replaceExact(
        mainSrc,
        `    const pluginsDirectory = path.join(serverDirectory, 'plugins');`,
        `    const pluginsDirectory = path.join(globalThis.DATA_ROOT || serverDirectory, '_plugins');`,
        'src/server-main.js pluginsDirectory',
    );
    mainSrc = replaceExact(
        mainSrc,
        `    serverEvents.emit(EVENT_NAMES.SERVER_STARTED, { url: browserLaunchUrl });`,
        `    serverEvents.emit(EVENT_NAMES.SERVER_STARTED, { url: browserLaunchUrl, port: cliArgs.port });`,
        'src/server-main.js SERVER_STARTED payload',
    );
    fs.writeFileSync(mainPath, mainSrc, 'utf8');

    // 8. Inject android-bridge.js into public/index.html & public/login.html
    for (const htmlRel of ['public/index.html', 'public/login.html']) {
        const htmlPath = path.join(stageDir, htmlRel);
        let html = fs.readFileSync(htmlPath, 'utf8');
        const targetNeedle = htmlRel.endsWith('index.html')
            ? `<script src="lib/polyfill.js"></script>`
            : `<script src="lib/jquery-3.5.1.min.js"></script>`;
        html = replaceExact(
            html,
            targetNeedle,
            `<script src="/_app/android-bridge.js"></script>\n    ${targetNeedle}`,
            `${htmlRel} bridge script injection`,
        );
        fs.writeFileSync(htmlPath, html, 'utf8');
    }

    // 9. Remove empty third-party/backups/plugins directories from st_app so
    //    requests/creation fall cleanly through to DATA_ROOT!
    for (const rel of [
        'public/scripts/extensions/third-party',
        'backups',
        'plugins',
        'data',
        'colab',
        'docker',
        'tests',
        'src/electron',
        '.git',
        '.github',
        'start.sh',
        'replit.nix',
        'recover.js',
        'plugins.js',
        'webpack.config.js',
        'jsconfig.json',
        'index.d.ts',
        'package-lock.json',
        'eslint.config.js',
        '.eslintrc.json',
    ]) {
        const p = path.join(stageDir, rel);
        if (fs.existsSync(p)) {
            fs.rmSync(p, { recursive: true, force: true });
        }
    }
}

function createZipArchive(stageDir, zipOutPath) {
    const pyScript = `
import os, sys, zipfile
src_dir = sys.argv[1]
out_zip = sys.argv[2]
with zipfile.ZipFile(out_zip, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
    for root, dirs, files in os.walk(src_dir):
        dirs.sort()
        files.sort()
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src_dir).replace('\\\\', '/')
            zf.write(full, rel)
`;
    execFileSync('python3', ['-c', pyScript, stageDir, zipOutPath], { stdio: 'inherit' });
}

async function main() {
    const args = parseArgs(process.argv);
    fs.rmSync(args.workDir, { recursive: true, force: true });
    fs.mkdirSync(args.workDir, { recursive: true });
    fs.mkdirSync(args.outDir, { recursive: true });

    const stageDir = path.join(args.workDir, 'st_app');

    logStep('1/7 Acquiring SillyTavern upstream source...');
    let commitSha = 'local';
    if (args.source) {
        fs.cpSync(args.source, stageDir, {
            recursive: true,
            filter: (src) => !src.includes(`${path.sep}node_modules`) && !src.includes(`${path.sep}.git`),
        });
        try {
            commitSha = execFileSync('git', ['-C', args.source, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim();
        } catch {
            // ignore
        }
    } else {
        run('git', ['clone', '--depth', '1', '--branch', args.ref, args.repo, stageDir], args.workDir);
        commitSha = execFileSync('git', ['-C', stageDir, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim();
    }

    const pkg = JSON.parse(fs.readFileSync(path.join(stageDir, 'package.json'), 'utf8'));
    const stVersion = pkg.version || 'unknown';
    console.log(`[st-packager] Packaging SillyTavern v${stVersion} (${commitSha})`);

    logStep('2/7 Installing production dependencies...');
    run('npm', ['ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], stageDir);

    logStep('3/7 Pre-compiling frontend library bundle (webpack lib.js)...');
    run('node', ['docker/build-lib.js'], stageDir);
    const webpackRoot = path.join(stageDir, 'dist', '_webpack');
    const cacheDirs = fs.existsSync(webpackRoot) ? fs.readdirSync(webpackRoot) : [];
    let builtLibPath = '';
    for (const dir of cacheDirs) {
        const candidate = path.join(webpackRoot, dir, 'output', 'lib.js');
        if (fs.existsSync(candidate)) {
            builtLibPath = candidate;
            break;
        }
    }
    if (!builtLibPath) {
        throw new Error('Failed to locate webpack-compiled lib.js under dist/_webpack');
    }
    fs.mkdirSync(path.join(stageDir, 'dist'), { recursive: true });
    fs.copyFileSync(builtLibPath, path.join(stageDir, 'dist', 'lib.js'));
    fs.rmSync(webpackRoot, { recursive: true, force: true });
    const libSize = fs.statSync(path.join(stageDir, 'dist', 'lib.js')).size;
    console.log(`[st-packager] Built dist/lib.js (${(libSize / (1024 * 1024)).toFixed(2)} MB)`);

    logStep('4/7 Tree-shaking build-only & CLI-only dependencies...');
    pruneUnreachableModules(stageDir, ['webpack', 'simple-git', 'command-exists', 'open']);
    pruneBloatFiles(path.join(stageDir, 'node_modules'));

    logStep('5/7 Applying self-written Android sandbox, pure-JS git, and bridge patches...');
    patchStage(stageDir);

    const meta = {
        sillytavernVersion: stVersion,
        upstreamCommit: commitSha,
        packagedAt: new Date().toISOString(),
        runtime: 'libnode-android-arm64',
    };
    fs.writeFileSync(path.join(stageDir, 'st_meta.json'), JSON.stringify(meta, null, 2));

    if (!args.skipTests) {
        logStep('6/7 Running automated verification suite against packaged bundle...');
        const verifyScript = path.join(__dirname, 'test', 'verify-packaged-st.mjs');
        run('node', [verifyScript, '--app-dir', stageDir], __dirname);
    } else {
        logStep('6/7 Skipping tests (--skip-tests passed).');
    }

    logStep('7/7 Creating compressed st_app.zip + SHA-256 checksum...');
    const zipPath = path.join(args.outDir, 'st_app.zip');
    fs.rmSync(zipPath, { force: true });
    createZipArchive(stageDir, zipPath);

    const hash = crypto.createHash('sha256').update(fs.readFileSync(zipPath)).digest('hex');
    fs.writeFileSync(path.join(args.outDir, 'st_app.sha256'), `${hash}\n`);
    fs.writeFileSync(path.join(args.outDir, 'st_meta.json'), JSON.stringify({ ...meta, sha256: hash }, null, 2));

    const zipSizeMb = (fs.statSync(zipPath).size / (1024 * 1024)).toFixed(2);
    console.log(`[st-packager] Done! Output: ${zipPath} (${zipSizeMb} MB, sha256=${hash.slice(0, 12)}...)`);
}

main().catch((err) => {
    console.error('[st-packager] FAILED:', err);
    process.exit(1);
});
