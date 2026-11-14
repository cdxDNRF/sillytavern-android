import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { serverDirectory } from './src/server-directory.js';

const SESSION_COOKIE_NAME = '__st_app_session';
const SESSION_SECRET = crypto.randomBytes(32).toString('hex');
const BRIDGE_SCRIPT_PATH = path.join(serverDirectory, '_app', 'android-bridge.js');

let cachedBridgeScript = null;

/**
 * Constant-time string comparison.
 * @param {string} a
 * @param {string} b
 * @returns {boolean}
 */
function safeEqual(a, b) {
    if (typeof a !== 'string' || typeof b !== 'string') return false;
    const bufA = Buffer.from(a, 'utf8');
    const bufB = Buffer.from(b, 'utf8');
    if (bufA.length !== bufB.length) return false;
    return crypto.timingSafeEqual(bufA, bufB);
}

/**
 * Parses a specific cookie value from Cookie header cleanly.
 * @param {string | undefined} cookieHeader
 * @param {string} name
 * @returns {string | null}
 */
function readCookie(cookieHeader, name) {
    if (!cookieHeader) return null;
    const parts = cookieHeader.split(';');
    for (const part of parts) {
        const eqIdx = part.indexOf('=');
        if (eqIdx === -1) continue;
        const k = part.slice(0, eqIdx).trim();
        if (k === name) {
            return part.slice(eqIdx + 1).trim();
        }
    }
    return null;
}

/**
 * Express middleware isolating SillyTavern to our own Android WebView process
 * and serving the client-side Android bridge assets.
 * @returns {import('express').RequestHandler}
 */
export function createAndroidAppMiddleware() {
    const appToken = process.env.ST_ANDROID_APP_TOKEN || '';

    return function androidAppMiddleware(req, res, next) {
        const reqPath = req.path;

        // Bootstrap endpoint called once by native WebView with X-App-Token header.
        if (req.method === 'GET' && reqPath === '/_app/bootstrap') {
            if (appToken) {
                const headerToken = req.headers['x-app-token']?.toString() || '';
                if (!safeEqual(headerToken, appToken)) {
                    return res.status(403).json({ error: 'Forbidden: invalid app token' });
                }
            }
            res.setHeader(
                'Set-Cookie',
                `${SESSION_COOKIE_NAME}=${SESSION_SECRET}; Path=/; HttpOnly; SameSite=Lax`,
            );
            return res.redirect(302, '/');
        }

        // Enforce local app-only authentication on every other request when token is configured.
        if (appToken) {
            const headerToken = req.headers['x-app-token']?.toString() || '';
            const cookieSession = readCookie(req.headers.cookie, SESSION_COOKIE_NAME) || '';
            const authorized = safeEqual(headerToken, appToken) || safeEqual(cookieSession, SESSION_SECRET);
            if (!authorized) {
                return res.status(403).json({ error: 'Forbidden: unauthorized local client' });
            }
        }

        if (req.method === 'GET' && reqPath === '/_app/android-bridge.js') {
            if (cachedBridgeScript === null) {
                cachedBridgeScript = fs.readFileSync(BRIDGE_SCRIPT_PATH, 'utf8');
            }
            res.setHeader('Content-Type', 'application/javascript; charset=utf-8');
            res.setHeader('Cache-Control', 'no-store');
            return res.send(cachedBridgeScript);
        }

        if (req.method === 'GET' && reqPath === '/_app/health') {
            return res.json({
                ok: true,
                pid: process.pid,
                port: globalThis.COMMAND_LINE_ARGS?.port ?? null,
                dataRoot: globalThis.DATA_ROOT ?? null,
            });
        }

        return next();
    };
}
