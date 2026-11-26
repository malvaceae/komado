package me.mallows.komado

import android.content.Context
import me.mallows.komado.security.DeviceSigningKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Pairing(val deviceID: String, val fingerprint: String, val keyAlias: String)
internal data class Host(val id: String, val name: String, val address: String, val port: Int = 48365, val pairing: Pairing? = null)

/** App-private address and public trust metadata; signing keys stay in Keystore. */
internal class HostStore(context: Context, preferenceName: String = "hosts") {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun load(): List<Host> = synchronized(lock) { read() }

    fun save(expected: Host?, updated: Host): Boolean = synchronized(lock) {
        val current = read()
        if (expected == null) {
            if (current.any { it.id == updated.id }) return false
            write(current + updated)
        } else {
            if (current.none { it == expected } || updated.id != expected.id || updated.pairing != expected.pairing) return false
            write(current.map { if (it.id == expected.id) updated else it })
        }
    }

    fun pair(expected: Host, pairing: Pairing): Boolean = synchronized(lock) {
        val current = read()
        if (expected.pairing != null || current.none { it == expected }) return false
        write(current.map { if (it.id == expected.id) it.copy(pairing = pairing) else it })
    }

    fun remove(expected: Host): Boolean = synchronized(lock) {
        val current = read()
        if (current.none { it == expected }) return false
        write(current.filterNot { it.id == expected.id })
    }

    private fun read(): List<Host> {
        val stored = preferences.getString("entries", null) ?: return emptyList()
        val array = JSONArray(stored)
        val ids = mutableSetOf<String>()
        val aliases = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("name")
            val address = item.getString("address")
            val port = if (item.has("port")) item.getInt("port") else 48365
            require(canonicalUUID(id) && ids.add(id) && validName(name) && validAddress(address) && port in 1..65535)
            val pairing = if (item.has("pairing")) {
                val saved = item.getJSONObject("pairing")
                val result = Pairing(saved.getString("deviceID"), saved.getString("fingerprint"), saved.getString("keyAlias"))
                require(canonicalUUID(result.deviceID) && validHex(result.fingerprint) && validAlias(result.keyAlias) && aliases.add(result.keyAlias))
                DeviceSigningKey(result.keyAlias) // validates the application namespace
                result
            } else null
            Host(id, name, address, port, pairing)
        }
    }

    private fun write(hosts: List<Host>): Boolean {
        val array = JSONArray()
        val ids = mutableSetOf<String>()
        val aliases = mutableSetOf<String>()
        hosts.forEach { host ->
            require(canonicalUUID(host.id) && ids.add(host.id) && validName(host.name) && validAddress(host.address) && host.port in 1..65535)
            val item = JSONObject().put("id", host.id).put("name", host.name).put("address", host.address).put("port", host.port)
            host.pairing?.let {
                require(canonicalUUID(it.deviceID) && validHex(it.fingerprint) && validAlias(it.keyAlias) && aliases.add(it.keyAlias))
                DeviceSigningKey(it.keyAlias)
                item.put("pairing", JSONObject().put("deviceID", it.deviceID)
                    .put("fingerprint", it.fingerprint).put("keyAlias", it.keyAlias))
            }
            array.put(item)
        }
        return preferences.edit().putString("entries", array.toString()).commit()
    }

    companion object {
        private val lock = Any()
        fun validName(value: String) = value.length <= 256 && value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 256 &&
            value.none { it.isISOControl() } && String(value.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == value
        fun validAddress(value: String) = value.isNotBlank() && value.length <= 253 && value.none { it.isWhitespace() || it.isISOControl() }
        fun validHex(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'A'..'F' }
        private fun validAlias(value: String): Boolean {
            val prefix = "me.mallows.komado.pair."
            return value.startsWith(prefix) && canonicalUUID(value.removePrefix(prefix))
        }
        private fun canonicalUUID(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
