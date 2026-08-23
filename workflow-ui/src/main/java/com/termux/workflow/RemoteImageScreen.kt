package com.termux.workflow

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject


private const val MAX_DECODED_IMAGE_PIXELS = 12_000_000L
private const val MAX_DECODED_IMAGE_DIMENSION = 4096


internal fun resolveRemoteImagePath(basePath: String, inputPath: String): String {
    val path = normalizeRemoteImagePath(inputPath)
    if (path.isEmpty() || path.startsWith('/')) return path
    val base = normalizeRemoteImagePath(basePath).trimEnd('/')
    if (base.isEmpty()) return path
    val relativePath = path.trimStart('/')
    val baseName = base.substringAfterLast('/')
    val normalizedRelativePath = relativePath.removePrefix("$baseName/")
    return "$base/$normalizedRelativePath"
}


internal fun needsImageWorkspaceRefresh(enabled: Boolean, workspaces: List<ActivationTarget>): Boolean =
    enabled && workspaces.isNotEmpty() && workspaces.none { !it.archived && it.directory.isNotBlank() }


internal fun imageWorkspaceOptions(
    workspaces: List<ActivationTarget>,
    activeIssueIds: Set<String>,
    currentWorkspaceId: String?,
): List<ActivationTarget> = workspaces.asSequence()
    .filter { !it.archived && it.directory.isNotBlank() }
    .filter { workspace ->
        workspace.id == currentWorkspaceId || workspace.issueId?.let(activeIssueIds::contains) == true
    }
    .sortedWith(
        compareByDescending<ActivationTarget> { it.id == currentWorkspaceId }
            .thenByDescending { it.issueId?.toIntOrNull() ?: -1 }
            .thenBy { it.name.lowercase() },
    )
    .distinctBy { it.directory.trimEnd('/') }
    .toList()


@Composable
internal fun RemoteImageScreen(
    enabled: Boolean,
    workspaces: List<ActivationTarget>,
    activeIssueIds: Set<String>,
    currentWorkspaceId: String?,
    onRefreshWorkspaces: () -> Unit,
    loadImage: suspend (String) -> RemoteImage,
    loadDirectory: suspend (String, String) -> RemoteImageDirectory,
) {
    var path by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<ImageViewerState>(ImageViewerState.Empty) }
    var selectedWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var browserOpen by remember { mutableStateOf(false) }
    var browserPath by remember { mutableStateOf("") }
    var browserState by remember { mutableStateOf<ImageBrowserState>(ImageBrowserState.Empty) }
    val scope = rememberCoroutineScope()
    val workspaceOptions = imageWorkspaceOptions(workspaces, activeIssueIds, currentWorkspaceId)
    val currentWorkspace = workspaceOptions.firstOrNull { it.id == currentWorkspaceId }
    val selectedWorkspace = workspaceOptions.firstOrNull { it.id == selectedWorkspaceId }

    LaunchedEffect(enabled, workspaces) {
        if (needsImageWorkspaceRefresh(enabled, workspaces)) onRefreshWorkspaces()
    }

    LaunchedEffect(workspaceOptions, currentWorkspaceId) {
        if (selectedWorkspaceId == null || workspaceOptions.none { it.id == selectedWorkspaceId }) {
            selectedWorkspaceId = currentWorkspace?.id ?: workspaceOptions.firstOrNull()?.id
        }
    }

    LaunchedEffect(selectedWorkspaceId) {
        browserOpen = false
        browserPath = ""
        browserState = ImageBrowserState.Empty
    }

    fun browse(directoryPath: String) {
        val basePath = selectedWorkspace?.directory.orEmpty()
        if (!enabled || basePath.isBlank() || browserState is ImageBrowserState.Loading) return
        browserPath = directoryPath
        browserState = ImageBrowserState.Loading
        scope.launch {
            browserState = try {
                ImageBrowserState.Ready(loadDirectory(basePath, directoryPath))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ImageBrowserState.Failed(error.imageViewerMessage())
            }
        }
    }

    fun openImage(imagePath: String = path) {
        val requestedPath = resolveRemoteImagePath(selectedWorkspace?.directory.orEmpty(), imagePath)
        if (!enabled || requestedPath.isEmpty() || state is ImageViewerState.Loading) return
        state = ImageViewerState.Loading
        scope.launch {
            state = try {
                ImageViewerState.Ready(prepareRemoteImage(loadImage(requestedPath)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ImageViewerState.Failed(error.imageViewerMessage())
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "PMGR IMAGE VIEWER",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
        )
        Text(
            text = "Select a workspace, then enter an image path relative to it. Absolute paths also work.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            fontSize = 12.sp,
        )
        WorkspaceBasePicker(
            workspaces = workspaceOptions,
            selected = selectedWorkspace,
            current = currentWorkspace,
            enabled = enabled && state !is ImageViewerState.Loading,
            onSelect = { selectedWorkspaceId = it.id },
        )
        OutlinedButton(
            onClick = {
                browserOpen = true
                browse("")
            },
            enabled = enabled && selectedWorkspace != null && browserState !is ImageBrowserState.Loading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
        ) {
            Text("BROWSE WORKSPACE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && state !is ImageViewerState.Loading,
            label = { Text("Relative image path") },
            placeholder = { Text("assets/preview.png") },
            singleLine = true,
            shape = RoundedCornerShape(0.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { openImage() }),
        )
        val resolvedPath = resolveRemoteImagePath(selectedWorkspace?.directory.orEmpty(), path)
        if (resolvedPath.isNotBlank()) {
            Text(
                text = "Resolved: $resolvedPath",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = { openImage() },
            enabled = enabled && path.isNotBlank() && state !is ImageViewerState.Loading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("OPEN IMAGE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }

        when (val current = state) {
            ImageViewerState.Empty -> EmptyImagePanel(
                message = if (enabled) "Enter an image path to begin." else "Select a pmgr host profile first.",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ImageViewerState.Loading -> LoadingImagePanel(Modifier.fillMaxWidth().weight(1f))
            is ImageViewerState.Failed -> EmptyImagePanel(
                message = current.message,
                modifier = Modifier.fillMaxWidth().weight(1f),
                error = true,
            )
            is ImageViewerState.Ready -> LoadedImageViewer(current.image)
        }
    }

    if (browserOpen && selectedWorkspace != null) {
        ImageBrowserDialog(
            workspace = selectedWorkspace,
            requestedPath = browserPath,
            state = browserState,
            onNavigate = ::browse,
            onImage = { entry ->
                path = entry.path
                browserOpen = false
                openImage(entry.path)
            },
            onDismiss = { browserOpen = false },
        )
    }
}


@Composable
private fun ImageBrowserDialog(
    workspace: ActivationTarget,
    requestedPath: String,
    state: ImageBrowserState,
    onNavigate: (String) -> Unit,
    onImage: (RemoteImageEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val directory = (state as? ImageBrowserState.Ready)?.directory
    Dialog(
        onDismissRequest = {
            directory?.parentPath?.let(onNavigate) ?: onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "WORKSPACE IMAGE BROWSER",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                )
                Text(
                    text = workspace.name.ifBlank { workspace.id },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = directory?.path?.ifBlank { "/" } ?: requestedPath.ifBlank { "/" },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { directory?.parentPath?.let(onNavigate) },
                        enabled = directory?.parentPath != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(0.dp),
                    ) {
                        Text("UP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(0.dp),
                    ) {
                        Text("CLOSE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }

                when (state) {
                    ImageBrowserState.Empty -> EmptyImagePanel(
                        message = "Open the workspace browser to list folders and images.",
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    ImageBrowserState.Loading -> LoadingImagePanel(Modifier.fillMaxWidth().weight(1f))
                    is ImageBrowserState.Failed -> EmptyImagePanel(
                        message = state.message,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        error = true,
                    )
                    is ImageBrowserState.Ready -> {
                        if (state.directory.entries.isEmpty()) {
                            EmptyImagePanel(
                                message = "No folders or supported images in this directory.",
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(state.directory.entries, key = { it.path }) { entry ->
                                    OutlinedButton(
                                        onClick = {
                                            if (entry.isDirectory) onNavigate(entry.path) else onImage(entry)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(0.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.name,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = if (entry.isDirectory) "DIRECTORY" else "IMAGE  ${formatImageBytes(entry.sizeBytes)}",
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                            )
                                        }
                                        Text(
                                            text = if (entry.isDirectory) ">" else "OPEN",
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                        if (state.directory.truncated) {
                            Text(
                                text = "Showing the first 500 folders and images.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun WorkspaceBasePicker(
    workspaces: List<ActivationTarget>,
    selected: ActivationTarget?,
    current: ActivationTarget?,
    enabled: Boolean,
    onSelect: (ActivationTarget) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled && workspaces.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Text(
                        text = selected?.name ?: "No workspace available",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("▾")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    workspaces.forEach { workspace ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(workspace.name.ifBlank { workspace.id }, maxLines = 1)
                                    Text(
                                        workspace.directory,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = {
                                onSelect(workspace)
                                expanded = false
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { current?.let(onSelect) },
                enabled = enabled && current != null && selected?.id != current.id,
                shape = RoundedCornerShape(0.dp),
            ) {
                Text("CURRENT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        if (selected != null) {
            Text(
                text = selected.directory,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun LoadedImageViewer(image: PreparedRemoteImage) {
    var fullScreen by remember(image.bitmap) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = image.remote.path.substringAfterLast('/').ifBlank { image.remote.path },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = buildString {
                append("${image.originalWidth} × ${image.originalHeight}")
                append("  ${image.remote.contentType}")
                    append("  ${formatImageBytes(image.remote.bytes.size.toLong())}")
                if (image.sampleSize > 1) append("  preview 1/${image.sampleSize}")
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
        )
        ZoomableImagePanel(
            image = image,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onFullScreen = { fullScreen = true },
        )
        Text(
            text = "Pinch to zoom. Drag to pan. Double-tap to toggle zoom.",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            fontSize = 11.sp,
        )
    }

    if (fullScreen) {
        FullScreenImageDialog(image = image, onDismiss = { fullScreen = false })
    }
}


@Composable
private fun ZoomableImagePanel(
    image: PreparedRemoteImage,
    modifier: Modifier,
    onFullScreen: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    var scale by remember(image.bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(image.bitmap) { mutableStateOf(Offset.Zero) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerButton("−") {
                scale = (scale - 0.5f).coerceAtLeast(1f)
                if (scale == 1f) offset = Offset.Zero
            }
            Text(
                text = "${(scale * 100).toInt()}%",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            ViewerButton("+") { scale = (scale + 0.5f).coerceAtMost(6f) }
            ViewerButton("RESET") {
                scale = 1f
                offset = Offset.Zero
            }
            onFullScreen?.let { open -> ViewerButton("FULL", open) }
            onClose?.let { close -> ViewerButton("CLOSE", close) }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = Color.Black,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(0.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(image.bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 6f)
                            val maxX = size.width * (nextScale - 1f) / 2f
                            val maxY = size.height * (nextScale - 1f) / 2f
                            scale = nextScale
                            offset = if (nextScale == 1f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                        }
                    }
                    .pointerInput(image.bitmap) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = image.remote.path,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}


@Composable
private fun FullScreenImageDialog(image: PreparedRemoteImage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
            contentColor = Color.White,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                Text(
                    text = image.remote.path.substringAfterLast('/').ifBlank { image.remote.path },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ZoomableImagePanel(
                    image = image,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onClose = onDismiss,
                )
            }
        }
    }
}


@Composable
private fun ViewerButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}


@Composable
private fun EmptyImagePanel(
    message: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}


@Composable
private fun LoadingImagePanel(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text("Loading image…", fontFamily = FontFamily.Monospace)
        }
    }
}


private suspend fun prepareRemoteImage(remote: RemoteImage): PreparedRemoteImage = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(remote.bytes, 0, remote.bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("Image data could not be decoded")
    }
    val sampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(remote.bytes, 0, remote.bytes.size, options)
        ?: throw IllegalArgumentException("Image data could not be decoded")
    PreparedRemoteImage(
        remote = remote,
        bitmap = bitmap.asImageBitmap(),
        originalWidth = bounds.outWidth,
        originalHeight = bounds.outHeight,
        sampleSize = sampleSize,
    )
}


internal fun imageSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sampleSize = 1
    while (
        width / sampleSize > MAX_DECODED_IMAGE_DIMENSION ||
        height / sampleSize > MAX_DECODED_IMAGE_DIMENSION ||
        (width / sampleSize).toLong() * (height / sampleSize) > MAX_DECODED_IMAGE_PIXELS
    ) {
        sampleSize *= 2
    }
    return sampleSize
}


private fun Throwable.imageViewerMessage(): String {
    val raw = message.orEmpty().ifBlank { "Image could not be loaded" }
    return runCatching {
        JSONObject(raw).optJSONObject("detail")?.optString("message")
    }.getOrNull()?.takeIf(String::isNotBlank) ?: raw
}


private fun formatImageBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MiB", bytes / (1024f * 1024f))
    bytes >= 1024 -> String.format("%.1f KiB", bytes / 1024f)
    else -> "$bytes B"
}


private sealed interface ImageViewerState {
    data object Empty : ImageViewerState
    data object Loading : ImageViewerState
    data class Ready(val image: PreparedRemoteImage) : ImageViewerState
    data class Failed(val message: String) : ImageViewerState
}

private sealed interface ImageBrowserState {
    data object Empty : ImageBrowserState
    data object Loading : ImageBrowserState
    data class Ready(val directory: RemoteImageDirectory) : ImageBrowserState
    data class Failed(val message: String) : ImageBrowserState
}


private data class PreparedRemoteImage(
    val remote: RemoteImage,
    val bitmap: ImageBitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val sampleSize: Int,
)
