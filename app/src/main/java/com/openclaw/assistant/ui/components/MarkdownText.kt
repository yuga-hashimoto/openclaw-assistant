package com.openclaw.assistant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Markdown(
        content = markdown,
        modifier = modifier,
        colors = markdownColor(
            text = color,
            codeText = color,
            linkText = MaterialTheme.colorScheme.primary,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineMedium.copy(color = color),
            h2 = MaterialTheme.typography.headlineSmall.copy(color = color),
            h3 = MaterialTheme.typography.titleLarge.copy(color = color),
            h4 = MaterialTheme.typography.titleMedium.copy(color = color),
            h5 = MaterialTheme.typography.titleSmall.copy(color = color),
            h6 = MaterialTheme.typography.labelLarge.copy(color = color),
            text = TextStyle(
                color = color,
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            code = TextStyle(
                color = color,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            ),
        ),
    )
}
