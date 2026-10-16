package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.ui.theme.LocalAppDarkTheme

@Composable
fun MemoraWordmark(modifier: Modifier = Modifier) {
    val dark = LocalAppDarkTheme.current
    Image(
        painter = painterResource(if (dark) R.drawable.memora_wordmark_dark else R.drawable.memora_wordmark_light),
        contentDescription = "Memora",
        modifier = modifier.testTag(if (dark) "memora_wordmark_dark" else "memora_wordmark_light"),
        alignment = Alignment.CenterStart,
        contentScale = ContentScale.Fit
    )
}
