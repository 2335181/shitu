package app.shitu.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

object Http {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/134.0.0.0 Safari/537.36"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/134.0.0.0 Mobile Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** 给 Coil 加载图片用，不写开发者日志（否则缩略图会刷屏）。 */
    val imageClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val client: OkHttpClient = imageClient.newBuilder()
        .addInterceptor(DevLog.interceptor)
        .build()

    /** 不自动跟随跳转的客户端，用于解析短链。 */
    val noRedirect: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** 挂起式执行，协程取消时取消请求。 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String {
    val request = Request.Builder().url(url).apply {
        header("User-Agent", Http.DESKTOP_UA)
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    newCall(request).await().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        return response.body.string()
    }
}

class UserFacingException(message: String) : Exception(message)

/** 把常见异常翻成一句人话。 */
fun Throwable.friendlyMessage(): String = when (this) {
    is UserFacingException -> message ?: "出错了"
    is java.net.UnknownHostException -> "网络不可用，检查一下连接"
    is java.net.SocketTimeoutException -> "连接超时，稍后再试"
    is javax.net.ssl.SSLException -> "安全连接失败"
    is IOException -> message?.let { "网络错误：$it" } ?: "网络错误"
    is kotlinx.serialization.SerializationException -> "收到的不是预期数据（网站可能被拦截、要求验证，或需要代理）"
    else -> message ?: javaClass.simpleName
}
