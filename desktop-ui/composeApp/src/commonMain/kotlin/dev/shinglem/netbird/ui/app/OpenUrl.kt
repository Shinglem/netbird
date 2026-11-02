package dev.shinglem.netbird.ui.app

/** 用系统默认浏览器打开 URL；打不开时返回 false，由调用方提示用户手动访问。 */
expect fun openUrl(url: String): Boolean
