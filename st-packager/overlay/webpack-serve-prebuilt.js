import path from 'node:path';
import { serverDirectory } from '../server-directory.js';

/**
 * Cloud-prebuilt lib.js middleware.
 * Skips running webpack on mobile devices so server startup is near-instant.
 * @returns {import('express').RequestHandler & { runWebpackCompiler: (opts?: any) => Promise<void> }}
 */
export default function getWebpackServeMiddleware() {
    const distDir = path.join(serverDirectory, 'dist');

    function devMiddleware(req, res, next) {
        if (req.method === 'GET' && req.path === '/lib.js') {
            return res.sendFile('lib.js', { root: distDir });
        }
        return next();
    }

    devMiddleware.runWebpackCompiler = async () => {
        // Prebuilt during cloud packaging; no runtime compilation needed.
    };

    return devMiddleware;
}
