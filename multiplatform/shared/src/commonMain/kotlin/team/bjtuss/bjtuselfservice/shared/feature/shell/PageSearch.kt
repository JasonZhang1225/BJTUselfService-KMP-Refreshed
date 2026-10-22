package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 页内搜索层的状态。搜索是独立的一层：打开后列表换成结果，不受页面筛选影响，也不写入筛选偏好。
 * iOS 原生栏由 UISearchController 承载输入框，Compose 只接收文字；其它端由 [MaterialSearchHeader] 承载。
 */
@Stable
class PageSearchState {
    var active by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    /** 只在刚打开时抢一次焦点；滚动、从详情返回都不再弹键盘。 */
    internal var focusPending by mutableStateOf(false)
        private set

    fun activate() {
        if (active) return
        active = true
        focusPending = true
    }

    fun toggle() {
        if (active) close() else activate()
    }

    fun update(value: String) {
        query = value.take(80)
    }

    fun close() {
        active = false
        query = ""
        focusPending = false
    }

    internal fun consumeFocus() {
        focusPending = false
    }
}

/**
 * Material 3 搜索视图的顶部：返回箭头 + 输入框 + 清除，盖住原来的顶栏。
 * 容器色与结果区一致（surfaceContainerHigh），下方一条分隔线，对应 M3 full-screen search view。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialSearchHeader(
    state: PageSearchState,
    prompt: String,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.focusPending) {
        if (state.focusPending) {
            focus.requestFocus()
            state.consumeFocus()
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.topSystemBars)
                    .height(64.dp)
                    .padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = state::close,
                    modifier = Modifier.semantics { contentDescription = "关闭搜索" },
                ) { BackChevron() }
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = state::update,
                    onSearch = { keyboard?.hide() },
                    expanded = true,
                    onExpandedChange = { if (!it) state.close() },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                                state.close()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text(prompt, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { state.update("") },
                                modifier = Modifier.semantics { contentDescription = "清除搜索内容" },
                            ) { ClearGlyph() }
                        }
                    } else {
                        null
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * iOS 自绘顶栏（iPad、旧系统回退壳）上的搜索头：圆角灰底搜索框 + 「取消」，对应 UISearchBar 激活态。
 * iPhone 原生壳不走这里，输入框由 UISearchController 画。
 */
@Composable
internal fun AppleSearchHeader(
    state: PageSearchState,
    prompt: String,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.focusPending) {
        if (state.focusPending) {
            focus.requestFocus()
            state.consumeFocus()
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.topSystemBars)
                .height(52.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TopBarSearchIcon(
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = state::update,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = prompt }
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                                    state.close()
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                }
                if (state.query.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickable(onClickLabel = "清除搜索内容") { state.update("") },
                        contentAlignment = Alignment.Center,
                    ) { ClearGlyph() }
                }
            }
            TextButton(onClick = state::close) { Text("取消") }
        }
    }
}

/** 搜索层空态：还没输入时提示可搜什么；有输入但无结果时说明搜索不受筛选影响。 */
@Composable
internal fun PageSearchEmptyState(query: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (query.isBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                "没有找到“${query.trim()}”",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "已搜索全部作业，不受筛选影响",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClearGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(12.dp)) {
            val stroke = 1.8.dp.toPx()
            drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}
