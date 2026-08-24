package com.bd.casttv.webparse

import android.content.Context

object AdapterSelector {
    fun select(context: Context, url: String, html: String, pageKind: ParsePageKind): AdapterSelectResult {
        val store = WebParseAdapterStore(context.applicationContext)
        val host = store.normalizeHost(url)
        val binding = store.getBinding(pageKind, host)
        if (binding != null) {
            val adapter = BuiltInAdapters.findById(binding.adapterId) ?: AdapterInfo(
                id = binding.adapterId,
                name = binding.adapterName.ifBlank { binding.ruleFileName.ifBlank { "自定义解析" } },
                kind = binding.adapterKind,
                frameworkType = binding.frameworkType,
                supportedPageKinds = setOf(pageKind),
                priority = 100,
                description = binding.ruleFileName
            )
            return AdapterSelectResult(
                adapterInfo = adapter,
                detectedFramework = binding.frameworkType,
                source = AdapterSelectResult.SelectSource.DOMAIN_BINDING,
                confidence = 1f
            )
        }

        val detected = WebFrameworkDetector.detect(html)
        BuiltInAdapters.forPageKind(pageKind)
            .filter { it.frameworkType == detected }
            .maxByOrNull { it.priority }
            ?.let { adapter ->
                return AdapterSelectResult(
                    adapterInfo = adapter,
                    detectedFramework = detected,
                    source = AdapterSelectResult.SelectSource.FRAMEWORK_MATCH,
                    confidence = 0.9f
                )
            }

        val fallbackId = when (pageKind) {
            ParsePageKind.LIST -> BuiltInAdapters.ID_LIST_GENERIC
            ParsePageKind.DETAIL -> BuiltInAdapters.ID_DETAIL_GENERIC
        }
        val fallback = BuiltInAdapters.findById(fallbackId) ?: BuiltInAdapters.forPageKind(pageKind).maxByOrNull { it.priority }
            ?: error("未找到可用的内置解析适配器")
        return AdapterSelectResult(
            adapterInfo = fallback,
            detectedFramework = detected,
            source = AdapterSelectResult.SelectSource.GENERIC_FALLBACK,
            confidence = 0.5f
        )
    }
}
