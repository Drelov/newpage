import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { deviceInfo, pasteboard } from '@kit.BasicServicesKit';
import { picker } from '@kit.CoreFileKit';
import { preferences } from '@kit.ArkData';
import { connection } from '@kit.NetworkKit';
import { display, promptAction } from '@kit.ArkUI';
import { FileStore } from './FileStore';

export class Bridge {
  static context: common.UIAbilityContext | null = null;
  static code: string = randomCode();
  static deviceId: string = randomHex(32);
  static token: string = '';
  static name: string = '鸿蒙手机';
  static osVersion: string = '';
  static ip: string = '正在获取 IP';
  static status: string = '正在启动';
  static remoteText: string = '';
  static lastAction: string = '';
  static screenOn: boolean = false;
  static quality: number = 70;
  static latestJpeg: Uint8Array = new Uint8Array(0);
  static frameW: number = 1080;
  static frameH: number = 2400;
  static width: number = 1080;
  static height: number = 2400;
  private static pref: preferences.Preferences | null = null;
  private static started: boolean = false;

  static start(context: common.UIAbilityContext): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.context = context;
    this.name = deviceInfo.marketName.length > 0 ? deviceInfo.marketName : '鸿蒙手机';
    this.osVersion = deviceInfo.osFullName;
    try {
      const info = display.getDefaultDisplaySync();
      this.width = info.width;
      this.height = info.height;
      this.frameW = info.width;
      this.frameH = info.height;
    } catch (_e) {
    }
    preferences.getPreferences(context, 'qiaolian').then((pref: preferences.Preferences) => {
      this.pref = pref;
      const savedCode = `${pref.getSync('code', '')}`;
      const savedId = `${pref.getSync('id', '')}`;
      const savedToken = `${pref.getSync('token', '')}`;
      if (savedCode.length === 6) {
        this.code = savedCode;
      } else {
        pref.putSync('code', this.code);
      }
      if (savedId.length > 0) {
        this.deviceId = savedId;
      } else {
        pref.putSync('id', this.deviceId);
      }
      if (savedToken.length > 0) {
        this.token = savedToken;
      }
      pref.flush();
    });
    this.refreshIp();
    this.status = '等待电脑连接';
  }

  static newCode(): void {
    this.code = randomCode();
    if (this.pref !== null) {
      this.pref.putSync('code', this.code);
      this.pref.flush();
    }
    this.status = '已更换配对码';
  }

  static saveToken(token: string): void {
    this.token = token;
    if (this.pref !== null) {
      this.pref.putSync('token', token);
      this.pref.flush();
    }
    this.status = '电脑已配对';
  }

  static clearPairing(): void {
    this.token = '';
    if (this.pref !== null) {
      this.pref.putSync('token', '');
      this.pref.flush();
    }
    this.status = '已取消配对，电脑需要重新输入配对码';
  }

  static async askPermissions(): Promise<void> {
    if (this.context === null) {
      return;
    }
    const at = abilityAccessCtrl.createAtManager();
    const perms: Array<Permissions> = [
      'ohos.permission.READ_WRITE_DOWNLOAD_DIRECTORY',
      'ohos.permission.READ_WRITE_DOCUMENTS_DIRECTORY'
    ];
    try {
      await at.requestPermissionsFromUser(this.context, perms);
      this.status = '已申请文件夹权限';
    } catch (_e) {
      this.status = '文件夹权限未授予，仍可使用应用文件';
    }
  }

  static async pickFiles(): Promise<void> {
    if (this.context === null) {
      return;
    }
    try {
      const documentPicker = new picker.DocumentViewPicker(this.context);
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 20;
      const uris = await documentPicker.select(options);
      const store = new FileStore(this.context);
      for (let i = 0; i < uris.length; i++) {
        store.copyUri(uris[i]);
      }
      this.status = `已放入 ${uris.length} 个文件到应用文件`;
    } catch (_e) {
      this.status = '没有放入文件';
    }
  }

  static async clipboard(): Promise<string> {
    try {
      const board = pasteboard.getSystemPasteboard();
      const data = await board.getData();
      return data.getPrimaryText();
    } catch (_e) {
      return '';
    }
  }

  static async setClipboard(text: string): Promise<void> {
    const board = pasteboard.getSystemPasteboard();
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    await board.setData(data);
  }

  static toast(text: string): void {
    try {
      promptAction.showToast({ message: text });
    } catch (_e) {
    }
  }

  static note(action: string): void {
    this.lastAction = action;
  }

  private static async refreshIp(): Promise<void> {
    try {
      const handle = await connection.getDefaultNet();
      const props = await connection.getConnectionProperties(handle);
      if (props.linkAddresses.length > 0) {
        this.ip = props.linkAddresses[0].address.address;
      } else {
        this.ip = '未连接 Wi-Fi';
      }
    } catch (_e) {
      this.ip = '未连接 Wi-Fi';
    }
  }
}

function randomCode(): string {
  return `${100000 + Math.floor(Math.random() * 900000)}`;
}

function randomHex(n: number): string {
  let out = '';
  for (let i = 0; i < n; i++) {
    out += Math.floor(Math.random() * 16).toString(16);
  }
  return out;
}
