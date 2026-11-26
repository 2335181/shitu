package app.shitu.ui.search

import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.core.Clipboard
import app.shitu.core.openUrl
import app.shitu.core.toast
import app.shitu.feature.search.Engine
import app.shitu.feature.search.EngineResult
import app.shitu.feature.search.EngineType
import app.shitu.feature.search.SearchHit
import app.shitu.ui.LocalContainer
import app.shitu.ui.LocalNav
import app.shitu.ui.design.DotTags
import app.shitu.ui.design.EditorialTitle
import app.shitu.ui.design.Eyebrow
import app.shitu.ui.design.Gap
import app.shitu.ui.design.InkButton
import app.shitu.ui.design.Lede
import app.shitu.ui.design.MistCard
import app.shitu.ui.design.OutlinePill
import app.shitu.ui.design.PaperCard
import app.shitu.ui.design.SectionHeader
import app.shitu.ui.design.SoftButton
import app.shitu.ui.theme.LocalShituPalette
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(padding: PaddingValues) {
    val container = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val session = container.searchSession
    val settings by container.settings.collectAsStateWithLifecycle()
    var selected by remember(settings.searchEngines) {
        mutableStateOf(settings.searchEngines.mapNotNull(Engine::byId).filter { it != Engine.SAUCENAO || settings.sauceNaoKey.isNotBlank() }.toSet())
    }
    val enabled = settings.searchEngines.mapNotNull(Engine::byId)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { session.load(context, it) }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { session.load(context, it) }
    }

    LaunchedEffect(nav.sharedImage) {
        nav.sharedImage?.let { session.load(context, it); nav.sharedImage = null }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Gap(12.dp)
                Eyebrow("GOOGLE · YANDEX · SAUCENAO · TRACE.MOE", textAlign = TextAlign.Center)
                Gap(18.dp)
                EditorialTitle(
                    "一张图，\n找回它的*出处*。",
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                )
                Gap(32.dp)
            }
        }

        item {
            PaperCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
                val bmp = session.bitmap
                when {
                    session.uri == null -> EmptyPicker(
                        onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onCamera = {
                            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            cameraUri = uri
                            camera.launch(uri)
                        },
                        onClipboard = {
                            val uri = Clipboard.imageUri(context)
                            if (uri != null) session.load(context, uri) else context.toast("剪贴板里没有图片")
                        },
                    )
                    session.loadError != null -> Column(Modifier.padding(16.dp)) {
                        Text("这张图打不开，换一张试试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                        Gap(12.dp)
                        SoftButton("重新选图", { session.clear() }, compact = true)
                    }
                    bmp == null -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> {
                        val image = remember(bmp) { bmp.asImageBitmap() }
                        CropPreview(image, session.crop, session.cropping) { session.crop = it }
                        Gap(12.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SoftButton(if (session.cropping) "完成裁剪" else "裁剪", {
                                if (!session.cropping && session.crop == RectF(0f, 0f, 1f, 1f)) session.crop = RectF(0.08f, 0.08f, 0.92f, 0.92f)
                                session.cropping = !session.cropping
                            }, icon = Icons.Rounded.Crop, compact = true)
                            if (session.crop != RectF(0f, 0f, 1f, 1f)) {
                                SoftButton("还原", { session.crop = RectF(0f, 0f, 1f, 1f) }, compact = true)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { session.clear() }) { Icon(Icons.Rounded.Close, "换一张") }
                        }
                    }
                }
                Gap(10.dp)
                InkButton(
                    text = if (session.searching) "正在搜索…" else "开始识图",
                    onClick = { session.search(enabled.filter { it in selected }, settings.imageMaxSide) },
                    enabled = session.bitmap != null && selected.isNotEmpty(),
                    loading = session.searching,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Gap(20.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                enabled.forEach { e ->
                    OutlinePill(e.label, onClick = {
                        selected = if (e in selected) selected - e else selected + e
                    }, selected = e in selected)
                }
            }
            Gap(6.dp)
            Text(
                "接口型引擎直接在下面出结果，网页型会在浏览器里打开结果页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (session.resultOrder.isNotEmpty()) {
            item { SectionHeader("搜索结果") }
            items(session.resultOrder, key = { it.id }) { engine ->
                EngineCard(engine, session.results[engine] ?: EngineResult.Loading)
                Gap(12.dp)
            }
        } else {
            item {
                Gap(28.dp)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Engine.GOOGLE, Engine.SAUCENAO, Engine.TRACEMOE, Engine.ASCII2D).forEach { e ->
                        MistCard(Modifier.fillMaxWidth()) {
                            Text(e.label, style = MaterialTheme.typography.headlineSmall)
                            Gap(6.dp)
                            Text(e.blurb, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Gap(12.dp)
                            DotTags(listOf(if (e.type == EngineType.API) "应用内结果" else "网页结果", if (e == Engine.SAUCENAO) "需 API Key" else "免登录"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPicker(onGallery: () -> Unit, onCamera: () -> Unit, onClipboard: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceContainerLow)
            .clickable(onClick = onGallery)
            .padding(vertical = 34.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Image, null, tint = LocalShituPalette.current.accent, modifier = Modifier.size(40.dp))
        Gap(10.dp)
        Text("点这里选一张图", style = MaterialTheme.typography.titleMedium)
        Text("支持截图、照片、动图的第一帧", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Gap(18.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("拍照", onCamera, icon = Icons.Rounded.CameraAlt, compact = true)
            SoftButton("剪贴板", onClipboard, icon = Icons.Rounded.ContentPaste, compact = true)
        }
    }
}

/** 图片预览 + 可拖动的裁剪框。拖四个角缩放，拖中间移动。 */
@Composable
private fun CropPreview(image: ImageBitmap, crop: RectF, editing: Boolean, onCrop: (RectF) -> Unit) {
    val ratio = (image.width.toFloat() / image.height).coerceIn(0.5f, 2f)
    val ink = MaterialTheme.colorScheme.primary
    val scrim = MaterialTheme.colorScheme.scrim
    val accent = LocalShituPalette.current.accent
    Box(Modifier.fillMaxWidth().heightIn(max = 380.dp), contentAlignment = Alignment.Center) {
    Box(
        Modifier
            .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
            .clip(MaterialTheme.shapes.large),
    ) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        val latest = androidx.compose.runtime.rememberUpdatedState(crop)
        var current by remember { mutableStateOf(crop) }
        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (editing) Modifier.pointerInput(Unit) {
                        var mode = -1
                        detectDragGestures(
                            onDragStart = { p ->
                                current = latest.value
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                val c = current
                                val corners = listOf(Offset(c.left * w, c.top * h), Offset(c.right * w, c.top * h), Offset(c.left * w, c.bottom * h), Offset(c.right * w, c.bottom * h))
                                val nearest = corners.withIndex().minBy { (it.value - p).getDistance() }
                                mode = if ((nearest.value - p).getDistance() < 64f) nearest.index else 4
                            },
                            onDrag = { change, d ->
                                change.consume()
                                val dx = d.x / size.width; val dy = d.y / size.height
                                val c = RectF(current)
                                val min = 0.12f
                                when (mode) {
                                    0 -> { c.left = (c.left + dx).coerceIn(0f, c.right - min); c.top = (c.top + dy).coerceIn(0f, c.bottom - min) }
                                    1 -> { c.right = (c.right + dx).coerceIn(c.left + min, 1f); c.top = (c.top + dy).coerceIn(0f, c.bottom - min) }
                                    2 -> { c.left = (c.left + dx).coerceIn(0f, c.right - min); c.bottom = (c.bottom + dy).coerceIn(c.top + min, 1f) }
                                    3 -> { c.right = (c.right + dx).coerceIn(c.left + min, 1f); c.bottom = (c.bottom + dy).coerceIn(c.top + min, 1f) }
                                    else -> {
                                        val mx = dx.coerceIn(-c.left, 1f - c.right); val my = dy.coerceIn(-c.top, 1f - c.bottom)
                                        c.offset(mx, my)
                                    }
                                }
                                current = c
                                onCrop(c)
                            },
                        )
                    } else Modifier,
                ),
        ) {
            val full = crop.left <= 0f && crop.top <= 0f && crop.right >= 1f && crop.bottom >= 1f
            if (!editing && full) return@Canvas
            val l = crop.left * size.width; val t = crop.top * size.height
            val r = crop.right * size.width; val b = crop.bottom * size.height
            val dim = scrim.copy(alpha = 0.45f)
            drawRect(dim, Offset.Zero, Size(size.width, t))
            drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(dim, Offset(0f, t), Size(l, b - t))
            drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
            drawRoundRect(Color.White, Offset(l, t), Size(r - l, b - t), CornerRadius(8f), style = Stroke(2.dp.toPx()))
            if (editing) {
                listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach {
                    drawCircle(Color.White, 9.dp.toPx(), it)
                    drawCircle(accent, 6.dp.toPx(), it)
                }
            }
        }
        if (editing) {
            Text(
                "拖四角调整，拖中间移动",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                    .clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }}
}


@Composable
private fun EngineCard(engine: Engine, result: EngineResult) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    MistCard(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(engine.label, style = MaterialTheme.typography.titleLarge)
                Text(engine.blurb, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            when (result) {
                EngineResult.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is EngineResult.Web -> SoftButton("打开结果", { context.openUrl(result.url) }, icon = Icons.AutoMirrored.Rounded.OpenInNew, compact = true)
                is EngineResult.Hits -> Text("${result.hits.size} 条", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                is EngineResult.Failed -> Unit
            }
        }
        when (result) {
            is EngineResult.Hits -> {
                if (result.hits.isEmpty()) {
                    Gap(8.dp)
                    Text("没找到相似的图", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                } else {
                    Gap(12.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        result.hits.take(6).forEach { HitRow(it) }
                    }
                }
            }
            is EngineResult.Failed -> {
                Gap(8.dp)
                Text(result.message, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                result.fallbackUrl?.let {
                    Gap(8.dp)
                    SoftButton("去官网手动搜", { context.openUrl(it) }, icon = Icons.Rounded.TravelExplore, compact = true)
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceContainerLowest)
            .border(BorderStroke(1.dp, scheme.outlineVariant), MaterialTheme.shapes.medium)
            .clickable { context.openUrl(hit.url) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            hit.thumbnail, null,
            modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small).background(scheme.surfaceContainer),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hit.detail.isNotBlank()) {
                Text(hit.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(hit.url.substringAfter("//").substringBefore('/'), style = MaterialTheme.typography.bodySmall, color = scheme.outline, maxLines = 1)
        }
        hit.similarity?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                "%.0f%%".format(it),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (it >= 80) LocalShituPalette.current.success else scheme.onSurfaceVariant,
            )
        }
    }
}
