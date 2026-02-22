package com.mygymapp.ui.components

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

private val youtubeRegex = Regex(
    "(?:youtube\\.com/(?:watch\\?.*v=|shorts/|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})"
)

private fun extractYouTubeId(url: String): String? =
    youtubeRegex.find(url)?.groupValues?.get(1)

private fun isYouTubeUrl(url: String) =
    url.contains("youtube.com") || url.contains("youtu.be")

private fun convertGoogleDriveUrl(url: String): String {
    Regex("drive\\.google\\.com/file/d/([^/?]+)").find(url)?.let {
        return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
    }
    Regex("drive\\.google\\.com/open\\?id=([^&]+)").find(url)?.let {
        return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
    }
    return url
}

/**
 * Shows a preview of an image URL or YouTube video.
 * - YouTube links: shows thumbnail with play button; tap opens the video in a Chrome Custom Tab
 *   (stays within the app's back stack — back button returns to the exercise screen).
 *   Note: WebView + YouTube is not viable on Android — the hardware video decoder (MediaCodec)
 *   writes decoded frames to a Surface that Compose cannot composite, causing a black screen.
 *   Chrome Custom Tabs is the reliable solution with no rendering limitations.
 * - Other URLs: converts Google Drive share links and loads the image via Coil.
 *
 * @param showErrorText if true, shows a red error message when the link can't be loaded.
 */
@Composable
fun MediaPreview(
    link: String,
    modifier: Modifier = Modifier,
    showErrorText: Boolean = false,
) {
    val trimmed = link.trim()
    if (trimmed.isBlank()) return

    if (isYouTubeUrl(trimmed)) {
        val videoId = extractYouTubeId(trimmed)
        if (videoId == null) {
            if (showErrorText) {
                Text(
                    text = "Not a valid YouTube URL.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        } else {
            YouTubeThumbnail(videoId = videoId, modifier = modifier)
        }
    } else {
        RemoteImage(
            url = convertGoogleDriveUrl(trimmed),
            modifier = modifier,
            showErrorText = showErrorText,
        )
    }
}

@Composable
private fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    showErrorText: Boolean = false,
) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = null,
        modifier = modifier.fillMaxWidth(),
        contentScale = ContentScale.FillWidth,
    ) {
        val state = painter.state
        when {
            state is AsyncImagePainter.State.Loading ||
            state is AsyncImagePainter.State.Empty -> Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            )

            state is AsyncImagePainter.State.Error && showErrorText -> Text(
                text = "Unable to load image. Check the link.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun YouTubeThumbnail(videoId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clickable {
                val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
                try {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(context, uri)
                } catch (_: Exception) {
                    // Fallback if Chrome Custom Tabs not available
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                .crossfade(true)
                .build(),
            contentDescription = "YouTube video thumbnail",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        ) {
            val state = painter.state
            when {
                state is AsyncImagePainter.State.Loading ||
                state is AsyncImagePainter.State.Empty -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent(
                    modifier = Modifier.fillMaxWidth(),
                )

                state is AsyncImagePainter.State.Error -> Box(
                    modifier = Modifier.fillMaxWidth().height(0.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White,
            )
        }
    }
}
