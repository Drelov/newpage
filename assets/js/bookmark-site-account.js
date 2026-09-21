(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    root.BookmarkSiteAccount = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    const VAULT_KEY = 'bookmarkSiteVault';
    const LEGACY_TOKEN_KEY = 'githubToken';
    const SESSION_TOKEN_KEY = 'bookmarkSiteSessionToken';
    const GIST_DESCRIPTION = '网址管理器数据';
    const BOOKMARK_FILENAME = 'urls.json';
    const PBKDF2_ITERATIONS = 210000;
    const GIST_LIST_URL = 'https://api.github.com/gists?per_page=100';
    const GIST_LIST_MAX_PAGES = 10;
    const RESERVED_FOLDERS = ['全部', '星标', '未分类'];
    const KEEPALIVE_MAX_BYTES = 60000;
    const AUTH_CHANNEL_NAME = 'newpage-bookmark-auth';

    function getCrypto() {
        const cryptoObj = globalThis.crypto;
        if (!cryptoObj || !cryptoObj.subtle) {
            throw new Error('当前环境不支持 Web Crypto，无法加密令牌');
        }
        return cryptoObj;
    }

    function toBase64(buffer) {
        const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
        let binary = '';
        for (let i = 0; i < bytes.length; i += 1) {
            binary += String.fromCharCode(bytes[i]);
        }
        return btoa(binary);
    }

    function fromBase64(value) {
        const binary = atob(String(value || ''));
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) {
            bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
    }

    async function deriveKey(password, salt, iterations) {
        const cryptoObj = getCrypto();
        const material = await cryptoObj.subtle.importKey(
            'raw',
            new TextEncoder().encode(password),
            'PBKDF2',
            false,
            ['deriveKey']
        );
        return cryptoObj.subtle.deriveKey(
            {
                name: 'PBKDF2',
                salt,
                iterations: iterations || PBKDF2_ITERATIONS,
                hash: 'SHA-256'
            },
            material,
            { name: 'AES-GCM', length: 256 },
            false,
            ['encrypt', 'decrypt']
        );
    }

    async function encryptToken(password, token) {
        if (!password) throw new Error('请设置本站密码');
        if (!token) throw new Error('请粘贴个人访问令牌');
        const cryptoObj = getCrypto();
        const salt = cryptoObj.getRandomValues(new Uint8Array(16));
        const iv = cryptoObj.getRandomValues(new Uint8Array(12));
        const key = await deriveKey(password, salt, PBKDF2_ITERATIONS);
        const ciphertext = await cryptoObj.subtle.encrypt(
            { name: 'AES-GCM', iv },
            key,
            new TextEncoder().encode(token)
        );
        return {
            v: 1,
            kdf: 'PBKDF2',
            hash: 'SHA-256',
            algo: 'AES-GCM',
            iterations: PBKDF2_ITERATIONS,
            salt: toBase64(salt),
            iv: toBase64(iv),
            ciphertext: toBase64(ciphertext)
        };
    }

    async function decryptToken(password, vault) {
        if (!vault || !vault.ciphertext || !vault.salt || !vault.iv) {
            throw new Error('本机没有可用的账号保险柜');
        }
        const key = await deriveKey(password, fromBase64(vault.salt), vault.iterations || PBKDF2_ITERATIONS);
        try {
            const plain = await getCrypto().subtle.decrypt(
                { name: 'AES-GCM', iv: fromBase64(vault.iv) },
                key,
                fromBase64(vault.ciphertext)
            );
            return new TextDecoder().decode(plain);
        } catch (error) {
            throw new Error('用户名或密码不正确，无法解锁令牌');
        }
    }

    function looksLikeAccessToken(value) {
        const token = String(value || '').trim();
        if (!token) return false;
        if (/^gh[pousr]_/.test(token) || token.indexOf('github_pat_') === 0) return true;
        return token.length >= 32 && !/\s/.test(token);
    }

    function isBookmarkGist(gist) {
        if (!gist || typeof gist !== 'object') return false;
        const files = gist.files || {};
        if (Object.prototype.hasOwnProperty.call(files, BOOKMARK_FILENAME)) return true;
        return String(gist.description || '').indexOf(GIST_DESCRIPTION) !== -1;
    }

    function gistTimestamp(gist) {
        return Date.parse((gist && (gist.updated_at || gist.created_at)) || 0) || 0;
    }

    function pickBookmarkGist(gists) {
        const matches = (Array.isArray(gists) ? gists : []).filter(isBookmarkGist);
        if (!matches.length) return null;
        matches.sort((a, b) => gistTimestamp(b) - gistTimestamp(a));
        return matches[0];
    }

    async function findBookmarkGist(token, fetchFn) {
        const fetchImpl = fetchFn || (typeof fetch === 'function' ? fetch : null);
        if (!fetchImpl) throw new Error('当前环境无法请求 GitHub');
        if (!token) throw new Error('缺少访问令牌');
        let url = GIST_LIST_URL;
        let best = null;
        let pages = 0;
        while (url && pages < GIST_LIST_MAX_PAGES) {
            pages += 1;
            const response = await fetchImpl(url, {
                headers: {
                    Authorization: 'Bearer ' + token,
                    Accept: 'application/vnd.github+json'
                }
            });
            let data = null;
            try {
                data = await response.json();
            } catch (error) {
                data = null;
            }
            if (!response.ok) {
                throw new Error((data && data.message) || '无法列出 Gist');
            }
            if (!Array.isArray(data)) throw new Error('Gist 列表格式不正确');
            const picked = pickBookmarkGist(data);
            if (picked && (!best || gistTimestamp(picked) > gistTimestamp(best))) {
                best = picked;
            }
            url = parseNextLink(responseLinkHeader(response));
        }
        return best;
    }

    function readVault(storage) {
        const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null);
        if (!store) return null;
        const raw = store.getItem(VAULT_KEY);
        if (!raw) return null;
        try {
            const parsed = JSON.parse(raw);
            if (!parsed || !parsed.ciphertext) return null;
            return parsed;
        } catch (error) {
            return null;
        }
    }

    async function updateVaultSecret(vault, currentPassword, options) {
        const opts = options || {};
        const currentToken = await decryptToken(currentPassword, vault);
        const nextPassword = opts.newPassword ? String(opts.newPassword) : currentPassword;
        const nextToken = opts.newToken ? String(opts.newToken).trim() : currentToken;
        if (!opts.newPassword && !opts.newToken) {
            throw new Error('请填写新密码或新令牌');
        }
        if (opts.newPassword && nextPassword.length < 6) {
            throw new Error('本站密码至少 6 位');
        }
        if (opts.newToken && !looksLikeAccessToken(nextToken)) {
            throw new Error('这不像 gist 个人访问令牌');
        }
        const record = await encryptToken(nextPassword, nextToken);
        record.username = (vault && vault.username) || '';
        record.gistId = (vault && vault.gistId) || '';
        return { record, token: nextToken };
    }

    function writeVault(record, storage) {
        const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null);
        if (!store) throw new Error('无法写入本机保险柜');
        store.setItem(VAULT_KEY, JSON.stringify(record));
        store.removeItem(LEGACY_TOKEN_KEY);
        return record;
    }

    function readLegacyPlaintextToken(storage) {
        const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null);
        if (!store) return '';
        return String(store.getItem(LEGACY_TOKEN_KEY) || '').trim();
    }

    function sessionStore(storage) {
        if (storage) return storage;
        try {
            return typeof sessionStorage !== 'undefined' ? sessionStorage : null;
        } catch (error) {
            return null;
        }
    }

    function readSessionToken(storage) {
        const store = sessionStore(storage);
        if (!store || typeof store.getItem !== 'function') return '';
        try {
            return String(store.getItem(SESSION_TOKEN_KEY) || '').trim();
        } catch (error) {
            return '';
        }
    }

    function writeSessionToken(token, storage) {
        const store = sessionStore(storage);
        const value = String(token || '').trim();
        if (!store) return value;
        try {
            if (value) store.setItem(SESSION_TOKEN_KEY, value);
            else store.removeItem(SESSION_TOKEN_KEY);
        } catch (error) {
            // sessionStorage can throw in private mode; memory token still works for this page.
        }
        return value;
    }

    function clearSessionToken(storage) {
        writeSessionToken('', storage);
    }

    function parseTimestamp(value) {
        if (value == null || value === '') return 0;
        if (typeof value === 'number') {
            return Number.isFinite(value) && value > 0 ? value : 0;
        }
        const str = String(value).trim();
        if (!str) return 0;
        if (/^-?\d+(\.\d+)?$/.test(str)) {
            const num = Number(str);
            return Number.isFinite(num) && num > 0 ? num : 0;
        }
        const parsed = Date.parse(str);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
    }

    function contentTimestamp(url) {
        return parseTimestamp(url && url.updatedAt);
    }

    function orderTimestamp(url) {
        return parseTimestamp(url && url.orderUpdatedAt);
    }

    function uid() {
        if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
        return 'id-' + Date.now() + '-' + Math.random().toString(16).slice(2);
    }

    function hostnameOf(link) {
        try {
            return new URL(link).hostname;
        } catch (error) {
            return '';
        }
    }

    function normalizeLink(raw) {
        const trimmed = String(raw || '').trim();
        if (!trimmed) return '';
        if (/^https?:\/\//i.test(trimmed)) return trimmed;
        if (/^\/\//.test(trimmed)) return 'https:' + trimmed;
        return 'https://' + trimmed;
    }

    const TRACKING_QUERY_PARAMS = [
        'fbclid', 'gclid', 'gclsrc', 'dclid', 'msclkid',
        'mc_cid', 'mc_eid', '_ga', '_gl', 'yclid', 'igshid', 'si'
    ];

    function isTrackingQueryParam(key) {
        const name = String(key || '').toLowerCase();
        if (!name) return false;
        if (name.indexOf('utm_') === 0) return true;
        return TRACKING_QUERY_PARAMS.indexOf(name) !== -1;
    }

    function stripTrackingSearch(search) {
        if (!search) return '';
        try {
            const raw = search.charAt(0) === '?' ? search.slice(1) : search;
            const params = new URLSearchParams(raw);
            const next = new URLSearchParams();
            params.forEach((value, key) => {
                if (isTrackingQueryParam(key)) return;
                next.append(key, value);
            });
            const serialized = next.toString();
            return serialized ? '?' + serialized : '';
        } catch (error) {
            return search;
        }
    }

    function canonicalHost(host) {
        const name = String(host || '').toLowerCase();
        return name.indexOf('www.') === 0 ? name.slice(4) : name;
    }

    function canonicalLink(raw) {
        const normalized = normalizeLink(raw);
        if (!normalized) return '';
        try {
            const parsed = new URL(normalized);
            if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
                return normalized.toLowerCase();
            }
            const host = canonicalHost(parsed.hostname);
            let path = parsed.pathname || '/';
            if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);
            return 'https://' + host + path + stripTrackingSearch(parsed.search);
        } catch (error) {
            return normalized.toLowerCase();
        }
    }

    function parseNextLink(header) {
        if (!header) return '';
        const parts = String(header).split(',');
        for (let i = 0; i < parts.length; i += 1) {
            const match = parts[i].match(/<([^>]+)>\s*;\s*rel="?next"?/i);
            if (match) return match[1];
        }
        return '';
    }

    function responseLinkHeader(response) {
        if (!response || !response.headers) return '';
        if (typeof response.headers.get === 'function') {
            return response.headers.get('Link') || response.headers.get('link') || '';
        }
        return response.headers.Link || response.headers.link || '';
    }

    function migrateUrl(raw, index) {
        const link = normalizeLink(raw && raw.link);
        const orderRaw = raw && raw.order;
        const orderNum = Number(orderRaw);
        return {
            id: (raw && raw.id) || uid(),
            name: String((raw && raw.name) || hostnameOf(link) || '未命名').trim(),
            link,
            folder: (raw && raw.folder) || '',
            favorite: !!(raw && raw.favorite),
            createdAt: parseTimestamp(raw && raw.createdAt),
            updatedAt: parseTimestamp(raw && raw.updatedAt),
            order: Number.isFinite(orderNum) ? orderNum : (typeof index === 'number' ? index : 0),
            orderUpdatedAt: parseTimestamp(raw && raw.orderUpdatedAt)
        };
    }

    function mergeUrlLists(localUrls, remoteUrls) {
        const merged = (localUrls || []).map((url, index) => migrateUrl(url, index));
        const byId = new Map(merged.map((url) => [url.id, url]));
        const byLink = new Map();
        merged.forEach((url) => {
            const key = canonicalLink(url.link);
            if (key) byLink.set(key, url);
        });

        (remoteUrls || []).forEach((raw, index) => {
            const remote = migrateUrl(raw, index);
            const remoteKey = canonicalLink(remote.link);
            let local = byId.get(remote.id);
            if (!local && remoteKey) local = byLink.get(remoteKey);
            if (!local) {
                merged.push(remote);
                byId.set(remote.id, remote);
                if (remoteKey) byLink.set(remoteKey, remote);
                return;
            }

            if (contentTimestamp(remote) > contentTimestamp(local)) {
                const keepId = local.id;
                const keepCreated = local.createdAt || remote.createdAt;
                const keepOrder = local.order;
                const keepOrderAt = local.orderUpdatedAt;
                Object.assign(local, remote, {
                    id: keepId,
                    createdAt: keepCreated,
                    order: keepOrder,
                    orderUpdatedAt: keepOrderAt
                });
                const localKey = canonicalLink(local.link);
                if (localKey) byLink.set(localKey, local);
            }

            if (orderTimestamp(remote) > orderTimestamp(local)) {
                local.order = remote.order;
                local.orderUpdatedAt = remote.orderUpdatedAt;
            }
        });

        merged.sort((a, b) => {
            const orderDiff = (Number(a.order) || 0) - (Number(b.order) || 0);
            if (orderDiff !== 0) return orderDiff;
            return String(a.id || '').localeCompare(String(b.id || ''));
        });
        return merged;
    }

    function mergeFolders(localFolders, remoteFolders) {
        const out = [];
        const seen = new Set();
        (localFolders || []).concat(remoteFolders || []).forEach((folder) => {
            if (!folder || RESERVED_FOLDERS.indexOf(folder) !== -1 || seen.has(folder)) return;
            seen.add(folder);
            out.push(folder);
        });
        return out;
    }

    function applyReorder(urls, orderedIds, now) {
        const list = Array.isArray(urls) ? urls : [];
        const ids = Array.isArray(orderedIds) ? orderedIds : [];
        if (!ids.length) return { urls: list, changed: false };
        const stamp = parseTimestamp(now) || Date.now();
        const visibleSet = new Set(ids);
        const byId = new Map(list.map((url) => [url.id, url]));
        const next = [];
        let visIndex = 0;
        list.forEach((url) => {
            if (visibleSet.has(url.id)) {
                const moved = byId.get(ids[visIndex++]);
                if (moved) next.push(moved);
            } else {
                next.push(url);
            }
        });
        let changed = false;
        next.forEach((url, index) => {
            if (url.order !== index) {
                url.order = index;
                url.orderUpdatedAt = stamp;
                changed = true;
            } else {
                url.order = index;
            }
        });
        return { urls: next, changed };
    }

    function dropTargetPatch(tabName) {
        if (!tabName || tabName === '全部') return null;
        if (tabName === '星标') return { favorite: true };
        if (tabName === '未分类') return { folder: '' };
        return { folder: tabName };
    }

    function applyDropTargetPatch(url, tabName) {
        const patch = dropTargetPatch(tabName);
        if (!url || !patch) return { changed: false, url };
        let changed = false;
        const next = url;
        Object.keys(patch).forEach((key) => {
            if (next[key] !== patch[key]) {
                next[key] = patch[key];
                changed = true;
            }
        });
        return { changed, url: next };
    }

    function cloneBookmarkFields(url) {
        const migrated = migrateUrl(url || {});
        return {
            id: migrated.id,
            name: migrated.name,
            link: migrated.link,
            folder: migrated.folder,
            favorite: migrated.favorite,
            createdAt: migrated.createdAt,
            updatedAt: migrated.updatedAt,
            order: migrated.order,
            orderUpdatedAt: migrated.orderUpdatedAt
        };
    }

    function snapshotUrlDelete(urls, id) {
        const list = Array.isArray(urls) ? urls : [];
        const index = list.findIndex((url) => url && url.id === id);
        if (index === -1) return null;
        return {
            type: 'url',
            index,
            url: cloneBookmarkFields(list[index])
        };
    }

    function restoreUrlDelete(urls, snapshot) {
        const list = Array.isArray(urls) ? urls.slice() : [];
        if (!snapshot || snapshot.type !== 'url' || !snapshot.url) return list;
        if (list.some((url) => url && url.id === snapshot.url.id)) return list;
        const restored = migrateUrl(snapshot.url, snapshot.index);
        const index = Math.min(Math.max(0, Number(snapshot.index) || 0), list.length);
        list.splice(index, 0, restored);
        return list;
    }

    function snapshotFolderDelete(folders, urls, folderName, currentFolder) {
        const name = String(folderName || '');
        const list = Array.isArray(folders) ? folders : [];
        return {
            type: 'folder',
            folderName: name,
            folderIndex: list.indexOf(name),
            currentFolder: currentFolder || '',
            affected: (Array.isArray(urls) ? urls : [])
                .filter((url) => url && url.folder === name)
                .map((url) => ({
                    id: url.id,
                    folder: url.folder,
                    updatedAt: url.updatedAt
                }))
        };
    }

    function restoreFolderDelete(folders, urls, snapshot, currentFolder) {
        const nextFolders = Array.isArray(folders) ? folders.slice() : [];
        const nextUrls = Array.isArray(urls) ? urls : [];
        if (!snapshot || snapshot.type !== 'folder' || !snapshot.folderName) {
            return { folders: nextFolders, urls: nextUrls, currentFolder: currentFolder || '' };
        }
        if (nextFolders.indexOf(snapshot.folderName) === -1) {
            const index = snapshot.folderIndex >= 0 ? snapshot.folderIndex : nextFolders.length;
            nextFolders.splice(Math.min(index, nextFolders.length), 0, snapshot.folderName);
        }
        const byId = new Map((snapshot.affected || []).map((item) => [item.id, item]));
        nextUrls.forEach((url) => {
            const rec = byId.get(url.id);
            if (!rec) return;
            url.folder = rec.folder;
            url.updatedAt = rec.updatedAt;
        });
        return {
            folders: nextFolders,
            urls: nextUrls,
            currentFolder: snapshot.currentFolder || currentFolder || ''
        };
    }

    function shouldUseKeepalive(body, force) {
        if (!force) return false;
        const size = typeof body === 'string' ? body.length : 0;
        return size > 0 && size <= KEEPALIVE_MAX_BYTES;
    }

    return {
        VAULT_KEY,
        LEGACY_TOKEN_KEY,
        SESSION_TOKEN_KEY,
        GIST_DESCRIPTION,
        BOOKMARK_FILENAME,
        PBKDF2_ITERATIONS,
        GIST_LIST_URL,
        GIST_LIST_MAX_PAGES,
        RESERVED_FOLDERS,
        KEEPALIVE_MAX_BYTES,
        AUTH_CHANNEL_NAME,
        toBase64,
        fromBase64,
        encryptToken,
        decryptToken,
        updateVaultSecret,
        looksLikeAccessToken,
        isBookmarkGist,
        pickBookmarkGist,
        parseNextLink,
        findBookmarkGist,
        readVault,
        writeVault,
        readLegacyPlaintextToken,
        readSessionToken,
        writeSessionToken,
        clearSessionToken,
        parseTimestamp,
        contentTimestamp,
        orderTimestamp,
        normalizeLink,
        canonicalLink,
        isTrackingQueryParam,
        stripTrackingSearch,
        snapshotUrlDelete,
        restoreUrlDelete,
        snapshotFolderDelete,
        restoreFolderDelete,
        migrateUrl,
        mergeUrlLists,
        mergeFolders,
        applyReorder,
        dropTargetPatch,
        applyDropTargetPatch,
        shouldUseKeepalive
    };
});
