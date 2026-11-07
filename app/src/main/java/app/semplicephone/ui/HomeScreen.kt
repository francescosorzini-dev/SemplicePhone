package app.semplicephone.ui

import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import app.semplicephone.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import app.semplicephone.phone.Badges
import app.semplicephone.phone.PhoneGuard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.semplicephone.launch.TileLauncher
import app.semplicephone.model.LauncherConfig
import app.semplicephone.model.Page

private val Gap = 12.dp

@Composable
fun HomeScreen(
    config: LauncherConfig,
    launcher: TileLauncher,
    homePressCount: Int,
    showSetHomeBanner: Boolean,
    onSetHome: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Messaggio della famiglia non ancora letto: riquadro in alto finché non si tocca «Ho letto». */
    message: app.semplicephone.remote.FamilyMessage? = null,
    onOpenMessage: () -> Unit = {},
) {
    val pages = config.pages
    val counts by Badges.counts.collectAsState()
    val badgeCounts = if (config.phone.badges && app.semplicephone.BuildConfig.NOTIFICATIONS) counts else emptyMap()
    val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }

    // Tasto Home premuto mentre si è già nel launcher → torna alla prima pagina.
    LaunchedEffect(homePressCount) { if (homePressCount > 0) pagerState.animateScrollToPage(0) }

    // Modalità comodino: in carica e senza tocchi per un minuto → orologio grande, schermo acceso.
    val battery by PhoneGuard.battery.collectAsState()
    val bedsideOn = config.phone.bedsideClock && battery?.charging == true
    var lastTouch by remember { androidx.compose.runtime.mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var bedside by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(bedsideOn, lastTouch) {
        bedside = false
        if (bedsideOn) { kotlinx.coroutines.delay(60_000); bedside = true }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(bedsideOn) {
        view.keepScreenOn = bedsideOn
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    lastTouch = android.os.SystemClock.elapsedRealtime()
                }
            }
        },
    ) {
    // Tema: alto contrasto sempre, oppure scuro la sera (dalle 21 alle 7).
    val hour by androidx.compose.runtime.produceState(java.time.LocalTime.now().hour) {
        while (true) { kotlinx.coroutines.delay(60_000); value = java.time.LocalTime.now().hour }
    }
    val look = TileLook(
        contrast = config.phone.theme == "contrast",
        night = config.phone.theme == "night" && (hour >= 21 || hour < 7),
        holdToOpen = config.phone.holdToOpen,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalTileLook provides look) {
    Column(
        Modifier
            .fillMaxSize()
            .background(if (look.contrast) Color.Black else if (look.night) Color(0xFF121212) else Palette.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        if (showSetHomeBanner) SetHomeBanner(onSetHome)
        message?.let { MessageBanner(it, onOpenMessage) }
        val threshold = config.phone.batteryAlertPercent.takeIf { it > 0 } ?: 15
        battery?.let { b -> if (!b.charging && b.level in 0..threshold) BatteryBanner(b.level) }
        ConnectivityBanner()

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { index ->
            pages.getOrNull(index)?.let { PageGrid(it, config, launcher, onOpenSettings, badgeCounts) }
        }

        if (pages.size > 1) PageDots(pages.size, pagerState.currentPage)
    }
    }
    if (bedside) BedsideClock { bedside = false; lastTouch = android.os.SystemClock.elapsedRealtime() }
    }
}

/** Orologio da comodino: grande, grigio su nero, luminosità bassa. Un tocco torna alla Home. */
@Composable
private fun BedsideClock(onDismiss: () -> Unit) {
    var now by remember { androidx.compose.runtime.mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = java.time.LocalDateTime.now()
        }
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(Unit) {
        val w = activity?.window
        val before = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 0.08f }
        onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = before ?: -1f } }
    }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val date = now.format(java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale))
        .replaceFirstChar { it.titlecase(locale) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), color = Color(0xFFB8B8B8), fontSize = 120.sp, fontWeight = FontWeight.Bold)
            Text(date, color = Color(0xFF8A8A8A), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Posizionamento assoluto per cella: supporta span w×h arbitrari validati dal repository. */
@Composable
private fun PageGrid(
    page: Page,
    config: LauncherConfig,
    launcher: TileLauncher,
    onOpenSettings: () -> Unit,
    badgeCounts: Map<String, Int>,
) {
    val packages = remember(page.tiles) { page.tiles.associate { it.id to launcher.badgePackages(it) } }
    val taps = remember { ArrayDeque<Long>() }
    val open by rememberUpdatedState(onOpenSettings)
    BoxWithConstraints(Modifier.fillMaxSize().secretTaps(taps) { open() }) {
        val cellW = (maxWidth - Gap * (page.cols - 1)) / page.cols
        val cellH = (maxHeight - Gap * (page.rows - 1)) / page.rows
        page.tiles.forEach { t ->
            TileView(
                tile = t,
                sos = config.sos,
                launcher = launcher,
                modifier0 = Modifier
                    .offset(x = (cellW + Gap) * t.col, y = (cellH + Gap) * t.row)
                    .size(width = cellW * t.w + Gap * (t.w - 1), height = cellH * t.h + Gap * (t.h - 1)),
                onSecretTap = onOpenSettings,
                badge = packages[t.id].orEmpty().sumOf { badgeCounts[it] ?: 0 },
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    val desc = stringResource(R.string.home_page_of, current + 1, count)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .then(
                        if (i == current) Modifier.background(Palette.Ink)
                        else Modifier.border(2.dp, Palette.Ink, CircleShape),
                    ),
            )
        }
    }
}

@Composable
private fun BatteryBanner(level: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFA84300))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            stringResource(R.string.home_battery_low, level),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MessageBanner(m: app.semplicephone.remote.FamilyMessage, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1F4FA8))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            (if (m.from.isNotBlank()) stringResource(R.string.message_from, m.from) else stringResource(R.string.message_title)) +
                ": " + m.text.take(60) + (if (m.text.length > 60) "…" else "") + "\n" + stringResource(R.string.message_tap),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
        )
    }
}

@Composable
private fun SetHomeBanner(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Ink)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.home_set_default),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Modalità aereo accesa o internet spento per errore: si vede in Home, si sente una volta,
 * e un pulsante grande apre la pagina giusta per sistemare.
 */
@Composable
private fun ConnectivityBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by androidx.compose.runtime.produceState(0) {
        while (true) {
            val airplane = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            value = when { airplane -> 1; !online -> 2; else -> 0 }
            kotlinx.coroutines.delay(10_000)
        }
    }
    if (state == 0) return
    val text = androidx.compose.ui.res.stringResource(if (state == 1) R.string.conn_airplane else R.string.conn_offline)
    // Si dice una volta per ogni volta che succede (e dopo 15 s, per non confondere un attimo senza rete).
    androidx.compose.runtime.LaunchedEffect(state) { kotlinx.coroutines.delay(15_000); app.semplicephone.ui.Voice.speak(context, text) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFE0B2))
            .border(3.dp, Palette.Sos, RoundedCornerShape(16.dp))
            .clickable {
                val action = if (state == 1) android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS else android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                runCatching { context.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Palette.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(androidx.compose.ui.res.stringResource(R.string.conn_fix), color = Palette.Sos, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
