package com.fable.browser.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fable.browser.data.repo.FaviconStore
import com.fable.browser.data.repo.SiteIconImage
import com.fable.browser.util.UrlUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaviconEntryPoint {
    fun faviconStore(): FaviconStore
}

@Composable
fun SiteIcon(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val context = LocalContext.current
    val store = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, FaviconEntryPoint::class.java).faviconStore()
    }
    val revision by store.revision.collectAsState()
    val icon by produceState<SiteIconImage?>(initialValue = store.cached(url), url, revision) {
        value = store.load(url)
    }
    val letter = remember(url) {
        UrlUtils.displayHost(url).removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    }
    val image = icon
    if (image != null) {
        val bitmap = remember(image) { image.bitmap.asImageBitmap() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White)
        ) {
            if (image.fullBleed) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(size * 0.62f)
                        .clip(RoundedCornerShape(size * 0.1f))
                )
            }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Text(
                letter,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.4f).sp
            )
        }
    }
}
