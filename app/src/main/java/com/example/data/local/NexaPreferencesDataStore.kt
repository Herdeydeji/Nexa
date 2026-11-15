package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppSettings
import com.example.domain.model.AppearancePosture
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ResponseVerbosity
import com.example.domain.model.TelegramConfig
import com.example.domain.model.TelegramConnectionStatus
import com.example.domain.security.ApiKeyMasker
import com.example.domain.security.CredentialCipher
import com.example.domain.security.DecryptResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.nexaDataStore: DataStore<Preferences> by preferencesDataStore(name = "nexa_prefs")

enum class StoredStatusKind {
    NONE,
    CONNECTED,
    AUTH_FAILED,
    RATE_LIMITED,
    ENDPOINT_ERROR,
    NETWORK_ERROR,
    VALIDATION_ERROR,
    KEY_INVALIDATED
}

data class StoredProviderEntry(
    val encryptedKey: String,
    val maskedKey: String,
    val baseUrl: String,
    val selectedModelId: String?,
    val manualModelId: String = "",
    val cachedModels: List<AiModelInfo> = emptyList(),
    val statusKind: StoredStatusKind = StoredStatusKind.NONE,
    val statusMessage: String = "",
    val modelCount: Int? = null,
    val credentialInvalidated: Boolean = false
)

class NexaPreferencesDataStore(
    private val context: Context,
    private val cipher: CredentialCipher
) {
    private val dataStore = context.nexaDataStore
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val modelListAdapter = moshi.adapter<List<AiModelInfo>>(
        Types.newParameterizedType(List::class.java, AiModelInfo::class.java)
    )

    val defaultProviderId: Flow<String?> = dataStore.data.map { it[KEY_DEFAULT_PROVIDER] }

    val providerEntries: Flow<Map<AiProviderType, StoredProviderEntry>> = dataStore.data.map { prefs ->
        AiProviderType.entries.associateWith { type ->
            val enc = prefs[providerKey(type, "enc")] ?: ""
            val cachedJson = prefs[providerKey(type, "cached_models")]
            val cachedModels = cachedJson?.let {
                runCatching { modelListAdapter.fromJson(it) }.getOrNull()
            } ?: emptyList()

            val persistedKind = runCatching {
                StoredStatusKind.valueOf(prefs[providerKey(type, "status_kind")] ?: StoredStatusKind.NONE.name)
            }.getOrDefault(StoredStatusKind.NONE)

            val decryptCheck = if (enc.isNotEmpty()) cipher.decryptSafely(enc) else DecryptResult.Empty
            val keyInvalidated = decryptCheck is DecryptResult.KeyInvalidated
            val effectiveKind = if (keyInvalidated) StoredStatusKind.KEY_INVALIDATED else persistedKind
            val effectiveMsg = if (keyInvalidated) {
                (decryptCheck as DecryptResult.KeyInvalidated).reason
            } else {
                prefs[providerKey(type, "status_msg")] ?: ""
            }

            StoredProviderEntry(
                encryptedKey = enc,
                maskedKey = prefs[providerKey(type, "mask")] ?: "",
                baseUrl = prefs[providerKey(type, "url")] ?: type.defaultBaseUrl,
                selectedModelId = prefs[providerKey(type, "model")],
                manualModelId = prefs[providerKey(type, "manual_model")] ?: "",
                cachedModels = cachedModels,
                statusKind = effectiveKind,
                statusMessage = effectiveMsg,
                modelCount = prefs[providerIntKey(type, "model_count")],
                credentialInvalidated = keyInvalidated
            )
        }
    }

    val telegramConfig: Flow<TelegramConfig> = dataStore.data.map { prefs ->
        val statusName = prefs[KEY_TG_STATUS] ?: TelegramConnectionStatus.DISCONNECTED.name
        val status = runCatching { TelegramConnectionStatus.valueOf(statusName) }
            .getOrDefault(TelegramConnectionStatus.DISCONNECTED)
        TelegramConfig(
            status = status,
            maskedBotToken = prefs[KEY_TG_MASKED_TOKEN] ?: "",
            pairingHandle = prefs[KEY_TG_HANDLE] ?: "",
            allowRemotePrompts = prefs[KEY_TG_REMOTE_PROMPTS] ?: false,
            requireDeviceApproval = prefs[KEY_TG_REQUIRE_APPROVAL] ?: true,
            shareTaskSummaries = prefs[KEY_TG_SHARE_SUMMARIES] ?: false,
            statusNote = prefs[KEY_TG_NOTE] ?: "No active Telegram bridge. Scheduled for Phase 3."
        )
    }

    val capabilityToggles: Flow<Map<String, Boolean>> = dataStore.data.map { prefs ->
        DeviceCapabilityType.entries.associate { cap ->
            cap.id to (prefs[booleanPreferencesKey("cap_toggle_${cap.id}")] ?: false)
        }
    }

    val appSettings: Flow<AppSettings> = dataStore.data.map { prefs ->
        readSettings(prefs)
    }

    suspend fun readDecryptedApiKey(type: AiProviderType): DecryptResult {
        val prefs = dataStore.data.first()
        val enc = prefs[providerKey(type, "enc")] ?: ""
        return cipher.decryptSafely(enc)
    }

    suspend fun saveProviderConfig(
        type: AiProviderType,
        rawApiKey: String?,
        baseUrl: String,
        selectedModelId: String?,
        manualModelId: String? = null,
        setAsDefault: Boolean
    ) {
        dataStore.edit { prefs ->
            val keyChanged = !rawApiKey.isNullOrBlank()
            val urlChanged = (prefs[providerKey(type, "url")] ?: type.defaultBaseUrl) != baseUrl.trim()
            if (keyChanged) {
                val trimmed = rawApiKey!!.trim()
                prefs[providerKey(type, "enc")] = cipher.encrypt(trimmed)
                prefs[providerKey(type, "mask")] = ApiKeyMasker.mask(trimmed)
            }
            prefs[providerKey(type, "url")] = baseUrl.trim()
            if (selectedModelId != null) {
                prefs[providerKey(type, "model")] = selectedModelId
            }
            if (manualModelId != null) {
                val cleanManual = manualModelId.trim()
                if (cleanManual.isNotEmpty()) {
                    prefs[providerKey(type, "manual_model")] = cleanManual
                } else {
                    prefs.remove(providerKey(type, "manual_model"))
                }
            }
            if (keyChanged || urlChanged) {
                prefs[providerKey(type, "status_kind")] = StoredStatusKind.NONE.name
                prefs.remove(providerKey(type, "status_msg"))
                prefs.remove(providerIntKey(type, "model_count"))
            }
            if (setAsDefault) {
                prefs[KEY_DEFAULT_PROVIDER] = type.id
            }
        }
    }

    suspend fun saveManualModel(type: AiProviderType, modelId: String, setAsDefault: Boolean) {
        val clean = modelId.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[providerKey(type, "manual_model")] = clean
            prefs[providerKey(type, "model")] = clean
            if (setAsDefault) {
                prefs[KEY_DEFAULT_PROVIDER] = type.id
            }
        }
    }

    suspend fun saveConnectionStatus(
        type: AiProviderType,
        kind: StoredStatusKind,
        message: String,
        fetchedModels: List<AiModelInfo>? = null
    ) {
        val sanitizedMsg = ApiKeyMasker.sanitizeErrorText(message)
        dataStore.edit { prefs ->
            prefs[providerKey(type, "status_kind")] = kind.name
            prefs[providerKey(type, "status_msg")] = sanitizedMsg
            if (fetchedModels != null) {
                prefs[providerKey(type, "cached_models")] = modelListAdapter.toJson(fetchedModels)
                prefs[providerIntKey(type, "model_count")] = fetchedModels.size
                val currentModel = prefs[providerKey(type, "model")]
                if (currentModel.isNullOrBlank() && fetchedModels.isNotEmpty()) {
                    prefs[providerKey(type, "model")] = fetchedModels.first().id
                }
            }
        }
    }

    suspend fun clearProviderKey(type: AiProviderType) {
        dataStore.edit { prefs ->
            prefs.remove(providerKey(type, "enc"))
            prefs.remove(providerKey(type, "mask"))
            prefs.remove(providerKey(type, "status_kind"))
            prefs.remove(providerKey(type, "status_msg"))
            prefs.remove(providerIntKey(type, "model_count"))
            prefs.remove(providerKey(type, "cached_models"))
            if (prefs[KEY_DEFAULT_PROVIDER] == type.id) {
                prefs.remove(KEY_DEFAULT_PROVIDER)
            }
        }
    }

    suspend fun setDefaultProvider(type: AiProviderType, modelId: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_PROVIDER] = type.id
            if (!modelId.isNullOrBlank()) {
                prefs[providerKey(type, "model")] = modelId.trim()
            }
        }
    }

    suspend fun saveTelegramDraft(rawBotToken: String, pairingHandle: String) {
        val masked = if (rawBotToken.isNotBlank()) ApiKeyMasker.mask(rawBotToken) else ""
        dataStore.edit { prefs ->
            if (rawBotToken.isNotBlank()) {
                prefs[KEY_TG_ENC_TOKEN] = cipher.encrypt(rawBotToken.trim())
                prefs[KEY_TG_MASKED_TOKEN] = masked
            }
            prefs[KEY_TG_HANDLE] = pairingHandle.trim()
            prefs[KEY_TG_STATUS] = TelegramConnectionStatus.PAIRING_PENDING.name
            prefs[KEY_TG_NOTE] =
                "Bot draft stored locally ($masked). Telegram gateway is scheduled for Phase 3 — not yet active."
        }
    }

    suspend fun updateTelegramPermissions(
        allowRemotePrompts: Boolean? = null,
        requireDeviceApproval: Boolean? = null,
        shareTaskSummaries: Boolean? = null
    ) {
        dataStore.edit { prefs ->
            allowRemotePrompts?.let { prefs[KEY_TG_REMOTE_PROMPTS] = it }
            requireDeviceApproval?.let { prefs[KEY_TG_REQUIRE_APPROVAL] = it }
            shareTaskSummaries?.let { prefs[KEY_TG_SHARE_SUMMARIES] = it }
        }
    }

    suspend fun disconnectTelegram() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TG_ENC_TOKEN)
            prefs.remove(KEY_TG_MASKED_TOKEN)
            prefs.remove(KEY_TG_HANDLE)
            prefs[KEY_TG_STATUS] = TelegramConnectionStatus.DISCONNECTED.name
            prefs[KEY_TG_REMOTE_PROMPTS] = false
            prefs[KEY_TG_NOTE] = "No active Telegram bridge. Scheduled for Phase 3."
        }
    }

    suspend fun setCapabilityToggle(id: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("cap_toggle_$id")] = enabled
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = readSettings(prefs)
            val latest = transform(current)
            prefs[KEY_COMPACT_MSG] = latest.compactMessageSpacing
            prefs[KEY_SHOW_SAMPLE_BADGES] = latest.showSampleBadges
            prefs[KEY_HAPTICS] = latest.hapticFeedbackEnabled
            prefs[KEY_APPEARANCE] = latest.appearancePosture.name
            prefs[KEY_CODE_LINES] = latest.showCodeLineNumbers
            prefs[KEY_VERBOSITY] = latest.responseVerbosity.name
            prefs[KEY_AUTO_EXPAND_EXEC] = latest.autoExpandExecutionPanel
            prefs[KEY_MAX_STEPS] = latest.maxStepsPerTask
            prefs[KEY_CUSTOM_SYSTEM_PROMPT] = latest.customSystemPrompt
            prefs[KEY_BG_QUEUE] = latest.allowBackgroundTaskQueue
            prefs[KEY_APP_SUGGESTIONS] = latest.allowAppLaunchSuggestions
            prefs[KEY_CONFIRM_MODE] = latest.confirmationMode.name
            prefs[KEY_APPROVE_EXTERNAL] = latest.requireApprovalForExternalComms
            prefs[KEY_SAVE_HISTORY] = latest.saveConversationHistory
            prefs[KEY_RETENTION_DAYS] = latest.historyRetentionDays
            prefs[KEY_LOCAL_MEMORY] = latest.localMemoryEnabled
            prefs[KEY_REMEMBER_MODEL] = latest.rememberPreferredModelPerThread
            prefs[KEY_MASK_EXPORTS] = latest.maskCredentialsInExports
            prefs[KEY_REDACT_LOGS] = latest.redactSensitiveStepsInLogs
            prefs[KEY_BIO_APPROVAL] = latest.biometricLockOnApproval
        }
    }

    private fun readSettings(prefs: Preferences): AppSettings = AppSettings(
        compactMessageSpacing = prefs[KEY_COMPACT_MSG] ?: false,
        showSampleBadges = prefs[KEY_SHOW_SAMPLE_BADGES] ?: true,
        hapticFeedbackEnabled = prefs[KEY_HAPTICS] ?: true,
        appearancePosture = runCatching {
            AppearancePosture.valueOf(prefs[KEY_APPEARANCE] ?: AppearancePosture.OBSIDIAN_DARK.name)
        }.getOrDefault(AppearancePosture.OBSIDIAN_DARK),
        showCodeLineNumbers = prefs[KEY_CODE_LINES] ?: true,
        responseVerbosity = runCatching {
            ResponseVerbosity.valueOf(prefs[KEY_VERBOSITY] ?: ResponseVerbosity.BALANCED.name)
        }.getOrDefault(ResponseVerbosity.BALANCED),
        autoExpandExecutionPanel = prefs[KEY_AUTO_EXPAND_EXEC] ?: true,
        maxStepsPerTask = prefs[KEY_MAX_STEPS] ?: 12,
        customSystemPrompt = prefs[KEY_CUSTOM_SYSTEM_PROMPT] ?: "",
        allowBackgroundTaskQueue = prefs[KEY_BG_QUEUE] ?: false,
        allowAppLaunchSuggestions = prefs[KEY_APP_SUGGESTIONS] ?: true,
        confirmationMode = runCatching {
            ConfirmationMode.valueOf(prefs[KEY_CONFIRM_MODE] ?: ConfirmationMode.SENSITIVE_ONLY.name)
        }.getOrDefault(ConfirmationMode.SENSITIVE_ONLY),
        requireApprovalForExternalComms = prefs[KEY_APPROVE_EXTERNAL] ?: true,
        saveConversationHistory = prefs[KEY_SAVE_HISTORY] ?: true,
        historyRetentionDays = prefs[KEY_RETENTION_DAYS] ?: 30,
        localMemoryEnabled = prefs[KEY_LOCAL_MEMORY] ?: false,
        rememberPreferredModelPerThread = prefs[KEY_REMEMBER_MODEL] ?: true,
        maskCredentialsInExports = prefs[KEY_MASK_EXPORTS] ?: true,
        redactSensitiveStepsInLogs = prefs[KEY_REDACT_LOGS] ?: true,
        biometricLockOnApproval = prefs[KEY_BIO_APPROVAL] ?: false
    )

    private fun providerKey(type: AiProviderType, field: String) =
        stringPreferencesKey("provider_${type.id}_$field")

    private fun providerIntKey(type: AiProviderType, field: String) =
        intPreferencesKey("provider_${type.id}_$field")

    private companion object {
        val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_provider_id")
        val KEY_TG_STATUS = stringPreferencesKey("tg_status")
        val KEY_TG_ENC_TOKEN = stringPreferencesKey("tg_enc_token")
        val KEY_TG_MASKED_TOKEN = stringPreferencesKey("tg_masked_token")
        val KEY_TG_HANDLE = stringPreferencesKey("tg_handle")
        val KEY_TG_REMOTE_PROMPTS = booleanPreferencesKey("tg_remote_prompts")
        val KEY_TG_REQUIRE_APPROVAL = booleanPreferencesKey("tg_require_approval")
        val KEY_TG_SHARE_SUMMARIES = booleanPreferencesKey("tg_share_summaries")
        val KEY_TG_NOTE = stringPreferencesKey("tg_note")

        val KEY_COMPACT_MSG = booleanPreferencesKey("set_compact_msg")
        val KEY_SHOW_SAMPLE_BADGES = booleanPreferencesKey("set_sample_badges")
        val KEY_HAPTICS = booleanPreferencesKey("set_haptics")
        val KEY_APPEARANCE = stringPreferencesKey("set_appearance")
        val KEY_CODE_LINES = booleanPreferencesKey("set_code_lines")
        val KEY_VERBOSITY = stringPreferencesKey("set_verbosity")
        val KEY_AUTO_EXPAND_EXEC = booleanPreferencesKey("set_auto_expand_exec")
        val KEY_MAX_STEPS = intPreferencesKey("set_max_steps")
        val KEY_CUSTOM_SYSTEM_PROMPT = stringPreferencesKey("set_custom_system_prompt")
        val KEY_BG_QUEUE = booleanPreferencesKey("set_bg_queue")
        val KEY_APP_SUGGESTIONS = booleanPreferencesKey("set_app_suggestions")
        val KEY_CONFIRM_MODE = stringPreferencesKey("set_confirm_mode")
        val KEY_APPROVE_EXTERNAL = booleanPreferencesKey("set_approve_external")
        val KEY_SAVE_HISTORY = booleanPreferencesKey("set_save_history")
        val KEY_RETENTION_DAYS = intPreferencesKey("set_retention_days")
        val KEY_LOCAL_MEMORY = booleanPreferencesKey("set_local_memory")
        val KEY_REMEMBER_MODEL = booleanPreferencesKey("set_remember_model")
        val KEY_MASK_EXPORTS = booleanPreferencesKey("set_mask_exports")
        val KEY_REDACT_LOGS = booleanPreferencesKey("set_redact_logs")
        val KEY_BIO_APPROVAL = booleanPreferencesKey("set_bio_approval")
    }
}
