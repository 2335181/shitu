# 拾图

一个找原图、存素材的安卓工具箱。免费，无广告，不收集数据，GPL-3.0 开源。
<img width="1080" height="2400" alt="Screenshot_1795718015" src="https://github.com/user-attachments/assets/d39366dd-340c-46ac-92c7-372eab0ca1a5" />
<img width="1080" height="2400" alt="Screenshot_1795718007" src="https://github.com/user-attachments/assets/ab4dc161-b027-45d0-a9c7-d478a3f7e350" />
<img width="1080" height="2400" alt="Screenshot_1795718021" src="https://github.com/user-attachments/assets/609d3034-883d-491b-b9c0-8eeb5c143e31" />
<img width="1080" height="2400" alt="Screenshot_1795718032" src="https://github.com/user-attachments/assets/7d1214b8-c455-45ba-9e02-a8f75d8d51bd" />
<img width="1080" height="2400" alt="Screenshot_1795718032" src="https://github.com/user-attachments/assets/d977076d-63fe-4109-aeeb-976d115764ea" />

**当前版本：0.6.0-beta**（测试版，功能可能有问题，欢迎提 Issue）

## 功能
- **识图**：同时用多个引擎搜图。
  - 应用内直接出结果：SauceNAO、trace.moe、IQDB、Danbooru、Yande.re、Konachan、Gelbooru、Sankaku；
  - 在浏览器打开结果页：Google Lens、Yandex、Bing、ascii2d、百度、搜狗、TinEye。
- **链接解析**：
  - 抖音、小红书、Pixiv（含动图转 GIF）、Pinterest 取无水印视频和原图；
  - YouTube、X、B站等交给内置的 yt-dlp。
- **B站收藏集与装扮**：卡片图片和视频、静态与动态表情、头像框、装扮背景。
- **直播流**：获取抖音和 B站直播间的 FLV / HLS 地址。
- **更多工具**：视频转 GIF、转实况（Motion Photo）、Telegram 贴纸包、Noto 动态 Emoji、Wallpaper Engine 素材提取、音乐封面。
- 下载的文件默认保存在 `Download/拾图/图片` 和 `Download/拾图/视频`。

## 使用提示
- 在中国大陆不开代理时，能用的识图引擎是 SauceNAO、trace.moe、IQDB、百度和各图站。其他引擎需要开代理。
- SauceNAO 需要 API Key，在「我的 → 识图引擎」里填写。同一页还可以选填图站账号：
  - Danbooru「用户名:API Key」：填了之后用 Danbooru 自己的以图搜图，收录更全；
  - Gelbooru 凭证：填了之后结果会显示标签和原图尺寸。
- Telegram 贴纸需要先在 @BotFather 免费创建一个机器人，把它给的 Token 填进应用。

## 开发
构建方法、代码结构和各模块接口见 [docs/开发维护.md](docs/开发维护.md)。

## 致谢与借鉴的开源项目
本项目借鉴或移植了下列开源项目的代码与思路，感谢原作者：

| 项目 | 许可 | 借鉴内容 |
|---|---|---|
| [hiyufan/shizhen](https://github.com/hiyufan/shizhen)（求原图） | MIT | B站装扮/收藏集字段、抖音图集与直播、小红书原图、网易云封面等解析思路 |
| [renmu123/biliAPI](https://github.com/renmu123/biliAPI) | GPL-3.0 | B站扫码登录接口 |
| [notscuffed/RePKG](https://github.com/notscuffed/RePKG) | MIT | Wallpaper Engine PKG / TEX 文件格式 |
| [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android) | GPL-3.0 | 内置 yt-dlp、Python、FFmpeg |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense | 通用视频下载 |
| [FFmpeg](https://ffmpeg.org/) | LGPL / GPL | 动图、视频转换 |
| [chrisbanes/haze](https://github.com/chrisbanes/haze) | Apache-2.0 | 毛玻璃导航栏与悬浮按钮 |
| [Coil](https://github.com/coil-kt/coil)、[OkHttp](https://github.com/square/okhttp)、[jsoup](https://github.com/jhy/jsoup)、[ZXing](https://github.com/zxing/zxing)、[lottie-android](https://github.com/airbnb/lottie-android) | Apache-2.0 / MIT | 图片加载、网络、网页解析、二维码、Lottie 渲染 |
| Jetpack Compose、AndroidX、Media3、kotlinx | Apache-2.0 | 界面与基础库 |
| 思源宋体 Noto Serif SC、Fraunces | OFL-1.1 | 字体 |
| Google Noto Animated Emoji | Apache-2.0 | 动态 Emoji（运行时读取，不随包分发） |

完整的许可说明见 [NOTICE](NOTICE)。

## 作者
- qiulvan
bilibili：小眠https://space.bilibili.com/481490124
## 许可
GPL-3.0。
