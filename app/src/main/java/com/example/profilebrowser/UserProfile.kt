package com.example.profilebrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One browsing profile. [countdownEnd] is an absolute epoch-millis timestamp
 * (null = countdown disabled), so it keeps counting even if the app is closed.
 */
data class UserProfile(
    val id: String,
    val name: String,
    val countdownEnd: Long?,
    val lastUrl: String
) {
    /** Name of the isolated WebView profile (own cookies, storage, cache). */
    val webProfileName: String get() = "via_$id"
}

class ProfileRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun getAll(): MutableList<UserProfile> {
        val result = mutableListOf<UserProfile>()
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val end = o.optLong("end", 0L)
            result.add(
                UserProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    countdownEnd = if (end > 0L) end else null,
                    lastUrl = o.optString("url", "")
                )
            )
        }
        return result
    }

    fun get(id: String): UserProfile? = getAll().firstOrNull { it.id == id }

    fun save(profile: UserProfile) {
        val list = getAll()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        write(list)
    }

    fun delete(id: String) {
        write(getAll().filterNot { it.id == id })
    }

    private fun write(list: List<UserProfile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("end", it.countdownEnd ?: 0L)
                    .put("url", it.lastUrl)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    companion object {
        private const val KEY = "list"
    }
}
