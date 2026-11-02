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

    const val REFRESH = "刷新"

    // ---- 多配置 ----
    const val START = "启动"
    const val STOP = "停止"
    const val START_ALL = "全部启动"
    const val STOP_ALL = "全部停止"
    const val NEW_PROFILE = "新建配置"
    const val DELETE_PROFILE = "删除"
    const val CREATE = "创建"
    const val CANCEL = "取消"

    const val NO_PROFILE = "还没有配置"
    const val NO_PROFILE_HINT = "新建一个配置，或把已有的 profiles 目录放进下面的位置："
    const val PROFILES_ROOT_LABEL = "配置目录"

    const val DAEMON_STOPPED = "未运行"
    const val DAEMON_STARTING = "启动中"
    const val DAEMON_RUNNING = "运行中"
    const val DAEMON_STOPPING = "停止中"
    const val DAEMON_FAILED = "启动失败"
    const val DAEMON_STOPPED_HINT = "这个实例的 daemon 还没运行：点顶部「启动」把它拉起来。"

    const val LABEL_INTERFACE = "接口"
    const val LABEL_WG_PORT = "WireGuard 端口"
    const val LABEL_GATEWAY = "网关"
    const val LABEL_PROFILE_MGMT = "管理服务器"

    const val LOG_TITLE = "daemon 日志"
    const val LOG_EMPTY = "（暂无输出）"

    const val NOT_ELEVATED_TITLE = "当前不是管理员"
    const val NOT_ELEVATED_BODY_PACKAGED =
        "启动时已请求管理员权限，但被拒绝或失败。daemon 要创建 wintun 网卡、改路由与 DNS，" +
            "普通权限下无法启动；请退出后重新打开，并在 UAC 提示里选「是」。"
    const val NOT_ELEVATED_BODY_DEV =
        "当前不是管理员。开发态请用管理员终端运行 ./gradlew :composeApp:run；" +
            "打包产物双击启动时会自动请求 UAC。"

    const val BINARY_MISSING_TITLE = "找不到 netbird 可执行文件"
    const val BINARY_MISSING_BODY = "期望路径："

    const val FIELD_NAME = "配置名（小写字母、数字、连字符）"
    const val FIELD_MANAGEMENT = "管理服务器地址（https://host:port，可留空后注册）"
    const val CREATE_FAILED = "创建失败"

    const val CONFIRM_DELETE_TITLE = "删除这个配置？"
    const val CONFIRM_DELETE_BODY = "会停止它的 daemon 并删除整个配置目录（含注册状态与私钥）。"

    const val TRAY_TOOLTIP = "NetBird UI"
    const val TRAY_SHOW = "显示窗口"
    const val TRAY_START_ALL = "全部启动"
    const val TRAY_STOP_ALL = "全部停止"
    const val TRAY_QUIT = "退出（停止所有配置）"

    // ---- 新界面：导航 ----
    const val NAV_CONNECT = "连接"
    const val NAV_PEERS = "Peers"
    const val NAV_NETWORKS = "网络"
    const val NAV_SETTINGS = "设置"
    const val NAV_LOGS = "日志"

    const val INSTANCE = "实例"
    const val NO_INSTANCE = "没有实例"
    const val PROFILE_AUTOSTART = "随界面启动"

    // ---- 桌面设置（顶部入口，全局，不随实例切换）----
    const val DESKTOP_TITLE = "桌面"
    const val DESKTOP_SECTION_APPEARANCE = "外观"
    const val DESKTOP_THEME = "配色"
    const val DESKTOP_THEME_HINT =
        "改完立即写入 ui.custom.json，升级替换目录后仍在。网络参数不在这里，走「设置」页。"
    const val DESKTOP_MODE = "明暗模式"
    const val DESKTOP_SECTION_STARTUP = "启动与托盘"
    const val DESKTOP_AUTOSTART = "开机自启"
    const val DESKTOP_AUTOSTART_ON = "已启用：登录时以管理员权限启动界面，不弹 UAC。"
    const val DESKTOP_AUTOSTART_OFF = "未启用。打开后会创建计划任务 NetBirdUI。"
    const val DESKTOP_AUTOSTART_DEV =
        "开发态不能在界面里安装自启动：没有 jpackage 启动器，也拿不到稳定的启动命令。" +
            "请用 desktop-ui/scripts/install-autostart-task.ps1 安装。"
    const val DESKTOP_AUTOSTART_NEEDS_ADMIN =
        "写入计划任务需要管理员权限。请用管理员身份运行界面后再试。"
    const val DESKTOP_AUTOSTART_FAILED = "自启动设置失败"
    const val DESKTOP_AUTOSTART_LOADING = "正在读取…"
    const val DESKTOP_CLOSE_TO_TRAY = "关闭窗口时收进托盘"
    const val DESKTOP_CLOSE_TO_TRAY_HINT =
        "关掉后点关闭按钮会直接退出界面，并停掉它拉起的全部 daemon。"
    const val DESKTOP_TRAY_HINT =
        "关闭窗口只会收进托盘，daemon 继续运行；真正退出走托盘菜单的「退出（停止所有配置）」，那时所有配置都会停掉。"
    const val DESKTOP_SECTION_ABOUT = "关于"
    const val DESKTOP_CONFIG_FILE = "用户配置"
    const val DESKTOP_APP_VERSION = "界面版本"
    const val DESKTOP_APP_VERSION_DEV = "开发态"
    const val DESKTOP_PROFILES_ROOT = "配置目录"
    const val DESKTOP_BINARY = "daemon 可执行文件"
    const val DESKTOP_ELEVATED = "当前权限"
    const val DESKTOP_ELEVATED_YES = "管理员"
    const val DESKTOP_ELEVATED_NO = "普通用户"

    // ---- 连接页 ----
    const val CONNECT = "连接"
    const val DISCONNECT = "断开"
    const val CONNECTING_HINT = "正在连接，进度会实时刷新。"
    const val NOT_CONNECTED_HINT = "当前未连接。点击「连接」开始建立覆盖网络。"
    const val NEEDS_LOGIN_HINT = "这个实例还没登录管理服务器，先完成授权。"
    const val LOGIN_SSO = "用浏览器登录（SSO）"
    const val LOGIN_SETUP_KEY = "用 Setup Key 登录"
    const val SETUP_KEY_LABEL = "Setup Key"
    const val MANAGEMENT_OVERRIDE_LABEL = "管理服务器地址（可留空沿用配置）"
    const val LOGIN_BUSY = "正在登录…"
    const val LOGIN_SLOW_HINT =
        "daemon 连 management 可能需要几秒到几十秒；如果 setup key 无效，它会在后台退避重试。" +
            "不想等就点「取消」，然后确认 key 是否过期或已被使用。"
    const val LOGIN_WAITING = "等待浏览器授权…"
    const val LOGIN_OPENING = "已打开浏览器，请在页面里完成授权。"
    const val LOGIN_CODE_LABEL = "授权码"
    const val LOGIN_URL_LABEL = "如果没有自动打开浏览器，请手动访问："
    const val LOGOUT = "退出登录"
    const val SESSION_EXPIRING = "SSO 会话将于此时到期："
    const val SECTION_LOCAL_PEER = "本机"
    const val SECTION_CONNECTIONS = "连接状态"
    const val LABEL_IPV6 = "IPv6 地址"
    const val LABEL_INTERFACE_KIND = "内核接口"
    const val LABEL_RELAYS = "Relay"
    const val LABEL_DNS_GROUPS = "DNS 分组"
    const val LABEL_SSH = "SSH 服务"
    const val LABEL_ROSENPASS = "Rosenpass"
    const val ENABLED = "已启用"
    const val DISABLED = "未启用"
    const val YES = "是"
    const val NO = "否"

    // ---- Peers ----
    const val PEERS_EMPTY = "当前没有 peer。连接成功后这里会列出网络里的其他节点。"
    const val PEERS_SEARCH = "搜索名称、IP 或域名"
    const val PEER_STATUS = "状态"
    const val PEER_LATENCY = "延迟"
    const val PEER_RX = "接收"
    const val PEER_TX = "发送"
    const val PEER_RELAYED = "经 Relay"
    const val PEER_DIRECT = "直连"
    const val PEER_LAST_HANDSHAKE = "上次握手"
    const val PEER_PUBKEY = "公钥"
    const val PEER_ICE_LOCAL = "本地 ICE"
    const val PEER_ICE_REMOTE = "对端 ICE"
    const val PEER_ENDPOINTS = "端点"
    const val PEER_NETWORKS = "网络"
    const val PEER_RELAY_ADDRESS = "Relay 地址"
    const val COLLAPSE = "收起"

    // ---- 网络 ----
    const val NETWORKS_EMPTY = "没有可选网络。"
    const val NETWORKS_NEED_CONNECTION = "连接后才能读取网络列表。"
    const val NETWORKS_EXIT_NODE = "出口节点"
    const val NETWORKS_OTHER = "其他网络"
    const val NETWORKS_HINT = "勾选即让这个实例路由该网段；出口节点会把默认路由交给对端。"
    const val NETWORKS_RELOAD = "重新读取"
    const val NETWORKS_DOMAINS = "域名"

    // ---- 设置 ----
    const val SETTINGS_LOADING = "正在读取配置…"
    const val SETTINGS_NEED_RUNNING = "启动这个实例后，可以在这里读取和修改它的配置。"
    const val SETTINGS_RESTART_HINT = "接口名、WireGuard 端口与 MTU 需要重启该实例后生效。"
    const val SETTINGS_MDM_HINT = "以下字段由 MDM 策略强制，界面只读。"
    const val SETTINGS_SAVE = "保存"
    const val SETTINGS_SAVED = "已保存"
    const val SETTINGS_UNCHANGED = "没有改动"
    const val SETTINGS_SECTION_TUNNEL = "隧道"
    const val SETTINGS_SECTION_ROUTES = "路由与 DNS"
    const val SETTINGS_SECTION_BEHAVIOR = "行为"
    const val SETTINGS_SECTION_DEBUG = "诊断"
    const val SETTINGS_SECTION_INFO = "运行信息"
    const val SETTINGS_LOCKED = "当前策略禁止修改设置（disableUpdateSettings）。"
    const val SWITCH_DISABLE_DNS = "禁用 DNS 管理"
    const val SWITCH_DISABLE_CLIENT_ROUTES = "禁用客户端路由"
    const val SWITCH_DISABLE_SERVER_ROUTES = "禁用服务端路由"
    const val SWITCH_BLOCK_LAN = "禁止访问本地网络"
    const val SWITCH_AUTO_CONNECT = "启动时自动连接"
    const val SWITCH_LAZY_CONNECTION = "按需连接（lazy connection）"
    const val SWITCH_BLOCK_INBOUND = "拒绝入站连接"
    const val SWITCH_NETWORK_MONITOR = "网络变化监视"
    const val SWITCH_DISABLE_NOTIFICATIONS = "禁用系统通知"
    const val SWITCH_DISABLE_IPV6 = "禁用 IPv6"
    const val SWITCH_ALLOW_SSH_SERVER = "允许 SSH 服务"
    const val SETTINGS_SSH_SERVER_HINT =
        "保持关闭才能在本界面直接注销：SSH 服务开启时，daemon 把注销当成特权操作，" +
            "而回环网关无法验证调用方身份。management 侧未启用 SSH 时，关闭它没有副作用。"

    // ---- 注销被 SSH 闸门拦住时的一键修复 ----
    const val LOGOUT_SSH_BLOCKED_HINT =
        "退出登录被拒绝：这个实例的配置把「允许 SSH 服务」记为开启，" +
            "daemon 因此把注销当成特权操作。下面的按钮会关掉这条声明、重启实例（会短暂断开）再注销。"
    const val LOGOUT_DISABLE_SSH_AND_RETRY = "关闭「允许 SSH 服务」，重启并重试注销"
    const val LABEL_INTERFACE_NAME = "接口名"
    const val LABEL_MTU = "MTU"
    const val LABEL_WG_PORT_SETTING = "WireGuard 端口"
    const val LABEL_CONFIG_FILE = "配置文件"
    const val LABEL_LOG_FILE = "日志文件"
    const val LABEL_MANAGEMENT_URL = "管理服务器"
    const val LABEL_LOG_LEVEL = "日志级别"
    const val MDM_MANAGED = "MDM 强制"
    const val SSH_UNAVAILABLE_TITLE = "SSH 设置不在这里"
    const val SSH_UNAVAILABLE_BODY =
        "经回环 TCP 网关调用时不带调用方身份，daemon 会拒绝改 SSH 相关配置。" +
            "需要时请用管理员命令行执行 netbird up --help 查看相关参数。"

    // ---- 日志 ----
    const val LOGS_EVENTS = "事件"
    const val LOGS_DAEMON = "daemon 输出"
    const val LOGS_EVENTS_EMPTY = "（暂无事件）"
    const val LOGS_AUTOSCROLL = "自动滚动"
    const val LOGS_CLEAR = "清空显示"
    const val LOGS_CLEAR_HINT = "「清空显示」只清空 daemon 输出面板，不影响日志文件，也不会清掉事件历史。"

    fun gateway(baseUrl: String): String = baseUrl.removePrefix("http://")
}
