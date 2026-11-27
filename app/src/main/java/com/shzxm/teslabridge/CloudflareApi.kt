package com.shzxm.teslabridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object CloudflareApi {

    private const val CF_API_HOST = "api.cloudflare.com"
    private const val CF_API_BASE = "https://$CF_API_HOST/client/v4"

    private data class HttpResp(
        val code: Int,
        val rawBody: String,
        val json: JSONObject?
    )

    private val ipv4Regex = Regex("""^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$""")

    fun upsertARecord(
        apiToken: String,
        rootDomain: String,
        subDomain: String,
        targetIp: String
    ): Result<String> {
        val cleanToken = apiToken.trim()
        val cleanRoot = rootDomain.trim().lowercase().removeSuffix(".")
        val cleanSub = subDomain.trim().lowercase().removeSuffix(".")
        val cleanIp = targetIp.trim()

        if (!ipv4Regex.matches(cleanIp)) {
            return Result.failure(
                IllegalArgumentException("参数校验失败：虚拟 IP \"$cleanIp\" 不是合法的 IPv4 地址（例如 100.64.0.1）")
            )
        }
        if (cleanSub != cleanRoot && !cleanSub.endsWith(".$cleanRoot")) {
            return Result.failure(
                IllegalArgumentException("参数校验失败：访问域名 \"$cleanSub\" 不属于主域名 \"$cleanRoot\"")
            )
        }
        if (cleanToken.any { it.isWhitespace() }) {
            return Result.failure(
                IllegalArgumentException("参数校验失败：Cloudflare API Token 中包含空白/换行符，请重新粘贴")
            )
        }

        return try {
            // 步骤 1: 查询 Zone ID
            val encodedRoot = URLEncoder.encode(cleanRoot, "UTF-8")
            val zoneResp = request(
                "GET",
                "$CF_API_BASE/zones?name=$encodedRoot",
                cleanToken
            )
            checkApiSuccess("步骤 1/3 查询主域名 Zone ($cleanRoot)", zoneResp)?.let {
                return Result.failure(IllegalStateException(it))
            }

            val zones = zoneResp.json?.optJSONArray("result")
            if (zones == null || zones.length() == 0) {
                return Result.failure(
                    IllegalStateException(
                        "步骤 1/3 失败：Cloudflare 账号下未找到主域名 \"$cleanRoot\"。\n" +
                            "可能原因：\n" +
                            "1) 主域名拼写有误（应填根域名如 example.com，而非子域名）；\n" +
                            "2) API Token 的权限范围（Zone Resources）未包含该域名，或缺少 Zone -> Zone -> Read 权限。"
                    )
                )
            }
            val zoneId = zones.getJSONObject(0).getString("id")

            // 步骤 2: 查询现有 A 记录
            val encodedSub = URLEncoder.encode(cleanSub, "UTF-8")
            val recResp = request(
                "GET",
                "$CF_API_BASE/zones/$zoneId/dns_records?type=A&name=$encodedSub",
                cleanToken
            )
            checkApiSuccess("步骤 2/3 查询现有 A 记录 ($cleanSub)", recResp)?.let {
                return Result.failure(IllegalStateException(it))
            }

            val records = recResp.json?.optJSONArray("result") ?: JSONArray()

            // 步骤 3: 更新或创建 A 记录
            val payload = JSONObject().apply {
                put("type", "A")
                put("name", cleanSub)
                put("content", cleanIp)
                put("ttl", 120)
                put("proxied", false)
            }.toString()

            val isUpdate = records.length() > 0
            val actionName = if (isUpdate) "步骤 3/3 更新 A 记录" else "步骤 3/3 创建 A 记录"
            val writeResp = if (isUpdate) {
                val recId = records.getJSONObject(0).getString("id")
                request(
                    "PUT",
                    "$CF_API_BASE/zones/$zoneId/dns_records/$recId",
                    cleanToken,
                    payload
                )
            } else {
                request(
                    "POST",
                    "$CF_API_BASE/zones/$zoneId/dns_records",
                    cleanToken,
                    payload
                )
            }

            checkApiSuccess(actionName, writeResp)?.let {
                return Result.failure(IllegalStateException(it))
            }

            val modeText = if (isUpdate) "已更新" else "已创建"
            Result.success("DNS $modeText: $cleanSub -> $cleanIp (TTL=120, 已关闭小黄云)")
        } catch (e: UnknownHostException) {
            Result.failure(
                IllegalStateException("网络错误 [DNS解析失败]：无法解析 $CF_API_HOST，请检查手机网络或代理设置 (${e.message})", e)
            )
        } catch (e: SocketTimeoutException) {
            Result.failure(
                IllegalStateException("网络错误 [请求超时]：连接 Cloudflare API 超时，请检查网络或切换节点 (${e.message})", e)
            )
        } catch (e: SSLException) {
            Result.failure(
                IllegalStateException("网络错误 [TLS/SSL 握手失败]：可能被代理拦截或系统时间不准 (${e.message})", e)
            )
        } catch (e: ConnectException) {
            Result.failure(
                IllegalStateException("网络错误 [无法建立连接]：无法连接到 $CF_API_HOST (${e.message})", e)
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("未预期异常 [${e.javaClass.simpleName}]：${e.message}", e)
            )
        }
    }

    private fun checkApiSuccess(step: String, resp: HttpResp): String? {
        val json = resp.json
        if (json == null) {
            val preview = resp.rawBody.take(300).ifBlank { "(空响应)" }
            return "$step 失败 [HTTP ${resp.code}]：返回内容不是合法 JSON（可能被代理/防火墙拦截）。\n响应片段: $preview"
        }

        val success = json.optBoolean("success", false)
        if (resp.code in 200..299 && success) {
            return null
        }

        val errorList = mutableListOf<String>()
        val errorCodes = mutableSetOf<Int>()
        val errorsArr = json.optJSONArray("errors")
        if (errorsArr != null) {
            for (i in 0 until errorsArr.length()) {
                val errObj = errorsArr.optJSONObject(i) ?: continue
                val c = errObj.optInt("code", -1)
                val m = errObj.optString("message", "")
                if (c != -1) errorCodes.add(c)
                val chain = errObj.optJSONArray("error_chain")
                val chainText = if (chain != null && chain.length() > 0) {
                    (0 until chain.length()).mapNotNull { idx ->
                        val item = chain.optJSONObject(idx) ?: return@mapNotNull null
                        val cc = item.optInt("code", -1)
                        val cm = item.optString("message", "")
                        if (cc != -1) errorCodes.add(cc)
                        "[$cc] $cm"
                    }.joinToString(" -> ")
                } else ""
                val entry = if (chainText.isNotEmpty()) "[$c] $m ($chainText)" else "[$c] $m"
                errorList.add(entry)
            }
        }

        val cfErrorDetail = if (errorList.isNotEmpty()) {
            errorList.joinToString("; ")
        } else {
            resp.rawBody.take(300).ifBlank { "(无详细错误字段)" }
        }

        val diagnosis = buildDiagnosis(resp.code, errorCodes, cfErrorDetail)
        return "$step 失败 [HTTP ${resp.code}]\n" +
            "诊断建议：$diagnosis\n" +
            "CF 原始错误：$cfErrorDetail"
    }

    private fun buildDiagnosis(httpCode: Int, codes: Set<Int>, detail: String): String {
        val lower = detail.lowercase()
        return when {
            httpCode == 401 || 6003 in codes || 6111 in codes || 9103 in codes || 9109 in codes || "invalid access token" in lower || "invalid request headers" in lower ->
                "API Token 鉴权失败。请确认填写的是 Cloudflare「API Token」（不是 Global API Key），且未过期、未多粘空格。"
            httpCode == 403 || 10000 in codes || "authentication error" in lower ->
                "Token 权限不足或认证被拒。请在 Cloudflare 重新创建 Token，确保授予「Zone -> Zone -> Read」和「Zone -> DNS -> Edit」权限，并在 Zone Resources 中选中该主域名。"
            81053 in codes || "cname" in lower ->
                "域名记录冲突：该子域名下已存在 CNAME 或其他冲突记录，请先在 Cloudflare 后台删除同名记录后重试。"
            81057 in codes || "already exists" in lower ->
                "相同 DNS 记录已存在，请检查 Cloudflare 后台记录列表。"
            httpCode == 400 || 9000 in codes || 1004 in codes ->
                "请求参数被 Cloudflare 拒绝，请检查主域名、子域名和 IP 地址格式是否正确。"
            httpCode == 404 ->
                "目标资源不存在，请核对主域名是否已正确接入当前 Cloudflare 账号。"
            httpCode == 429 ->
                "请求过于频繁（触发 Cloudflare 限流），请稍候几分钟再试。"
            httpCode >= 500 ->
                "Cloudflare 服务端临时异常，请稍后重试。"
            else ->
                "请根据下方 CF 原始错误码排查 Token 权限或域名配置。"
        }
    }

    private fun request(method: String, urlStr: String, token: String, body: String? = null): HttpResp {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val parsed = try {
                if (text.isNotBlank()) JSONObject(text) else null
            } catch (_: Exception) {
                null
            }
            HttpResp(code, text, parsed)
        } finally {
            conn.disconnect()
        }
    }
}
