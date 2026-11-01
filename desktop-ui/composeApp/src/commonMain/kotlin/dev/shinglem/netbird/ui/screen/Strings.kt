package dev.shinglem.netbird.ui.screen

/** 界面文案只放这里；组件里不写死字符串。只做简体中文。 */
object Strings {
    const val APP_TITLE = "NetBird"

    const val STATUS_IDLE = "未连接"
    const val STATUS_CONNECTING = "连接中"
    const val STATUS_CONNECTED = "已连接"
    const val STATUS_NEEDS_LOGIN = "需要登录"
    const val STATUS_LOGIN_FAILED = "登录失败"
    const val STATUS_SESSION_EXPIRED = "会话已过期"
    const val STATUS_UNAVAILABLE = "daemon 不可达"
    const val STATUS_UNKNOWN = "未知"

    const val LOADING = "正在读取 daemon 状态…"
    const val DASH = "—"

    const val LABEL_DAEMON_VERSION = "daemon 版本"
    const val LABEL_LOCAL_IP = "本机地址"
    const val LABEL_FQDN = "域名"
    const val LABEL_MANAGEMENT = "Management"
    const val LABEL_SIGNAL = "Signal"
    const val LABEL_PEERS = "Peer"

    const val CONNECTED = "已连"
    const val DISCONNECTED = "未连"

    const val HINT_NO_GATEWAY = "请在 daemon 启动参数里加 --enable-json-socket --json-socket tcp://127.0.0.1:8471"
    const val REFRESH = "刷新"
}
