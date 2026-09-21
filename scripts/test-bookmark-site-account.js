#!/usr/bin/env node
'use strict';

const assert = require('assert');
const path = require('path');
const api = require(path.join(__dirname, '..', 'assets/js/bookmark-site-account.js'));

function memoryStorage(initial) {
    const map = Object.assign({}, initial || {});
    return {
        getItem(key) {
            return Object.prototype.hasOwnProperty.call(map, key) ? map[key] : null;
        },
        setItem(key, value) {
            map[key] = String(value);
        },
        removeItem(key) {
            delete map[key];
        },
        dump() {
            return Object.assign({}, map);
        }
    };
}

async function run() {
    const older = {
        id: 'old',
        description: '网址管理器数据',
        updated_at: '2024-01-01T00:00:00Z',
        files: { 'urls.json': { filename: 'urls.json' } }
    };
    const newer = {
        id: 'new',
        description: '其它',
        updated_at: '2025-06-01T00:00:00Z',
        files: { 'urls.json': { filename: 'urls.json' } }
    };
    const descOnly = {
        id: 'desc',
        description: '网址管理器数据（备份）',
        updated_at: '2023-01-01T00:00:00Z',
        files: { 'readme.md': { filename: 'readme.md' } }
    };
    const unrelated = {
        id: 'other',
        description: 'random notes',
        updated_at: '2026-01-01T00:00:00Z',
        files: { 'notes.txt': { filename: 'notes.txt' } }
    };

    assert.strictEqual(api.pickBookmarkGist([unrelated]), null, 'unrelated gists are ignored');
    assert.strictEqual(api.pickBookmarkGist([older, newer, descOnly, unrelated]).id, 'new', 'newest bookmark gist wins');
    assert.strictEqual(api.pickBookmarkGist([descOnly, unrelated]).id, 'desc', 'description match is enough');
    assert.strictEqual(api.pickBookmarkGist([]), null, 'empty list yields null');

    const calls = [];
    const fakeToken = 'ghp_testtoken_not_a_password';
    const sitePassword = 'site-password-should-never-be-sent';
    const fetchMock = async (url, options) => {
        calls.push({ url, options });
        return {
            ok: true,
            json: async () => [unrelated, older, newer]
        };
    };
    const found = await api.findBookmarkGist(fakeToken, fetchMock);
    assert.strictEqual(found.id, 'new');
    assert.strictEqual(calls.length, 1);
    assert.strictEqual(calls[0].url, api.GIST_LIST_URL);
    const auth = calls[0].options.headers.Authorization;
    assert.strictEqual(auth, 'Bearer ' + fakeToken);
    assert.ok(auth.indexOf(sitePassword) === -1, 'site password must not be sent to GitHub');

    const missing = await api.findBookmarkGist(fakeToken, async () => ({
        ok: true,
        json: async () => [unrelated]
    }));
    assert.strictEqual(missing, null, 'no match returns null so caller can create');

    await assert.rejects(
        () => api.findBookmarkGist(fakeToken, async () => ({
            ok: false,
            json: async () => ({ message: 'Bad credentials' })
        })),
        /Bad credentials/
    );

    const secret = 'ghp_plaintext_secret_token_value';
    const vault = await api.encryptToken('correct horse', secret);
    const serialized = JSON.stringify(vault);
    assert.ok(serialized.indexOf(secret) === -1, 'ciphertext blob must not contain plaintext token');
    assert.ok(vault.salt && vault.iv && vault.ciphertext);
    assert.strictEqual(vault.kdf, 'PBKDF2');
    assert.strictEqual(vault.algo, 'AES-GCM');
    const unlocked = await api.decryptToken('correct horse', vault);
    assert.strictEqual(unlocked, secret);
    await assert.rejects(() => api.decryptToken('wrong password', vault), /无法解锁令牌/);

    const storage = memoryStorage({ githubToken: secret, gistId: 'legacy' });
    vault.username = 'alice';
    vault.gistId = '';
    api.writeVault(vault, storage);
    assert.strictEqual(storage.getItem('githubToken'), null, 'legacy plaintext token is removed');
    assert.ok(storage.getItem(api.VAULT_KEY).indexOf(secret) === -1);
    const loaded = api.readVault(storage);
    assert.strictEqual(loaded.username, 'alice');
    assert.strictEqual(api.readLegacyPlaintextToken(storage), '');

    assert.strictEqual(api.looksLikeAccessToken('mypassword'), false);
    assert.strictEqual(api.looksLikeAccessToken('ghp_abcdefghijklmnopqrstuvwxyz012345'), true);
    assert.strictEqual(api.looksLikeAccessToken('github_pat_11AAAA'), true);

    console.log('bookmark-site-account tests passed');
}

run().catch((error) => {
    console.error(error);
    process.exit(1);
});
