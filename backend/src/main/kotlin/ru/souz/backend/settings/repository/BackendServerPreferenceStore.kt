package ru.souz.backend.settings.repository

interface BackendServerPreferenceStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
