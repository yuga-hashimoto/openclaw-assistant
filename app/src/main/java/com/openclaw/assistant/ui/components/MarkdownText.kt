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
            h1 = MaterialTheme.typography.headlineMedium.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 28.sp
            ),
            h2 = MaterialTheme.typography.headlineSmall.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 24.sp
            ),
            h3 = MaterialTheme.typography.titleLarge.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 20.sp
            ),
            h4 = MaterialTheme.typography.titleMedium.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 18.sp
            ),
            h5 = MaterialTheme.typography.titleSmall.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 16.sp
            ),
            h6 = MaterialTheme.typography.labelLarge.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 14.sp
            ),
            text = TextStyle(
                color = color,
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            code = TextStyle(
                color = color,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            quote = TextStyle(
                color = color.copy(alpha = 0.8f),
                fontSize = 16.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            ),
            paragraph = TextStyle(
                color = color,
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            list = TextStyle(
                color = color,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        ),
    )
}
