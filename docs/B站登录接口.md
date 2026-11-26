# B站登录接口整理（基于 renmu123/biliAPI 源码）

> 整理范围：只读仓库源码，不运行仓库代码，不联网。
> 结论先行：该仓库只实现了 **TV 扫码登录** 和 **Web 扫码登录**，以及 **TV OAuth token 刷新**；**没有短信登录、Web Cookie 刷新、退出登录** 的实现。文末「仓库中未找到」列出全部缺口。

## 1. 来源

| 项目 | 内容 | 确认位置 |
| --- | --- | --- |
| 仓库 | `https://github.com/renmu123/biliAPI`（`renmu123/biliAPI`） | `package.json:6` 的 `homepage` |
| 包名/版本 | `@renmu/bili-api` / `2.15.2` | `package.json:2-3` |
| commit | `01cce27f77fdd0600e80275f92b6908868f1762b`（短号 `01cce27`） | 本地仓库 `git rev-parse HEAD` 核对 |
| 许可证 | `GPL`；`LICENSE` 正文为 **GNU General Public License Version 3**（GPL-3.0） | `package.json:31`；`LICENSE:1-2` |
| Node 要求 | `>=18` | `package.json:19-21` |

许可证说明：`package.json:31` 只写了 `"license": "GPL"`，未写 SPDX 的 `GPL-3.0`；但仓库根目录 `LICENSE:1-2` 明确是 `GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007`，因此按 **GPL-3.0** 记录。

登录相关公开导出只有两个类：

- `TvQrcodeLogin`：`src/user/login.ts:59`
- `WebQrcodeLogin`：`src/user/login.ts:259`
- 导出位置：`src/index.ts:1`、`src/index.ts:120-124`

---

## 2. 扫码登录

### 2.1 公共行为

两个扫码类都继承 `BaseRequest`，构造时把 `Content-Type` 设为 `application/x-www-form-urlencoded`，并把 `cookie` 置为 `null`：

- TV：`src/user/login.ts:69-79`
- Web：`src/user/login.ts:267-277`

轮询间隔默认 **2000 ms**：

- TV：`src/user/login.ts:67`
- Web：`src/user/login.ts:261`

两个 `login()` 都会在轮询计数 `count > 180` 时主动按「二维码已失效」结束：

- TV：`src/user/login.ts:163-179`
- Web：`src/user/login.ts:352-368`

按默认 2 秒间隔，约 6 分钟后本地超时；超时事件里的对象是 `{ code: 86038, message: "二维码已失效", ttl: 1, data: null }`（同上两处）。

响应处理注意：`BaseRequest` 默认会把响应解包成 `response.data.data`；带 `extra.rawResponse: true` 时则返回完整 Axios 响应，再由调用方取 `.data`；`code !== 0` 默认会抛错。见 `src/base/index.ts:64-83`。

---

### 2.2 TV 扫码登录

#### 2.2.1 申请二维码

| 项 | 内容 | 出处 |
| --- | --- | --- |
| URL | `http://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code` | `src/user/login.ts:102` |
| 方法 | `POST` | `src/user/login.ts:101-104` |
| 请求体 | `application/x-www-form-urlencoded` | `src/user/login.ts:72` |
| 参数 | `appkey`、`local_id`、`ts`、`sign` | `src/user/login.ts:88-99` |
| 固定值 | `appkey = "4409e2ce8ffd12b8"`；`local_id = "0"` | `src/user/login.ts:60`、`src/user/login.ts:95` |
| 时间戳 | `ts = Math.floor(Date.now() / 1000)`（秒） | `src/user/login.ts:96` |
| 签名 | `sign = md5("appkey=...&local_id=...&ts=..." + secretKey)` | `src/user/login.ts:247-253` |
| 密钥 | `secretKey = "59b43e04ad6965f34319062b478f83dd"`（源码硬编码） | `src/user/login.ts:61` |
| 返回 | `{ url, auth_code }` | `src/user/login.ts:84-87`；`auth_code` 用于轮询 `src/user/login.ts:139`；`url` 用于生成二维码 `src/user/login.ts:183` |

签名细节（`src/user/login.ts:247-253`）：

```ts
const queryString = Object.keys(params)
  .map(key => `${key}=${params[key]}`)
  .join("&");
const signString = `${queryString}${this.secretKey}`;
return md5(signString);
```

`md5` 是 Node `crypto` 的 MD5 十六进制小写摘要，见 `src/utils/index.ts:93-97`。

注意：

- 该接口源码写的是 **`http://`**，不是 `https://`（`src/user/login.ts:102`）。
- 计算签名时 `params.sign` 尚未赋值，因此 `sign` 不参与自身计算；参与的是 `appkey`、`local_id`、`ts`（`src/user/login.ts:99`、`src/user/login.ts:247-253`）。
- 返回类型没有 `CommonResponse` 外壳，是因为默认响应拦截器已经把 `data.data` 解包（`src/base/index.ts:64-83`）。

#### 2.2.2 轮询二维码状态

| 项 | 内容 | 出处 |
| --- | --- | --- |
| URL | `http://passport.bilibili.com/x/passport-tv-login/qrcode/poll` | `src/user/login.ts:126` |
| 方法 | `POST` | `src/user/login.ts:125-133` |
| 参数 | `appkey`、`auth_code`、`local_id`、`ts`、`sign` | `src/user/login.ts:111-124` |
| 固定值 | `appkey` 同申请接口；`local_id = "0"` | `src/user/login.ts:60`、`src/user/login.ts:120` |
| 签名 | 同 TV 申请接口的 MD5 规则 | `src/user/login.ts:124`、`src/user/login.ts:247-253` |
| 请求选项 | `extra.rawResponse: true` | `src/user/login.ts:128-132` |
| 返回 | 响应体 `{ code, message, ttl, data }` | 类型声明 `src/user/login.ts:15-21`；返回 `src/user/login.ts:134` |

TV 轮询返回的层级：`poll()` 在 `rawResponse` 下拿到完整 Axios 响应，返回 `res.data`，即服务端响应体本身（`src/user/login.ts:125-134`）。因此**状态码取顶层 `code`**，与 Web 不同。

TV 状态码（源码注释，`src/user/login.ts:16-17`）：

| code | 含义 | 源码处理 |
| --- | --- | --- |
| `0` | 成功 | 触发 `completed` + `end`，停止轮询：`src/user/login.ts:145-149` |
| `86039` | 二维码尚未扫描 | 触发 `scan`，继续轮询：`src/user/login.ts:155-156` |
| `86090` | 二维码已扫码未确认 | 触发 `scan`，继续轮询：`src/user/login.ts:155-156` |
| `86038` | 二维码已失效 | 触发 `end` + `error`，停止轮询：`src/user/login.ts:150-154` |
| 其他 | 未列出的错误码 | 触发 `end` + `error`，停止轮询：`src/user/login.ts:157-162` |

#### 2.2.3 成功后 Cookie 与 refresh_token

TV 成功响应中的 `data` 在源码里只声明为 `any`：

```ts
interface TvLoginResponse {
  code: 0 | 86039 | 86038 | 86090;
  message: string;
  ttl: 1;
  data: any | null;
}
```

出处：`src/user/login.ts:15-21`（`data: any` 在 `src/user/login.ts:20`）。

仓库没有展开 TV 成功 `data` 的完整字段，但 `Auth.loadCookieFile()` 明确从保存下来的该 `data` 中读取：

| 读取路径 | 用途 | 出处 |
| --- | --- | --- |
| `cookie_info.cookies[].name` / `.value` | 拼成 `name=value; name=value` 的 Cookie 字符串 | `src/base/Auth.ts:21-26` |
| `cookie_info.cookies[]` | 规约为 `cookieObj` 键值对象 | `src/base/Auth.ts:27-36` |
| `token_info.access_token` | 赋给 `this.accessToken` | `src/base/Auth.ts:37` |
| `token_info.mid` | 赋给 `this.uid` | `src/base/Auth.ts:38` |

因此：

- **TV 的 Cookie 来自 JSON 响应体的 `data.cookie_info.cookies`，不是 `Set-Cookie` 响应头。** 仓库登录代码没有读取 `Set-Cookie` 来落 Cookie。
- 使用方式是把完成回调里的 `res.data` 原样保存，再交给 `loadCookieFile`；README 也是这样示范的：`README.md:113-114`，加载逻辑 `src/base/Auth.ts:17-39`、`src/index.ts:86-92`。
- **TV 的 `refresh_token` 在 `data` 的哪个字段，仓库没有声明也没有读取。** `TvLoginResponse.data` 是 `any`（`src/user/login.ts:20`），`Auth` 只读 `token_info.access_token` 和 `token_info.mid`（`src/base/Auth.ts:37-38`），没有读 `token_info.refresh_token`。`refresh()` 方法只是接收调用方传入的 `refresh_token`（`src/user/login.ts:186`），源码没有展示它的来源。
- TV 登录类构造时没有注入 `Auth`（`super(undefined, ...)`，`src/user/login.ts:70`），所以登录成功后**不会自动写入 `Client.auth`**；需要保存后加载，或手动调用 `setAuth`。

---

### 2.3 Web 扫码登录

#### 2.3.1 申请二维码

| 项 | 内容 | 出处 |
| --- | --- | --- |
| URL | `https://passport.bilibili.com/x/passport-login/web/qrcode/generate` | `src/user/login.ts:287` |
| 方法 | `GET` | `src/user/login.ts:286-288` |
| 参数 | 无 | `src/user/login.ts:286-288` |
| 返回 | `{ url, qrcode_key }` | `src/user/login.ts:282-285` |

`url` 用于生成二维码；`qrcode_key` 用于轮询（`src/user/login.ts:321-322`）。默认响应拦截器会解包出 `data.data`（`src/base/index.ts:64-83`）。

#### 2.3.2 轮询二维码状态

| 项 | 内容 | 出处 |
| --- | --- | --- |
| URL | `https://passport.bilibili.com/x/passport-login/web/qrcode/poll` | `src/user/login.ts:296` |
| 方法 | `GET` | `src/user/login.ts:295-305` |
| 查询参数 | `qrcode_key` | `src/user/login.ts:298-300` |
| 请求选项 | `extra.rawResponse: true` | `src/user/login.ts:301-303` |
| 返回 | 响应体 `CommonResponse<{ url, refresh_token, timestamp, code, message }>` | `src/user/login.ts:30-37`；返回 `src/user/login.ts:306` |

类型声明（`src/user/login.ts:30-37`）：

```ts
type WebLoginResponse = CommonResponse<{
  url: string;
  refresh_token: string;
  timestamp: number;
  /** 0：成功;86101:二维码尚未扫描;86038：二维码已失效;86090：二维码已扫码未确认 */
  code: 0 | 86101 | 86038 | 86090;
  message: string;
}>;
```

外层 `CommonResponse` 的 `code` / `message` / `ttl` / `data` 定义见 `src/types/index.ts:6-11`。

Web 轮询返回的层级与 TV 不同：

- `poll()` 返回响应体 `res.data`（`src/user/login.ts:294-306`）。
- `login()` 再取 `res.data` 作为内层数据，并检查其 `code`：`src/user/login.ts:327-329`。
- 也就是说，**二维码状态在内层 `data.code`**，外层是接口调用的 `code`。

Web 状态码（源码注释，`src/user/login.ts:34-35`）：

| 内层 `data.code` | 含义 | 源码处理 |
| --- | --- | --- |
| `0` | 成功 | 解析 URL/Cookie，触发 `completed` + `end`，停止轮询：`src/user/login.ts:329-338` |
| `86101` | 二维码尚未扫描 | 触发 `scan`，继续轮询：`src/user/login.ts:344-345` |
| `86090` | 二维码已扫码未确认 | 触发 `scan`，继续轮询：`src/user/login.ts:344-345` |
| `86038` | 二维码已失效 | 触发 `end` + `error`，停止轮询：`src/user/login.ts:339-343` |
| 其他 | 未列出的错误码 | 触发 `end` + `error`，停止轮询：`src/user/login.ts:346-351` |

#### 2.3.3 成功后 Cookie 如何获得、refresh_token 在哪

成功分支代码（`src/user/login.ts:329-338`）：

```ts
if (response.code === 0) {
  const url = new URL(response.url);
  const data = {
    ...paramsToObject(url.searchParams.entries()),
    refresh_token: response.refresh_token,
  } as WebCompleteResponse;
  ...
}
```

结论：

- **Web 的 Cookie 来自登录成功 URL 的查询参数，不是 `Set-Cookie` 响应头。** 代码把 `response.url` 交给 `new URL()`，再遍历 `url.searchParams.entries()` 逐个塞进对象（`src/user/login.ts:330-334`，辅助函数 `src/user/login.ts:310-319`）。仓库没有读取响应头 `Set-Cookie`。
- **`refresh_token` 不来自 URL 查询参数，而是来自轮询响应内层 `data.refresh_token`**，然后单独合并进对象（`src/user/login.ts:333`；类型字段见 `src/user/login.ts:32`）。
- `WebCompleteResponse` 声明的字段为：`DedeUserID`、`DedeUserID__ckMd5`、`Expires`、`SESSDATA`、`bili_jct`、`gourl`、`first_domain`、`refresh_token`（`src/user/login.ts:38-47`）。
- 由于代码是展开 URL 的**全部**查询参数（`src/user/login.ts:332`），实际字段集合以服务端返回的 `url` 为准；上面的接口只是类型声明。README 的示例字段为 `DedeUserID`、`DedeUserID__ckMd5`、`Expires`、`SESSDATA`、`bili_jct`、`gourl`、`refresh_token`（`README.md:115-124`），未列 `first_domain`。
- Web 登录类同样在构造时没有注入 `Auth`（`src/user/login.ts:268`），完成后需要手动 `setAuth`；README 写的是「可以尝试使用 setAuth 进行登录」（`README.md:115`）。

`Auth.setAuth()` 接收 Cookie 对象并拼成 Cookie 字符串，见 `src/base/Auth.ts:44-64`；`Auth.authLogin()` 只检查 Cookie/accessToken 是否存在，不校验过期时间，见 `src/base/Auth.ts:70-94`。

---

## 3. 短信登录

### 3.1 结论

**仓库没有短信登录实现。** `src/user/login.ts` 只有 `TvQrcodeLogin`（`src/user/login.ts:59`）和 `WebQrcodeLogin`（`src/user/login.ts:259`）两个登录类，没有短信登录类或方法。

对 `src` 全目录检索 `sms`、`captcha`、`geetest` 等关键词，**无任何命中**；登录相关 URL 只有 TV/Web 二维码和 TV token 刷新（`src/user/login.ts:102`、`126`、`203`、`287`、`296`）。

### 3.2 三步流程核对

| 步骤 | 期望的接口 | 仓库情况 |
| --- | --- | --- |
| 1. 获取极验/captcha 参数 | 获取 `token`、`gt`、`challenge` 等 | **未找到**：`src` 内无 `captcha` / `geetest` |
| 2. 发送短信验证码 | 传入 `tel`、`cid`、`captcha_key` 等 | **未找到**：无发送短信接口 |
| 3. 短信登录 | 传入 `tel`、`cid`、`code` 等换取登录态 | **未找到**：无短信登录接口 |

### 3.3 逐项核对

| 需要的项 | 仓库是否存在 | 说明 |
| --- | --- | --- |
| 获取极验参数（captcha / geetest）的 URL、方法 | 否 | `src` 内无 `captcha` / `geetest` 命中 |
| 极验参数 `token`、`challenge`、`gt` | 否 | 未出现 |
| 发送短信验证码的 URL、方法 | 否 | 无 `sms/send` 或同类接口 |
| 发送验证码参数 `tel`、`cid`、`captcha_key` | 否 | 未出现 |
| 短信登录的 URL、方法 | 否 | 无 `login/sms` 或同类接口 |
| 短信登录参数 `tel`、`cid`、`code`、`source`、`csrf` | 否 | 未出现 |
| 极验验证参数 `challenge`、`validate`、`seccode` | 否 | 未出现 |
| 极验验证返回的 `captcha_key` | 否 | 未出现 |
| 短信登录返回字段（Cookie / token 等） | 否 | 无实现，无法从本仓库确认 |

因此，**第 3 节不能给出可溯源到本仓库的 URL、方法、参数和返回字段**；本文不填写猜测值。若要补全短信登录，需另找可信来源，并按「非仓库来源，待验证」单独标注。

---

## 4. Cookie / 登录态刷新

### 4.1 仓库实际有的：TV OAuth token 刷新

`TvQrcodeLogin.refresh(access_key, refresh_token)` 是仓库唯一的刷新方法：

| 项 | 内容 | 出处 |
| --- | --- | --- |
| 方法 | `refresh(access_key, refresh_token)` | `src/user/login.ts:186` |
| URL | `https://passport.bilibili.com/api/v2/oauth2/refresh_token` | `src/user/login.ts:203` |
| 方法（HTTP） | `POST` | `src/user/login.ts:202-205` |
| 参数 | `appkey`、`access_key`、`refresh_token`、`ts`、`sign` | `src/user/login.ts:187-198` |
| 签名 | 同 TV 二维码接口的 MD5 规则 | `src/user/login.ts:200`、`src/user/login.ts:247-253` |
| 返回 | 类型标注为 `CommonResponse<TvLoginResponse>`；未启用 `rawResponse`，按拦截器实际会解包为 `data.data`（源码未进一步解析） | `src/user/login.ts:202-205`、`src/user/login.ts:15-21`、`src/base/index.ts:64-83` |
| 是否写回 `Auth` | 否，仅返回响应；没有更新 `auth.accessToken` 或 Cookie 的代码 | `src/user/login.ts:186-206` |

注意：这是 **TV 端 OAuth access_token / refresh_token 的刷新**，不是 Web 端 Cookie 刷新。源码没有展示 `refresh_token` 的获取位置（见 2.2.3）。

### 4.2 Web Cookie 刷新：仓库中不存在

用户要求的标准 Web Cookie 刷新链路，在仓库里逐项核对如下：

| 步骤 | 仓库是否存在 | 依据 |
| --- | --- | --- |
| 检查是否需要刷新（按过期时间/登录态） | 否 | `Auth.authLogin()` 只判断 Cookie 字符串或 accessToken 是否存在，不检查过期：`src/base/Auth.ts:70-94` |
| 生成 `correspondPath` | 否 | 全仓库无 `correspondPath` / `correspond_path` 命中 |
| RSA 公钥 / RSA 加密算法 | 否 | 全仓库无对应实现；`refresh` 只有 MD5 签名 `src/user/login.ts:247-253` |
| 获取 `refresh_csrf` | 否 | 全仓库无 `refresh_csrf` 命中 |
| 调用刷新接口 | 否（Web 无） | `WebQrcodeLogin` 类只有 `getQrcode`、`poll`、`login`、`interrupt`、`removeAllListeners`：`src/user/login.ts:259-391` |
| 确认刷新（confirm / 二次请求） | 否 | 无对应方法或 URL |

结论：**Web Cookie 的 `correspondPath`、RSA、`refresh_csrf`、刷新、确认刷新这一整套，在本仓库中完全没有实现**。仓库只有 4.1 的 TV OAuth token 刷新。

---

## 5. 退出登录接口

**仓库中没有退出登录 / 注销 / logout 接口。**

- 对全仓库检索 `logout`、`退出`、`注销`、`signOut`、`revoke`，没有登录态退出实现。
- `exit` 的命中仅与子进程退出码处理有关，与登录无关：`src/utils/index.ts:167-172`、`src/utils/ffmpeg.ts:31-32`。
- `src/index.ts:120-136` 的公开导出里也没有 logout 相关类或方法。

因此本仓库不提供「退出登录」的 URL、方法或参数；`TvQrcodeLogin.refresh()` 是刷新授权，不是退出。

---

## 6. 其他相关

### 6.1 请求头与 Cookie 注入

| 项 | 内容 | 出处 |
| --- | --- | --- |
| 默认 User-Agent | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36` | `src/base/index.ts:42-45` |
| 登录请求 Content-Type | `application/x-www-form-urlencoded` | `src/user/login.ts:72`、`src/user/login.ts:270` |
| 登录请求初始 Cookie | `cookie: null` | `src/user/login.ts:73`、`src/user/login.ts:271` |
| Cookie 自动注入 | 请求未显式设置 `cookie` 时，注入 `auth.cookie`；`extra.useCookie === false` 时置空 | `src/base/index.ts:49-54` |
| Host 头 | 未显式设置时，从 URL 取 `hostname` 填入 `host` | `src/base/index.ts:56-59` |
| Referer / Origin | **登录模块没有设置**；`Referer`/`Origin` 只出现在用户空间、视频、搜索等业务接口 | 登录：`src/user/login.ts:69-79`、`267-277`；其他示例：`src/user/index.ts:69-70`、`src/video/index.ts:450` |
| 响应解包 | 默认返回 `data.data`；`rawResponse: true` 返回完整响应；`code !== 0` 抛 `BiliResponseError` | `src/base/index.ts:64-83` |
| 重试 | axios-retry 配置为 `retries: 0` | `src/base/index.ts:104` |

说明：TV/Web 二维码登录的请求没有额外设置 `Referer` 或 `Origin`；如果实现方要补，需自行验证 B 站是否要求，不能从本仓库推出。

### 6.2 TV 接口的 appkey + MD5 签名

仅用于 TV 二维码申请、TV 二维码轮询、TV token 刷新：

- 参数按对象键顺序拼成 `k=v&k=v`，末尾直接拼 `secretKey`，再取 MD5：`src/user/login.ts:247-253`
- `secretKey` 硬编码为 `59b43e04ad6965f34319062b478f83dd`：`src/user/login.ts:61`
- `appkey` 硬编码为 `4409e2ce8ffd12b8`：`src/user/login.ts:60`
- `md5()` 实现：`src/utils/index.ts:93-97`

该签名不是 WBI，不涉及 `w_rid` / `wts`。

### 6.3 WBI 签名（登录流程未使用）

WBI 在本仓库用于用户空间等接口，**不用于本仓库的二维码登录流程**。相关实现：

| 项 | 内容 | 出处 |
| --- | --- | --- |
| mixinKey 混淆表 | 64 个下标的数组 | `src/base/sign.ts:4-9` |
| 生成 mixinKey | 按混淆表取字符后截取前 32 位 | `src/base/sign.ts:12-18` |
| WBI 签名 | 加 `wts`、按 key 排序、过滤 `!'()*`、URL 编码、MD5(query + mixinKey)、追加 `w_rid` | `src/base/sign.ts:21-41` |
| 获取 img_key / sub_key | 请求 `https://api.bilibili.com/x/web-interface/nav`，从 `wbi_img.img_url` / `sub_url` 文件名截取 | `src/base/sign.ts:44-64` |
| `BaseRequest.WbiSign` | 缓存 keys 后调用 `encWbi` | `src/base/index.ts:110-122` |
| 业务使用示例 | `User.getUserInfo` 对 `mid` 等参数做 WBI 签名 | `src/user/index.ts:48-54` |

### 6.4 登录轮询的节奏与超时

- 默认 `interval = 2000` ms：`src/user/login.ts:67`、`src/user/login.ts:261`
- 每次轮询后 `count++`，`count > 180` 时本地判定失效：`src/user/login.ts:163-179`、`src/user/login.ts:352-368`
- 失效对象固定为 `code: 86038`、`message: "二维码已失效"`：同上
- 这是本地轮询次数上限，不是服务端返回的 `ttl`；TV 类型里的 `ttl: 1` 只是声明（`src/user/login.ts:19`）。

### 6.5 登录结果不会自动持久化

- `TvQrcodeLogin` / `WebQrcodeLogin` 构造时都传入 `undefined` 作为 `Auth`（`src/user/login.ts:70`、`src/user/login.ts:268`）。
- 完成后只通过事件返回数据（`src/user/login.ts:335-336`、`src/user/login.ts:146-147`）。
- TV 需要保存 `res.data` 再 `loadCookieFile`（`README.md:113-114`、`src/base/Auth.ts:17-39`）；Web 需要手动 `setAuth`（`README.md:115`、`src/base/Auth.ts:44-64`）。

---

## 7. 仓库中未找到

以下项目在 `D:\ai\ext-repos\biliAPI`（commit `01cce27`）中**没有实现或没有声明**：

| 未找到项 | 说明 / 检索依据 |
| --- | --- |
| 短信登录全流程 | `src` 内无 `sms`、`captcha`、`geetest` 命中；`src/user/login.ts` 只有 TV/Web 扫码两个类（`src/user/login.ts:59`、`259`） |
| 获取极验参数的 URL / 方法 / 返回 | 无 `captcha` / `geetest` 代码 |
| `tel`、`cid`、`token`、`challenge`、`validate`、`seccode`、`captcha_key` 字段 | 全仓库未出现 |
| 发送短信验证码接口 | 无 |
| 短信登录接口 | 无 |
| 短信登录返回字段 / Cookie 获取方式 | 无实现，无法从仓库确认 |
| Web Cookie 刷新：检查是否需要刷新 | `Auth.authLogin()` 只检查存在性（`src/base/Auth.ts:70-94`） |
| `correspondPath` | 全仓库无命中 |
| RSA 公钥 / RSA 加密算法 | 全仓库无实现 |
| `refresh_csrf` | 全仓库无命中 |
| Web Cookie 刷新接口 | `WebQrcodeLogin` 类无 refresh 方法（`src/user/login.ts:259-391`） |
| 刷新确认（confirm）步骤 | 无 |
| 退出登录 / 注销 / logout 接口 | 全仓库无登录态退出实现；`exit` 仅子进程退出码（`src/utils/index.ts:167-172`） |
| 登录态过期判断 | 仅判断 Cookie/accessToken 是否存在（`src/base/Auth.ts:70-94`） |
| TV 成功响应 `data` 的完整字段定义 | `data: any`（`src/user/login.ts:20`），仓库只读取 `cookie_info.cookies`、`token_info.access_token`、`token_info.mid`（`src/base/Auth.ts:21-38`） |
| TV `refresh_token` 在成功响应中的具体路径 | 未声明、未读取；`refresh()` 只接收调用方传入值（`src/user/login.ts:186`） |
| Web 登录读取 `Set-Cookie` | 代码只解析成功 URL 的查询参数（`src/user/login.ts:330-334`） |
| 登录二维码图片生成 | 只返回 `url` 字符串，不生成图片：TV `src/user/login.ts:183`，Web `src/user/login.ts:372` |
| 短信/极验相关错误码 | 无实现，无声明 |

> 关于仓库外补充：本文没有把任何外部接口知识写成正式接口。若后续需要补短信登录或 Web Cookie 刷新，应另行核对可信资料，并逐条标注「非仓库来源，待验证」，不要与上述源码出处混用。
