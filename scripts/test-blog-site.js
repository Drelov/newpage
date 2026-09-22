#!/usr/bin/env node
'use strict';

const assert = require('assert');
const path = require('path');
const fs = require('fs');
const api = require(path.join(__dirname, '..', 'assets/js/blog-site.js'));

async function run() {
    assert.strictEqual(api.slugify('改进点记录'), '改进点记录');
    assert.strictEqual(api.slugify('Hello World'), 'Hello-World');
    assert.strictEqual(api.slugify('a/b:c*d'), 'abcd');
    assert.strictEqual(api.slugify('   '), 'post');

    const filename = api.postFilename('QT软件问题记录', new Date('2026-09-22T04:00:00Z'));
    assert.ok(/^\d{4}-\d{2}-\d{2}-QT软件问题记录\.md$/.test(filename));

    const imgName = api.imageFilename('Photo.JPEG', new Date('2026-09-22T04:00:00Z'));
    assert.ok(/\.jpg$/.test(imgName), imgName);
    assert.ok(imgName.indexOf('Photo') !== -1);

    const sample = [
        '---',
        'layout: post',
        'title: 改进点记录',
        'date: 2024-12-26 17:46:30 +0800',
        'permalink: 改进点',
        'tag: 编程',
        '---',
        '',
        '1. 主界面按钮',
        ''
    ].join('\n');
    const parsed = api.parseFrontMatter(sample);
    assert.strictEqual(parsed.title, '改进点记录');
    assert.strictEqual(parsed.permalink, '改进点');
    assert.strictEqual(parsed.tag, '编程');
    assert.ok(parsed.body.indexOf('主界面按钮') !== -1);

    const roundTrip = api.parseFrontMatter(api.serializePost(parsed));
    assert.strictEqual(roundTrip.title, '改进点记录');
    assert.strictEqual(roundTrip.tag, '编程');
    assert.strictEqual(roundTrip.layout, 'post');

    const typo = api.parseFrontMatter('---\npost: layout\ntitle: x\n---\nbody');
    assert.strictEqual(typo.title, 'x');
    assert.strictEqual(typo.body.trim(), 'body');

    assert.strictEqual(api.markdownImage('图', '/newpage/assets/blog/a.png'), '![图](/newpage/assets/blog/a.png)');
    assert.strictEqual(api.markdownFormula('a^2', false), '$a^2$');
    assert.ok(api.markdownFormula('a^2', true).indexOf('$$') !== -1);

    const wrapped = api.wrapSelection('hello', 0, 5, '**', '**', 'x');
    assert.strictEqual(wrapped.value, '**hello**');
    const inserted = api.insertText('ab', 1, 1, 'X');
    assert.strictEqual(inserted.value, 'aXb');

    const encoded = api.encodeUtf8Base64('改进点');
    assert.strictEqual(api.decodeUtf8Base64(encoded), '改进点');

    const repo = api.detectRepo({ hostname: 'drelov.github.io', pathname: '/newpage/write.html' });
    assert.strictEqual(repo.owner, 'drelov');
    assert.strictEqual(repo.repo, 'newpage');
    assert.strictEqual(repo.pageBase, '/newpage');

    const local = api.detectRepo({ hostname: '127.0.0.1', pathname: '/write.html' });
    assert.strictEqual(local.repo, 'newpage');

    assert.strictEqual(api.publicImageUrl('a.png'), '/newpage/assets/blog/a.png');
    const rewritten = api.rewritePreviewImages(
        '<img src="/newpage/assets/blog/a.png">',
        'Drelov',
        'newpage'
    );
    assert.ok(rewritten.indexOf('raw.githubusercontent.com/Drelov/newpage/main/assets/blog/a.png') !== -1);

    const calls = [];
    const listed = await api.listPosts('ghp_test', {
        fetchFn: async (url, options) => {
            calls.push({ url, options });
            return {
                ok: true,
                json: async () => [
                    { type: 'file', name: '2025-02-06-哪吒.md', path: '_posts/2025-02-06-哪吒.md' },
                    { type: 'dir', name: 'skip' },
                    { type: 'file', name: 'notes.txt' }
                ]
            };
        }
    });
    assert.strictEqual(listed.length, 1);
    assert.ok(calls[0].url.indexOf('/contents/_posts') !== -1);
    assert.strictEqual(calls[0].options.headers.Authorization, 'Bearer ghp_test');

    const got = await api.getFile('ghp_test', '_posts/a.md', {
        fetchFn: async () => ({
            ok: true,
            json: async () => ({
                sha: 'abc',
                path: '_posts/a.md',
                name: 'a.md',
                content: api.encodeUtf8Base64(sample)
            })
        })
    });
    assert.strictEqual(got.sha, 'abc');
    assert.strictEqual(api.parseFrontMatter(got.text).title, '改进点记录');

    const putCalls = [];
    await api.putFile('ghp_test', '_posts/a.md', sample, {
        sha: 'abc',
        fetchFn: async (url, options) => {
            putCalls.push(JSON.parse(options.body));
            return { ok: true, json: async () => ({ content: { path: '_posts/a.md' } }) };
        }
    });
    assert.strictEqual(putCalls[0].sha, 'abc');
    assert.strictEqual(api.decodeUtf8Base64(putCalls[0].content), sample);

    await assert.rejects(
        () => api.putFile('', '_posts/a.md', sample, { fetchFn: async () => ({ ok: true, json: async () => ({}) }) }),
        /登录/
    );

    await assert.rejects(
        () => api.putBinaryFile('ghp_test', 'assets/blog/x.png', new Uint8Array(3), {
            maxBytes: 2,
            fetchFn: async () => ({ ok: true, json: async () => ({}) })
        }),
        /2MB/
    );

    assert.strictEqual(api.isImageFile({ type: 'image/png', name: 'a.png' }), true);
    assert.strictEqual(api.isImageFile({ type: 'text/plain', name: 'a.txt' }), false);

    const mem = {
        data: {},
        getItem(key) { return Object.prototype.hasOwnProperty.call(this.data, key) ? this.data[key] : null; },
        setItem(key, value) { this.data[key] = String(value); },
        removeItem(key) { delete this.data[key]; }
    };
    api.writeDraft({ title: '草稿' }, mem);
    assert.strictEqual(api.readDraft(mem).title, '草稿');
    api.clearDraft(mem);
    assert.strictEqual(api.readDraft(mem), null);

    const writeHtml = fs.readFileSync(path.join(__dirname, '..', 'write.html'), 'utf8');
    assert.ok(writeHtml.indexOf('assets/js/blog-site.js') !== -1);
    assert.ok(writeHtml.indexOf('insertImageFiles') !== -1);
    assert.ok(writeHtml.indexOf('insertFormula') !== -1);
    assert.ok(writeHtml.indexOf('katex') !== -1);
    assert.ok(writeHtml.indexOf('id="mdEditor"') !== -1);

    const postsHtml = fs.readFileSync(path.join(__dirname, '..', 'all-posts.html'), 'utf8');
    assert.ok(postsHtml.indexOf('data-tag="编程"') === -1, 'default tag must not force 编程');
    assert.ok(postsHtml.indexOf('write.html') !== -1);
    assert.ok(postsHtml.indexOf("{% for tag in post.tags %}") === -1, 'posts must not duplicate per tag');

    const indexMd = fs.readFileSync(path.join(__dirname, '..', 'index.md'), 'utf8');
    assert.ok(indexMd.indexOf('password123') === -1);
    assert.ok(indexMd.indexOf('write.html') !== -1);
    assert.ok(indexMd.indexOf('new2.html') !== -1);

    const layout = fs.readFileSync(path.join(__dirname, '..', '_layouts/post.html'), 'utf8');
    assert.ok(layout.indexOf('katex') !== -1);
    assert.ok(layout.indexOf('write.html') !== -1);

    const new2 = fs.readFileSync(path.join(__dirname, '..', 'new2.html'), 'utf8');
    assert.ok(new2.indexOf('write.html') !== -1);
    assert.ok(new2.indexOf('hostnameOf(link)') !== -1);

    console.log('blog-site tests passed');
}

run().catch((error) => {
    console.error(error);
    process.exit(1);
});
