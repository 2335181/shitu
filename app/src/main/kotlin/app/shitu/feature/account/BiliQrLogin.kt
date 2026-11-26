/*
 * B站 Web 扫码登录。接口参考 renmu123/biliAPI（GPL-3.0，commit 01cce27）src/user/login.ts
 * 第 287（generate）、296（poll）行，见 docs/B站登录接口.md。
 */
package app.shitu.feature.account

import android.net.Uri
import app.shitu.core.Http
import app.shitu.core.UserFacingException
import app.shitu.core.getText
import app.shitu.feature.bili.obj
import app.shitu.feature.bili.str
import kotlinx.serialization.json.jsonObject

object BiliQrLogin {
    data class Qr(val url: String, val key: String)

    sealed interface Poll {
        data object Waiting : Poll
        data object Scanned : Poll
        data object Expired : Poll
        data class Success(val cookies: Map<String, String>, val refreshToken: String) : Poll
    }

    private val headers = mapOf("Referer" to "https://www.bilibili.com/")

    suspend fun generate(): Qr {
        val root = Http.json.parseToJsonElement(
            Http.client.getText("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", headers),
        ).jsonObject
        val data = root.obj("data") ?: throw UserFacingException("二维码申请失败")
        return Qr(data.str("url") ?: throw UserFacingException("二维码申请失败"), data.str("qrcode_key")!!)
    }

    suspend fun poll(key: String): Poll {
        val root = Http.json.parseToJsonElement(
            Http.client.getText(
                "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + Uri.encode(key),
                headers,
            ),
        ).jsonObject
        val data = root.obj("data") ?: return Poll.Waiting
        return when (data.str("code")?.toIntOrNull()) {
            0 -> {
                val uri = Uri.parse(data.str("url").orEmpty())
                val cookies = listOf("DedeUserID", "DedeUserID__ckMd5", "SESSDATA", "bili_jct", "sid")
                    .mapNotNull { k -> uri.getQueryParameter(k)?.let { k to it } }.toMap()
                if ("SESSDATA" !in cookies) throw UserFacingException("登录成功但没拿到 Cookie")
                Poll.Success(cookies, data.str("refresh_token").orEmpty())
            }
            86090 -> Poll.Scanned
            86038 -> Poll.Expired
            else -> Poll.Waiting
        }
    }
}
