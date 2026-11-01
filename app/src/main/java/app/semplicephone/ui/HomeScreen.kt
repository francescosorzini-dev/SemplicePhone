package app.semplicephone.ui

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
) {
    val pages = config.pages
    val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }

    // Tasto Home premuto mentre si è già nel launcher → torna alla prima pagina.
    LaunchedEffect(homePressCount) { if (homePressCount > 0) pagerState.animateScrollToPage(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        if (showSetHomeBanner) SetHomeBanner(onSetHome)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { index ->
            pages.getOrNull(index)?.let { PageGrid(it, config, launcher, onOpenSettings) }
        }

        if (pages.size > 1) PageDots(pages.size, pagerState.currentPage)
    }
}

/** Posizionamento assoluto per cella: supporta span w×h arbitrari validati dal repository. */
@Composable
private fun PageGrid(page: Page, config: LauncherConfig, launcher: TileLauncher, onOpenSettings: () -> Unit) {
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
                modifier = Modifier
                    .offset(x = (cellW + Gap) * t.col, y = (cellH + Gap) * t.row)
                    .size(width = cellW * t.w + Gap * (t.w - 1), height = cellH * t.h + Gap * (t.h - 1)),
                onSecretTap = onOpenSettings,
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Pagina ${current + 1} di $count" },
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
            "Tocca qui per usare SemplicePhone come schermata Home",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
