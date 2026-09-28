import { util } from '@kit.ArkTS';

export const TCP_PORT: number = 37622;
export const UDP_PORT: number = 37621;
export const MAX_JSON: number = 1 << 20;
export const MAX_BIN: number = 4 << 20;
export const CHUNK: number = 256 * 1024;

export const TYPE_HELLO: number = 1;
export const TYPE_PAIR: number = 2;
export const TYPE_PAIR_RESULT: number = 3;
export const TYPE_PING: number = 4;
export const TYPE_PONG: number = 5;
export const TYPE_LIST: number = 10;
export const TYPE_LIST_RESULT: number = 11;
export const TYPE_PULL: number = 12;
export const TYPE_PUSH_BEGIN: number = 13;
export const TYPE_CHUNK: number = 14;
export const TYPE_TRANSFER: number = 15;
export const TYPE_MKDIR: number = 16;
export const TYPE_DELETE: number = 17;
export const TYPE_RENAME: number = 18;
export const TYPE_OP_RESULT: number = 19;
export const TYPE_SCREEN_START: number = 20;
export const TYPE_SCREEN_STOP: number = 21;
export const TYPE_FRAME: number = 22;
export const TYPE_SCREEN_STATUS: number = 23;
export const TYPE_INPUT: number = 30;
export const TYPE_INPUT_RESULT: number = 31;
export const TYPE_CLIP_SET: number = 32;
export const TYPE_CLIP_GET: number = 33;
export const TYPE_CLIP_DATA: number = 34;
export const TYPE_ERROR: number = 40;

const encoder = new util.TextEncoder();
const decoder = new util.TextDecoder('utf-8', { ignoreBOM: true });

export function encodeText(text: string): Uint8Array {
  return encoder.encodeInto(text);
}

export function decodeText(bytes: Uint8Array): string {
  return decoder.decodeToString(bytes);
}

export interface QFrame {
  type: number;
  json: string;
  bin: Uint8Array;
}

export function encodeFrame(type: number, json: string, bin: Uint8Array): ArrayBuffer {
  const jsonBytes = encodeText(json);
  const total = 12 + jsonBytes.byteLength + bin.byteLength;
  const out = new ArrayBuffer(total);
  const view = new DataView(out);
  const bytes = new Uint8Array(out);
  bytes[0] = 0x51;
  bytes[1] = 0x4c;
  bytes[2] = 1;
  bytes[3] = type;
  view.setUint32(4, jsonBytes.byteLength, false);
  view.setUint32(8, bin.byteLength, false);
  bytes.set(jsonBytes, 12);
  if (bin.byteLength > 0) {
    bytes.set(bin, 12 + jsonBytes.byteLength);
  }
  return out;
}

export class FrameParser {
  private buf: Uint8Array = new Uint8Array(0);

  push(chunk: Uint8Array): QFrame[] {
    const merged = new Uint8Array(this.buf.length + chunk.length);
    merged.set(this.buf, 0);
    merged.set(chunk, this.buf.length);
    this.buf = merged;
    const frames: QFrame[] = [];
    while (this.buf.length >= 12) {
      if (this.buf[0] !== 0x51 || this.buf[1] !== 0x4c || this.buf[2] !== 1) {
        throw new Error('协议头不匹配');
      }
      const view = new DataView(this.buf.buffer, this.buf.byteOffset, this.buf.byteLength);
      const jsonLen = view.getUint32(4, false);
      const binLen = view.getUint32(8, false);
      if (jsonLen > MAX_JSON || binLen > MAX_BIN) {
        throw new Error('帧过大');
      }
      const total = 12 + jsonLen + binLen;
      if (this.buf.length < total) {
        break;
      }
      const jsonBytes = this.buf.slice(12, 12 + jsonLen);
      const bin = this.buf.slice(12 + jsonLen, total);
      frames.push({
        type: this.buf[3],
        json: jsonLen === 0 ? '{}' : decodeText(jsonBytes),
        bin: bin
      });
      this.buf = this.buf.slice(total);
    }
    return frames;
  }
}

export function field(obj: Object, key: string): string {
  const rec = obj as Record<string, Object>;
  const value = rec[key];
  if (value === undefined || value === null) {
    return '';
  }
  return `${value}`;
}

export function fieldNum(obj: Object, key: string, fallback: number): number {
  const text = field(obj, key);
  if (text.length === 0) {
    return fallback;
  }
  const n = Number(text);
  if (Number.isNaN(n)) {
    return fallback;
  }
  return n;
}

export function fieldBool(obj: Object, key: string): boolean {
  const rec = obj as Record<string, Object>;
  return rec[key] === true;
}
