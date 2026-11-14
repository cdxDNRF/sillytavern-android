(function initSillyTavernAndroidBridge() {
    if (window.__stAndroidBridgeInstalled) {
        return;
    }
    window.__stAndroidBridgeInstalled = true;

    const blobRegistry = new Map();
    const BLOB_RETENTION_MS = 60_000;
    const CHUNK_SIZE = 192 * 1024; // 192 KiB raw -> ~256 KiB base64 per JNI bridge call

    function hasNativeBridge() {
        return typeof window.STAndroidBridge === 'object' && window.STAndroidBridge !== null;
    }

    function arrayBufferToBase64(buffer) {
        const bytes = new Uint8Array(buffer);
        const step = 0x8000;
        const parts = [];
        for (let i = 0; i < bytes.length; i += step) {
            parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + step)));
        }
        return btoa(parts.join(''));
    }

    function sanitizeFallbackFilename(name, mimeType) {
        const trimmed = (name || '').trim();
        if (trimmed) return trimmed;
        if (mimeType && mimeType.includes('png')) return 'export.png';
        if (mimeType && mimeType.includes('json')) return 'export.json';
        if (mimeType && mimeType.includes('zip')) return 'backup.zip';
        return 'sillytavern-export.dat';
    }

    function trackBlobUrl(url, object) {
        if (typeof url !== 'string' || !(object instanceof Blob)) {
            return;
        }
        const existing = blobRegistry.get(url);
        if (existing && existing.timer) {
            clearTimeout(existing.timer);
        }
        const timer = setTimeout(() => {
            blobRegistry.delete(url);
        }, BLOB_RETENTION_MS);
        blobRegistry.set(url, { blob: object, timer });
    }

    const nativeCreateObjectURL = URL.createObjectURL.bind(URL);
    const nativeRevokeObjectURL = URL.revokeObjectURL.bind(URL);

    URL.createObjectURL = function patchedCreateObjectURL(object) {
        const url = nativeCreateObjectURL(object);
        trackBlobUrl(url, object);
        return url;
    };

    URL.revokeObjectURL = function patchedRevokeObjectURL(url) {
        // Keep the Blob reference in blobRegistry until retention timer expires
        // so synchronous a.click() + URL.revokeObjectURL() exports never race.
        setTimeout(() => {
            try {
                nativeRevokeObjectURL(url);
            } catch {
                // ignore
            }
        }, 500);
    };

    const activeExports = new Set();

    async function exportBlobToNative(blob, filename, mimeTypeHint) {
        const bridge = window.STAndroidBridge;
        if (!bridge) {
            return false;
        }
        const mimeType = mimeTypeHint || blob.type || 'application/octet-stream';
        const finalName = sanitizeFallbackFilename(filename, mimeType);
        const totalSize = blob.size || 0;
        const key = `${finalName}:${totalSize}:${Date.now()}`;
        if (activeExports.has(key)) {
            return true;
        }
        activeExports.add(key);

        let exportId = '';
        try {
            exportId = bridge.beginExport(finalName, mimeType, totalSize);
            if (!exportId) {
                throw new Error('Failed to initialize native export stream');
            }
            for (let offset = 0; offset < totalSize; offset += CHUNK_SIZE) {
                const slice = blob.slice(offset, Math.min(offset + CHUNK_SIZE, totalSize));
                const buffer = await slice.arrayBuffer();
                if (buffer.byteLength > 0) {
                    bridge.appendExportChunk(exportId, arrayBufferToBase64(buffer));
                }
            }
            bridge.finishExport(exportId);
            return true;
        } catch (error) {
            console.error('[STAndroidBridge] Export failed:', error);
            if (exportId && typeof bridge.abortExport === 'function') {
                try {
                    bridge.abortExport(exportId, String(error?.message || error));
                } catch {
                    // ignore
                }
            }
            return false;
        } finally {
            activeExports.delete(key);
        }
    }

    async function handleDownloadAnchor(anchor) {
        if (!hasNativeBridge()) {
            return false;
        }
        const href = anchor.getAttribute('href') || anchor.href || '';
        const downloadName = anchor.getAttribute('download') || anchor.download || '';
        if (!href) {
            return false;
        }

        const isBlobOrData = href.startsWith('blob:') || href.startsWith('data:');
        const isExplicitDownload = anchor.hasAttribute('download') || Boolean(downloadName);

        if (!isBlobOrData && !isExplicitDownload) {
            return false;
        }

        if (href.startsWith('blob:')) {
            const entry = blobRegistry.get(href);
            if (entry && entry.blob) {
                await exportBlobToNative(entry.blob, downloadName, entry.blob.type);
                return true;
            }
        }

        try {
            const response = await fetch(href, { credentials: 'same-origin' });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const blob = await response.blob();
            let resolvedName = downloadName;
            if (!resolvedName) {
                const disposition = response.headers.get('content-disposition') || '';
                const match = disposition.match(/filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i);
                if (match && match[1]) {
                    try {
                        resolvedName = decodeURIComponent(match[1]);
                    } catch {
                        resolvedName = match[1];
                    }
                }
            }
            await exportBlobToNative(blob, resolvedName, blob.type);
            return true;
        } catch (error) {
            console.error('[STAndroidBridge] Anchor fetch export failed:', error);
            return false;
        }
    }

    const nativeAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function patchedAnchorClick() {
        const href = this.getAttribute('href') || this.href || '';
        const hasDownload = this.hasAttribute('download') || Boolean(this.download);
        if (hasNativeBridge() && (href.startsWith('blob:') || href.startsWith('data:') || hasDownload)) {
            void handleDownloadAnchor(this);
            return;
        }
        return nativeAnchorClick.apply(this, arguments);
    };

    document.addEventListener('click', (event) => {
        if (!hasNativeBridge()) return;
        const target = event.target instanceof Element ? event.target.closest('a') : null;
        if (!target) return;
        const href = target.getAttribute('href') || target.href || '';
        const hasDownload = target.hasAttribute('download') || Boolean(target.download);
        if (href.startsWith('blob:') || href.startsWith('data:') || hasDownload) {
            event.preventDefault();
            event.stopPropagation();
            void handleDownloadAnchor(target);
        }
    }, true);

    // Coordinate Android system back gesture with SillyTavern modals & drawers.
    window.__STAndroidHandleBack = function handleBack() {
        const openPopup = document.querySelector('dialog.popup[open], .popup[open]');
        if (openPopup) {
            const cancelBtn = openPopup.querySelector('.popup-button-cancel, .popup-button-close, .popup-button-ok');
            if (cancelBtn instanceof HTMLElement) {
                cancelBtn.click();
                return true;
            }
        }

        const shadowPopup = document.getElementById('shadow_popup');
        if (shadowPopup && getComputedStyle(shadowPopup).display !== 'none' && getComputedStyle(shadowPopup).opacity !== '0') {
            const closeBtn = shadowPopup.querySelector('#dialogue_popup_cancel, #dialogue_popup_ok');
            if (closeBtn instanceof HTMLElement) {
                closeBtn.click();
                return true;
            }
        }

        const openDrawer = document.querySelector('.drawer-content.openDrawer');
        if (openDrawer) {
            const drawer = openDrawer.closest('.drawer');
            const toggle = drawer?.querySelector('.drawer-toggle');
            if (toggle instanceof HTMLElement) {
                toggle.click();
                return true;
            }
        }

        return false;
    };
})();
