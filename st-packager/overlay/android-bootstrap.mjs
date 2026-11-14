import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

/**
 * Clean Android embedded entrypoint for SillyTavern.
 * Configured via environment variables provided by libst_bridge.so (or test harness):
 *   - ST_ANDROID_APP_DIR     (optional, defaults to directory containing this script)
 *   - ST_ANDROID_DATA_ROOT   (required in app: filesDir/st_data)
 *   - ST_ANDROID_CONFIG_PATH (optional, defaults to filesDir/st_config/config.yaml)
 *   - ST_ANDROID_PORT        (optional, defaults to 0 = ephemeral loopback port)
 *   - ST_ANDROID_APP_TOKEN   (optional in tests, always set in Android app)
 */
async function main() {
    const appDir = path.resolve(
        process.env.ST_ANDROID_APP_DIR || path.dirname(new URL(import.meta.url).pathname),
    );
    const dataRoot = path.resolve(
        process.env.ST_ANDROID_DATA_ROOT || path.join(appDir, 'data'),
    );
    const configPath = path.resolve(
        process.env.ST_ANDROID_CONFIG_PATH || path.join(dataRoot, 'config.yaml'),
    );
    const port = process.env.ST_ANDROID_PORT !== undefined ? String(process.env.ST_ANDROID_PORT) : '0';

    fs.mkdirSync(dataRoot, { recursive: true });
    fs.mkdirSync(path.dirname(configPath), { recursive: true });

    // Expose DATA_ROOT before any static SillyTavern module imports evaluate constants.js.
    process.env.ST_ANDROID_DATA_ROOT = dataRoot;
    globalThis.DATA_ROOT = dataRoot;
    process.chdir(appDir);

    // Ensure clean deterministic CLI flags: strictly localhost-only, no external browser spawn,
    // explicit sandboxed configPath and dataRoot.
    process.argv = [
        process.argv[0] || 'node',
        path.join(appDir, 'server.js'),
        '--configPath',
        configPath,
        '--dataRoot',
        dataRoot,
        '--port',
        port,
        '--listen',
        'false',
        '--browserLaunchEnabled',
        'false',
        '--enableIPv4',
        'true',
        '--enableIPv6',
        'false',
    ];

    const { CommandLineParser } = await import(pathToFileURL(path.join(appDir, 'src/command-line.js')).href);
    const cliArgs = new CommandLineParser().parse(process.argv);
    cliArgs.listen = false;
    cliArgs.browserLaunchEnabled = false;
    cliArgs.enableIPv4 = true;
    cliArgs.enableIPv6 = false;
    globalThis.DATA_ROOT = cliArgs.dataRoot;
    globalThis.COMMAND_LINE_ARGS = cliArgs;

    const { serverEvents, EVENT_NAMES } = await import(pathToFileURL(path.join(appDir, 'src/server-events.js')).href);
    serverEvents.once(EVENT_NAMES.SERVER_STARTED, (payload) => {
        const boundPort = Number(payload?.port ?? globalThis.COMMAND_LINE_ARGS?.port ?? 0);
        const boundUrl = payload?.url ? String(payload.url) : `http://127.0.0.1:${boundPort}`;
        if (typeof globalThis.__ST_ANDROID_NOTIFY_READY__ === 'function') {
            globalThis.__ST_ANDROID_NOTIFY_READY__(boundPort, boundUrl);
        }
    });

    await import(pathToFileURL(path.join(appDir, 'src/server-main.js')).href);
}

main().catch((err) => {
    console.error('[STAndroidBootstrap] Fatal startup error:', err);
    if (typeof globalThis.__ST_ANDROID_NOTIFY_ERROR__ === 'function') {
        globalThis.__ST_ANDROID_NOTIFY_ERROR__(String(err?.stack || err));
    }
    process.exitCode = 1;
});
