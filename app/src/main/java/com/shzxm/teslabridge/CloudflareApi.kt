package com.shzxm.teslabridge

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudflareApi {

    fun upsertARecord(
        apiToken: String,
        rootDomain: String,
        subDomain: String,
        targetIp: String
    ): Result<String> {
        return try {
            val zoneJson = request("GET", "https://api.cloudflare.com/client/v4/zones?name=$rootDomain", apiToken)
            val zones = zoneJson.getJSONArray("result")
            if (zones.length() == 0) {
                return Result.failure(IllegalStateException("未找到域名 Zone: $rootDomain，请检查 Token 权限"))
            }
            val zoneId = zones.getJSONObject(0).getString("id")

            val recJson = request(
                "GET",
                "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records?type=A&name=$subDomain",
                apiToken
            )
            val records = recJson.getJSONArray("result")

            val payload = JSONObject().apply {
                put("type", "A")
                put("name", subDomain)
                put("content", targetIp)
                put("ttl", 120)
                put("proxied", false)
            }.toString()

            val resp = if (records.length() > 0) {
                val recId = records.getJSONObject(0).getString("id")
                request("PUT", "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$recId", apiToken, payload)
            } else {
                request("POST", "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records", apiToken, payload)
            }

            if (resp.optBoolean("success", false)) {
                Result.success("DNS 已更新: $subDomain -> $targetIp (已关闭小黄云)")
            } else {
                Result.failure(IllegalStateException("CF API 返回失败: $resp"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun request(method: String, urlStr: String, token: String, body: String? = null): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            }
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }
}
