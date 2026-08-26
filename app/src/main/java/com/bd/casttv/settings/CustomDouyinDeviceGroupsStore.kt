package com.bd.casttv.settings

import android.content.Context
import android.util.Log
import com.bd.casttv.dlna.DeviceIdentity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户"克隆真电视"得到的自定义 [DouyinDeviceGroup] 持久化存储。
 *
 * 数据存于 SharedPreferences（JSON 字符串）。
 *
 * 数据结构与 [DouyinDeviceGroups.ALL] 一致：每个 group 含若干 member [DeviceIdentity]，
 * 以便"作为新组加入"和"加入现有组成员"两种模式都支持。
 *
 * 线程安全：所有 API 内部 synchronized。
 */
object CustomDouyinDeviceGroupsStore {

    private const val TAG = "CustomDeviceGroups"
    private const val PREFS = "casttv_custom_device_groups"
    private const val KEY_GROUPS = "groups_json"

    @Synchronized
    fun list(context: Context): List<DouyinDeviceGroup> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            parse(JSONArray(raw))
        } catch (t: Throwable) {
            Log.w(TAG, "list parse failed: ${t.message}")
            emptyList()
        }
    }

    /** 已克隆过的设备 UDN 集合（去重用）。 */
    @Synchronized
    fun knownUdns(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        list(context).forEach { g ->
            g.members.forEach { /* member 内未单独存 udn —— 沿用 group.id 派生 */
                // group.id = "custom_" + udn hash；UDN 本体从 group.id 反推不出来，
                // 因此去重直接用 group.id 作为"已克隆指纹"
                out.add(g.id)
            }
        }
        return out
    }

    /** 作为新组加入。若同 id 已存在则覆盖（同设备重复克隆 = 更新）。 */
    @Synchronized
    fun addGroup(context: Context, group: DouyinDeviceGroup): Boolean {
        val current = list(context).toMutableList()
        current.removeAll { it.id == group.id }
        current.add(group)
        return save(context, current)
    }

    /**
     * 作为现有组的成员加入。
     *
     * @param groupId 目标组 id（必须存在于 [DouyinDeviceGroups.ALL] 中或 [list] 中）
     * @return 成员添加后的目标组内 index；-1 表示目标组未找到或 member 已存在
     */
    @Synchronized
    fun addMemberToGroup(context: Context, groupId: String, member: DeviceIdentity): Int {
        if (groupId.isBlank()) return -1
        val current = list(context).toMutableList()

        // 先在自定义组里找
        val targetIdx = current.indexOfFirst { it.id == groupId }
        if (targetIdx >= 0) {
            val g = current[targetIdx]
            // 去重：已存在相同 friendlyName + manufacturer + modelName 的 member
            val exists = g.members.any {
                it.friendlyName == member.friendlyName &&
                    it.manufacturer == member.manufacturer &&
                    it.modelName == member.modelName
            }
            if (exists) return -1
            val newMembers = g.members + member
            current[targetIdx] = g.copy(members = newMembers)
            save(context, current)
            return newMembers.size - 1
        }

        // 不在自定义组，但可能在内置组（"加入现有组"模式：内置组 + 自定义 member）
        // 内置组本身不可变 —— 我们改成把内置组克隆一份到自定义组里，并打标 id 为 "<original>_custom_<udnhash>"
        val builtin = DouyinDeviceGroups.ALL.firstOrNull { it.id == groupId } ?: return -1
        // 去重（按相同字段判断）
        val exists = builtin.members.any {
            it.friendlyName == member.friendlyName &&
                it.manufacturer == member.manufacturer &&
                it.modelName == member.modelName
        }
        if (exists) return -1
        // 为这个"扩展过的内置组"创建一个新的自定义组副本，避免污染内置表
        // 注意：内部仍引用 group.id 为原 builtin id，渲染时按 id 合并即可。
        val extended = builtin.copy(members = builtin.members + member)
        current.add(extended)
        save(context, current)
        return extended.members.size - 1
    }

    /** 删除自定义组。若 id 不在自定义组中返回 false。 */
    @Synchronized
    fun removeGroup(context: Context, groupId: String): Boolean {
        val current = list(context).toMutableList()
        val removed = current.removeAll { it.id == groupId }
        if (removed) save(context, current)
        return removed
    }

    @Synchronized
    private fun save(context: Context, groups: List<DouyinDeviceGroup>): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONArray()
        groups.forEach { g ->
            val membersArr = JSONArray()
            g.members.forEach { m ->
                membersArr.put(JSONObject().apply {
                    put("friendlyName", m.friendlyName)
                    put("manufacturer", m.manufacturer)
                    put("manufacturerUrl", m.manufacturerUrl)
                    put("modelName", m.modelName)
                    put("modelDescription", m.modelDescription)
                    put("modelNumber", m.modelNumber)
                    put("modelUrl", m.modelUrl)
                    put("ssdpServer", m.ssdpServer)
                    put("dlnaProfiles", m.dlnaProfiles)
                    put("presentationUrl", m.presentationUrl)
                    // icons 暂不持久化（自定义组通常无 icon，由 UpnpXml 兜底）
                })
            }
            json.put(JSONObject().apply {
                put("id", g.id)
                put("label", g.label)
                put("defaultIndex", g.defaultIndex)
                put("members", membersArr)
            })
        }
        return try {
            prefs.edit().putString(KEY_GROUPS, json.toString()).apply()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: ${t.message}")
            false
        }
    }

    private fun parse(json: JSONArray): List<DouyinDeviceGroup> {
        val out = mutableListOf<DouyinDeviceGroup>()
        for (i in 0 until json.length()) {
            val gObj = json.optJSONObject(i) ?: continue
            val id = gObj.optString("id")
            if (id.isBlank()) continue
            val label = gObj.optString("label").ifBlank { "克隆组" }
            val defaultIndex = gObj.optInt("defaultIndex", 0)
            val membersArr = gObj.optJSONArray("members") ?: continue
            val members = mutableListOf<DeviceIdentity>()
            for (j in 0 until membersArr.length()) {
                val mObj = membersArr.optJSONObject(j) ?: continue
                val fn = mObj.optString("friendlyName")
                if (fn.isBlank()) continue
                members.add(
                    DeviceIdentity(
                        friendlyName = fn,
                        manufacturer = mObj.optString("manufacturer").ifBlank { "Unknown" },
                        manufacturerUrl = mObj.optString("manufacturerUrl").ifBlank { "https://casttv.local" },
                        modelName = mObj.optString("modelName").ifBlank { "Unknown" },
                        modelDescription = mObj.optString("modelDescription").ifBlank { "DLNA Media Renderer" },
                        modelNumber = mObj.optString("modelNumber").ifBlank { "1.0" },
                        modelUrl = mObj.optString("modelUrl").ifBlank { "https://casttv.local" },
                        ssdpServer = mObj.optString("ssdpServer").ifBlank { DeviceIdentity.DEFAULT_SSDP_SERVER },
                        dlnaProfiles = mObj.optString("dlnaProfiles").ifBlank { DeviceIdentity.DEFAULT_DLNA_PROFILES },
                        presentationUrl = mObj.optString("presentationUrl").ifBlank { "/" }
                    )
                )
            }
            if (members.isEmpty()) continue
            out.add(DouyinDeviceGroup(id = id, label = label, members = members, defaultIndex = defaultIndex))
        }
        return out
    }

    private fun String.ifBlank(default: () -> String): String = if (isBlank()) default() else this
}
