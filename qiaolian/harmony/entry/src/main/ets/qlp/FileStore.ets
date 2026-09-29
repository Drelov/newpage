import { Environment, fileIo as fs } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';

interface EntryJson {
  name: string;
  dir: boolean;
  size: number;
  mtime: number;
}

export interface ListJson {
  path: string;
  entries: EntryJson[];
  truncated?: boolean;
  error?: string;
}

interface RootDir {
  name: string;
  path: string;
}

export function cleanRel(path: string): string {
  if (path.length > 1024 || path.indexOf('\u0000') >= 0) {
    throw new Error('路径无效');
  }
  const normalized = path.trim().split('\\').join('/');
  const parts: string[] = [];
  const segs = normalized.split('/');
  for (let i = 0; i < segs.length; i++) {
    const part = segs[i];
    if (part.length === 0 || part === '.') {
      continue;
    }
    if (part === '..') {
      throw new Error('路径无效');
    }
    parts.push(part);
  }
  return parts.join('/');
}

export class FileStore {
  private context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  list(path: string): ListJson {
    const clean = cleanRel(path);
    if (clean.length === 0) {
      const roots = this.roots();
      const entries: EntryJson[] = [];
      for (let i = 0; i < roots.length; i++) {
        entries.push({ name: roots[i].name, dir: true, size: 0, mtime: 0 });
      }
      return { path: '', entries: entries };
    }
    const located = this.locate(clean);
    const stat = fs.statSync(located.full);
    if (!stat.isDirectory()) {
      throw new Error('不是文件夹');
    }
    const names = fs.listFileSync(located.full);
    const entries: EntryJson[] = [];
    let truncated = false;
    for (let i = 0; i < names.length; i++) {
      if (entries.length >= 5000) {
        truncated = true;
        break;
      }
      const child = located.full + '/' + names[i];
      try {
        const info = fs.statSync(child);
        entries.push({
          name: names[i],
          dir: info.isDirectory(),
          size: info.isDirectory() ? 0 : info.size,
          mtime: Math.floor(info.mtime / 1000)
        });
      } catch (_e) {
      }
    }
    entries.sort((a: EntryJson, b: EntryJson) => {
      if (a.dir !== b.dir) {
        return a.dir ? -1 : 1;
      }
      if (a.name < b.name) {
        return -1;
      }
      if (a.name > b.name) {
        return 1;
      }
      return 0;
    });
    const result: ListJson = { path: clean, entries: entries };
    if (truncated) {
      result.truncated = true;
    }
    return result;
  }

  openRead(path: string): number {
    const located = this.locate(cleanRel(path));
    const stat = fs.statSync(located.full);
    if (stat.isDirectory()) {
      throw new Error('这是文件夹');
    }
    return fs.openSync(located.full, fs.OpenMode.READ_ONLY).fd;
  }

  fileSize(path: string): number {
    return fs.statSync(this.locate(cleanRel(path)).full).size;
  }

  startPush(path: string): PushFile {
    const clean = cleanRel(path);
    if (clean.length === 0 || clean.indexOf('/') < 0) {
      throw new Error('路径无效');
    }
    const located = this.locate(clean);
    this.ensureParent(located.full);
    const part = located.full + '.qiaolian.part';
    const file = fs.openSync(part, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    return { fd: file.fd, part: part, dest: located.full };
  }

  append(fd: number, bytes: Uint8Array): void {
    if (bytes.byteLength === 0) {
      return;
    }
    const copy = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(copy).set(bytes);
    fs.writeSync(fd, copy);
  }

  finishPush(push: PushFile): number {
    fs.closeSync(push.fd);
    try {
      if (fs.accessSync(push.dest)) {
        fs.unlinkSync(push.dest);
      }
    } catch (_e) {
    }
    fs.renameSync(push.part, push.dest);
    return fs.statSync(push.dest).size;
  }

  abortPush(push: PushFile): void {
    try {
      fs.closeSync(push.fd);
    } catch (_e) {
    }
    try {
      fs.unlinkSync(push.part);
    } catch (_e) {
    }
  }

  mkdir(path: string): void {
    const clean = cleanRel(path);
    if (clean.length === 0) {
      throw new Error('路径无效');
    }
    const located = this.locate(clean);
    if (!pathExists(located.full)) {
      fs.mkdirSync(located.full);
    }
  }

  remove(path: string): void {
    const clean = cleanRel(path);
    if (clean.length === 0 || clean.indexOf('/') < 0) {
      throw new Error('路径无效');
    }
    const located = this.locate(clean);
    this.removeAll(located.full);
  }

  rename(from: string, to: string): void {
    const src = this.locate(cleanRel(from));
    const dstRel = cleanRel(to);
    if (dstRel.length === 0 || dstRel.indexOf('/') < 0) {
      throw new Error('路径无效');
    }
    const dst = this.locate(dstRel);
    this.ensureParent(dst.full);
    fs.renameSync(src.full, dst.full);
  }

  copyUri(uri: string): string {
    const fileName = safeFileName(uri);
    const dest = this.context.filesDir + '/' + fileName;
    const src = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    const dst = fs.openSync(dest, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      const buf = new ArrayBuffer(64 * 1024);
      while (true) {
        const n = fs.readSync(src.fd, buf);
        if (n <= 0) {
          break;
        }
        fs.writeSync(dst.fd, buf, { length: n });
      }
    } finally {
      fs.closeSync(src);
      fs.closeSync(dst);
    }
    return '应用文件/' + fileName;
  }

  private roots(): RootDir[] {
    const list: RootDir[] = [{ name: '应用文件', path: this.context.filesDir }];
    try {
      list.push({ name: '下载', path: Environment.getUserDownloadDir() });
    } catch (_e) {
    }
    try {
      list.push({ name: '文档', path: Environment.getUserDocumentDir() });
    } catch (_e) {
    }
    return list;
  }

  private locate(clean: string): Located {
    const slash = clean.indexOf('/');
    const head = slash < 0 ? clean : clean.substring(0, slash);
    const rest = slash < 0 ? '' : clean.substring(slash + 1);
    const roots = this.roots();
    let root = '';
    for (let i = 0; i < roots.length; i++) {
      if (roots[i].name === head) {
        root = roots[i].path;
        break;
      }
    }
    if (root.length === 0) {
      throw new Error('路径无效');
    }
    const full = rest.length === 0 ? root : root + '/' + rest;
    if (!inside(root, full)) {
      throw new Error('路径无效');
    }
    return { root: root, full: full };
  }

  private ensureParent(full: string): void {
    const slash = full.lastIndexOf('/');
    if (slash <= 0) {
      return;
    }
    const parent = full.substring(0, slash);
    if (!pathExists(parent)) {
      fs.mkdirSync(parent);
    }
  }

  private removeAll(full: string): void {
    const stat = fs.statSync(full);
    if (stat.isDirectory()) {
      const names = fs.listFileSync(full);
      for (let i = 0; i < names.length; i++) {
        this.removeAll(full + '/' + names[i]);
      }
      fs.rmdirSync(full);
    } else {
      fs.unlinkSync(full);
    }
  }
}

export interface PushFile {
  fd: number;
  part: string;
  dest: string;
}

interface Located {
  root: string;
  full: string;
}

function pathExists(path: string): boolean {
  try {
    return fs.accessSync(path);
  } catch (_e) {
    return false;
  }
}

function inside(root: string, full: string): boolean {
  if (full === root) {
    return true;
  }
  const prefix = root.endsWith('/') ? root : root + '/';
  return full.startsWith(prefix);
}

function safeFileName(uri: string): string {
  let name = uri;
  const slash = name.lastIndexOf('/');
  if (slash >= 0) {
    name = name.substring(slash + 1);
  }
  const query = name.indexOf('?');
  if (query >= 0) {
    name = name.substring(0, query);
  }
  try {
    name = decodeURIComponent(name);
  } catch (_e) {
  }
  name = name.replace(/[\\/]/g, '_');
  if (name.length === 0 || name === '.' || name === '..') {
    name = '收到的文件';
  }
  return name;
}
