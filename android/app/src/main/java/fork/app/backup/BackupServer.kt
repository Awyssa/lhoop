// Fork-owned. The backup server as the app sees it, and the one class in the app that opens a connection.
//
// AGENTS.md: the app uses the network for this upload and for nothing else. NetworkUseTest holds the
// rest of the code to that. The routes are in fork/docs/12-server-backup.md; none of them returns data.
package fork.app.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The server answered, and the answer was no. [status] is the HTTP status. */
internal class ServerSaidNo(val status: Int, message: String) : IOException("$status: $message")

/** What the server says to a plan: whether it has a copy for this schema, and the hours it wants. */
internal data class PlanAnswer(val schema: String, val want: List<Bucket>) {
    companion object {
        const val OK = "ok"
        const val MISSING = "missing"
    }
}

internal interface BackupServer {
    /** Asks which of [prints], the checksums of hours of [table], the server wants. */
    fun plan(head: SchemaHead, table: String, prints: Map<Bucket, String>): PlanAnswer

    /** Has the server make its empty copy. Allowed once. */
    fun putSchema(request: SchemaRequest)

    /** Sends one gzipped delta file. Returns when the server has merged it. */
    fun delta(gz: File)
}

internal class HttpBackupServer(base: String, private val token: String) : BackupServer {

    private val base = base.trimEnd('/')

    override fun plan(head: SchemaHead, table: String, prints: Map<Bucket, String>): PlanAnswer {
        val byDevice = JSONObject()
        for ((bucket, print) in prints) {
            val hours = byDevice.optJSONObject(bucket.device) ?: JSONObject().also { byDevice.put(bucket.device, it) }
            hours.put(bucket.hour.toString(), print)
        }
        val body = head.json().put("prints", JSONObject().put(table, byDevice))
        val answer = json("POST", "/v1/plan", body)
        val want = ArrayList<Bucket>()
        answer.optJSONObject("want")?.optJSONObject(table)?.let { devices ->
            for (device in devices.keys()) {
                val hours = devices.getJSONArray(device)
                for (i in 0 until hours.length()) want += Bucket(device, hours.getLong(i))
            }
        }
        return PlanAnswer(answer.getString("schema"), want)
    }

    override fun putSchema(request: SchemaRequest) {
        val body = request.head.json()
            .put("pageSize", request.pageSize)
            .put("autoVacuum", request.autoVacuum)
            .put("statements", JSONArray(request.statements))
        json("PUT", "/v1/schema", body)
    }

    override fun delta(gz: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        gz.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        call("POST", "/v1/delta", "application/octet-stream", gz.length(), mapOf("X-Lhoop-Sha256" to sha256)) { out ->
            gz.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
    }

    private fun SchemaHead.json(): JSONObject =
        JSONObject().put("format", BackupDelta.FORMAT).put("userVersion", userVersion).put("identityHash", identityHash)

    private fun json(method: String, path: String, body: JSONObject): JSONObject {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        return JSONObject(call(method, path, "application/json", raw.size.toLong(), emptyMap()) { it.write(raw) })
    }

    private fun call(
        method: String,
        path: String,
        contentType: String,
        length: Long,
        headers: Map<String, String>,
        write: (java.io.OutputStream) -> Unit,
    ): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false   // a redirect would carry the token somewhere else
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(length)
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", contentType)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.outputStream.use(write)
            val status = connection.responseCode
            if (status in 200..299) return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val said = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val message = runCatching { JSONObject(said).optString("error") }.getOrDefault("")
            throw ServerSaidNo(status, message.ifEmpty { "the server refused" })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 300_000   // the server answers a delta only once it is merged
    }
}

/** Whether an address the owner typed can be used, and the address as it will be used. */
internal object BackupAddress {

    /** The address without a trailing slash, or null with the reason in [problem]. */
    fun clean(typed: String, allowPlainHttpTo: Set<String> = emptySet()): String? =
        if (problem(typed, allowPlainHttpTo) == null) typed.trim().trimEnd('/') else null

    /**
     * Why [typed] cannot be used, or null when it can. Only `https://` is accepted, so the token and
     * the data are never sent in the clear. [allowPlainHttpTo] names hosts a debug build may reach
     * over plain HTTP: the emulator's own host, where the server under test runs.
     */
    fun problem(typed: String, allowPlainHttpTo: Set<String> = emptySet()): String? {
        val url = runCatching { URL(typed.trim()) }.getOrNull() ?: return "That is not a web address."
        if (url.host.isNullOrEmpty()) return "That is not a web address."
        if (url.userInfo != null || url.query != null || url.ref != null) return "Give the address alone, such as https://backup.example.com"
        if (url.path.trim('/').isNotEmpty()) return "Give the address alone, such as https://backup.example.com"
        return when (url.protocol) {
            "https" -> null
            "http" -> if (url.host in allowPlainHttpTo) null else "The address must start with https://"
            else -> "The address must start with https://"
        }
    }
}
