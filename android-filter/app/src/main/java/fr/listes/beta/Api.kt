package fr.listes.beta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiFailure(val status: String, message: String): Exception(message)
data class SocialSession(val network: String, val token: String, val accountId: String, val name: String, val clientId: String = "") {
    fun json() = JSONObject().put("network",network).put("token",token).put("accountId",accountId).put("name",name).put("clientId",clientId)
    companion object {
        fun parse(j: JSONObject) = SocialSession(j.getString("network"),j.getString("token"),j.getString("accountId"),j.getString("name"),j.optString("clientId"))
    }
}
object Http {
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
        .callTimeout(35,TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    suspend fun request(url: String, method: String = "GET", token: String = "", clientId: String = "",
        body: String? = null, form: Map<String,String>? = null): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        if (token.isNotEmpty()) builder.header("Authorization",
            (if(url == "https://id.twitch.tv/oauth2/validate") "OAuth " else "Bearer ") + token)
        if (clientId.isNotEmpty()) builder.header("Client-Id", clientId)
        val requestBody = if (form != null) FormBody.Builder().apply { form.forEach { (k,v) -> add(k,v) } }.build()
            else body?.toRequestBody("application/json".toMediaType())
        builder.method(method, if (method in listOf("POST","PUT")) requestBody ?: ByteArray(0).toRequestBody() else requestBody)
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.source()?.let { source ->
                source.request(4_000_001)
                require(source.buffer.size <= 4_000_000) { "Réponse trop volumineuse" }
                source.readUtf8()
            }.orEmpty()
            val j = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val reason = j.optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                val status = when {
                    response.code == 401 -> "auth_required"
                    response.code == 429 || reason in setOf("quotaExceeded","dailyLimitExceeded","subscriptionForbidden") -> "rate_limited"
                    else -> "failed"
                }
                // Never expose raw response bodies, headers or tokens in logs.
                throw ApiFailure(status, "HTTP ${response.code}" + if (reason.matches(Regex("[A-Za-z]{1,60}"))) " · $reason" else "")
            }
            j
        }
    }
    suspend fun signed(url: String) = request(url).toString()
}
