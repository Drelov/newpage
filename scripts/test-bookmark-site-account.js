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

    assert.strictEqual(api.parseTimestamp(undefined), 0);
    assert.strictEqual(api.parseTimestamp(null), 0);
    assert.strictEqual(api.parseTimestamp(''), 0);
    assert.strictEqual(api.parseTimestamp(0), 0);
    assert.strictEqual(api.parseTimestamp(42), 42);
    assert.ok(api.parseTimestamp('2024-01-01T00:00:00Z') > 0);

    const skinny = api.migrateUrl({ id: 's', link: 'https://skinny.example', name: 'Skinny' });
    assert.strictEqual(skinny.updatedAt, 0, 'missing updatedAt is oldest');
    assert.strictEqual(skinny.createdAt, 0, 'missing createdAt is oldest');
    assert.strictEqual(skinny.orderUpdatedAt, 0);
    const firstStamp = api.migrateUrl({ name: 'A', link: 'https://a.example' }).updatedAt;
    await new Promise((resolve) => setTimeout(resolve, 5));
    const secondStamp = api.migrateUrl({ name: 'B', link: 'https://b.example' }).updatedAt;
    assert.strictEqual(firstStamp, 0);
    assert.strictEqual(secondStamp, 0);

    const localNewer = api.mergeUrlLists(
        [api.migrateUrl({ id: 's', link: 'https://skinny.example', name: 'LocalEdit', updatedAt: 80, folder: '工作', favorite: true, order: 1 })],
        [{ id: 's', link: 'https://skinny.example', name: 'SkinnyCloud', folder: '娱乐' }]
    );
    assert.strictEqual(localNewer.length, 1);
    assert.strictEqual(localNewer[0].name, 'LocalEdit', 'skinny gist must not overwrite content');
    assert.strictEqual(localNewer[0].folder, '工作');
    assert.strictEqual(localNewer[0].favorite, true);

    const remoteNewerContent = api.mergeUrlLists(
        [api.migrateUrl({ id: 'x', link: 'https://x.example', name: 'L', updatedAt: 10, order: 0, orderUpdatedAt: 500 })],
        [api.migrateUrl({ id: 'x', link: 'https://x.example', name: 'R', updatedAt: 20, order: 9, orderUpdatedAt: 5 })]
    );
    assert.strictEqual(remoteNewerContent[0].name, 'R');
    assert.strictEqual(remoteNewerContent[0].order, 0, 'older remote order must not win');

    const reorderOnly = api.mergeUrlLists(
        [api.migrateUrl({
            id: 'r',
            link: 'https://r.example',
            name: 'NewName',
            updatedAt: 200,
            folder: '学习',
            favorite: true,
            order: 0,
            orderUpdatedAt: 10
        })],
        [api.migrateUrl({
            id: 'r',
            link: 'https://r.example',
            name: 'OldName',
            updatedAt: 50,
            folder: '工作',
            favorite: false,
            order: 7,
            orderUpdatedAt: 300
        })]
    );
    assert.strictEqual(reorderOnly[0].name, 'NewName', 'reorder must not stomp name');
    assert.strictEqual(reorderOnly[0].folder, '学习', 'reorder must not stomp folder');
    assert.strictEqual(reorderOnly[0].favorite, true, 'reorder must not stomp favorite');
    assert.strictEqual(reorderOnly[0].link, 'https://r.example');
    assert.strictEqual(reorderOnly[0].order, 7, 'newer order wins independently');
    assert.strictEqual(reorderOnly[0].orderUpdatedAt, 300);

    const first = api.migrateUrl({ id: 'a', link: 'https://a.example', name: 'Keep', updatedAt: 80, order: 0 });
    const second = api.migrateUrl({ id: 'b', link: 'https://b.example', name: 'Moved', updatedAt: 90, order: 1 });
    const reordered = api.applyReorder([first, second], ['b', 'a'], 999);
    assert.ok(reordered.changed);
    assert.strictEqual(reordered.urls[0].id, 'b');
    assert.strictEqual(reordered.urls[0].order, 0);
    assert.strictEqual(reordered.urls[0].updatedAt, 90, 'reorder does not bump content updatedAt');
    assert.strictEqual(reordered.urls[0].orderUpdatedAt, 999);
    assert.strictEqual(reordered.urls[1].id, 'a');
    assert.strictEqual(reordered.urls[1].order, 1);
    assert.strictEqual(reordered.urls[1].updatedAt, 80);
    assert.strictEqual(reordered.urls[1].orderUpdatedAt, 999);

    const folders = api.mergeFolders(['工作'], ['学习', '工作', '全部']);
    assert.deepStrictEqual(folders, ['工作', '学习']);

    const localStore = memoryStorage({ githubToken: secret });
    const sessionStore = memoryStorage();
    api.writeSessionToken(secret, sessionStore);
    assert.strictEqual(api.readSessionToken(sessionStore), secret);
    assert.strictEqual(sessionStore.getItem('githubToken'), null, 'session store must not use plaintext githubToken key');
    assert.strictEqual(localStore.getItem('githubToken'), secret, 'legacy plaintext token stays until vault migrate');
    const reloadedSession = memoryStorage(sessionStore.dump());
    assert.strictEqual(api.readSessionToken(reloadedSession), secret, 'session token survives simulated reload');
    api.clearSessionToken(reloadedSession);
    assert.strictEqual(api.readSessionToken(reloadedSession), '');
    assert.strictEqual(localStore.getItem('githubToken'), secret, 'logout/session clear does not wipe migrate token');
    assert.ok(api.shouldUseKeepalive('{"ok":true}', true));
    assert.strictEqual(api.shouldUseKeepalive('{"ok":true}', false), false);
    assert.strictEqual(api.shouldUseKeepalive('x'.repeat(api.KEEPALIVE_MAX_BYTES + 1), true), false);

    const fs = require('fs');
    const newHtml = fs.readFileSync(path.join(__dirname, '..', 'new.html'), 'utf8');
    assert.ok(/url=new2\.html/.test(newHtml), 'new.html meta-refreshs to new2');
    assert.ok(/location\.replace\(\s*['"]new2\.html/.test(newHtml), 'new.html JS-redirects to new2');
    assert.ok(newHtml.indexOf('saveToGitHub') === -1, 'old gist saver must not remain');
    assert.ok(newHtml.indexOf("localStorage.setItem('githubToken'") === -1);
    assert.ok(newHtml.indexOf('旧版网址管理器已停用') !== -1);

    const new2 = fs.readFileSync(path.join(__dirname, '..', 'new2.html'), 'utf8');
    assert.ok(new2.indexOf('id="deviceLoginBtn"') === -1, 'device-flow button must not be present');
    assert.ok(new2.indexOf('静态站点不可用') !== -1);
    assert.ok(new2.indexOf('pagehide') !== -1);
    assert.ok(new2.indexOf('visibilitychange') !== -1);
    assert.ok(new2.indexOf('keepalive') !== -1);
    assert.ok(new2.indexOf('max-height: none') !== -1);
    const testAssign = new2.indexOf('window.__new2Test');
    const selfTestFlag = new2.indexOf('SELF_TEST');
    assert.ok(testAssign !== -1 && selfTestFlag !== -1 && testAssign > selfTestFlag, '__new2Test only after selftest gate');
    assert.ok(new2.indexOf('writeSessionToken') !== -1);

    console.log('bookmark-site-account tests passed');
}

run().catch((error) => {
    console.error(error);
    process.exit(1);
});
