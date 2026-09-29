import { componentSnapshot } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { Bridge } from '../qlp/Bridge';
import { PhoneServer } from '../qlp/PhoneServer';

@Entry
@Component
struct Index {
  @State code: string = '';
  @State ip: string = '';
  @State status: string = '';
  @State remoteText: string = '';
  @State lastAction: string = '';
  private timer: number = -1;
  private capturing: boolean = false;

  aboutToAppear(): void {
    PhoneServer.start();
    Bridge.askPermissions();
    this.timer = setInterval(() => {
      this.code = Bridge.code;
      this.ip = Bridge.ip;
      this.status = Bridge.status;
      this.remoteText = Bridge.remoteText;
      this.lastAction = Bridge.lastAction;
      if (Bridge.screenOn) {
        this.capture();
      }
    }, 300);
  }

  aboutToDisappear(): void {
    clearInterval(this.timer);
  }

  private capture(): void {
    if (this.capturing) {
      return;
    }
    this.capturing = true;
    componentSnapshot.get('mirrorRoot').then((pm: image.PixelMap) => {
      const packer = image.createImagePacker();
      packer.packing(pm, { format: 'image/jpeg', quality: Bridge.quality }).then((data: ArrayBuffer) => {
        Bridge.latestJpeg = new Uint8Array(data);
        pm.getImageInfo().then((info: image.ImageInfo) => {
          Bridge.frameW = info.size.width;
          Bridge.frameH = info.size.height;
          pm.release();
          packer.release();
          this.capturing = false;
        }).catch(() => {
          pm.release();
          packer.release();
          this.capturing = false;
        });
      }).catch(() => {
        pm.release();
        this.capturing = false;
      });
    }).catch(() => {
      this.capturing = false;
    });
  }

  build() {
    Scroll() {
      Column() {
        Text('桥联')
          .fontSize(14)
          .fontColor('#2FBFB0')
          .letterSpacing(4)
        Text(this.code)
          .fontSize(48)
          .fontColor('#E8EEF8')
          .letterSpacing(8)
          .margin({ top: 12 })
        Text(this.ip + '  ·  端口 37622')
          .fontSize(16)
          .fontColor('#93A0B5')
          .margin({ top: 8 })
        Text(this.status)
          .fontSize(16)
          .fontColor('#E8EEF8')
          .margin({ top: 16 })
        Text(this.lastAction.length === 0 ? '还没有电脑操作' : ('最近操作  ' + this.lastAction))
          .fontSize(14)
          .fontColor('#E2A43A')
          .margin({ top: 8 })
        TextInput({ text: this.remoteText, placeholder: '电脑发来的文字出现在这里' })
          .margin({ top: 16 })
          .onChange((value: string) => {
            Bridge.remoteText = value;
          })
        Button('换一个配对码')
          .width('100%')
          .margin({ top: 16 })
          .onClick(() => Bridge.newCode())
        Button('取消所有配对')
          .width('100%')
          .margin({ top: 8 })
          .onClick(() => {
            Bridge.clearPairing();
            PhoneServer.dropClient();
          })
        Button('申请文件夹权限')
          .width('100%')
          .margin({ top: 8 })
          .onClick(() => Bridge.askPermissions())
        Button('把选中的文件放进共享')
          .width('100%')
          .margin({ top: 8 })
          .onClick(() => Bridge.pickFiles())
        Text('电脑和手机连同一个 Wi-Fi，在电脑打开桥联并输入配对码。鸿蒙普通应用不能截取其他应用，也不能把触控注入系统。电脑上的投屏是这个页面，远程操作也作用在这个页面。传文件可以用「应用文件」，系统允许时还有「下载」和「文档」。')
          .fontSize(14)
          .fontColor('#93A0B5')
          .margin({ top: 20 })
      }
      .id('mirrorRoot')
      .width('100%')
      .padding(24)
      .backgroundColor('#10141B')
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#10141B')
  }
}
