package com.qiqi.floatshow.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 只放本项目真正用到的图标.
 *
 * 原先依赖 `material-icons-extended`, 它把约 5000 个图标都编进 dex, 占掉安装体积的大头
 * (APK 内 classes.dex 44MB, 其中 45573 处是图标定义). 这里手写这 13 个, 体积可以忽略.
 *
 * pathData 取自 Material Icons (Apache-2.0), 均为 24x24 viewport.
 */
object AppIcons {

    val ArrowBack: ImageVector by lazy {
        build("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    }

    val Add: ImageVector by lazy { build("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }

    val ArrowUpward: ImageVector by lazy {
        build("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }

    val ArrowDownward: ImageVector by lazy {
        build("ArrowDownward", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
    }

    val Close: ImageVector by lazy {
        build(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
        )
    }

    val Favorite: ImageVector by lazy {
        build(
            "Favorite",
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        )
    }

    val Image: ImageVector by lazy {
        build(
            "Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
        )
    }

    val MoreVert: ImageVector by lazy {
        build(
            "MoreVert",
            "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
        )
    }

    val Notes: ImageVector by lazy {
        build("Notes", "M3,18h12v-2H3v2zM3,6v2h18V6H3zM3,13h18v-2H3v2z")
    }

    val Palette: ImageVector by lazy {
        build(
            "Palette",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
        )
    }

    val PlayArrow: ImageVector by lazy { build("PlayArrow", "M8,5v14l11,-7z") }

    val PushPin: ImageVector by lazy {
        build(
            "PushPin",
            "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1 1,-1v-7H19v-2h0c-1.66,0 -3,-1.34 -3,-3z"
        )
    }

    val VerticalAlignTop: ImageVector by lazy {
        build("VerticalAlignTop", "M8,11h3v10h2V11h3l-4,-4 -4,4zM4,3v2h16V3H4z")
    }

    val VerticalAlignBottom: ImageVector by lazy {
        build("VerticalAlignBottom", "M16,13h-3V3h-2v10H8l4,4 4,-4zM4,19v2h16v-2H4z")
    }

    private fun build(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 用 Compose 自带的解析器把 pathData 转成节点列表, 不自己写解析.
            // addPath 是 ImageVector.Builder 的成员(在 ui 模块), 不是 ui-graphics 的扩展
            addPath(
                pathData = addPathNodes(pathData),
                fill = SolidColor(Color.Black)
            )
        }.build()
}
