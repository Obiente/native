// Generated from design/app-icon/native-mark.svg by tools/generate-native-icons.mjs.
package dev.obiente.nextcloudnative.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal fun nativeBrandVector(ink: Color): ImageVector = ImageVector.Builder(
    name = "nati.ve", defaultWidth = 32.dp, defaultHeight = 28.dp,
    viewportWidth = 128f, viewportHeight = 112f,
).addPath(PathParser().parsePathString("M8 96V44a36 36 0 0 1 72 0v52H58V44a14 14 0 0 0-28 0v52Z").toNodes(), fill = SolidColor(ink))
    .addPath(PathParser().parsePathString("M96 84a12 12 0 1 0 24 0a12 12 0 1 0-24 0").toNodes(), fill = SolidColor(Color(0xFF36C69C)))
    .build()
