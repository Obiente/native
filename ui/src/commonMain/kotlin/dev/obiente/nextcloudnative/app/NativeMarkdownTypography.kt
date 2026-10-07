package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography

/** Document headings use content typography instead of display-sized page branding. */
@Composable
internal fun nativeMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.headlineSmall,
    h2 = MaterialTheme.typography.titleLarge,
    h3 = MaterialTheme.typography.titleMedium,
    h4 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
    h5 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
    h6 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
)
