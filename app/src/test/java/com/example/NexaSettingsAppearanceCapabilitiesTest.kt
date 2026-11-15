package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.android.accessibility.AccessibilityNodeInspector
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.LiveAccessibilityScreenBridge
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.app.AppDiscoveryBatch
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.security.ApprovalAuthResult
import com.example.android.security.ApprovalAuthenticator
import com.example.data.local.LocalMemoryStore
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaPreferencesDataStore
import com.example.data.local.NexaTypeConverters
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.DiagnosticsExporter
import com.example.domain.agent.ExecutableTool
import com.example.domain.agent.SaveMemoryNoteTool
import com.example.domain.agent.ToolExecutionOutput
import com.example.domain.agent.ToolPolicyEvaluation
import com.example.domain.chat.AndroidAttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentTask
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppLaunchOutcome
import com.example.domain.model.AppSettings
import com.example.domain.model.AppearancePosture
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.Conversation
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.MessageRole
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolParameter
import com.example.domain.model.ToolParameterType
import com.example.domain.model.ToolRiskCategory
import com.example.domain.provider.AiProviderRegistry
import com.example.domain.security.ApiKeyMasker
import com.example.domain.security.CredentialCipher
import com.example.ui.chat.ChatEmptyWelcome
import com.example.ui.chat.ChatViewModel
import com.example.ui.connections.CapabilitiesTab
import com.example.ui.connections.ConnectionsViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.DaylightLightPalette
import com.example.ui.theme.HighContrastDarkPalette
import com.example.ui.theme.NexaTheme
import com.example.ui.theme.ObsidianDarkPalette
import com.example.ui.theme.resolveNexaPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaSettingsAppearanceCapabilitiesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val passThroughCipher = object : CredentialCipher {
        override fun encrypt(plainText: String): String = plainText
        override fun decrypt(cipherPayload: String): String = cipherPayload
    }

    private fun newPrefs(context: Context): NexaPreferencesDataStore =
        NexaPreferencesDataStore(context, passThroughCipher)

    private fun newInMemoryDb(context: Context): NexaDatabase =
        Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    // 1. Theme & Appearance Posture Tests

    @Test
    fun `resolveNexaPalette maps all 4 AppearancePosture themes and preserves Obsidian Dark default`() {
        assertEquals(AppearancePosture.OBSIDIAN_DARK, AppSettings().appearancePosture)
        assertEquals(ObsidianDarkPalette, resolveNexaPalette(AppearancePosture.OBSIDIAN_DARK, systemIsDark = false))
        assertEquals(DaylightLightPalette, resolveNexaPalette(AppearancePosture.LIGHT, systemIsDark = true))
        assertEquals(HighContrastDarkPalette, resolveNexaPalette(AppearancePosture.HIGH_CONTRAST_DARK, systemIsDark = false))
        assertEquals(ObsidianDarkPalette, resolveNexaPalette(AppearancePosture.SYSTEM, systemIsDark = true))
        assertEquals(DaylightLightPalette, resolveNexaPalette(AppearancePosture.SYSTEM, systemIsDark = false))

        assertFalse(DaylightLightPalette.isDark)
        assertTrue(HighContrastDarkPalette.isDark)
        assertEquals(Color(0xFF000000), HighContrastDarkPalette.background)
    }

    @Test
    fun `NexaTheme composition local updates dynamically across Obsidian Dark Daylight Light and High Contrast Dark`() {
        var observedBgIsDark = true
        var observedBgColor = Color.Unspecified
        var currentPosture by mutableStateOf(AppearancePosture.OBSIDIAN_DARK)

        composeRule.setContent {
            NexaTheme(appearancePosture = currentPosture) {
                observedBgIsDark = NexaTheme.colors.isDark
                observedBgColor = NexaTheme.colors.background
            }
        }
        composeRule.waitForIdle()
        assertTrue(observedBgIsDark)

        currentPosture = AppearancePosture.LIGHT
        composeRule.waitForIdle()
        assertFalse(observedBgIsDark)

        currentPosture = AppearancePosture.HIGH_CONTRAST_DARK
        composeRule.waitForIdle()
        assertTrue(observedBgIsDark)
        assertEquals(Color(0xFF000000), observedBgColor)
    }

    // 2. Room v4 -> v5 Migration & Preferred Model Per Thread

    @Test
    fun `room migration 4 to 5 adds preferredProviderId and preferredModelId cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mig_4_5_${System.nanoTime()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, isSamplePreview INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, lastMessagePreview TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, executionPlanJson TEXT, taskSummaryJson TEXT, attachmentsJson TEXT, isPreviewMessage INTEGER NOT NULL, status TEXT NOT NULL, errorMessage TEXT, providerLabel TEXT, modelLabel TEXT, promptTokens INTEGER, completionTokens INTEGER, totalTokens INTEGER, toolCallsJson TEXT, toolCallId TEXT, toolName TEXT, verificationJson TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, prompt TEXT NOT NULL, status TEXT NOT NULL, isSampleData INTEGER NOT NULL, providerLabel TEXT NOT NULL, modelLabel TEXT NOT NULL, targetApp TEXT, requiredCapabilitiesJson TEXT NOT NULL, failureReason TEXT, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, summaryMessage TEXT, verificationJson TEXT, resumableSnapshotJson TEXT)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS task_steps (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, stepOrder INTEGER NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, state TEXT NOT NULL, toolName TEXT, timestampLabel TEXT, kind TEXT NOT NULL, FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_steps_taskId ON task_steps (taskId)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val rawDb = helper.writableDatabase
            rawDb.execSQL("INSERT INTO conversations VALUES ('c_v4','Thread v4',0,500,500,'hello')")
            rawDb.close()
            helper.close()

            val upgraded = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
                .addMigrations(NexaDatabase.MIGRATION_4_5)
                .allowMainThreadQueries()
                .build()
            runTest {
                val repo = ChatRepository(upgraded.conversationDao(), NexaTypeConverters())
                val convs = repo.getConversationsSnapshot()
                assertEquals(1, convs.size)
                assertNull(convs[0].preferredProviderId)
                repo.updateConversationPreferredModel("c_v4", AiProviderType.OPENAI.id, "gpt-4.1")
                val updated = repo.getConversationsSnapshot().single()
                assertEquals(AiProviderType.OPENAI.id, updated.preferredProviderId)
                assertEquals("gpt-4.1", updated.preferredModelId)
            }
            upgraded.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // 3. History Retention Pruning & Incognito Mode

    @Test
    fun `pruneExpiredConversations deletes old user chats while preserving recent samples and active task threads`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val converters = NexaTypeConverters()
        val repo = ChatRepository(db.conversationDao(), converters)
        try {
            val now = 1_700_000_000_000L
            val tenDaysAgo = now - 10L * 86_400_000L
            val twoDaysAgo = now - 2L * 86_400_000L

            db.conversationDao().upsertConversation(
                com.example.data.local.ConversationEntity(
                    id = "old_user",
                    title = "Old user chat",
                    isSamplePreview = false,
                    createdAtMillis = tenDaysAgo,
                    updatedAtMillis = tenDaysAgo,
                    lastMessagePreview = "Old"
                )
            )
            db.conversationDao().upsertConversation(
                com.example.data.local.ConversationEntity(
                    id = "old_active_task",
                    title = "Old active task chat",
                    isSamplePreview = false,
                    createdAtMillis = tenDaysAgo,
                    updatedAtMillis = tenDaysAgo,
                    lastMessagePreview = "Active"
                )
            )
            db.conversationDao().upsertConversation(
                com.example.data.local.ConversationEntity(
                    id = "old_sample",
                    title = "Sample thread",
                    isSamplePreview = true,
                    createdAtMillis = tenDaysAgo,
                    updatedAtMillis = tenDaysAgo,
                    lastMessagePreview = "Sample"
                )
            )
            db.conversationDao().upsertConversation(
                com.example.data.local.ConversationEntity(
                    id = "recent_user",
                    title = "Recent chat",
                    isSamplePreview = false,
                    createdAtMillis = twoDaysAgo,
                    updatedAtMillis = twoDaysAgo,
                    lastMessagePreview = "Recent"
                )
            )

            val deleted = repo.pruneExpiredConversations(
                retentionDays = 7,
                nowMillis = now,
                activeConversationIds = setOf("old_active_task")
            )
            assertEquals(1, deleted)

            val remainingIds = repo.getConversationsSnapshot().map { it.id }.toSet()
            assertFalse(remainingIds.contains("old_user"))
            assertTrue(remainingIds.contains("old_active_task"))
            assertTrue(remainingIds.contains("old_sample"))
            assertTrue(remainingIds.contains("recent_user"))
        } finally {
            db.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `incognito mode keeps new chats out of Room when saveConversationHistory is false`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefs = newPrefs(context)
        try {
            prefs.updateSettings { it.copy(saveConversationHistory = false) }
            val chatRepo = ChatRepository(db.conversationDao(), NexaTypeConverters())
            val settingsRepo = SettingsRepository(prefs)
            val connectionsRepo = ConnectionsRepository(prefs, AiProviderRegistry(), AndroidCapabilityInspector(context))
            val registry = DefaultToolRegistry(context = context)
            val vm = ChatViewModel(
                chatRepository = chatRepo,
                connectionsRepository = connectionsRepo,
                settingsRepository = settingsRepo,
                toolRegistry = registry,
                systemPromptBuilder = SystemPromptBuilder(),
                contextBuilder = ConversationContextBuilder(),
                attachmentProcessor = AndroidAttachmentProcessor(context)
            )

            val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.uiState.collect {}
            }
            vm.startNewConversation()
            advanceUntilIdle()
            vm.sendMessage("Quick temp math check")
            advanceUntilIdle()

            val state = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.uiState.first {
                        it.conversations.any { c -> c.id.startsWith("incognito_") } &&
                            it.isIncognitoConversation
                    }
                }
            }
            collectJob.cancel()

            assertEquals(0, chatRepo.getConversationsSnapshot().size)
            assertTrue(state.conversations.any { it.id.startsWith("incognito_") })
            assertTrue(state.isIncognitoConversation)
        } finally {
            prefs.updateSettings { it.copy(saveConversationHistory = true) }
            Dispatchers.resetMain()
            db.close()
        }
    }

    // 4. Local Memory CRUD, SaveMemoryNoteTool, and SystemPromptBuilder Injection

    @Test
    fun `SaveMemoryNoteTool and SystemPromptBuilder enforce localMemoryEnabled end to end`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = newPrefs(context)
        val memoryRepo = MemoryRepository(LocalMemoryStore(prefs))
        memoryRepo.clearAll()

        var memoryEnabled = false
        val tool = SaveMemoryNoteTool(
            onSaveMemory = { note, category -> memoryRepo.addMemory(note, category) },
            isMemoryEnabled = { memoryEnabled }
        )

        // Disabled -> returns error cleanly without storing
        memoryEnabled = false
        val disabledResult = tool.execute(JSONObject().put("note", "Prefer Kotlin Flow"))
        assertTrue(disabledResult.isError)
        assertEquals(0, memoryRepo.getSnapshot().size)

        // Enabled -> stores note directly and deduplicates case-insensitively
        memoryEnabled = true
        val enabledResult = tool.execute(
            JSONObject()
                .put("note", "Prefer concise Kotlin Flow examples")
                .put("category", "Preference")
        )
        assertFalse(enabledResult.isError)
        assertTrue(enabledResult.outputJson.getBoolean("saved"))
        val saved = memoryRepo.getSnapshot()
        assertEquals(1, saved.size)

        val promptBuilder = SystemPromptBuilder()
        val promptWithMemory = promptBuilder.build(
            settings = AppSettings(localMemoryEnabled = true),
            memoryEntries = saved
        )
        assertTrue(promptWithMemory.contains("Prefer concise Kotlin Flow examples"))

        val promptWithoutMemory = promptBuilder.build(
            settings = AppSettings(localMemoryEnabled = false),
            memoryEntries = saved
        )
        assertFalse(promptWithoutMemory.contains("Prefer concise Kotlin Flow examples"))

        // Update + Remove + Clear
        memoryRepo.updateMemory(saved[0].id, "Always use StateFlow in ViewModels", "Workflow")
        assertEquals("Always use StateFlow in ViewModels", memoryRepo.getSnapshot().single().text)
        memoryRepo.removeMemory(saved[0].id)
        assertTrue(memoryRepo.getSnapshot().isEmpty())
    }

    // 5. Security, Diagnostics Masking, Redaction & Biometric Approval Gate

    @Test
    fun `DiagnosticsExporter and ApiKeyMasker scrub API keys and respect settings flags`() {
        val conv = Conversation("c1", "Debug thread", false, 100L, 200L, "sk-proj-secret1234567890")
        val msg = ChatMessage(
            id = "m1",
            conversationId = "c1",
            role = MessageRole.USER,
            content = "My token is sk-or-v1-abcdefghijklmnopqrstuvwxyz1234 and AIzaSyD1234567890abcdefg",
            createdAtMillis = 150L
        )
        val task = AgentTask(
            id = "t1",
            title = "Type message",
            prompt = "Type message",
            status = AgentTaskStatus.COMPLETED,
            isSampleData = false,
            providerLabel = "OpenAI",
            modelLabel = "gpt-4.1",
            targetApp = "Messages",
            requiredCapabilities = listOf("type_text"),
            steps = listOf(
                ExecutionStep("s1", "Type text", "Typed text [redacted, 12 chars]", ExecutionStepState.COMPLETED, "type_text")
            ),
            createdAtMillis = 100L,
            updatedAtMillis = 200L
        )
        val provider = ProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            hasApiKey = true,
            maskedApiKey = "sk-or-••••1234",
            baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
            selectedModelId = "gpt-4.1",
            isDefault = true,
            availableModels = emptyList()
        )

        val bundleMasked = DiagnosticsExporter.buildReport(
            settings = AppSettings(maskCredentialsInExports = true, redactSensitiveStepsInLogs = true),
            providers = listOf(provider),
            capabilities = emptyList(),
            tasks = listOf(task),
            conversations = listOf(conv),
            messagesByConversation = mapOf("c1" to listOf(msg)),
            memories = emptyList()
        )
        assertTrue(bundleMasked.maskedCredentialsApplied)
        assertTrue(bundleMasked.redactedSensitiveStepsApplied)
        assertFalse(bundleMasked.content.contains("sk-or-v1-abcdefghijklmnopqrstuvwxyz1234"))
        assertFalse(bundleMasked.content.contains("AIzaSyD1234567890abcdefg"))
        assertTrue(bundleMasked.content.contains("[REDACTED_KEY]"))

        val directSanitize = ApiKeyMasker.sanitizeTranscriptOrDiagnostics(
            "Bearer supersecrettokenvalue123456 custom=my_secret_xyz",
            secretsToMask = listOf("my_secret_xyz")
        )
        assertFalse(directSanitize.contains("supersecrettokenvalue123456"))
        assertFalse(directSanitize.contains("my_secret_xyz"))
    }

    @Test
    fun `SafetyPolicyManager enforces requireApprovalForExternalComms even in SUPERVISED_AUTO`() {
        val policy = DefaultSafetyPolicyManager()
        val openUrlTool = object : ExecutableTool {
            override val definition = ToolDefinition(
                id = "browser.open_url",
                name = "open_url",
                description = "Open URL",
                parameters = listOf(ToolParameter("url", ToolParameterType.STRING, "URL")),
                riskCategory = ToolRiskCategory.REVERSIBLE
            )
            override val requiredCapability: DeviceCapabilityType? = null
            override fun validateArguments(args: JSONObject): String? = null
            override fun describeAction(args: JSONObject): String = "Open SMS URI"
            override suspend fun execute(args: JSONObject): ToolExecutionOutput =
                ToolExecutionOutput(JSONObject(), "ok")
        }
        val args = JSONObject().put("url", "sms:+15550100")
        val call = ToolCall("call_sms", "open_url", args.toString())

        val mustConfirm = policy.evaluateToolCall(
            taskId = "t_1",
            tool = openUrlTool,
            call = call,
            args = args,
            settings = AppSettings(
                confirmationMode = ConfirmationMode.SUPERVISED_AUTO,
                requireApprovalForExternalComms = true
            )
        )
        assertTrue(mustConfirm is ToolPolicyEvaluation.RequireUserApproval)

        val skipConfirm = policy.evaluateToolCall(
            taskId = "t_1",
            tool = openUrlTool,
            call = call,
            args = args,
            settings = AppSettings(
                confirmationMode = ConfirmationMode.SUPERVISED_AUTO,
                requireApprovalForExternalComms = false
            )
        )
        assertTrue(skipConfirm is ToolPolicyEvaluation.Allow)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `biometricLockOnApproval handles Verified Cancelled and ProceedWithWarning fallback banner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefs = newPrefs(context)
        try {
            prefs.updateSettings { it.copy(biometricLockOnApproval = true) }
            val runController = AgentRunController()

            var authResponse: ApprovalAuthResult = ApprovalAuthResult.ProceedWithWarning(
                "No lock screen enrolled. Approval proceeded with notice."
            )
            val fakeAuth = object : ApprovalAuthenticator {
                override fun canAuthenticate(): Boolean = false
                override suspend fun authenticate(
                    activity: Activity?,
                    title: String,
                    subtitle: String,
                    onDeviceCredentialIntentRequired: ((Intent) -> Unit)?
                ): ApprovalAuthResult = authResponse
            }

            val vm = ChatViewModel(
                chatRepository = ChatRepository(db.conversationDao(), NexaTypeConverters()),
                connectionsRepository = ConnectionsRepository(prefs, AiProviderRegistry(), AndroidCapabilityInspector(context)),
                settingsRepository = SettingsRepository(prefs),
                toolRegistry = DefaultToolRegistry(context = context),
                systemPromptBuilder = SystemPromptBuilder(),
                contextBuilder = ConversationContextBuilder(),
                attachmentProcessor = AndroidAttachmentProcessor(context),
                agentRunController = runController,
                approvalAuthenticator = fakeAuth
            )

            val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.uiState.collect {}
            }
            val req = ToolApprovalRequest(
                approvalId = "appr_1",
                taskId = "task_1",
                callId = "call_1",
                toolId = "open_url",
                toolName = "open_url",
                riskCategory = ToolRiskCategory.SENSITIVE,
                intentSummary = "Open billing page",
                targetDataSummary = "https://example.com",
                isReversible = true,
                approvalEffect = "Opens external URL",
                argumentsPreview = """{"url":"https://example.com"}"""
            )
            val handle = runController.beginRun("task_1", "c_1", null)
            val decision1 = async {
                handle.requestApproval(req)
            }
            advanceUntilIdle()
            vm.confirmApprovalWithAuth(null, req)
            advanceUntilIdle()

            assertEquals(ApprovalDecision.APPROVE, decision1.await())
            val stateWithWarning = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.uiState.first { it.biometricWarningBanner != null }
                }
            }
            assertNotNull(stateWithWarning.biometricWarningBanner)

            // Cancelled does not approve
            authResponse = ApprovalAuthResult.Cancelled("User cancelled biometric prompt.")
            val req2 = req.copy(callId = "call_2", approvalId = "appr_2")
            val decision2 = async {
                handle.requestApproval(req2)
            }
            advanceUntilIdle()
            vm.confirmApprovalWithAuth(null, req2)
            advanceUntilIdle()
            assertFalse(decision2.isCompleted)
            val stateCancelled = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.uiState.first { it.biometricWarningBanner == "User cancelled biometric prompt." }
                }
            }
            assertEquals("User cancelled biometric prompt.", stateCancelled.biometricWarningBanner)
            vm.rejectToolCall("call_2")
            assertEquals(ApprovalDecision.REJECT, decision2.await())
            collectJob.cancel()
        } finally {
            prefs.updateSettings { it.copy(biometricLockOnApproval = false) }
            Dispatchers.resetMain()
            db.close()
        }
    }

    // 6. Welcome Suggestions Gate

    @Test
    fun `ChatEmptyWelcome hides app launch prompt suggestion when allowAppLaunchSuggestions is false`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidCapabilityInspector(context)
        val caps = inspector.inspectAll(mapOf(DeviceCapabilityType.APP_LAUNCHING.id to true))
        val registry = DefaultToolRegistry(context = context)
        val registered = registry.getAllRegisteredTools(caps)

        composeRule.setContent {
            NexaTheme {
                ChatEmptyWelcome(
                    defaultProvider = null,
                    capabilities = caps,
                    registeredTools = registered,
                    accessibilityState = AccessibilityRuntimeState.SERVICE_DISABLED,
                    mediaProjectionState = MediaProjectionConsentState.NOT_GRANTED,
                    allowAppLaunchSuggestions = false,
                    onPromptSelect = {},
                    onConfigureProvider = {},
                    onOpenAccessibilitySettings = {},
                    onRequestScreenCapture = {},
                    onStopScreenCapture = {}
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText("List installed apps on this device or open YouTube.")
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    // 7. SettingsScreen UI Integration (Appearance, Retention, Memory Sheet, Diagnostics Export)

    @Test
    fun `SettingsScreen switches themes, manages memory notes, and exports masked diagnostics`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefs = newPrefs(context)
        val settingsRepo = SettingsRepository(prefs)
        val chatRepo = ChatRepository(db.conversationDao(), NexaTypeConverters())
        val connectionsRepo = ConnectionsRepository(prefs, AiProviderRegistry(), AndroidCapabilityInspector(context))
        val memoryRepo = MemoryRepository(LocalMemoryStore(prefs))
        val taskRepo = TaskRepository(db.taskDao(), NexaTypeConverters())

        kotlinx.coroutines.runBlocking {
            prefs.updateSettings { AppSettings() }
            memoryRepo.clearAll()
        }

        val settingsVm = SettingsViewModel(
            settingsRepository = settingsRepo,
            connectionsRepository = connectionsRepo,
            chatRepository = chatRepo,
            memoryRepository = memoryRepo,
            taskRepository = taskRepo
        )

        composeRule.setContent {
            NexaTheme {
                SettingsScreen(
                    viewModel = settingsVm,
                    onNavigateToConnectionsTab = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Switch Appearance to Daylight Light
        composeRule.onNodeWithTag("settings_list").performScrollToKey("sec_appearance")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("appearance_option_LIGHT").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        val storedAfterClick = kotlinx.coroutines.runBlocking {
            withTimeout(5_000) {
                settingsRepo.settings.first { it.appearancePosture == AppearancePosture.LIGHT }
            }
        }
        assertEquals(AppearancePosture.LIGHT, storedAfterClick.appearancePosture)
        composeRule.waitUntil(5_000) {
            settingsVm.uiState.value.settings.appearancePosture == AppearancePosture.LIGHT
        }
        assertEquals(AppearancePosture.LIGHT, settingsVm.uiState.value.settings.appearancePosture)

        // Open Memory Manager Sheet and add a note
        composeRule.onNodeWithTag("settings_list").performScrollToKey("sec_memory")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manage_memory_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memory_manager_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_add_input").performTextInput("Prefer dark terminal themes")
        composeRule.onNodeWithTag("memory_add_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Prefer dark terminal themes").assertIsDisplayed()

        // Clear memory inside sheet
        composeRule.onNodeWithTag("memory_clear_all_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No saved memories yet").assertIsDisplayed()

        // Close sheet and open Diagnostics Export Dialog via button
        composeRule.onNodeWithTag("memory_sheet_close_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_list").performScrollToKey("sec_security")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_diagnostics_button").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("diagnostics_export_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("diagnostics_export_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Keys Masked").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_close_button").performClick()
        composeRule.waitForIdle()
        db.close()
    }

    // 8. CapabilitiesTab UI Integration (Dynamic Counts, Category Filtering, Inline Actions, Installed Apps Sheet)

    @Test
    fun `CapabilitiesTab keeps dynamic All count across category filters and opens searchable Installed Apps sheet`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = newPrefs(context)
        val inspector = AndroidCapabilityInspector(context)
        val connectionsRepo = ConnectionsRepository(prefs, AiProviderRegistry(), inspector)

        val fakeApps = listOf(
            InstalledAppInfo("YouTube", "com.google.android.youtube", isLaunchable = true),
            InstalledAppInfo("Calculator", "com.android.calculator2", isLaunchable = true)
        )
        val fakeDiscovery = object : AndroidAppDiscovery(context) {
            override fun listLaunchableApps(
                query: String?,
                includeSystemApps: Boolean,
                limit: Int,
                offset: Int
            ): AppDiscoveryBatch {
                val filtered = if (query.isNullOrBlank()) {
                    fakeApps
                } else {
                    fakeApps.filter {
                        it.appName.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
                }
                return AppDiscoveryBatch(filtered, fakeApps.size, "Full package inventory visible")
            }
        }
        val permManager = AndroidPermissionManager(context)
        val screenObserver = AndroidScreenObserver(
            LiveAccessibilityScreenBridge(permManager, AccessibilityNodeInspector())
        )
        val verifier = AndroidActionVerifier(screenObserver)
        var launchedPkg: String? = null
        val fakeLauncher = object : AndroidAppLauncher(context, fakeDiscovery, permManager, verifier) {
            override suspend fun launch(appQuery: String): AppLaunchOutcome {
                launchedPkg = appQuery
                return AppLaunchOutcome(
                    success = true,
                    appName = appQuery,
                    packageName = appQuery,
                    launchIntentDispatched = true,
                    foregroundVerified = true,
                    message = "Launched $appQuery"
                )
            }
        }

        val vm = ConnectionsViewModel(
            connectionsRepository = connectionsRepo,
            appDiscovery = fakeDiscovery,
            appLauncher = fakeLauncher
        )

        composeRule.setContent {
            val collected = vm.uiState.collectAsStateWithLifecycle().value
            NexaTheme {
                CapabilitiesTab(
                    capabilities = collected.capabilities,
                    selectedCategory = collected.capabilityFilter,
                    accessibilityState = collected.accessibilityState,
                    accessibilityReason = collected.accessibilityReason,
                    canPostNotifications = collected.canPostNotifications,
                    onSelectCategory = vm::selectCapabilityCategory,
                    onRefresh = vm::refreshCapabilities,
                    onToggleCapability = vm::toggleCapabilityPreference,
                    totalCapabilitiesCount = collected.totalCapabilitiesCount,
                    availableCapabilitiesCount = collected.availableCapabilitiesCount,
                    actionNeededCapabilitiesCount = collected.actionNeededCapabilitiesCount,
                    installedAppsSheetVisible = collected.installedAppsSheetVisible,
                    installedAppsSearchQuery = collected.installedAppsSearchQuery,
                    discoveredApps = collected.discoveredApps,
                    appLaunchFeedback = collected.appLaunchFeedback,
                    onOpenInstalledApps = vm::openInstalledAppsSheet,
                    onCloseInstalledApps = vm::closeInstalledAppsSheet,
                    onAppsSearchQueryChange = vm::updateAppsSearchQuery,
                    onTestLaunchApp = vm::testLaunchDiscoveredApp,
                    onDismissAppLaunchFeedback = vm::dismissAppLaunchFeedback
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("capabilities_summary_counts").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capabilities_list").performScrollToKey("cap_filters")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capability_filter_all").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("All (14)").assertIsDisplayed()
        composeRule.onNodeWithTag("capabilities_list").performScrollToKey("cap_summary_row")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("14 total").assertIsDisplayed()

        // Filter to Connectivity -> All (14) must still say All (14)!
        composeRule.onNodeWithTag("capabilities_list").performScrollToKey("cap_filters")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capability_filter_connectivity").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capability_filter_all").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("All (14)").assertIsDisplayed()
        composeRule.onNodeWithTag("capabilities_list").performScrollToKey("wifi")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capability_card_wifi").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("capability_card_app_launching")
                .fetchSemanticsNodes()
                .isEmpty()
        )

        // Back to All and open Installed Apps sheet from App Launching card action
        composeRule.onNodeWithTag("capabilities_list").performScrollToKey("cap_filters")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capability_filter_all").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capabilities_list").performScrollToKey("app_launching")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capability_action_app_launching").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("installed_apps_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
        composeRule.onNodeWithTag("installed_apps_search_input").performTextInput("Calc")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("YouTube").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("launch_app_com.android.calculator2").performClick()
        composeRule.waitUntil(5_000) { launchedPkg == "com.android.calculator2" }
        assertEquals("com.android.calculator2", launchedPkg)
    }
}
