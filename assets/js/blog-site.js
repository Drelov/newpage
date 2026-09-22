(function (root, factory) {
    const api = factory(root);
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    root.BlogSite = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    const DEFAULT_OWNER = 'Drelov';
    const DEFAULT_REPO = 'newpage';
    const POSTS_DIR = '_posts';
    const IMAGE_DIR = 'assets/blog';
    const DRAFT_KEY = 'blogEditorDraft';
    const PAGE_BASE = '/newpage';
    const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    const BRANCH = 'main';

    function detectRepo(locationLike) {
        const loc = locationLike || (typeof location !== 'undefined' ? location : {});
        const host = String(loc.hostname || '');
        const path = String(loc.pathname || '');
        const pages = host.match(/^([a-z0-9-]+)\.github\.io$/i);
        const owner = pages ? pages[1] : DEFAULT_OWNER;
        const parts = path.split('/').filter(Boolean);
        const first = parts[0] || '';
        const looksLikeFile = /\.(html|md)$/i.test(first) || first.indexOf('.') !== -1;
        const repo = first && !looksLikeFile ? first : DEFAULT_REPO;
        const pageBase = '/' + repo;
        return { owner: owner || DEFAULT_OWNER, repo: repo || DEFAULT_REPO, pageBase: pageBase };
    }

    function slugify(title) {
        const s = String(title || '')
            .trim()
            .replace(/[\\/:*?"<>|#%\u0000-\u001f]+/g, '')
            .replace(/\s+/g, '-')
            .replace(/-+/g, '-')
            .replace(/^-|-$/g, '');
        return s || 'post';
    }

    function pad(value) {
        return String(value).padStart(2, '0');
    }

    function formatPostDate(date) {
        const src = date instanceof Date ? date : new Date(date || Date.now());
        if (Number.isNaN(src.getTime())) return formatPostDate(new Date());
        const offsetMin = 8 * 60;
        const local = new Date(src.getTime() + (offsetMin + src.getTimezoneOffset()) * 60000);
        return local.getFullYear() + '-' + pad(local.getMonth() + 1) + '-' + pad(local.getDate()) + ' ' +
            pad(local.getHours()) + ':' + pad(local.getMinutes()) + ':' + pad(local.getSeconds()) + ' +0800';
    }

    function dateStamp(date) {
        const src = date instanceof Date ? date : new Date(date || Date.now());
        const offsetMin = 8 * 60;
        const local = new Date(src.getTime() + (offsetMin + src.getTimezoneOffset()) * 60000);
        return local.getFullYear() + '-' + pad(local.getMonth() + 1) + '-' + pad(local.getDate());
    }

    function fileStamp(date) {
        const src = date instanceof Date ? date : new Date(date || Date.now());
        const offsetMin = 8 * 60;
        const local = new Date(src.getTime() + (offsetMin + src.getTimezoneOffset()) * 60000);
        return local.getFullYear() + pad(local.getMonth() + 1) + pad(local.getDate()) + '-' +
            pad(local.getHours()) + pad(local.getMinutes()) + pad(local.getSeconds());
    }

    function postFilename(title, date) {
        return dateStamp(date) + '-' + slugify(title) + '.md';
    }

    function imageFilename(originalName, date) {
        const raw = String(originalName || 'image.png');
        const dot = raw.lastIndexOf('.');
        const ext = (dot === -1 ? '.png' : raw.slice(dot)).toLowerCase().replace(/[^.a-z0-9]/g, '');
        const safeExt = /^\.(png|jpe?g|gif|webp|svg)$/.test(ext) ? ext.replace('jpeg', 'jpg') : '.png';
        return fileStamp(date) + '-' + slugify(raw.replace(/\.[^.]+$/, '')).slice(0, 40) + safeExt;
    }

    function quoteYaml(value) {
        const text = String(value == null ? '' : value);
        if (!text) return '""';
        if (/[:#{}[\],&*?]|^\s|\s$|"|'/.test(text)) {
            return '"' + text.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
        }
        return text;
    }

    function unquoteYaml(value) {
        const text = String(value == null ? '' : value).trim();
        if ((text.charAt(0) === '"' && text.charAt(text.length - 1) === '"') ||
            (text.charAt(0) === "'" && text.charAt(text.length - 1) === "'")) {
            return text.slice(1, -1).replace(/\\"/g, '"');
        }
        return text;
    }

    function parseFrontMatter(raw) {
        const text = String(raw || '').replace(/^\uFEFF/, '');
        const match = text.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
        const data = {
            layout: 'post',
            title: '',
            date: '',
            permalink: '',
            tag: '',
            tags: [],
            body: text
        };
        if (!match) return data;
        const yaml = match[1];
        data.body = match[2].replace(/^\r?\n/, '');
        yaml.split(/\r?\n/).forEach((line) => {
            const idx = line.indexOf(':');
            if (idx === -1) return;
            const key = line.slice(0, idx).trim();
            const value = unquoteYaml(line.slice(idx + 1));
            if (key === 'title') data.title = value;
            else if (key === 'date') data.date = value;
            else if (key === 'permalink') data.permalink = value;
            else if (key === 'layout') data.layout = value || 'post';
            else if (key === 'tag') data.tag = value;
            else if (key === 'tags') data.tag = value.replace(/^\[|\]$/g, '').split(/[,\s]+/).filter(Boolean).join(', ');
        });
        if (!data.tag && data.tags && data.tags.length) data.tag = data.tags.join(', ');
        if (data.layout === 'layout' || !data.layout) data.layout = 'post';
        return data;
    }

    function serializePost(post) {
        const title = String((post && post.title) || '未命名').trim() || '未命名';
        const date = (post && post.date) || formatPostDate(new Date());
        const permalink = slugify((post && post.permalink) || title);
        const tag = String((post && post.tag) || '').trim();
        const body = String((post && post.body) || '').replace(/^\uFEFF/, '');
        const lines = [
            '---',
            'layout: post',
            'title: ' + quoteYaml(title),
            'date: ' + date,
            'permalink: ' + permalink
        ];
        if (tag) lines.push('tag: ' + tag);
        lines.push('---', '', body.replace(/^\s+/, '').replace(/\s+$/, ''), '');
        return lines.join('\n');
    }

    function markdownImage(alt, url) {
        return '![' + String(alt || 'image') + '](' + String(url || '') + ')';
    }

    function markdownFormula(tex, display) {
        const body = String(tex || 'E = mc^2').trim() || 'E = mc^2';
        if (display) return '\n$$\n' + body + '\n$$\n';
        return '$' + body.replace(/\$/g, '') + '$';
    }

    function publicImageUrl(filename, pageBase) {
        const base = pageBase || PAGE_BASE;
        return base + '/' + IMAGE_DIR + '/' + filename;
    }

    function rawImageUrl(filename, owner, repo) {
        return 'https://raw.githubusercontent.com/' +
            encodeURIComponent(owner || DEFAULT_OWNER) + '/' +
            encodeURIComponent(repo || DEFAULT_REPO) + '/' +
            BRANCH + '/' + IMAGE_DIR + '/' + filename;
    }

    function rewritePreviewImages(html, owner, repo) {
        const prefix = PAGE_BASE + '/' + IMAGE_DIR + '/';
        const rawPrefix = 'https://raw.githubusercontent.com/' + owner + '/' + repo + '/' + BRANCH + '/' + IMAGE_DIR + '/';
        return String(html || '').split(prefix).join(rawPrefix);
    }

    function insertText(value, start, end, snippet) {
        const text = String(value || '');
        const from = Math.max(0, start == null ? text.length : start);
        const to = Math.max(from, end == null ? from : end);
        return {
            value: text.slice(0, from) + snippet + text.slice(to),
            start: from,
            end: from + String(snippet).length
        };
    }

    function wrapSelection(value, start, end, before, after, placeholder) {
        const text = String(value || '');
        const from = Math.max(0, start == null ? 0 : start);
        const to = Math.max(from, end == null ? from : end);
        const selected = text.slice(from, to) || placeholder || '';
        const snippet = before + selected + after;
        return {
            value: text.slice(0, from) + snippet + text.slice(to),
            start: from + before.length,
            end: from + before.length + selected.length
        };
    }

    function encodeUtf8Base64(text) {
        const bytes = new TextEncoder().encode(String(text || ''));
        let binary = '';
        for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
        return btoa(binary);
    }

    function decodeUtf8Base64(b64) {
        const binary = atob(String(b64 || '').replace(/\s/g, ''));
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
        return new TextDecoder().decode(bytes);
    }

    function contentsUrl(owner, repo, filePath) {
        const encoded = String(filePath || '').split('/').map(encodeURIComponent).join('/');
        return 'https://api.github.com/repos/' +
            encodeURIComponent(owner) + '/' +
            encodeURIComponent(repo) + '/contents/' + encoded;
    }

    function authHeaders(token) {
        const headers = { Accept: 'application/vnd.github+json' };
        if (token) headers.Authorization = 'Bearer ' + token;
        return headers;
    }

    function friendlyGitHubError(data, fallback) {
        const raw = String((data && data.message) || fallback || 'GitHub 请求失败');
        if (/bad credentials/i.test(raw) || /\b401\b/.test(raw)) return '访问令牌无效';
        if (/not found/i.test(raw) || /\b404\b/.test(raw)) return '没有找到仓库或文件（请确认令牌有 Contents 权限）';
        if (/must have .+ permission|resource not accessible|403/i.test(raw)) {
            return '令牌缺少本仓库 Contents 写权限。请用 gist + 仓库内容权限重新注册令牌';
        }
        return raw;
    }

    async function githubJson(url, options, fetchFn) {
        const fetchImpl = fetchFn || (typeof fetch === 'function' ? fetch : null);
        if (!fetchImpl) throw new Error('当前环境无法请求 GitHub');
        const response = await fetchImpl(url, options);
        let data = null;
        try {
            data = await response.json();
        } catch (error) {
            data = null;
        }
        if (!response.ok) throw new Error(friendlyGitHubError(data, 'HTTP ' + response.status));
        return data;
    }

    async function listPosts(token, options) {
        const opts = options || {};
        const owner = opts.owner || DEFAULT_OWNER;
        const repo = opts.repo || DEFAULT_REPO;
        const data = await githubJson(
            contentsUrl(owner, repo, POSTS_DIR),
            { headers: authHeaders(token) },
            opts.fetchFn
        );
        const files = Array.isArray(data) ? data : [];
        return files
            .filter((item) => item && item.type === 'file' && /\.md$/i.test(item.name || ''))
            .sort((a, b) => String(b.name).localeCompare(String(a.name), 'zh'));
    }

    async function getFile(token, filePath, options) {
        const opts = options || {};
        const owner = opts.owner || DEFAULT_OWNER;
        const repo = opts.repo || DEFAULT_REPO;
        const data = await githubJson(
            contentsUrl(owner, repo, filePath),
            { headers: authHeaders(token) },
            opts.fetchFn
        );
        const decoded = decodeUtf8Base64((data && data.content) || '');
        return { sha: data.sha, path: data.path, name: data.name, text: decoded, raw: data };
    }

    async function putFile(token, filePath, text, options) {
        const opts = options || {};
        if (!token) throw new Error('请先登录后再保存到 GitHub');
        const owner = opts.owner || DEFAULT_OWNER;
        const repo = opts.repo || DEFAULT_REPO;
        const body = {
            message: opts.message || ('blog: update ' + filePath),
            content: encodeUtf8Base64(text),
            branch: opts.branch || BRANCH
        };
        if (opts.sha) body.sha = opts.sha;
        return githubJson(
            contentsUrl(owner, repo, filePath),
            {
                method: 'PUT',
                headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders(token)),
                body: JSON.stringify(body)
            },
            opts.fetchFn
        );
    }

    async function putBinaryFile(token, filePath, bytes, options) {
        const opts = options || {};
        if (!token) throw new Error('请先登录后再上传图片');
        const owner = opts.owner || DEFAULT_OWNER;
        const repo = opts.repo || DEFAULT_REPO;
        const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        if (arr.length > (opts.maxBytes || MAX_IMAGE_BYTES)) {
            throw new Error('图片请小于 2MB，可先压缩后再插入');
        }
        let binary = '';
        for (let i = 0; i < arr.length; i += 1) binary += String.fromCharCode(arr[i]);
        const body = {
            message: opts.message || ('blog: add image ' + filePath),
            content: btoa(binary),
            branch: opts.branch || BRANCH
        };
        if (opts.sha) body.sha = opts.sha;
        return githubJson(
            contentsUrl(owner, repo, filePath),
            {
                method: 'PUT',
                headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders(token)),
                body: JSON.stringify(body)
            },
            opts.fetchFn
        );
    }

    function isImageFile(file) {
        if (!file) return false;
        if (file.type && file.type.indexOf('image/') === 0) return true;
        return /\.(png|jpe?g|gif|webp|svg)$/i.test(file.name || '');
    }

    function readDraft(storage) {
        const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null);
        if (!store) return null;
        try {
            const parsed = JSON.parse(store.getItem(DRAFT_KEY) || 'null');
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (error) {
            return null;
        }
    }

    function writeDraft(draft, storage) {
        const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null);
        if (!store) return;
        store.setItem(DRAFT_KEY, JSON.stringify(draft || {}));
    }

    function clearDraft(storage) {
        const store = storage || (typeof localStorage !== 'undefined' ? localStorage : null);
        if (!store) return;
        store.removeItem(DRAFT_KEY);
    }

    return {
        DEFAULT_OWNER,
        DEFAULT_REPO,
        POSTS_DIR,
        IMAGE_DIR,
        DRAFT_KEY,
        PAGE_BASE,
        MAX_IMAGE_BYTES,
        BRANCH,
        detectRepo,
        slugify,
        formatPostDate,
        dateStamp,
        postFilename,
        imageFilename,
        parseFrontMatter,
        serializePost,
        markdownImage,
        markdownFormula,
        publicImageUrl,
        rawImageUrl,
        rewritePreviewImages,
        insertText,
        wrapSelection,
        encodeUtf8Base64,
        decodeUtf8Base64,
        listPosts,
        getFile,
        putFile,
        putBinaryFile,
        isImageFile,
        readDraft,
        writeDraft,
        clearDraft
    };
});
