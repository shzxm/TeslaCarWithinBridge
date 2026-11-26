package com.shzxm.teslabridge

import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(val exitCode: Int, val output: String)

data class HotspotPeer(val ip: String, val mac: String, val iface: String, val state: String)

object RootShell {

    fun exec(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    out.appendLine(line)
                }
            }
            val code = process.waitFor()
            ShellResult(code, out.toString().trim())
        } catch (e: Exception) {
            ShellResult(-1, e.message ?: "su execution failed")
        }
    }

    fun hasRoot(): Boolean {
        val res = exec("id")
        return res.exitCode == 0 && res.output.contains("uid=0")
    }

    fun applyIptables(virtualIp: String, listenPort: Int): ShellResult {
        val cmd = buildString {
            append("iptables -t nat -C PREROUTING -d $virtualIp -p tcp --dport $listenPort -j REDIRECT --to-ports $listenPort 2>/dev/null ")
            append("|| iptables -t nat -A PREROUTING -d $virtualIp -p tcp --dport $listenPort -j REDIRECT --to-ports $listenPort; ")
            append("iptables -C INPUT -p tcp --dport $listenPort -j ACCEPT 2>/dev/null ")
            append("|| iptables -I INPUT -p tcp --dport $listenPort -j ACCEPT")
        }
        return exec(cmd)
    }

    fun cleanupIptables(virtualIp: String, listenPort: Int): ShellResult {
        val cmd = buildString {
            append("iptables -t nat -D PREROUTING -d $virtualIp -p tcp --dport $listenPort -j REDIRECT --to-ports $listenPort 2>/dev/null; ")
            append("iptables -D INPUT -p tcp --dport $listenPort -j ACCEPT 2>/dev/null; true")
        }
        return exec(cmd)
    }

    fun scanHotspotPeers(): List<HotspotPeer> {
        val res = exec("ip -4 neigh show")
        if (res.exitCode != 0) return emptyList()
        val peers = mutableListOf<HotspotPeer>()
        val regex = Regex("""^(\d+\.\d+\.\d+\.\d+)\s+dev\s+(\S+)\s+lladdr\s+([0-9a-fA-F:]+)\s+(\S+)""" )
        res.output.lines().forEach { line ->
            val m = regex.find(line.trim())
            if (m != null) {
                val ip = m.groupValues[1]
                val iface = m.groupValues[2]
                val mac = m.groupValues[3]
                val state = m.groupValues[4]
                if (!ip.startsWith("127.") && state != "FAILED") {
                    peers.add(HotspotPeer(ip, mac, iface, state))
                }
            }
        }
        return peers
    }
}
