package dev.shinglem.netbird.ui.app

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 应用图标：官方 NetBird mark 的蓝色版，PNG 由 `scripts/make-icons.ps1` 从
 * `icons/netbird-blue.svg` 生成。
 *
 * 只接受脚本产出的那几个尺寸（16/24/32/48/64/128/256）。资源缺失说明构建产物不完整，
 * 这里直接失败，而不是悄悄退回一个不一样的占位图标。
 */
internal fun appIconImage(size: Int = 64): BufferedImage {
    val resource = "/icons/netbird-$size.png"
    val stream = AppIconLoader::class.java.getResourceAsStream(resource)
        ?: error("缺少图标资源 $resource：先跑 desktop-ui/scripts/make-icons.ps1")
    return stream.use { ImageIO.read(it) ?: error("无法解码图标资源 $resource") }
}

private object AppIconLoader
