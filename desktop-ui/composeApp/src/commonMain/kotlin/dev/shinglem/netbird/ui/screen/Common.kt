package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.theme.appMonoFontFamily
import kotlin.math.round

/** 状态语义色；具体颜色在 [toneColor] 里映射到 M3 主题。 */
enum class StatusTone { OK, WARN, BAD, NEUTRAL }

/** 带标题的卡片区块。各功能页的主体都由它拼起来，保持间距一致。 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            content()
        }
    }
}

/** 左标签右值的一行；标签列固定宽度，多个 [InfoRow] 才能对齐。 */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value.ifBlank { Strings.DASH },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) appMonoFontFamily() else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 左标签右开关的一行，两个设置页共用。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    invert: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = if (invert) !checked else checked,
            onCheckedChange = { onChange(if (invert) !it else it) },
            enabled = enabled,
        )
    }
}

/** 操作失败的统一展示：daemon 的原文直接给出去，不做二次包装。 */
@Composable
fun ErrorBanner(message: String?) {
    if (message.isNullOrBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun StatusPill(text: String, tone: StatusTone) {
    val color = toneColor(tone)
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.OK -> MaterialTheme.colorScheme.primary
    StatusTone.WARN -> MaterialTheme.colorScheme.tertiary
    StatusTone.BAD -> MaterialTheme.colorScheme.error
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** daemon 的状态名 → 文案。未知值原样显示，方便上游加状态时被发现。 */
fun statusText(raw: String): String = when (raw) {
    "Idle" -> Strings.STATUS_IDLE
    "Connecting" -> Strings.STATUS_CONNECTING
    "Connected" -> Strings.STATUS_CONNECTED
    "NeedsLogin" -> Strings.STATUS_NEEDS_LOGIN
    "LoginFailed" -> Strings.STATUS_LOGIN_FAILED
    "SessionExpired" -> Strings.STATUS_SESSION_EXPIRED
    "" -> Strings.STATUS_UNKNOWN
    else -> raw
}

fun statusTone(raw: String): StatusTone = when (raw) {
    "Connected" -> StatusTone.OK
    "Connecting" -> StatusTone.WARN
    "NeedsLogin" -> StatusTone.WARN
    "LoginFailed", "SessionExpired" -> StatusTone.BAD
    else -> StatusTone.NEUTRAL
}

/** 是不是「未登录」这一类状态（决定要不要展示登录区）。 */
fun needsLogin(raw: String): Boolean =
    raw == "NeedsLogin" || raw == "LoginFailed" || raw == "SessionExpired"

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return Strings.DASH
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val tenths = round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[index]}"
}

/**
 * protobuf Timestamp（RFC3339，UTC）→ 可读文本。
 *
 * 不做时区换算：本项目解析到 kotlinx-datetime 的 0.6 兼容变体，`Instant` 那套 API
 * 已被该变体标为弃用。时间戳只用来判断"最近一次握手 / 事件是什么时候"，
 * 末尾明确标注 UTC 比换成本地时间更重要。
 */
fun formatTimestamp(raw: String?): String {
    if (raw.isNullOrBlank()) return Strings.DASH
    val text = raw.trim()
    val separator = text.indexOf('T')
    if (separator < 0) return text
    val date = text.substring(0, separator)
    val time = text.substring(separator + 1).substringBefore('.').removeSuffix("Z")
    return "$date $time UTC"
}

/** protobuf Duration 字符串（`"0.012s"`）→ `12 ms`。 */
fun formatLatency(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "0s") return Strings.DASH
    val seconds = text.removeSuffix("s").toDoubleOrNull() ?: return text
    val millis = (seconds * 1000).toLong()
    return if (millis < 1) "<1 ms" else "$millis ms"
}
