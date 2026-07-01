package com.nuvio.app.features.iptv

/**
 * Local, profile-scoped persistence of the Xtream accounts list as a JSON string.
 * Mirrors features/addons AddonStorage (SharedPreferences on Android, NSUserDefaults on iOS).
 *
 * ponytail: local only; Supabase cloud sync (like addons have) is the upgrade path.
 */
internal expect object XtreamAccountStorage {
    fun loadAccountsJson(profileId: Int): String?
    fun saveAccountsJson(profileId: Int, json: String)
    /** Recently-watched live channels (JSON), profile-scoped — for the Live TV Continue-Watching row. */
    fun loadRecentsJson(profileId: Int): String?
    fun saveRecentsJson(profileId: Int, json: String)
}
