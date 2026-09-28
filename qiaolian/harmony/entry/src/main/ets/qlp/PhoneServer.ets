import { socket } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { Bridge } from './Bridge';
import { FileStore, PushFile } from './FileStore';
import {
  CHUNK,
  encodeFrame,
  field,
  fieldBool,
  fieldNum,
  decodeText,
  FrameParser,
  QFrame,
  TCP_PORT,
  TYPE_CHUNK,
  TYPE_CLIP_DATA,
  TYPE_CLIP_GET,
  TYPE_CLIP_SET,
  TYPE_DELETE,
  TYPE_ERROR,
  TYPE_FRAME,
  TYPE_HELLO,
  TYPE_INPUT,
  TYPE_INPUT_RESULT,
  TYPE_LIST,
  TYPE_LIST_RESULT,
  TYPE_MKDIR,
  TYPE_OP_RESULT,
  TYPE_PAIR,
  TYPE_PAIR_RESULT,
  TYPE_PING,
  TYPE_PONG,
  TYPE_PULL,
  TYPE_PUSH_BEGIN,
  TYPE_RENAME,
  TYPE_SCREEN_START,
  TYPE_SCREEN_STATUS,
  TYPE_SCREEN_STOP,
  TYPE_TRANSFER,
  UDP_PORT
} from './FrameCodec';

const empty = new Uint8Array(0);

export class PhoneServer {
  private static client: socket.TCPSocketConnection | null = null;
  private static parser: FrameParser = new FrameParser();
  private static authed: boolean = false;
  private static failCount: number = 0;
  private static lockUntil: number = 0;
  private static push: PushFile | null = null;
  private static screenTimer: number = -1;
  private static sendChain: Promise<void> = Promise.resolve();
  private static started: boolean = false;

  static start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    const tcp: socket.TCPSocketServer = socket.constructTCPSocketServerInstance();
    tcp.on('connect', (client: socket.TCPSocketConnection) => {
      this.accept(client);
    });
    tcp.listen({ address: '0.0.0.0', port: TCP_PORT, family: 1 }).catch((_err: BusinessError) => {
      Bridge.status = `端口 ${TCP_PORT} 被占用`;
    });
    this.announce();
  }

  static dropClient(): void {
    if (this.client !== null) {
      this.client.close();
      this.client = null;
    }
    this.stopScreen();
  }

  private static accept(client: socket.TCPSocketConnection): void {
    if (this.client !== null) {
      this.client.close();
    }
    this.client = client;
    this.parser = new FrameParser();
    this.authed = false;
    this.abortPush();
    this.stopScreen();
    this.send(TYPE_HELLO, JSON.stringify({
      role: 'phone',
      name: Bridge.name,
      os: 'harmony',
      osVersion: Bridge.osVersion,
      proto: 1,
      id: Bridge.deviceId,
      w: Bridge.width,
      h: Bridge.height,
      needPair: true
    }), empty);
    Bridge.status = '电脑已连上';
    client.on('message', (value: socket.SocketMessageInfo) => {
      try {
        const frames = this.parser.push(new Uint8Array(value.message));
        for (let i = 0; i < frames.length; i++) {
          this.onFrame(frames[i]);
        }
      } catch (_e) {
        client.close();
      }
    });
    client.on('close', () => {
      if (this.client === client) {
        this.client = null;
        this.abortPush();
        this.stopScreen();
        Bridge.status = '等待电脑连接';
      }
    });
  }

  private static send(type: number, json: string, bin: Uint8Array): void {
    const client = this.client;
    if (client === null) {
      return;
    }
    const packet = encodeFrame(type, json, bin);
    this.sendChain = this.sendChain.then(() => {
      return client.send({ data: packet });
    }).then(() => {
    }).catch(() => {
    });
  }

  private static onFrame(frame: QFrame): void {
    if (!this.authed && frame.type !== TYPE_HELLO && frame.type !== TYPE_PAIR && frame.type !== TYPE_PING) {
      this.send(TYPE_ERROR, JSON.stringify({ error: '未配对' }), empty);
      return;
    }
    let obj: Object = {};
    try {
      obj = JSON.parse(frame.json) as Object;
    } catch (_e) {
      obj = {};
    }
    if (frame.type === TYPE_PING) {
      this.send(TYPE_PONG, '{}', empty);
      return;
    }
    if (frame.type === TYPE_HELLO) {
      const token = field(obj, 'token');
      if (token.length > 0 && Bridge.token.length > 0 && token === Bridge.token) {
        this.authed = true;
        this.send(TYPE_PAIR_RESULT, JSON.stringify({ ok: true, token: Bridge.token }), empty);
      } else {
        this.send(TYPE_PAIR_RESULT, JSON.stringify({ ok: false, error: '需要配对码' }), empty);
      }
      return;
    }
    if (frame.type === TYPE_PAIR) {
      const result = this.tryPair(field(obj, 'code'));
      if (fieldBool(result, 'ok')) {
        this.authed = true;
      }
      this.send(TYPE_PAIR_RESULT, JSON.stringify(result), empty);
      return;
    }
    if (frame.type === TYPE_LIST) {
      this.list(field(obj, 'path'));
      return;
    }
    if (frame.type === TYPE_PULL) {
      this.pull(field(obj, 'path'), field(obj, 'id'));
      return;
    }
    if (frame.type === TYPE_PUSH_BEGIN) {
      this.beginPush(field(obj, 'path'), field(obj, 'id'));
      return;
    }
    if (frame.type === TYPE_CHUNK) {
      this.onChunk(obj, frame.bin);
      return;
    }
    if (frame.type === TYPE_MKDIR) {
      this.simple('mkdir', field(obj, 'path'), (store: FileStore, path: string) => store.mkdir(path));
      return;
    }
    if (frame.type === TYPE_DELETE) {
      this.simple('delete', field(obj, 'path'), (store: FileStore, path: string) => store.remove(path));
      return;
    }
    if (frame.type === TYPE_RENAME) {
      const from = field(obj, 'path');
      const to = field(obj, 'to');
      try {
        this.store().rename(from, to);
        this.send(TYPE_OP_RESULT, JSON.stringify({ op: 'rename', ok: true, path: to }), empty);
      } catch (e) {
        this.send(TYPE_OP_RESULT, JSON.stringify({ op: 'rename', ok: false, path: to, error: messageOf(e) }), empty);
      }
      return;
    }
    if (frame.type === TYPE_SCREEN_START) {
      this.startScreen(fieldNum(obj, 'maxWidth', 720), fieldNum(obj, 'quality', 70), fieldNum(obj, 'fps', 8));
      return;
    }
    if (frame.type === TYPE_SCREEN_STOP) {
      this.stopScreen();
      this.send(TYPE_SCREEN_STATUS, JSON.stringify({ ok: true, active: false }), empty);
      return;
    }
    if (frame.type === TYPE_INPUT) {
      this.onInput(obj);
      return;
    }
    if (frame.type === TYPE_CLIP_GET) {
      Bridge.clipboard().then((text: string) => {
        this.send(TYPE_CLIP_DATA, JSON.stringify({ text: text }), empty);
      });
      return;
    }
    if (frame.type === TYPE_CLIP_SET) {
      const text = field(obj, 'text');
      if (text.length > 200000) {
        this.send(TYPE_ERROR, JSON.stringify({ error: '剪贴板内容过长' }), empty);
        return;
      }
      Bridge.setClipboard(text).then(() => {
        this.send(TYPE_CLIP_DATA, JSON.stringify({ text: text }), empty);
      }).catch((e: Object) => {
        this.send(TYPE_ERROR, JSON.stringify({ error: messageOf(e) }), empty);
      });
      return;
    }
    this.send(TYPE_ERROR, JSON.stringify({ error: '未知消息' }), empty);
  }

  private static tryPair(code: string): Object {
    const now = Date.now();
    if (now < this.lockUntil) {
      return { ok: false, error: '尝试过多，请稍后再试' };
    }
    if (code.length === 0 || code !== Bridge.code) {
      this.failCount++;
      if (this.failCount >= 5) {
        this.lockUntil = now + 30000;
        this.failCount = 0;
      }
      return { ok: false, error: '配对码错误' };
    }
    this.failCount = 0;
    if (Bridge.token.length === 0) {
      Bridge.saveToken(randomToken());
    }
    return { ok: true, token: Bridge.token };
  }

  private static list(path: string): void {
    try {
      const result = this.store().list(path);
      this.send(TYPE_LIST_RESULT, JSON.stringify(result), empty);
    } catch (e) {
      this.send(TYPE_LIST_RESULT, JSON.stringify({ path: path, entries: [], error: messageOf(e) }), empty);
    }
  }

  private static pull(path: string, id: string): void {
    let fd = -1;
    try {
      const store = this.store();
      const size = store.fileSize(path);
      fd = store.openRead(path);
      const buf = new ArrayBuffer(CHUNK);
      let sent = 0;
      let seq = 0;
      while (true) {
        const n = fs.readSync(fd, buf);
        if (n <= 0) {
          if (sent === 0) {
            this.send(TYPE_CHUNK, JSON.stringify({ id: id, seq: seq, eof: true }), empty);
          }
          break;
        }
        sent += n;
        const eof = sent >= size;
        const copy = new Uint8Array(n);
        copy.set(new Uint8Array(buf, 0, n));
        this.send(TYPE_CHUNK, JSON.stringify({ id: id, seq: seq, eof: eof }), copy);
        seq++;
        if (eof) {
          break;
        }
      }
      fs.closeSync(fd);
      this.send(TYPE_TRANSFER, JSON.stringify({ id: id, ok: true, size: size }), empty);
    } catch (e) {
      if (fd >= 0) {
        try {
          fs.closeSync(fd);
        } catch (_close) {
        }
      }
      this.send(TYPE_TRANSFER, JSON.stringify({ id: id, ok: false, error: messageOf(e) }), empty);
    }
  }

  private static beginPush(path: string, id: string): void {
    this.abortPush();
    try {
      this.push = this.store().startPush(path);
      this.send(TYPE_TRANSFER, JSON.stringify({ id: id, ok: true, ready: true }), empty);
    } catch (e) {
      this.send(TYPE_TRANSFER, JSON.stringify({ id: id, ok: false, error: messageOf(e) }), empty);
    }
  }

  private static onChunk(obj: Object, bin: Uint8Array): void {
    const current = this.push;
    if (current === null) {
      this.send(TYPE_TRANSFER, JSON.stringify({ ok: false, error: '没有正在接收的文件' }), empty);
      return;
    }
    try {
      this.store().append(current.fd, bin);
      if (fieldBool(obj, 'eof')) {
        const size = this.store().finishPush(current);
        this.push = null;
        this.send(TYPE_TRANSFER, JSON.stringify({ id: field(obj, 'id'), ok: true, size: size }), empty);
      }
    } catch (e) {
      this.abortPush();
      this.send(TYPE_TRANSFER, JSON.stringify({ id: field(obj, 'id'), ok: false, error: messageOf(e) }), empty);
    }
  }

  private static simple(op: string, path: string, fn: (store: FileStore, path: string) => void): void {
    try {
      fn(this.store(), path);
      this.send(TYPE_OP_RESULT, JSON.stringify({ op: op, ok: true, path: path }), empty);
    } catch (e) {
      this.send(TYPE_OP_RESULT, JSON.stringify({ op: op, ok: false, path: path, error: messageOf(e) }), empty);
    }
  }

  private static startScreen(maxWidth: number, quality: number, fps: number): void {
    this.stopScreen();
    Bridge.quality = Math.min(95, Math.max(30, quality));
    Bridge.screenOn = true;
    const every = Math.max(80, Math.floor(1000 / Math.min(20, Math.max(1, fps))));
    this.send(TYPE_SCREEN_STATUS, JSON.stringify({
      ok: true,
      active: true,
      width: Bridge.width,
      height: Bridge.height
    }), empty);
    const pace = Math.min(20, Math.max(1, fps));
    const widthCap = Math.min(1920, Math.max(320, maxWidth));
    if (widthCap > 0 && pace > 0) {
      Bridge.frameW = Math.min(Bridge.frameW, widthCap);
    }
    this.screenTimer = setInterval(() => {
      if (Bridge.latestJpeg.byteLength === 0) {
        return;
      }
      const jpeg = Bridge.latestJpeg;
      this.send(TYPE_FRAME, JSON.stringify({
        w: Bridge.frameW,
        h: Bridge.frameH,
        ts: Date.now()
      }), jpeg);
    }, every);
  }

  private static stopScreen(): void {
    Bridge.screenOn = false;
    if (this.screenTimer >= 0) {
      clearInterval(this.screenTimer);
      this.screenTimer = -1;
    }
  }

  private static onInput(obj: Object): void {
    const action = field(obj, 'action');
    if (action === 'toast') {
      Bridge.toast(field(obj, 'text'));
      Bridge.note('提示');
      this.send(TYPE_INPUT_RESULT, JSON.stringify({ ok: true }), empty);
      return;
    }
    if (action === 'text') {
      const text = field(obj, 'text');
      if (text.length > 4000) {
        this.send(TYPE_INPUT_RESULT, JSON.stringify({ ok: false, error: '文字过长' }), empty);
        return;
      }
      Bridge.remoteText = text;
      Bridge.note('文字');
      this.send(TYPE_INPUT_RESULT, JSON.stringify({ ok: true }), empty);
      return;
    }
    if (action === 'key') {
      const key = field(obj, 'key');
      Bridge.note('按键 ' + key);
      if (key === 'back' || key === 'home' || key === 'recents') {
        this.send(TYPE_INPUT_RESULT, JSON.stringify({
          ok: false,
          error: '鸿蒙普通应用不能控制系统按键，只能操作桥联这个页面'
        }), empty);
        return;
      }
      this.send(TYPE_INPUT_RESULT, JSON.stringify({ ok: true }), empty);
      return;
    }
    if (action === 'tap' || action === 'swipe' || action === 'scroll') {
      Bridge.note(`${action} ${field(obj, 'x')} ${field(obj, 'y')}`);
      this.send(TYPE_INPUT_RESULT, JSON.stringify({ ok: true }), empty);
      return;
    }
    this.send(TYPE_INPUT_RESULT, JSON.stringify({ ok: false, error: '未知操作' }), empty);
  }

  private static store(): FileStore {
    if (Bridge.context === null) {
      throw new Error('应用还没准备好');
    }
    return new FileStore(Bridge.context);
  }

  private static abortPush(): void {
    if (this.push !== null && Bridge.context !== null) {
      new FileStore(Bridge.context).abortPush(this.push);
    }
    this.push = null;
  }

  private static announce(): void {
    const udp: socket.UDPSocket = socket.constructUDPSocketInstance();
    const beacon = (): string => JSON.stringify({
      magic: 'QLP1',
      name: Bridge.name,
      os: 'harmony',
      id: Bridge.deviceId,
      port: TCP_PORT,
      proto: 1
    });
    udp.bind({ address: '0.0.0.0', port: UDP_PORT, family: 1 }).then(() => {
      udp.on('message', (value: socket.SocketMessageInfo) => {
        const text = bytesToString(value.message);
        if (text.indexOf('"op":"discover"') < 0) {
          return;
        }
        udp.send({
          data: beacon(),
          address: { address: value.remoteInfo.address, port: value.remoteInfo.port, family: 1 }
        });
      });
    }).catch(() => {
    });
    setInterval(() => {
      udp.send({
        data: beacon(),
        address: { address: '255.255.255.255', port: UDP_PORT, family: 1 }
      }).catch(() => {
      });
    }, 2000);
  }
}

function messageOf(e: Object): string {
  const err = e as Error;
  if (err.message !== undefined && err.message.length > 0) {
    if (err.message.indexOf('No such file') >= 0 || err.message.indexOf('not exist') >= 0) {
      return '文件不存在';
    }
    return err.message;
  }
  return '失败';
}

function randomToken(): string {
  let out = '';
  for (let i = 0; i < 64; i++) {
    out += Math.floor(Math.random() * 16).toString(16);
  }
  return out;
}

function bytesToString(message: ArrayBuffer): string {
  return decodeText(new Uint8Array(message));
}
