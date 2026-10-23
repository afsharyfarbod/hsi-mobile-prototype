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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.jhdf.HdfFile
import io.jhdf.api.Dataset
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
    val type: String, // "point" or "rect"
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
        setContent {
            MaterialTheme {
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
    var hsi by remember { mutableStateOf<HsiData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // Display Settings
    var displayMode by remember { mutableStateOf("Single Band") }
    var selectedBand by remember { mutableIntStateOf(0) }
    var rWl by remember { mutableFloatStateOf(650f) }
    var gWl by remember { mutableFloatStateOf(550f) }
    var bWl by remember { mutableFloatStateOf(450f) }

    // Selection Settings
    var selectionMode by remember { mutableStateOf("Single Pixel") }
    var shapes by remember { mutableStateOf<List<DrawnShape>>(emptyList()) }
    var spectra by remember { mutableStateOf<List<SpectrumRecord>>(emptyList()) }
    var activeCsvMsg by remember { mutableStateOf<String?>(null) }

    val palette = listOf(Color.Blue, Color.Red, Color.Green, Color.Magenta, Color.Cyan, Color(0xFFFFA500))

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
                    val data = loadH5FromUri(context, uri)
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
        topBar = { TopAppBar(title = { Text("HSI Mobile", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) {
                Text(if (loading) "Loading..." else "Select .h5 File")
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text("Error: $it", color = Color.Red, fontSize = 13.sp)
            }

            hsi?.let { data ->
                Spacer(Modifier.height(8.dp))
                
                // Auto-binning warning
                if (data.spatialBin > 1) {
                    Text(
                        "⚠️ Auto-binned ${data.spatialBin}×${data.spatialBin} to fit memory. (Original: ${data.origHeight}×${data.origWidth} → New: ${data.height}×${data.width})",
                        color = Color(0xFFD32F2F), fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                
                // --- Display Modes ---
                Text("Display Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("Single Band", "Global Sum", "RGB").forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = displayMode == mode,
                                onClick = { displayMode = mode }
                            )
                            Text(mode, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }

                if (displayMode == "Single Band") {
                    Text("Band ${selectedBand + 1} / ${data.bands}   λ = %.1f nm".format(data.wavelengths[selectedBand]), fontSize = 13.sp)
                    Slider(
                        value = selectedBand.toFloat(),
                        onValueChange = { selectedBand = it.toInt().coerceIn(0, data.bands - 1) },
                        valueRange = 0f..(data.bands - 1).toFloat().coerceAtLeast(1f),
                        steps = if (data.bands > 2) data.bands - 2 else 0
                    )
                } else if (displayMode == "RGB") {
                    Text("R λ: %.0f nm | G λ: %.0f nm | B λ: %.0f nm".format(rWl, gWl, bWl), fontSize = 13.sp)
                    Column {
                        Slider(value = rWl, onValueChange = { rWl = it }, valueRange = data.wavelengths.min()..data.wavelengths.max())
                        Slider(value = gWl, onValueChange = { gWl = it }, valueRange = data.wavelengths.min()..data.wavelengths.max())
                        Slider(value = bWl, onValueChange = { bWl = it }, valueRange = data.wavelengths.min()..data.wavelengths.max())
                    }
                }

                Spacer(Modifier.height(16.dp))

                // --- Selection Modes ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("Single Pixel", "Rectangle ROI").forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectionMode == mode,
                                    onClick = { selectionMode = mode }
                                )
                                Text(mode, fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                    if (shapes.isNotEmpty()) {
                        TextButton(onClick = { 
                            shapes = emptyList()
                            spectra = emptyList()
                        }) { Text("Clear", color = Color.Red) }
                    }
                }
                
                // --- Heatmap Canvas with Axes and Shapes ---
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
                        val label = if (type == "point") "Pixel $nextNum ($x1, $y1)" else "ROI $nextNum ($x1-$x2, $y1-$y2)"
                        val col = palette[(nextNum - 1) % palette.size]
                        spectra = spectra + SpectrumRecord(label, spec, col, false)
                    }
                )

                // --- Export Controls ---
                if (spectra.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = {
                            val last = spectra.last()
                            spectra = spectra.dropLast(1) + last.copy(isReference = true, color = Color.Black)
                        }) { Text("Pin Last as Ref") }
                        
                        Button(onClick = { activeCsvMsg = saveCsv(context, data, spectra) }) { 
                            Text("Save CSV") 
                        }
                    }
                    activeCsvMsg?.let { Text(it, fontSize = 12.sp, color = Color(0xFF388E3C)) }
                }

                Spacer(Modifier.height(16.dp))

                // --- Spectrum Viewer with Axes and Legend ---
                Text("Spectra Viewer", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                SpectrumCanvas(data = data, spectra = spectra)
                
                // Legend
                if (spectra.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(modifier = Modifier.fillMaxWidth()) {
                        spectra.forEach { rec ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 4.dp)) {
                                Box(modifier = Modifier.size(12.dp).background(rec.color))
                                Spacer(Modifier.width(4.dp))
                                Text(rec.label, fontSize = 11.sp)
                            }
                        }
                    }
                }

            } ?: run {
                if (!loading) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Tap 'Select .h5 File' to load a hyperspectral cube.\n\n" +
                                "The .h5 must contain a 3D dataset at /cube (H×W×Bands) " +
                                "and a 1D dataset at /wavelengths.",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

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

    val padLeft = 55.dp
    val padBottom = 50.dp
    val padTop = 15.dp
    val padRight = 15.dp

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
            .fillMaxWidth()
            .aspectRatio(data.width.toFloat() / data.height.toFloat())
            .background(Color.White)
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

        // 1. Draw Image
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

        // 2. Draw Persistence Shapes & Numbers
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 35f
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }

        shapes.forEach { shape ->
            if (shape.type == "point") {
                val cx = padL + (shape.x1 + 0.5f) * cellW
                val cy = padT + (shape.y1 + 0.5f) * cellH
                drawCircle(Color.Red, radius = 12f, center = Offset(cx, cy))
                drawCircle(Color.White, radius = 12f, center = Offset(cx, cy), style = Stroke(width = 3f))
                drawContext.canvas.nativeCanvas.drawText("${shape.number}", cx, cy + 12f, textPaint)
            } else {
                val left = padL + shape.x1 * cellW
                val top = padT + shape.y1 * cellH
                val rw = (shape.x2 - shape.x1 + 1) * cellW
                val rh = (shape.y2 - shape.y1 + 1) * cellH
                drawRect(Color.Red, topLeft = Offset(left, top), size = Size(rw, rh), style = Stroke(width = 4f))
                drawContext.canvas.nativeCanvas.drawText("${shape.number}", left + rw/2, top + rh/2 + 12f, textPaint)
            }
        }

        // 3. Draw Active Drag ROI Box
        if (dragStart != null && dragEnd != null && selectionMode == "Rectangle ROI") {
            val topLeft = Offset(min(dragStart!!.x, dragEnd!!.x), min(dragStart!!.y, dragEnd!!.y))
            val size = Size(abs(dragStart!!.x - dragEnd!!.x), abs(dragStart!!.y - dragEnd!!.y))
            drawRect(Color.Yellow, topLeft, size, style = Stroke(width = 3f))
        }

        // 4. Draw Axes, Titles, and Ticks
        drawLine(Color.Black, Offset(padL, padT), Offset(padL, padT + imgH), strokeWidth = 3f)
        drawLine(Color.Black, Offset(padL, padT + imgH), Offset(padL + imgW, padT + imgH), strokeWidth = 3f)

        val textPaintAxes = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val textPaintY = android.graphics.Paint(textPaintAxes).apply {
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val titlePaint = android.graphics.Paint(textPaintAxes).apply {
            textSize = 35f
            isFakeBoldText = true
        }

        // X Ticks & Labels
        for (i in 0..5) {
            val frac = i / 5f
            val xPos = padL + frac * imgW
            val valX = (frac * data.origWidth).toInt()
            drawLine(Color.Black, Offset(xPos, padT + imgH), Offset(xPos, padT + imgH + 10f), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText("$valX", xPos, padT + imgH + 40f, textPaintAxes)
        }
        // X Title
        drawContext.canvas.nativeCanvas.drawText("X (px)", padL + imgW/2, size.height - 5f, titlePaint)

        // Y Ticks & Labels (inverted so 0 is at top)
        for (i in 0..5) {
            val frac = i / 5f
            val yPos = padT + imgH - frac * imgH
            val valY = (frac * data.origHeight).toInt()
            drawLine(Color.Black, Offset(padL, yPos), Offset(padL - 10f, yPos), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText("$valY", padL - 15f, yPos + 10f, textPaintY)
        }
        // Y Title (Rotated)
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 15f, padT + imgH/2)
        drawContext.canvas.nativeCanvas.drawText("Y (px)", 15f, padT + imgH/2, titlePaint)
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

    Canvas(modifier = Modifier.fillMaxWidth().height(300.dp).background(Color.White)) {
        val padLeft = 70.dp.toPx()
        val padBottom = 70.dp.toPx()
        val padTop = 20.dp.toPx()
        val padRight = 20.dp.toPx()

        val w = size.width - padLeft - padRight
        val h = size.height - padTop - padBottom

        // 1. Draw Axes Lines
        drawLine(Color.Black, Offset(padLeft, padTop), Offset(padLeft, padTop + h), strokeWidth = 3f)
        drawLine(Color.Black, Offset(padLeft, padTop + h), Offset(padLeft + w, padTop + h), strokeWidth = 3f)

        // 2. Draw Spectrum Lines
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

        // 3. Draw Axis Labels & Titles
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val textPaintY = android.graphics.Paint(textPaint).apply {
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val titlePaint = android.graphics.Paint(textPaint).apply {
            textSize = 35f
            isFakeBoldText = true
        }

        // X Ticks & Labels
        for (i in 0..4) {
            val frac = i / 4f
            val xPos = padLeft + frac * w
            val valX = minWl + frac * spanWl
            drawLine(Color.Black, Offset(xPos, padTop + h), Offset(xPos, padTop + h + 10f), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText("%.0f".format(valX), xPos, padTop + h + 40f, textPaint)
        }
        // X Title
        drawContext.canvas.nativeCanvas.drawText("Wavelength (nm)", padLeft + w/2, size.height - 10f, titlePaint)

        // Y Ticks & Labels
        for (i in 0..4) {
            val frac = i / 4f
            val yPos = padTop + h - frac * h
            val valY = minInt + frac * spanInt
            drawLine(Color.Black, Offset(padLeft, yPos), Offset(padLeft - 10f, yPos), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText("%.2f".format(valY), padLeft - 15f, yPos + 10f, textPaintY)
        }
        // Y Title (Rotated)
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 20f, padTop + h/2)
        drawContext.canvas.nativeCanvas.drawText("Intensity", 20f, padTop + h/2, titlePaint)
        drawContext.canvas.nativeCanvas.restore()
    }
}

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

// ---------- File Loading & Saving ----------
fun findDataset(hdf: HdfFile, name: String): Dataset {
    val node = try { hdf.getByPath(name) } catch (e: Exception) { null }
        ?: try { hdf.getByPath("/$name") } catch (e: Exception) { null }
        ?: throw IllegalArgumentException("Dataset '$name' not found in file root")
    return node as? Dataset ?: throw IllegalArgumentException("'$name' exists but is not a dataset")
}

fun loadH5FromUri(context: Context, uri: Uri): HsiData {
    val tmp = File(context.cacheDir, "input_${System.currentTimeMillis()}.h5")
    context.contentResolver.openInputStream(uri)?.use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Cannot open file from picker")

    try {
        var finalCube: FloatArray? = null
        var wls: FloatArray? = null
        var newH = 0; var newW = 0; var b = 0
        var origH = 0; var origW = 0
        var spatialBin = 1

        HdfFile(tmp.toPath()).use { hdf ->
            val cubeDs = findDataset(hdf, "cube")
            val wlDs = findDataset(hdf, "wavelengths")

            val shape = cubeDs.getDimensions()
            origH = shape[0].toInt()
            origW = shape[1].toInt()
            b = shape[2].toInt()

            // Read the entire file as a flat array (handles chunked datasets natively)
            val rawAny = cubeDs.getDataFlat()
            val flatRaw = when (rawAny) {
                is FloatArray -> rawAny
                is DoubleArray -> FloatArray(rawAny.size) { rawAny[it].toFloat() }
                else -> throw IllegalStateException("cube must be float32 or float64")
            }

            // AUTO-BINNING LOGIC (in memory)
            val maxElements = 50_000_000L // ~200MB limit
            spatialBin = 1
            while ((origH / spatialBin).toLong() * (origW / spatialBin).toLong() * b.toLong() > maxElements) {
                spatialBin++
            }

            newH = max(1, origH / spatialBin)
            newW = max(1, origW / spatialBin)

            if (spatialBin == 1) {
                finalCube = flatRaw
            } else {
                val binnedCube = FloatArray(newH * newW * b)
                val binArea = (spatialBin * spatialBin).toFloat()

                for (yOut in 0 until newH) {
                    for (xOut in 0 until newW) {
                        val outOffset = (yOut * newW + xOut) * b
                        for (bIdx in 0 until b) {
                            var sum = 0f
                            var count = 0
                            for (dy in 0 until spatialBin) {
                                val srcY = yOut * spatialBin + dy
                                if (srcY >= origH) continue
                                for (dx in 0 until spatialBin) {
                                    val srcX = xOut * spatialBin + dx
                                    if (srcX >= origW) continue
                                    
                                    val srcOffset = (srcY * origW + srcX) * b + bIdx
                                    sum += flatRaw[srcOffset]
                                    count++
                                }
                            }
                            binnedCube[outOffset + bIdx] = if (count > 0) sum / count else 0f
                        }
                    }
                }
                finalCube = binnedCube
                System.gc() // Free flatRaw immediately
            }

            val wlRaw = wlDs.getDataFlat()
            wls = when (wlRaw) {
                is FloatArray -> wlRaw
                is DoubleArray -> FloatArray(wlRaw.size) { wlRaw[it].toFloat() }
                else -> throw IllegalStateException("wavelengths must be 1D float")
            }
        }
        
        System.gc()

        return HsiData(finalCube!!, wls!!, newH, newW, b, spatialBin, origH, origW)
    } catch (e: OutOfMemoryError) {
        throw Exception("File is too large for your phone's RAM even with auto-binning.")
    } finally {
        tmp.delete() 
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
