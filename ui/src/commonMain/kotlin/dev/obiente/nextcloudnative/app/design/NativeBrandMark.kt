package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/** Product identity is separate from cloud, sync and file status icons. */
@Composable
internal fun NativeBrandMark(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    val vector = remember(ink) { nativeBrandVector(ink) }
    Image(painter = rememberVectorPainter(vector), contentDescription = contentDescription, modifier = modifier)
}
