(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    root.BookmarkSiteAccount = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    const VAULT_KEY = 'bookmarkSiteVault';
    const LEGACY_TOKEN_KEY = 'githubToken';
    const GIST_DESCRIPTION = '网址管理器数据';
    const BOOKMARK_FILENAME = 'urls.json';
    const PBKDF2_ITERATIONS = 210000;
    const GIST_LIST_URL = 'https://api.github.com/gists?per_page=100';

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
        const response = await fetchImpl(GIST_LIST_URL, {
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
        return pickBookmarkGist(data);
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

    return {
        VAULT_KEY,
        LEGACY_TOKEN_KEY,
        GIST_DESCRIPTION,
        BOOKMARK_FILENAME,
        PBKDF2_ITERATIONS,
        GIST_LIST_URL,
        toBase64,
        fromBase64,
        encryptToken,
        decryptToken,
        looksLikeAccessToken,
        isBookmarkGist,
        pickBookmarkGist,
        findBookmarkGist,
        readVault,
        writeVault,
        readLegacyPlaintextToken
    };
});
