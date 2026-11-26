# 拾图

一个找原图、存素材的安卓工具箱。免费，无广告，不收集数据，GPL-3.0 开源。

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
- SauceNAO 需要 API Key，在「我的 → 识图引擎」里填写。在同一页还可以选填 Gelbooru 凭证，填了之后 Gelbooru 的结果会显示标签和原图尺寸。
- Telegram 贴纸需要先在 @BotFather 免费创建一个机器人，把它给的 Token 填进应用。

## 开发
构建方法、代码结构和各模块接口见 [docs/开发维护.md](docs/开发维护.md)。

## 许可
GPL-3.0。第三方组件的来源和许可证见 [NOTICE](NOTICE)。
