package com.moutrancorp.memspike

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var appState: MemAppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appState = MemAppState(
            context = this,
            appearanceStore = AppearanceStore(this),
            extractor = YtDlpExtractor(this),
        )
        setContent {
            MemApp(appState)
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (shared.isNotEmpty()) {
            appState.openCapture(shared, autoExtract = true)
        }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    Mem("Mem", Icons.Rounded.Home),
    Inbox("Inbox", Icons.Rounded.Inbox),
    Capture("Capture", Icons.Rounded.Add),
    Library("Library", Icons.Rounded.Bookmarks),
}

private enum class LibraryMode(val label: String, val icon: ImageVector) {
    Feed("Feed", Icons.Rounded.ViewAgenda),
    Grid("Grid", Icons.Rounded.GridView),
    Timeline("Timeline", Icons.Rounded.Timeline),
}

private enum class ThemeProfile(val label: String) {
    Default("Default"),
    Focus("Focus"),
    Journal("Journal"),
    Glass("Glass"),
    Library("Library"),
    HighContrast("High Contrast"),
}

private enum class ThemeMode(val label: String) {
    Light("Light"),
    Dark("Dark"),
}

private enum class DensityScale(val label: String, val spacingMultiplier: Float) {
    Comfortable("Comfort", 1.16f),
    Standard("Standard", 1f),
    Compact("Compact", 0.84f),
}

private enum class CornerStyle(val label: String, val multiplier: Float) {
    Soft("Soft", 1.28f),
    Standard("Standard", 1f),
    Sharp("Sharp", 0.42f),
}

private enum class DockStyle(val label: String) {
    Glass("Glass"),
    Translucent("Translucent"),
    Solid("Solid"),
}

private data class AppearanceSettings(
    val profile: ThemeProfile = ThemeProfile.Default,
    val mode: ThemeMode = ThemeMode.Light,
    val density: DensityScale = DensityScale.Standard,
    val cornerStyle: CornerStyle = CornerStyle.Standard,
    val dockStyle: DockStyle = DockStyle.Glass,
    val libraryMode: LibraryMode = LibraryMode.Feed,
)

private class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("mem_appearance", Context.MODE_PRIVATE)

    fun load(): AppearanceSettings {
        return AppearanceSettings(
            profile = prefs.enum("profile", ThemeProfile.Default),
            mode = prefs.enum("mode", ThemeMode.Light),
            density = prefs.enum("density", DensityScale.Standard),
            cornerStyle = prefs.enum("cornerStyle", CornerStyle.Standard),
            dockStyle = prefs.enum("dockStyle", DockStyle.Glass),
            libraryMode = prefs.enum("libraryMode", LibraryMode.Feed),
        )
    }

    fun save(settings: AppearanceSettings) {
        prefs.edit()
            .putString("profile", settings.profile.name)
            .putString("mode", settings.mode.name)
            .putString("density", settings.density.name)
            .putString("cornerStyle", settings.cornerStyle.name)
            .putString("dockStyle", settings.dockStyle.name)
            .putString("libraryMode", settings.libraryMode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(
        key: String,
        fallback: T,
    ): T {
        return getString(key, null)?.let { value ->
            enumValues<T>().firstOrNull { it.name == value }
        } ?: fallback
    }
}

private class MemAppState(
    private val context: Context,
    private val appearanceStore: AppearanceStore,
    private val extractor: YtDlpExtractor,
) {
    var selectedTab by mutableStateOf(MainTab.Mem)
    var showCapture by mutableStateOf(false)
    var showAppearance by mutableStateOf(false)
    var captureText by mutableStateOf("")
    var isExtracting by mutableStateOf(false)
    var logOutput by mutableStateOf("Ready. Share or paste a link to extract metadata on-device.")
    var appearance by mutableStateOf(appearanceStore.load())
        private set

    val jobs = mutableStateListOf<IngestionJobUi>()
    val memories = mutableStateListOf<MemoryUi>()

    init {
        jobs.addAll(sampleJobs())
        memories.addAll(sampleMemories())
    }

    fun updateAppearance(settings: AppearanceSettings) {
        appearance = settings
        appearanceStore.save(settings)
    }

    fun updateLibraryMode(mode: LibraryMode) {
        updateAppearance(appearance.copy(libraryMode = mode))
    }

    fun openCapture(initialText: String = "", autoExtract: Boolean = false) {
        captureText = initialText
        showCapture = true
        selectedTab = MainTab.Capture
        if (autoExtract) extract(initialText)
    }

    fun closeCapture() {
        showCapture = false
        if (selectedTab == MainTab.Capture) selectedTab = MainTab.Mem
    }

    fun extract(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            logOutput = "Enter a URL first."
            return
        }

        val jobId = UUID.randomUUID().toString()
        jobs.add(
            0,
            IngestionJobUi(
                id = jobId,
                title = trimmed,
                source = "Shared link",
                state = "Extracting",
                progress = 0.35f,
                icon = Icons.Rounded.Link,
                isError = false,
            ),
        )

        isExtracting = true
        logOutput = "Running packaged yt-dlp on-device...\n\n$trimmed"
        CoroutineScope(Dispatchers.Main).launch {
            val result = extractor.extract(trimmed)
            isExtracting = false
            logOutput = result.prettyText
            val index = jobs.indexOfFirst { it.id == jobId }
            if (index >= 0) {
                jobs[index] = jobs[index].copy(
                    state = if (result.ok) "Indexed" else if (result.authRequired) "Needs auth" else "Failed",
                    progress = 1f,
                    isError = !result.ok,
                )
            }
            if (result.ok) {
                memories.add(0, result.toMemory(trimmed))
            }
        }
    }

    fun copyLog() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Mem extraction output", logOutput))
        Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
    }

    fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logOutput)
        }
        context.startActivity(Intent.createChooser(send, "Share extraction output"))
    }
}

private class YtDlpExtractor(private val activity: Activity) {
    suspend fun extract(url: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(activity))
            }
            val py = Python.getInstance()
            val extractor = py.getModule("mem_yt_dlp_extractor")
            val ffmpegPath = findPackagedExecutable("ffmpeg")
            val raw: PyObject = extractor.callAttr(
                "extract",
                url,
                activity.filesDir.absolutePath,
                ffmpegPath,
            )
            ExtractionResult.fromJson(raw.toString())
        } catch (t: Throwable) {
            ExtractionResult(
                ok = false,
                title = null,
                source = null,
                extractor = null,
                authRequired = false,
                prettyText = "Extraction failed:\n${t::class.java.simpleName}: ${t.message}",
            )
        }
    }

    private fun findPackagedExecutable(name: String): String {
        val nativeLibDir = File(activity.applicationInfo.nativeLibraryDir)
        val candidate = File(nativeLibDir, "lib$name.so")
        return if (candidate.exists() && candidate.canExecute()) candidate.absolutePath else ""
    }
}

private data class ExtractionResult(
    val ok: Boolean,
    val title: String?,
    val source: String?,
    val extractor: String?,
    val authRequired: Boolean,
    val prettyText: String,
) {
    fun toMemory(url: String): MemoryUi {
        return MemoryUi(
            title = title ?: url,
            source = extractor ?: source ?: "Extracted link",
            type = "Video",
            summary = "Metadata extracted on-device and ready for the memory index.",
            time = "Just now",
            icon = Icons.Rounded.SmartDisplay,
            tags = listOf("extracted", "rag candidate"),
        )
    }

    companion object {
        fun fromJson(json: String): ExtractionResult {
            val root = JSONObject(json)
            val pretty = try {
                root.toString(2)
            } catch (_: Exception) {
                json
            }
            val candidate = root.optJSONObject("ragCandidate")
            return ExtractionResult(
                ok = root.optBoolean("ok", false),
                title = candidate?.optString("title")?.takeIf { it.isNotBlank() },
                source = candidate?.optString("webpageUrl")?.takeIf { it.isNotBlank() },
                extractor = candidate?.optString("extractor")?.takeIf { it.isNotBlank() },
                authRequired = root.optBoolean("authRequired", false),
                prettyText = pretty,
            )
        }
    }
}

private data class IngestionJobUi(
    val id: String,
    val title: String,
    val source: String,
    val state: String,
    val progress: Float,
    val icon: ImageVector,
    val isError: Boolean,
)

private data class MemoryUi(
    val title: String,
    val source: String,
    val type: String,
    val summary: String,
    val time: String,
    val icon: ImageVector,
    val tags: List<String>,
)

@Stable
private data class MemColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceFloating: Color,
    val surfaceMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentMuted: Color,
    val warning: Color,
    val success: Color,
    val danger: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val dockBackground: Color,
    val dockSelected: Color,
)

@Stable
private data class MemSpacing(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
)

@Stable
private data class MemShapes(
    val sm: RoundedCornerShape,
    val md: RoundedCornerShape,
    val lg: RoundedCornerShape,
    val xl: RoundedCornerShape,
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
)

@Stable
private data class MemThemeTokens(
    val colors: MemColors,
    val spacing: MemSpacing,
    val shapes: MemShapes,
    val dockStyle: DockStyle,
)

private val LocalMemTokens = staticCompositionLocalOf {
    MemThemeTokens(
        colors = lightMemColors(ThemeProfile.Default),
        spacing = memSpacing(DensityScale.Standard),
        shapes = memShapes(CornerStyle.Standard),
        dockStyle = DockStyle.Glass,
    )
}

@Composable
private fun MemApp(state: MemAppState) {
    MemTheme(state.appearance) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MemTokens.colors.background),
        ) {
            MemScaffold(state)
        }
    }
}

@Composable
private fun MemTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = settings.mode == ThemeMode.Dark
    val tokens = remember(settings) {
        MemThemeTokens(
            colors = if (dark) darkMemColors(settings.profile) else lightMemColors(settings.profile),
            spacing = memSpacing(settings.density),
            shapes = memShapes(settings.cornerStyle),
            dockStyle = settings.dockStyle,
        )
    }
    val materialColors = if (dark) {
        darkColorScheme(
            primary = tokens.colors.accent,
            surface = tokens.colors.surface,
            background = tokens.colors.background,
            onSurface = tokens.colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = tokens.colors.accent,
            surface = tokens.colors.surface,
            background = tokens.colors.background,
            onSurface = tokens.colors.textPrimary,
        )
    }
    CompositionLocalProvider(LocalMemTokens provides tokens) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content,
        )
    }
}

private object MemTokens {
    val colors: MemColors
        @Composable get() = LocalMemTokens.current.colors
    val spacing: MemSpacing
        @Composable get() = LocalMemTokens.current.spacing
    val shapes: MemShapes
        @Composable get() = LocalMemTokens.current.shapes
    val dockStyle: DockStyle
        @Composable get() = LocalMemTokens.current.dockStyle
}

private fun lightMemColors(profile: ThemeProfile): MemColors {
    val accent = when (profile) {
        ThemeProfile.Default -> Color(0xFF087565)
        ThemeProfile.Focus -> Color(0xFF295E6A)
        ThemeProfile.Journal -> Color(0xFF6D7D4E)
        ThemeProfile.Glass -> Color(0xFF087A82)
        ThemeProfile.Library -> Color(0xFF0F7A92)
        ThemeProfile.HighContrast -> Color(0xFF005F4D)
    }
    return MemColors(
        background = if (profile == ThemeProfile.Journal) Color(0xFFFFFCF5) else Color(0xFFFAFAF7),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFF6F7F2),
        surfaceFloating = Color(0xF2FFFFFF),
        surfaceMuted = Color(0xFFF0F2ED),
        textPrimary = Color(0xFF111A17),
        textSecondary = Color(0xFF52605B),
        textTertiary = Color(0xFF83908B),
        accent = accent,
        accentMuted = accent.copy(alpha = 0.12f),
        warning = Color(0xFFD28A16),
        success = Color(0xFF2C8A53),
        danger = Color(0xFFC6473E),
        borderSubtle = Color(0x1F17201D),
        borderStrong = Color(0x5517201D),
        dockBackground = Color(0xEBFFFFFF),
        dockSelected = accent.copy(alpha = 0.13f),
    )
}

private fun darkMemColors(profile: ThemeProfile): MemColors {
    val accent = when (profile) {
        ThemeProfile.Journal -> Color(0xFFE1C36A)
        ThemeProfile.HighContrast -> Color(0xFF7EF7D0)
        else -> Color(0xFF72D6C3)
    }
    return MemColors(
        background = Color(0xFF101412),
        surface = Color(0xFF171D1A),
        surfaceRaised = Color(0xFF202823),
        surfaceFloating = Color(0xEE202823),
        surfaceMuted = Color(0xFF252D28),
        textPrimary = Color(0xFFF7FAF6),
        textSecondary = Color(0xFFBCC8C2),
        textTertiary = Color(0xFF899790),
        accent = accent,
        accentMuted = accent.copy(alpha = 0.16f),
        warning = Color(0xFFFFC15D),
        success = Color(0xFF72D68C),
        danger = Color(0xFFFF8A82),
        borderSubtle = Color(0x24FFFFFF),
        borderStrong = Color(0x66FFFFFF),
        dockBackground = Color(0xE6202823),
        dockSelected = accent.copy(alpha = 0.20f),
    )
}

private fun memSpacing(density: DensityScale): MemSpacing {
    fun scaled(value: Int): Dp = (value * density.spacingMultiplier).dp
    return MemSpacing(
        xxs = scaled(4),
        xs = scaled(8),
        sm = scaled(12),
        md = scaled(16),
        lg = scaled(20),
        xl = scaled(28),
        xxl = scaled(36),
    )
}

private fun memShapes(style: CornerStyle): MemShapes {
    fun radius(value: Int): RoundedCornerShape = RoundedCornerShape((value * style.multiplier).dp)
    return MemShapes(
        sm = radius(8),
        md = radius(14),
        lg = radius(20),
        xl = radius(28),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemScaffold(state: MemAppState) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.selectedTab,
            label = "main-tab",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                MainTab.Mem -> MemHomeScreen(state)
                MainTab.Inbox -> InboxScreen(state)
                MainTab.Capture -> MemHomeScreen(state)
                MainTab.Library -> LibraryScreen(state)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(132.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MemTokens.colors.background),
                    ),
                ),
        )

        FloatingDock(
            selectedTab = if (state.selectedTab == MainTab.Capture) MainTab.Mem else state.selectedTab,
            onSelect = { tab ->
                if (tab == MainTab.Capture) state.openCapture() else state.selectedTab = tab
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = MemTokens.spacing.md, vertical = MemTokens.spacing.sm),
        )
    }

    if (state.showCapture) {
        ModalBottomSheet(
            onDismissRequest = { state.closeCapture() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MemTokens.colors.surface,
            contentColor = MemTokens.colors.textPrimary,
        ) {
            CaptureSheet(state)
        }
    }

    if (state.showAppearance) {
        ModalBottomSheet(
            onDismissRequest = { state.showAppearance = false },
            containerColor = MemTokens.colors.surface,
            contentColor = MemTokens.colors.textPrimary,
        ) {
            AppearanceSheet(state)
        }
    }
}

@Composable
private fun MemHomeScreen(state: MemAppState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = MemTokens.spacing.md,
            end = MemTokens.spacing.md,
            top = MemTokens.spacing.lg,
            bottom = 148.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.lg),
    ) {
        item {
            HeaderRow(
                title = "Mem",
                subtitle = "Private memory console",
                onAppearance = { state.showAppearance = true },
            )
        }
        item {
            CommandPanel(
                isExtracting = state.isExtracting,
                onOpenAgent = { state.logOutput = "Agent shell opened from the command field. Tool wiring comes after RAG/search." },
            )
        }
        item {
            QuickActionRow(
                actions = listOf(
                    QuickAction("Summarize", Icons.Rounded.AutoAwesome),
                    QuickAction("Find a source", Icons.Rounded.Search),
                    QuickAction("Organize", Icons.Rounded.Folder),
                    QuickAction("Make playlist", Icons.Rounded.Bookmarks),
                ),
            )
        }
        item {
            ProcessingPanel(jobs = state.jobs.take(3), onViewInbox = { state.selectedTab = MainTab.Inbox })
        }
        item {
            MemoryContextPanel()
        }
        item {
            SectionHeader("Recent sources", "View all") { state.selectedTab = MainTab.Library }
            Spacer(modifier = Modifier.height(MemTokens.spacing.sm))
            RecentSourcesRow(state.memories.take(8))
        }
        item {
            TodayTimelinePanel()
        }
    }
}

@Composable
private fun InboxScreen(state: MemAppState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = MemTokens.spacing.md,
            end = MemTokens.spacing.md,
            top = MemTokens.spacing.lg,
            bottom = 148.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.md),
    ) {
        item {
            HeaderRow(
                title = "Inbox",
                subtitle = "Processing, review, and extraction logs",
                onAppearance = { state.showAppearance = true },
            )
        }
        item {
            PrimaryButton(
                label = "Capture new memory",
                icon = Icons.Rounded.Add,
                onClick = { state.openCapture() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(state.jobs, key = { it.id }) { job ->
            JobRow(job)
        }
        item {
            LogPanel(
                output = state.logOutput,
                isExtracting = state.isExtracting,
                onCopy = { state.copyLog() },
                onShare = { state.shareLog() },
            )
        }
    }
}

@Composable
private fun LibraryScreen(state: MemAppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(
                start = MemTokens.spacing.md,
                end = MemTokens.spacing.md,
                top = MemTokens.spacing.lg,
            ),
    ) {
        HeaderRow(
            title = "Library",
            subtitle = "Everything you have saved",
            onAppearance = { state.showAppearance = true },
        )
        Spacer(modifier = Modifier.height(MemTokens.spacing.md))
        ModeSegmentedControl(
            selected = state.appearance.libraryMode,
            onSelected = state::updateLibraryMode,
        )
        Spacer(modifier = Modifier.height(MemTokens.spacing.md))
        SearchFilterRow()
        Spacer(modifier = Modifier.height(MemTokens.spacing.md))
        when (state.appearance.libraryMode) {
            LibraryMode.Feed -> LibraryFeed(state.memories)
            LibraryMode.Grid -> LibraryGrid(state.memories)
            LibraryMode.Timeline -> LibraryTimeline(state.memories)
        }
    }
}

@Composable
private fun HeaderRow(title: String, subtitle: String, onAppearance: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = title,
                color = MemTokens.colors.textPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                color = MemTokens.colors.textSecondary,
                fontSize = 14.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.xs)) {
            MemIconButton(Icons.Rounded.Search, "Search") {}
            MemIconButton(Icons.Rounded.Tune, "Appearance", onAppearance)
        }
    }
}

@Composable
private fun CommandPanel(isExtracting: Boolean, onOpenAgent: () -> Unit) {
    SurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        container = MemTokens.colors.textPrimary,
        border = null,
    ) {
        Column(
            modifier = Modifier.padding(MemTokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.AutoAwesome, MemTokens.colors.accentMuted, Color.White)
                Spacer(modifier = Modifier.width(MemTokens.spacing.md))
                Column {
                    Text("Ask your memory", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Search, retrieve, organize, and act across saved context.",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MemTokens.shapes.lg)
                    .background(Color.White.copy(alpha = 0.09f))
                    .clickable(onClick = onOpenAgent)
                    .padding(MemTokens.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ask or find anything...",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                if (isExtracting) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun QuickActionRow(actions: List<QuickAction>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
        items(actions) { action ->
            AssistChip(
                onClick = {},
                label = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

private data class QuickAction(val label: String, val icon: ImageVector)

@Composable
private fun ProcessingPanel(jobs: List<IngestionJobUi>, onViewInbox: () -> Unit) {
    SurfaceCard {
        Column(modifier = Modifier.padding(MemTokens.spacing.md)) {
            SectionHeader("Capture queue", "${jobs.size} active", onViewInbox)
            Spacer(modifier = Modifier.height(MemTokens.spacing.sm))
            jobs.forEachIndexed { index, job ->
                if (index > 0) DividerLine()
                JobRow(job, compact = true)
            }
        }
    }
}

@Composable
private fun MemoryContextPanel() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm),
    ) {
        ContextMetric("12", "People", Icons.Rounded.CheckCircle, Modifier.weight(1f))
        ContextMetric("28", "Projects", Icons.Rounded.Folder, Modifier.weight(1f))
        ContextMetric("1.2k", "Notes", Icons.AutoMirrored.Rounded.Article, Modifier.weight(1f))
        ContextMetric("342", "Topics", Icons.Rounded.Tag, Modifier.weight(1f))
    }
}

@Composable
private fun ContextMetric(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(MemTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.xs),
        ) {
            IconBadge(icon, MemTokens.colors.accentMuted, MemTokens.colors.accent, size = 34.dp)
            Text(value, color = MemTokens.colors.textPrimary, fontSize = 25.sp, fontWeight = FontWeight.Medium)
            Text(label, color = MemTokens.colors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RecentSourcesRow(memories: List<MemoryUi>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
        items(memories) { memory ->
            SurfaceCard(modifier = Modifier.width(156.dp)) {
                Column(
                    modifier = Modifier.padding(MemTokens.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm),
                ) {
                    IconBadge(memory.icon, MemTokens.colors.accentMuted, MemTokens.colors.accent)
                    Text(
                        memory.title,
                        color = MemTokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(memory.source, color = MemTokens.colors.textSecondary, fontSize = 12.sp, maxLines = 1)
                    Text(memory.time, color = MemTokens.colors.textTertiary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TodayTimelinePanel() {
    Column(verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
        SectionHeader("Today", "View timeline") {}
        SurfaceCard {
            Column(modifier = Modifier.padding(MemTokens.spacing.md)) {
                TimelineRow("9:30 AM", "Morning reflection", "Walked 30 min, read 10 pages, saved product ideas.", Icons.Rounded.AutoAwesome)
                DividerLine()
                TimelineRow("10:15 AM", "Team sync", "Discussed onboarding flow v2 and metrics for activation.", Icons.Rounded.Waves)
                DividerLine()
                TimelineRow("Now", "Mem shell", "Compose UI foundation is running with tokenized styling.", Icons.Rounded.CheckCircle)
            }
        }
    }
}

@Composable
private fun TimelineRow(time: String, title: String, body: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MemTokens.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(time, color = MemTokens.colors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(74.dp))
        IconBadge(icon, MemTokens.colors.accentMuted, MemTokens.colors.accent, size = 34.dp)
        Spacer(modifier = Modifier.width(MemTokens.spacing.sm))
        Column {
            Text(title, color = MemTokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(body, color = MemTokens.colors.textSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CaptureSheet(state: MemAppState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(MemTokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Capture", color = MemTokens.colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            MemIconButton(Icons.Rounded.Close, "Close") { state.closeCapture() }
        }
        MemTextInput(
            value = state.captureText,
            onValueChange = { state.captureText = it },
            placeholder = "Paste a link, note, or source text...",
            minHeight = 126.dp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
            CaptureType("Link", Icons.Rounded.Link, Modifier.weight(1f))
            CaptureType("File", Icons.Rounded.UploadFile, Modifier.weight(1f))
            CaptureType("Voice", Icons.Rounded.Waves, Modifier.weight(1f))
            CaptureType("Note", Icons.AutoMirrored.Rounded.Article, Modifier.weight(1f))
        }
        PrimaryButton(
            label = if (state.isExtracting) "Extracting metadata" else "Extract metadata",
            icon = if (state.isExtracting) Icons.Rounded.MoreHoriz else Icons.Rounded.AutoAwesome,
            onClick = { state.extract(state.captureText) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isExtracting,
        )
        OutlinedButton(
            onClick = {
                state.selectedTab = MainTab.Inbox
                state.closeCapture()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MemTokens.shapes.pill,
        ) {
            Text("Open inbox and logs")
        }
    }
}

@Composable
private fun CaptureType(label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(vertical = MemTokens.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.xs),
        ) {
            Icon(icon, contentDescription = null, tint = MemTokens.colors.accent)
            Text(label, color = MemTokens.colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AppearanceSheet(state: MemAppState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(MemTokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.lg),
    ) {
        Text("Appearance", color = MemTokens.colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        SettingsGroup("Theme profile") {
            EnumOptions(ThemeProfile.entries, state.appearance.profile) {
                state.updateAppearance(state.appearance.copy(profile = it))
            }
        }
        SettingsGroup("Mode") {
            EnumOptions(ThemeMode.entries, state.appearance.mode) {
                state.updateAppearance(state.appearance.copy(mode = it))
            }
        }
        SettingsGroup("Density") {
            EnumOptions(DensityScale.entries, state.appearance.density) {
                state.updateAppearance(state.appearance.copy(density = it))
            }
        }
        SettingsGroup("Corners") {
            EnumOptions(CornerStyle.entries, state.appearance.cornerStyle) {
                state.updateAppearance(state.appearance.copy(cornerStyle = it))
            }
        }
        SettingsGroup("Dock") {
            EnumOptions(DockStyle.entries, state.appearance.dockStyle) {
                state.updateAppearance(state.appearance.copy(dockStyle = it))
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
        Text(title, color = MemTokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private inline fun <reified T> EnumOptions(
    values: List<T>,
    selected: T,
    crossinline onSelected: (T) -> Unit,
) where T : Enum<T> {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
        items(values) { value ->
            val label = when (value) {
                is ThemeProfile -> value.label
                is ThemeMode -> value.label
                is DensityScale -> value.label
                is CornerStyle -> value.label
                is DockStyle -> value.label
                else -> value.name
            }
            SelectablePill(label = label, selected = selected == value) { onSelected(value) }
        }
    }
}

@Composable
private fun ModeSegmentedControl(selected: LibraryMode, onSelected: (LibraryMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MemTokens.shapes.pill)
            .background(MemTokens.colors.surfaceMuted)
            .padding(MemTokens.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.xxs),
    ) {
        LibraryMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(MemTokens.shapes.pill)
                    .selectable(selected = selected == mode, onClick = { onSelected(mode) })
                    .background(if (selected == mode) MemTokens.colors.surface else Color.Transparent)
                    .padding(vertical = MemTokens.spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(mode.icon, contentDescription = null, tint = if (selected == mode) MemTokens.colors.accent else MemTokens.colors.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(MemTokens.spacing.xs))
                Text(mode.label, color = if (selected == mode) MemTokens.colors.textPrimary else MemTokens.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun SearchFilterRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm), modifier = Modifier.fillMaxWidth()) {
        SurfaceCard(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.padding(horizontal = MemTokens.spacing.md, vertical = MemTokens.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MemTokens.colors.textTertiary)
                Spacer(modifier = Modifier.width(MemTokens.spacing.sm))
                Text("Search your library", color = MemTokens.colors.textTertiary)
            }
        }
        MemIconButton(Icons.Rounded.Tune, "Filters") {}
    }
}

@Composable
private fun LibraryFeed(memories: List<MemoryUi>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm),
        contentPadding = PaddingValues(bottom = 148.dp),
    ) {
        items(memories) { memory ->
            FeedItem(memory)
        }
    }
}

@Composable
private fun LibraryGrid(memories: List<MemoryUi>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 148.dp),
        horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm),
    ) {
        items(memories) { memory ->
            MemoryGridCard(memory)
        }
    }
}

@Composable
private fun LibraryTimeline(memories: List<MemoryUi>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm),
        contentPadding = PaddingValues(bottom = 148.dp),
    ) {
        items(memories) { memory ->
            TimelineRow(memory.time, memory.title, memory.summary, memory.icon)
        }
    }
}

@Composable
private fun FeedItem(memory: MemoryUi) {
    SurfaceCard {
        Column(modifier = Modifier.padding(MemTokens.spacing.md), verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(memory.icon, MemTokens.colors.accentMuted, MemTokens.colors.accent)
                Spacer(modifier = Modifier.width(MemTokens.spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(memory.type, color = MemTokens.colors.textSecondary, fontSize = 12.sp)
                    Text(memory.title, color = MemTokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(memory.time, color = MemTokens.colors.textTertiary, fontSize = 12.sp)
            }
            Text(memory.summary, color = MemTokens.colors.textSecondary, fontSize = 14.sp)
            TagRow(memory.tags)
            DividerLine()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                InlineAction("Ask", Icons.Rounded.AutoAwesome)
                InlineAction("Playlist", Icons.Rounded.Bookmarks)
                InlineAction("Tag", Icons.Rounded.Tag)
                InlineAction("Archive", Icons.Rounded.Archive)
            }
        }
    }
}

@Composable
private fun MemoryGridCard(memory: MemoryUi) {
    SurfaceCard {
        Column(modifier = Modifier.padding(MemTokens.spacing.md), verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
                    .clip(MemTokens.shapes.md)
                    .background(MemTokens.colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(memory.icon, contentDescription = null, tint = MemTokens.colors.accent, modifier = Modifier.size(36.dp))
            }
            Text(memory.title, color = MemTokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(memory.source, color = MemTokens.colors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun LogPanel(output: String, isExtracting: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    SurfaceCard {
        Column(modifier = Modifier.padding(MemTokens.spacing.md), verticalArrangement = Arrangement.spacedBy(MemTokens.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Extractor log", color = MemTokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (isExtracting) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                MemIconButton(Icons.Rounded.ContentCopy, "Copy", onCopy)
                MemIconButton(Icons.Rounded.Share, "Share", onShare)
            }
            SelectionContainer {
                Text(
                    text = output,
                    color = MemTokens.colors.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .verticalScroll(rememberScrollState())
                        .clip(MemTokens.shapes.md)
                        .background(MemTokens.colors.surfaceMuted)
                        .padding(MemTokens.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun JobRow(job: IngestionJobUi, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) MemTokens.spacing.xs else MemTokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = if (job.isError) Icons.Rounded.ErrorOutline else job.icon,
            background = if (job.isError) MemTokens.colors.danger.copy(alpha = 0.12f) else MemTokens.colors.accentMuted,
            tint = if (job.isError) MemTokens.colors.danger else MemTokens.colors.accent,
        )
        Spacer(modifier = Modifier.width(MemTokens.spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(job.title, color = MemTokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${job.source} - ${job.state}", color = if (job.isError) MemTokens.colors.danger else MemTokens.colors.textSecondary, fontSize = 13.sp)
        }
        Text("${(job.progress * 100).toInt()}%", color = MemTokens.colors.textTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun FloatingDock(selectedTab: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val container = when (MemTokens.dockStyle) {
        DockStyle.Solid -> MemTokens.colors.surface
        DockStyle.Translucent -> MemTokens.colors.dockBackground.copy(alpha = 0.92f)
        DockStyle.Glass -> MemTokens.colors.dockBackground
    }
    Row(
        modifier = modifier
            .shadow(if (MemTokens.dockStyle == DockStyle.Solid) 8.dp else 22.dp, MemTokens.shapes.xl)
            .clip(MemTokens.shapes.xl)
            .background(container)
            .border(BorderStroke(1.dp, MemTokens.colors.borderSubtle), MemTokens.shapes.xl)
            .padding(MemTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.xs),
    ) {
        listOf(MainTab.Mem, MainTab.Inbox).forEach { tab ->
            DockItem(tab, selectedTab == tab) { onSelect(tab) }
        }
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(MemTokens.colors.accent)
                .clickable { onSelect(MainTab.Capture) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Capture", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        DockItem(MainTab.Library, selectedTab == MainTab.Library) { onSelect(MainTab.Library) }
    }
}

@Composable
private fun DockItem(tab: MainTab, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 74.dp)
            .clip(MemTokens.shapes.lg)
            .clickable(onClick = onClick)
            .background(if (selected) MemTokens.colors.dockSelected else Color.Transparent)
            .padding(horizontal = MemTokens.spacing.sm, vertical = MemTokens.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = if (selected) MemTokens.colors.accent else MemTokens.colors.textSecondary)
        Text(tab.label, color = if (selected) MemTokens.colors.accent else MemTokens.colors.textSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = MemTokens.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Text(action, color = MemTokens.colors.accent)
        }
    }
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    container: Color = MemTokens.colors.surface,
    border: BorderStroke? = BorderStroke(1.dp, MemTokens.colors.borderSubtle),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MemTokens.shapes.lg,
        color = container,
        border = border,
        content = content,
    )
}

@Composable
private fun IconBadge(icon: ImageVector, background: Color, tint: Color, size: Dp = 42.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(MemTokens.shapes.md)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

@Composable
private fun MemIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .background(MemTokens.colors.surfaceRaised),
    ) {
        Icon(icon, contentDescription = label, tint = MemTokens.colors.textPrimary)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        enabled = enabled,
        shape = MemTokens.shapes.pill,
        colors = ButtonDefaults.buttonColors(containerColor = MemTokens.colors.accent, contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(MemTokens.spacing.xs))
        Text(label)
    }
}

@Composable
private fun MemTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: Dp,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = MemTokens.colors.textPrimary, fontSize = 16.sp),
        cursorBrush = SolidColor(MemTokens.colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .height(minHeight)
            .clip(MemTokens.shapes.lg)
            .background(MemTokens.colors.surfaceRaised)
            .border(BorderStroke(1.dp, MemTokens.colors.borderSubtle), MemTokens.shapes.lg)
            .padding(MemTokens.spacing.md),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(placeholder, color = MemTokens.colors.textTertiary)
            }
            innerTextField()
        },
    )
}

@Composable
private fun SelectablePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MemTokens.shapes.pill)
            .clickable(onClick = onClick)
            .background(if (selected) MemTokens.colors.accent else MemTokens.colors.surfaceRaised)
            .border(BorderStroke(1.dp, if (selected) MemTokens.colors.accent else MemTokens.colors.borderSubtle), MemTokens.shapes.pill)
            .padding(horizontal = MemTokens.spacing.md, vertical = MemTokens.spacing.sm),
    ) {
        Text(label, color = if (selected) Color.White else MemTokens.colors.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun TagRow(tags: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MemTokens.spacing.xs)) {
        items(tags) { tag ->
            Text(
                text = tag,
                color = MemTokens.colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(MemTokens.shapes.pill)
                    .background(MemTokens.colors.surfaceMuted)
                    .padding(horizontal = MemTokens.spacing.sm, vertical = MemTokens.spacing.xxs),
            )
        }
    }
}

@Composable
private fun InlineAction(label: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MemTokens.colors.accent, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = MemTokens.colors.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun DividerLine() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MemTokens.colors.borderSubtle),
    )
}

private fun sampleJobs() = listOf(
    IngestionJobUi("job-1", "Design review notes.pdf", "PDF", "Processing", 0.6f, Icons.AutoMirrored.Rounded.Article, false),
    IngestionJobUi("job-2", "Interview with Sarah.mp4", "Video", "Queued", 0.1f, Icons.Rounded.PlayCircle, false),
    IngestionJobUi("job-3", "Instagram reel", "Link", "Needs auth", 1f, Icons.Rounded.Link, true),
)

private fun sampleMemories() = listOf(
    MemoryUi(
        "How to Build Habits That Stick",
        "YouTube",
        "Video",
        "Practical framework for identity, environment, and systems over willpower.",
        "2h ago",
        Icons.Rounded.SmartDisplay,
        listOf("habits", "productivity", "mindset"),
    ),
    MemoryUi(
        "The Philosophy of Solaris",
        "Article",
        "Article",
        "A saved essay about consciousness, otherness, and the limits of understanding.",
        "5h ago",
        Icons.AutoMirrored.Rounded.MenuBook,
        listOf("philosophy", "science fiction"),
    ),
    MemoryUi(
        "Weekly Reflections - May 12",
        "Note",
        "Note",
        "Review of deep work, reading, outreach, and product prototype progress.",
        "1d ago",
        Icons.AutoMirrored.Rounded.Article,
        listOf("reflection", "goals"),
    ),
    MemoryUi(
        "Travel Inspiration - Portugal",
        "Screenshot",
        "Image",
        "Screenshotted ideas for coast hikes, hidden beaches, and local spots.",
        "1d ago",
        Icons.Rounded.Bookmarks,
        listOf("travel", "portugal"),
    ),
    MemoryUi(
        "Interview Insight - Design Thinking",
        "Voice",
        "Audio",
        "Key insight on empathy in design and observing behavior before asking questions.",
        "2d ago",
        Icons.Rounded.Waves,
        listOf("design", "empathy"),
    ),
)
