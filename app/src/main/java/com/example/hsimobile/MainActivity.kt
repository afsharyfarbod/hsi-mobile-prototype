package com.example.hsimobile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.jhdf.HdfFile
import io.jhdf.api.Dataset
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------- Data Classes ----------
class HsiData(
    val flatCube: FloatArray,
    val wavelengths: FloatArray,
    val height: Int,
    val width: Int,
    val bands: Int,
    val spatialBin: Int = 1,
    val origHeight: Int = height,
    val origWidth: Int = width
) {
    fun getBand(bandIndex: Int): FloatArray {
        val out = FloatArray(height * width)
        for (i in 0 until height * width) {
            out[i] = flatCube[i * bands + bandIndex]
        }
        return out
    }

    fun getPixel(x: Int, y: Int): FloatArray {
        val out = FloatArray(bands)
        val offset = (y * width + x) * bands
        System.arraycopy(flatCube, offset, out, 0, bands)
        return out
    }

    fun getRoiAverage(x1: Int, y1: Int, x2: Int, y2: Int): FloatArray {
        val minX = min(x1, x2).coerceIn(0, width - 1)
        val maxX = max(x1, x2).coerceIn(0, width - 1)
        val minY = min(y1, y2).coerceIn(0, height - 1)
        val maxY = max(y1, y2).coerceIn(0, height - 1)
        
        val avg = FloatArray(bands)
        var count = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val offset = (y * width + x) * bands
                for (b in 0 until bands) {
                    avg[b] += flatCube[offset + b]
                }
                count++
            }
        }
        if (count > 0) {
            for (b in 0 until bands) avg[b] /= count.toFloat()
        }
        return avg
    }

    fun getGlobalSum(): FloatArray {
        val sum = FloatArray(height * width)
        for (i in 0 until height * width) {
            for (b in 0 until bands) {
                sum[i] += flatCube[i * bands + b]
            }
        }
        return sum
    }
}

data class DrawnShape(
    val type: String,
    val x1: Int, val y1: Int,
    val x2: Int, val y2: Int,
    val number: Int
)

data class SpectrumRecord(
    val label: String,
    val data: FloatArray,
    val color: Color,
    val isReference: Boolean = false
)

// ---------- Main Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2E7D32), // Nice HSI green
                    secondary = Color(0xFF1565C0)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HsiApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HsiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var hsi by remember { mutableStateOf<HsiData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    
    var displayMode by remember { mutableStateOf("Single Band") }
    var selectedBand by remember { mutableIntStateOf(0) }
    var rWl by remember { mutableFloatStateOf(650f) }
    var gWl by remember { mutableFloatStateOf(550f) }
    var bWl by remember { mutableFloatStateOf(450f) }

    var selectionMode by remember { mutableStateOf("Single Pixel") }
    var shapes by remember { mutableStateOf<List<DrawnShape>>(emptyList()) }
    var spectra by remember { mutableStateOf<List<SpectrumRecord>>(emptyList()) }
    var activeCsvMsg by remember { mutableStateOf<String?>(null) }

    val palette = listOf(Color.Blue, Color.Red, Color(0xFF00C853), Color.Magenta, Color.Cyan, Color(0xFFFFA500))

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loading = true
            error = null
            shapes = emptyList()
            spectra = emptyList()
            Thread {
                try {
                    val data = loadH5WithPython(context, uri)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        hsi = data
                        selectedBand = 0
                        loading = false
                    }
                } catch (e: Throwable) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        error = e.message ?: e.javaClass.simpleName
                        loading = false
                    }
                }
            }.start()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CHIMERA HSI", fontWeight = FontWeight.Bold) },
                actions = {
                    if (hsi != null) {
                        IconButton(onClick = { 
                            val msg = saveCsv(context, hsi!!, spectra)
                            activeCsvMsg = msg
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Save CSV")
                        }
                        IconButton(onClick = { 
                            shapes = emptyList()
                            spectra = emptyList()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear All")
                        }
                    }
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5))
        ) {
            if (hsi == null) {
                // Empty State
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No Data Loaded", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !loading
                        ) {
                            Text(if (loading) "Processing (Auto-binning)..." else "Select .h5 File")
                        }
                    }
                }
            } else {
                val data = hsi!!
                
                // Loading / Warning Banner
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (data.spatialBin > 1) {
                    Surface(color = Color(0xFFFFCDD2), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Auto-binned ${data.spatialBin}x${data.spatialBin} (${data.origWidth}x${data.origHeight} → ${data.width}x${data.height})",
                            modifier = Modifier.padding(8.dp),
                            color = Color(0xFFB71C1C), fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                error?.let {
                    Text(it, color = Color.Red, modifier = Modifier.padding(8.dp))
                }

                // --- Split View ---
                // Top: Heatmap (60% of remaining screen)
                Box(modifier = Modifier.weight(1.2f).fillMaxWidth().padding(8.dp)) {
                    HeatmapCanvas(
                        data = data,
                        displayMode = displayMode,
                        band = selectedBand,
                        rWl = rWl, gWl = gWl, bWl = bWl,
                        selectionMode = selectionMode,
                        shapes = shapes,
                        onExtractSpectrum = { type, x1, y1, x2, y2, spec ->
                            val nextNum = shapes.size + 1
                            shapes = shapes + DrawnShape(type, x1, y1, x2, y2, nextNum)
                            val label = if (type == "point") "P$nextNum" else "ROI $nextNum"
                            val col = palette[(nextNum - 1) % palette.size]
                            spectra = spectra + SpectrumRecord(label, spec, col, false)
                        }
                    )
                }

                // Bottom: Spectrum & Legend (40% of remaining screen)
                Card(
                    modifier = Modifier.weight(0.9f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        Text("Spectra Viewer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            SpectrumCanvas(data = data, spectra = spectra)
                        }
                        
                        // Horizontal Scrollable Legend
                        if (spectra.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(spectra) { rec ->
                                    Surface(
                                        color = rec.color.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, rec.color)
                                    ) {
                                        Text(
                                            rec.label,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            fontSize = 12.sp, color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (activeCsvMsg != null) {
                    Text(activeCsvMsg!!, color = Color(0xFF2E7D32), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    // --- Bottom Sheet for Controls ---
    if (showSheet && hsi != null) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Display Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
                // Display Mode
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Single Band", "Global Sum", "RGB").forEach { mode ->
                        FilterChip(
                            selected = displayMode == mode,
                            onClick = { displayMode = mode },
                            label = { Text(mode) }
                        )
                    }
                }

                // Conditional Sliders
                if (displayMode == "Single Band") {
                    Text("Band ${selectedBand + 1} / ${hsi!!.bands}   λ = %.1f nm".format(hsi!!.wavelengths[selectedBand]))
                    Slider(
                        value = selectedBand.toFloat(),
                        onValueChange = { selectedBand = it.toInt().coerceIn(0, hsi!!.bands - 1) },
                        valueRange = 0f..(hsi!!.bands - 1).toFloat().coerceAtLeast(1f),
                        steps = if (hsi!!.bands > 2) hsi!!.bands - 2 else 0
                    )
                } else if (displayMode == "RGB") {
                    Text("Red λ: %.0f nm".format(rWl))
                    Slider(value = rWl, onValueChange = { rWl = it }, valueRange = hsi!!.wavelengths.min()..hsi!!.wavelengths.max())
                    Text("Green λ: %.0f nm".format(gWl))
                    Slider(value = gWl, onValueChange = { gWl = it }, valueRange = hsi!!.wavelengths.min()..hsi!!.wavelengths.max())
                    Text("Blue λ: %.0f nm".format(bWl))
                    Slider(value = bWl, onValueChange = { bWl = it }, valueRange = hsi!!.wavelengths.min()..hsi!!.wavelengths.max())
                }

                Divider()

                Text("Selection Mode", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Single Pixel", "Rectangle ROI").forEach { mode ->
                        FilterChip(
                            selected = selectionMode == mode,
                            onClick = { selectionMode = mode },
                            label = { Text(mode) }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------- Canvas & Drawing Functions ----------
@Composable
fun HeatmapCanvas(
    data: HsiData,
    displayMode: String,
    band: Int,
    rWl: Float, gWl: Float, bWl: Float,
    selectionMode: String,
    shapes: List<DrawnShape>,
    onExtractSpectrum: (String, Int, Int, Int, Int, FloatArray) -> Unit
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val padLeft = 45.dp
    val padBottom = 40.dp
    val padTop = 10.dp
    val padRight = 10.dp

    val visualData = remember(data, displayMode, band, rWl, gWl, bWl) {
        when (displayMode) {
            "Single Band" -> data.getBand(band)
            "Global Sum" -> data.getGlobalSum()
            "RGB" -> {
                val rIdx = findClosestBand(data.wavelengths, rWl)
                val gIdx = findClosestBand(data.wavelengths, gWl)
                val bIdx = findClosestBand(data.wavelengths, bWl)
                val r = data.getBand(rIdx)
                val g = data.getBand(gIdx)
                val b = data.getBand(bIdx)
                val rgb = FloatArray(r.size * 3)
                for (i in r.indices) {
                    rgb[i * 3] = r[i]
                    rgb[i * 3 + 1] = g[i]
                    rgb[i * 3 + 2] = b[i]
                }
                rgb
            }
            else -> FloatArray(0)
        }
    }

    val (mn, mx) = remember(visualData) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in visualData) {
            if (v < min) min = v
            if (v > max) max = v
        }
        if (max - min < 1e-8f) min to (min + 1f) else min to max
    }
    val span = (mx - mn).coerceAtLeast(1e-6f)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White, RoundedCornerShape(8.dp))
            .pointerInput(data, selectionMode, visualData) {
                val padL = padLeft.toPx()
                val padB = padBottom.toPx()
                val padT = padTop.toPx()
                val padR = padRight.toPx()
                
                val imgW = size.width - padL - padR
                val imgH = size.height - padT - padB

                if (selectionMode == "Single Pixel") {
                    detectTapGestures { offset ->
                        if (offset.x < padL || offset.x > padL + imgW || offset.y < padT || offset.y > padT + imgH) return@detectTapGestures
                        val px = ((offset.x - padL) / imgW * data.width).toInt().coerceIn(0, data.width - 1)
                        val py = ((offset.y - padT) / imgH * data.height).toInt().coerceIn(0, data.height - 1)
                        val spec = data.getPixel(px, py)
                        onExtractSpectrum("point", px, py, px, py, spec)
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { 
                            if (it.x >= padL && it.x <= padL + imgW && it.y >= padT && it.y <= padT + imgH) {
                                dragStart = it
                                dragEnd = it
                            }
                        },
                        onDrag = { change, _ -> change.consume(); dragEnd = change.position },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                val x1 = ((start.x - padL) / imgW * data.width).toInt().coerceIn(0, data.width - 1)
                                val y1 = ((start.y - padT) / imgH * data.height).toInt().coerceIn(0, data.height - 1)
                                val x2 = ((end.x - padL) / imgW * data.width).toInt().coerceIn(0, data.width - 1)
                                val y2 = ((end.y - padT) / imgH * data.height).toInt().coerceIn(0, data.height - 1)
                                val spec = data.getRoiAverage(x1, y1, x2, y2)
                                onExtractSpectrum("rect", x1, y1, x2, y2, spec)
                            }
                            dragStart = null
                            dragEnd = null
                        }
                    )
                }
            }
    ) {
        val padL = padLeft.toPx()
        val padB = padBottom.toPx()
        val padT = padTop.toPx()
        val padR = padRight.toPx()

        val imgW = size.width - padL - padR
        val imgH = size.height - padT - padB

        val cellW = imgW / data.width
        val cellH = imgH / data.height

        for (y in 0 until data.height) {
            for (x in 0 until data.width) {
                if (displayMode == "RGB") {
                    val idx = (y * data.width + x) * 3
                    drawRect(
                        color = Color(
                            (visualData[idx] - mn) / span,
                            (visualData[idx + 1] - mn) / span,
                            (visualData[idx + 2] - mn) / span
                        ),
                        topLeft = Offset(padL + x * cellW, padT + y * cellH),
                        size = Size(cellW + 1f, cellH + 1f)
                    )
                } else {
                    val v = (visualData[y * data.width + x] - mn) / span
                    drawRect(
                        color = jetColor(v),
                        topLeft = Offset(padL + x * cellW, padT + y * cellH),
                        size = Size(cellW + 1f, cellH + 1f)
                    )
                }
            }
        }

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }

        shapes.forEach { shape ->
            if (shape.type == "point") {
                val cx = padL + (shape.x1 + 0.5f) * cellW
                val cy = padT + (shape.y1 + 0.5f) * cellH
                drawCircle(Color.Red, radius = 10f, center = Offset(cx, cy))
                drawCircle(Color.White, radius = 10f, center = Offset(cx, cy), style = Stroke(width = 2f))
                drawContext.canvas.nativeCanvas.drawText("${shape.number}", cx, cy + 10f, textPaint)
            } else {
                val left = padL + shape.x1 * cellW
                val top = padT + shape.y1 * cellH
                val rw = (shape.x2 - shape.x1 + 1) * cellW
                val rh = (shape.y2 - shape.y1 + 1) * cellH
                drawRect(Color.Red, topLeft = Offset(left, top), size = Size(rw, rh), style = Stroke(width = 3f))
                drawContext.canvas.nativeCanvas.drawText("${shape.number}", left + rw/2, top + rh/2 + 10f, textPaint)
            }
        }

        if (dragStart != null && dragEnd != null && selectionMode == "Rectangle ROI") {
            val topLeft = Offset(min(dragStart!!.x, dragEnd!!.x), min(dragStart!!.y, dragEnd!!.y))
            val size = Size(abs(dragStart!!.x - dragEnd!!.x), abs(dragStart!!.y - dragEnd!!.y))
            drawRect(Color.Yellow, topLeft, size, style = Stroke(width = 2f))
        }

        // Draw Axes
        drawLine(Color.Black, Offset(padL, padT), Offset(padL, padT + imgH), strokeWidth = 2f)
        drawLine(Color.Black, Offset(padL, padT + imgH), Offset(padL + imgW, padT + imgH), strokeWidth = 2f)

        val textPaintAxes = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val textPaintY = android.graphics.Paint(textPaintAxes).apply {
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val titlePaint = android.graphics.Paint(textPaintAxes).apply {
            textSize = 28f
            isFakeBoldText = true
        }

        for (i in 0..5) {
            val frac = i / 5f
            val xPos = padL + frac * imgW
            val valX = (frac * data.origWidth).toInt()
            drawLine(Color.Black, Offset(xPos, padT + imgH), Offset(xPos, padT + imgH + 5f), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("$valX", xPos, padT + imgH + 25f, textPaintAxes)
        }
        drawContext.canvas.nativeCanvas.drawText("X (px)", padL + imgW/2, size.height - 2f, titlePaint)

        for (i in 0..5) {
            val frac = i / 5f
            val yPos = padT + imgH - frac * imgH
            val valY = (frac * data.origHeight).toInt()
            drawLine(Color.Black, Offset(padL, yPos), Offset(padL - 5f, yPos), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("$valY", padL - 8f, yPos + 8f, textPaintY)
        }
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 10f, padT + imgH/2)
        drawContext.canvas.nativeCanvas.drawText("Y (px)", 10f, padT + imgH/2, titlePaint)
        drawContext.canvas.nativeCanvas.restore()
    }
}

@Composable
fun SpectrumCanvas(data: HsiData, spectra: List<SpectrumRecord>) {
    val minWl = data.wavelengths.minOrNull() ?: 0f
    val maxWl = data.wavelengths.maxOrNull() ?: 1f
    val spanWl = (maxWl - minWl).coerceAtLeast(1e-6f)

    val (minInt, maxInt) = remember(spectra) {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        spectra.forEach { rec ->
            rec.data.forEach { v ->
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        if (spectra.isEmpty()) 0f to 1f else (mn to mx)
    }
    val spanInt = (maxInt - minInt).coerceAtLeast(1e-6f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val padLeft = 50.dp.toPx()
        val padBottom = 40.dp.toPx()
        val padTop = 10.dp.toPx()
        val padRight = 10.dp.toPx()

        val w = size.width - padLeft - padRight
        val h = size.height - padTop - padBottom

        drawLine(Color.Black, Offset(padLeft, padTop), Offset(padLeft, padTop + h), strokeWidth = 2f)
        drawLine(Color.Black, Offset(padLeft, padTop + h), Offset(padLeft + w, padTop + h), strokeWidth = 2f)

        spectra.forEach { rec ->
            val path = Path()
            val n = rec.data.size
            for (i in 0 until n) {
                val x = padLeft + (i.toFloat() / (n - 1).coerceAtLeast(1)) * w
                val y = padTop + h - ((rec.data[i] - minInt) / spanInt) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val stroke = if (rec.isReference) Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))) else Stroke(width = 2f)
            drawPath(path, rec.color, style = stroke)
        }

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val textPaintY = android.graphics.Paint(textPaint).apply {
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val titlePaint = android.graphics.Paint(textPaint).apply {
            textSize = 28f
            isFakeBoldText = true
        }

        for (i in 0..4) {
            val frac = i / 4f
            val xPos = padLeft + frac * w
            val valX = minWl + frac * spanWl
            drawLine(Color.Black, Offset(xPos, padTop + h), Offset(xPos, padTop + h + 5f), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("%.0f".format(valX), xPos, padTop + h + 25f, textPaint)
        }
        drawContext.canvas.nativeCanvas.drawText("Wavelength (nm)", padLeft + w/2, size.height - 2f, titlePaint)

        for (i in 0..4) {
            val frac = i / 4f
            val yPos = padTop + h - frac * h
            val valY = minInt + frac * spanInt
            drawLine(Color.Black, Offset(padLeft, yPos), Offset(padLeft - 5f, yPos), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("%.2f".format(valY), padLeft - 8f, yPos + 8f, textPaintY)
        }
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 10f, padTop + h/2)
        drawContext.canvas.nativeCanvas.drawText("Intensity", 10f, padTop + h/2, titlePaint)
        drawContext.canvas.nativeCanvas.restore()
    }
}

// ... (Keep the rest of the helper functions exactly the same: findClosestBand, jetColor, findDataset, loadH5WithPython, saveCsv) ...

fun findClosestBand(wls: FloatArray, target: Float): Int {
    var best = 0
    var minDiff = Float.MAX_VALUE
    for (i in wls.indices) {
        val diff = abs(wls[i] - target)
        if (diff < minDiff) {
            minDiff = diff
            best = i
        }
    }
    return best
}

fun jetColor(v: Float): Color {
    val t = v.coerceIn(0f, 1f)
    val r: Float; val g: Float; val b: Float
    when {
        t < 0.25f -> { r = 0f; g = 4 * t; b = 1f }
        t < 0.5f -> { r = 0f; g = 1f; b = 1f - 4 * (t - 0.25f) }
        t < 0.75f -> { r = 4 * (t - 0.5f); g = 1f; b = 0f }
        else -> { r = 1f; g = 1f - 4 * (t - 0.75f); b = 0f }
    }
    return Color(r, g, b)
}

fun findDataset(hdf: HdfFile, name: String): Dataset {
    val node = try { hdf.getByPath(name) } catch (e: Exception) { null }
        ?: try { hdf.getByPath("/$name") } catch (e: Exception) { null }
        ?: throw IllegalArgumentException("Dataset '$name' not found in file root")
    return node as? Dataset ?: throw IllegalArgumentException("'$name' exists but is not a dataset")
}

fun loadH5WithPython(context: Context, uri: Uri): HsiData {
    val tmpInput = File(context.cacheDir, "raw_input_${System.currentTimeMillis()}.h5")
    context.contentResolver.openInputStream(uri)?.use { input ->
        tmpInput.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Cannot open file from picker")

    val tmpBinned = File(context.cacheDir, "binned_${System.currentTimeMillis()}.h5")

    try {
        val py = Python.getInstance()
        val pyObj = py.getModule("hsi_processor")
        val binFactor = pyObj.callAttr("bin_if_needed", tmpInput.absolutePath, tmpBinned.absolutePath).toInt()

        var flatCube: FloatArray? = null
        var wls: FloatArray? = null
        var h = 0; var w = 0; var b = 0

        HdfFile(tmpBinned.toPath()).use { hdf ->
            val cubeDs = findDataset(hdf, "cube")
            val wlDs = findDataset(hdf, "wavelengths")

            val shape = cubeDs.getDimensions()
            h = shape[0].toInt()
            w = shape[1].toInt()
            b = shape[2].toInt()

            val flatRaw = cubeDs.getDataFlat()
            flatCube = when (flatRaw) {
                is FloatArray -> flatRaw
                is DoubleArray -> FloatArray(flatRaw.size) { flatRaw[it].toFloat() }
                else -> throw IllegalStateException("cube must be float32")
            }

            val wlRaw = wlDs.getDataFlat()
            wls = when (wlRaw) {
                is FloatArray -> wlRaw
                is DoubleArray -> FloatArray(wlRaw.size) { wlRaw[it].toFloat() }
                else -> throw IllegalStateException("wavelengths must be 1D float")
            }
        }

        val origH = h * binFactor
        val origW = w * binFactor

        return HsiData(flatCube!!, wls!!, h, w, b, binFactor, origH, origW)
    } finally {
        tmpInput.delete()
        tmpBinned.delete()
    }
}

fun saveCsv(context: Context, data: HsiData, spectra: List<SpectrumRecord>): String {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val name = "spectra_${System.currentTimeMillis()}.csv"
    val file = File(baseDir, name)
    
    if (spectra.isEmpty()) return "No spectra to save."

    file.bufferedWriter().use { w ->
        w.write("wavelength_nm,")
        w.write(spectra.joinToString(",") { it.label.replace(" ", "_").replace(",", "") })
        w.write("\n")

        for (i in data.wavelengths.indices) {
            w.write("${data.wavelengths[i]}")
            for (spec in spectra) {
                w.write(",${spec.data[i]}")
            }
            w.write("\n")
        }
    }
    return "Saved: ${file.absolutePath}"
}
