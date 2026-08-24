package com.bd.casttv.webparse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

class WebParseAdapterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class DomainBinding(
        val pageKind: ParsePageKind,
        val host: String,
        val adapterId: String,
        val adapterKind: AdapterKind,
        val adapterName: String,
        val ruleFileName: String,
        val frameworkType: WebFrameworkType,
        val updatedAt: Long
    )

    sealed class SaveResult {
        object Success : SaveResult()
        object Duplicate : SaveResult()
        data class Conflict(val existingAdapterName: String) : SaveResult()
    }

    fun normalizeHost(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return ""
        return runCatching {
            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val uri = URI(withScheme)
            (uri.host ?: uri.authority.orEmpty())
                .substringBefore('@')
                .substringBefore(':')
                .trim()
                .lowercase(Locale.US)
        }.getOrElse {
            raw.substringAfter("://", raw)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore('@')
                .substringBefore(':')
                .trim()
                .lowercase(Locale.US)
        }
    }

    fun getBinding(pageKind: ParsePageKind, host: String): DomainBinding? {
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isBlank()) return null
        return getAllBindings().firstOrNull { it.pageKind == pageKind && it.host == normalizedHost }
    }

    fun saveBinding(binding: DomainBinding): SaveResult {
        val normalized = binding.normalized()
        if (normalized.host.isBlank() || normalized.adapterId.isBlank()) return SaveResult.Duplicate
        val bindings = getAllBindings().toMutableList()
        val existing = bindings.firstOrNull { it.pageKind == normalized.pageKind && it.host == normalized.host }
        if (existing != null) {
            return if (existing.adapterId == normalized.adapterId) {
                SaveResult.Duplicate
            } else {
                SaveResult.Conflict(existing.adapterName)
            }
        }
        bindings.add(normalized)
        saveAll(bindings)
        return SaveResult.Success
    }

    fun forceUpdateBinding(binding: DomainBinding) {
        val normalized = binding.normalized()
        if (normalized.host.isBlank() || normalized.adapterId.isBlank()) return
        val bindings = getAllBindings()
            .filterNot { it.pageKind == normalized.pageKind && it.host == normalized.host }
            .toMutableList()
            .apply { add(normalized) }
        saveAll(bindings)
    }

    fun removeBinding(pageKind: ParsePageKind, host: String) {
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isBlank()) return
        val bindings = getAllBindings().filterNot { it.pageKind == pageKind && it.host == normalizedHost }
        saveAll(bindings)
    }

    fun getAllBindings(): List<DomainBinding> {
        val raw = prefs.getString(KEY_DOMAIN_BINDINGS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val host = normalizeHost(item.optString("host"))
                    val adapterId = item.optString("adapterId").trim()
                    if (host.isBlank() || adapterId.isBlank()) continue
                    add(
                        DomainBinding(
                            pageKind = enumValueOrDefault(item.optString("pageKind"), ParsePageKind.DETAIL),
                            host = host,
                            adapterId = adapterId,
                            adapterKind = enumValueOrDefault(item.optString("adapterKind"), AdapterKind.CUSTOM_JSON),
                            adapterName = item.optString("adapterName").trim(),
                            ruleFileName = item.optString("ruleFileName").trim(),
                            frameworkType = enumValueOrDefault(item.optString("frameworkType"), WebFrameworkType.UNKNOWN),
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.distinctBy { it.pageKind.name + "|" + it.host }
        }.getOrElse { emptyList() }
    }

    private fun saveAll(bindings: List<DomainBinding>) {
        val array = JSONArray()
        bindings.forEach { binding ->
            val normalized = binding.normalized()
            array.put(JSONObject().apply {
                put("pageKind", normalized.pageKind.name)
                put("host", normalized.host)
                put("adapterId", normalized.adapterId)
                put("adapterKind", normalized.adapterKind.name)
                put("adapterName", normalized.adapterName)
                put("ruleFileName", normalized.ruleFileName)
                put("frameworkType", normalized.frameworkType.name)
                put("updatedAt", normalized.updatedAt)
            })
        }
        prefs.edit().putString(KEY_DOMAIN_BINDINGS, array.toString()).apply()
    }

    private fun DomainBinding.normalized(): DomainBinding {
        return copy(
            host = normalizeHost(host),
            adapterId = adapterId.trim(),
            adapterName = adapterName.trim(),
            ruleFileName = ruleFileName.trim()
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return runCatching { enumValueOf<T>(value.trim()) }.getOrDefault(default)
    }

    companion object {
        private const val PREFS = "web_parse_adapter_store"
        private const val KEY_DOMAIN_BINDINGS = "domain_bindings"
    }
}
