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
import androidx.compose.ui.graphics.toArgb
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
    val hdfFile: HdfFile,
    val cubeDs: Dataset,
    val wavelengths: FloatArray,
    val height: Int,
    val width: Int,
    val bands: Int
) {
    // Memory-efficient reading: loads ONLY the requested band
    fun getBand(bandIndex: Int): FloatArray {
        val offsets = longArrayOf(0, 0, bandIndex.toLong())
        val shape = intArrayOf(height, width, 1)
        val data = cubeDs.getData(offsets, shape) as Array<Array<FloatArray>>
        val flat = FloatArray(height * width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                flat[y * width + x] = data[y][x][0]
            }
        }
        return flat
    }

    // Loads ONLY a single pixel's spectrum
    fun getPixel(x: Int, y: Int): FloatArray {
        val offsets = longArrayOf(y.toLong(), x.toLong(), 0)
        val shape = intArrayOf(1, 1, bands)
        val data = cubeDs.getData(offsets, shape) as Array<Array<FloatArray>>
        return data[0][0].clone()
    }

    // Loads a small ROI and averages it
    fun getRoiAverage(x1: Int, y1: Int, x2: Int, y2: Int): FloatArray {
        val minX = min(x1, x2).coerceIn(0, width - 1)
        val maxX = max(x1, x2).coerceIn(0, width - 1)
        val minY = min(y1, y2).coerceIn(0, height - 1)
        val maxY = max(y1, y2).coerceIn(0, height - 1)
        
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        
        val offsets = longArrayOf(minY.toLong(), minX.toLong(), 0)
        val shape = intArrayOf(h, w, bands)
        val data = cubeDs.getData(offsets, shape) as Array<Array<FloatArray>>
        
        val avg = FloatArray(bands)
        var count = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = data[y][x]
                for (b in 0 until bands) {
                    avg[b] += px[b]
                }
                count++
            }
        }
        for (b in 0 until bands) avg[b] /= count.toFloat()
        return avg
    }

    // Global sum for visualisation
    fun getGlobalSum(): FloatArray {
        val sum = FloatArray(height * width)
        // For memory safety, load band by band
        for (b in 0 until bands) {
            val band = getBand(b)
            for (i in band.indices) {
                sum[i] += band[i]
            }
        }
        return sum
    }
}

data class SpectrumRecord(
    val label: String,
    val data: FloatArray,
    val isReference: Boolean = false
)

data class Pixel(val x: Int, val y: Int)

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

@OptIn(ExperimentalMaterial3Api::class)
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
    var spectra by remember { mutableStateOf<List<SpectrumRecord>>(emptyList()) }
    var activeCsvMsg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loading = true
            error = null
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
                            Spacer(Modifier.width(8.dp))
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Slider(value = rWl, onValueChange = { rWl = it }, valueRange = data.wavelengths.min()..data.wavelengths.max())
                            Slider(value = gWl, onValueChange = { gWl = it }, valueRange = data.wavelengths.min()..data.wavelengths.max())
                            Slider(value = bWl, onValueChange = { bWl = it }, valueRange = data.wavelengths.min()..data.wavelengths.max())
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // --- Selection Modes ---
                Text("Selection Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("Single Pixel", "Rectangle ROI").forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectionMode == mode,
                                onClick = { selectionMode = mode }
                            )
                            Text(mode, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                // --- Heatmap Canvas with Axes ---
                HeatmapCanvas(
                    data = data,
                    displayMode = displayMode,
                    band = selectedBand,
                    rWl = rWl, gWl = gWl, bWl = bWl,
                    selectionMode = selectionMode,
                    onExtractSpectrum = { label, spec ->
                        spectra = spectra + SpectrumRecord(label, spec, false)
                    }
                )

                // --- Reference & Export Controls ---
                if (spectra.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = {
                            val last = spectra.last()
                            spectra = spectra.dropLast(1) + SpectrumRecord(last.label, last.data, true)
                        }) { Text("Pin as Reference") }
                        
                        Button(onClick = { spectra = emptyList() }) { Text("Clear All") }
                    }
                    
                    Button(
                        onClick = {
                            activeCsvMsg = saveCsv(context, data, spectra)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save Spectra as CSV") }
                    
                    activeCsvMsg?.let { Text(it, fontSize = 12.sp, color = Color(0xFF388E3C)) }
                }

                Spacer(Modifier.height(16.dp))

                // --- Spectrum Viewer with Axes ---
                Text("Spectra Viewer", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                SpectrumCanvas(data = data, spectra = spectra)
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
    onExtractSpectrum: (String, FloatArray) -> Unit
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    // Cache the visual data to prevent re-reading on every frame
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
                // Return RGB as a giant FloatArray where every 3 values are R,G,B
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
            .background(Color.LightGray.copy(alpha = 0.3f))
            .pointerInput(data, selectionMode, visualData) {
                val cw = size.width.toFloat()
                val ch = size.height.toFloat()
                
                if (selectionMode == "Single Pixel") {
                    detectTapGestures { offset ->
                        val px = (offset.x / cw * data.width).toInt().coerceIn(0, data.width - 1)
                        val py = (offset.y / ch * data.height).toInt().coerceIn(0, data.height - 1)
                        val spec = data.getPixel(px, py)
                        onExtractSpectrum("Pixel ($px, $py)", spec)
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, _ -> change.consume(); dragEnd = change.position },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                val x1 = (start.x / cw * data.width).toInt().coerceIn(0, data.width - 1)
                                val y1 = (start.y / ch * data.height).toInt().coerceIn(0, data.height - 1)
                                val x2 = (end.x / cw * data.width).toInt().coerceIn(0, data.width - 1)
                                val y2 = (end.y / ch * data.height).toInt().coerceIn(0, data.height - 1)
                                val spec = data.getRoiAverage(x1, y1, x2, y2)
                                onExtractSpectrum("ROI ($x1-$x2, $y1-$y2)", spec)
                            }
                            dragStart = null
                            dragEnd = null
                        }
                    )
                }
            }
    ) {
        val cellW = size.width / data.width
        val cellH = size.height / data.height

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
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(cellW + 1f, cellH + 1f)
                    )
                } else {
                    val v = (visualData[y * data.width + x] - mn) / span
                    drawRect(
                        color = jetColor(v),
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(cellW + 1f, cellH + 1f)
                    )
                }
            }
        }

        // 2. Draw ROI box if dragging
        if (dragStart != null && dragEnd != null && selectionMode == "Rectangle ROI") {
            val topLeft = Offset(min(dragStart!!.x, dragEnd!!.x), min(dragStart!!.y, dragEnd!!.y))
            val size = Size(abs(dragStart!!.x - dragEnd!!.x), abs(dragStart!!.y - dragEnd!!.y))
            drawRect(Color.Red, topLeft, size, style = Stroke(width = 3f))
        }

        // 3. Draw Axes
        drawAxes(data.width.toFloat(), data.height.toFloat(), cellW, cellH)
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
    
    // Map to add padding for axes
    val padLeft = 60.dp.value
    val padBottom = 40.dp.value
    val padTop = 20.dp.value
    val padRight = 20.dp.value

    Canvas(modifier = Modifier.fillMaxWidth().height(250.dp)) {
        val w = size.width - padLeft - padRight
        val h = size.height - padTop - padBottom

        // 1. Draw Axes Lines
        drawLine(Color.Black, Offset(padLeft, padTop), Offset(padLeft, padTop + h), strokeWidth = 2f) // Y
        drawLine(Color.Black, Offset(padLeft, padTop + h), Offset(padLeft + w, padTop + h), strokeWidth = 2f) // X

        // 2. Draw Spectrum Lines
        val colors = listOf(Color.Blue, Color.Red, Color.Green, Color.Magenta, Color.Cyan, Color(0xFFFFA500))
        spectra.forEachIndexed { idx, rec ->
            val path = Path()
            val n = rec.data.size
            for (i in 0 until n) {
                val x = padLeft + (i.toFloat() / (n - 1).coerceAtLeast(1)) * w
                val y = padTop + h - ((rec.data[i] - minInt) / spanInt) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val color = if (rec.isReference) Color.Black else colors[idx % colors.size]
            val stroke = if (rec.isReference) Stroke(width = 4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))) else Stroke(width = 2f)
            drawPath(path, color, style = stroke)
        }

        // 3. Draw Axis Labels
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val paintY = android.graphics.Paint(paint).apply {
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        // X Labels
        for (i in 0..4) {
            val frac = i / 4f
            val xPos = padLeft + frac * w
            val valX = minWl + frac * spanWl
            drawLine(Color.Black, Offset(xPos, padTop + h), Offset(xPos, padTop + h + 10f), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText("%.0f".format(valX), xPos, padTop + h + 40f, paint)
        }

        // Y Labels
        for (i in 0..4) {
            val frac = i / 4f
            val yPos = padTop + h - frac * h
            val valY = minInt + frac * spanInt
            drawLine(Color.Black, Offset(padLeft, yPos), Offset(padLeft - 10f, yPos), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText("%.2f".format(valY), padLeft - 15f, yPos + 10f, paintY)
        }
    }
}

fun drawAxes(dataW: Float, dataH: Float, cellW: Float, cellH: Float) {
    // Logic for heatmap axes is drawn in the main Canvas block if needed
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

    // We do NOT delete the temp file immediately. We keep it alive so Lazy Loading works.
    val hdf = HdfFile(tmp.toPath())
    val cubeDs = findDataset(hdf, "cube")
    val wlDs = findDataset(hdf, "wavelengths")

    val shape = cubeDs.getDimensions()
    val h = shape[0]
    val w = shape[1]
    val b = shape[2]

    val wlRaw = wlDs.data
    val wls = when (wlRaw) {
        is FloatArray -> wlRaw
        is DoubleArray -> FloatArray(wlRaw.size) { wlRaw[it].toFloat() }
        else -> throw IllegalStateException("wavelengths must be 1D float")
    }

    return HsiData(hdf, cubeDs, wls, h, w, b)
}

fun saveCsv(context: Context, data: HsiData, spectra: List<SpectrumRecord>): String {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val name = "spectra_${System.currentTimeMillis()}.csv"
    val file = File(baseDir, name)
    
    if (spectra.isEmpty()) return "No spectra to save."

    file.bufferedWriter().use { w ->
        // Header
        w.write("wavelength_nm,")
        w.write(spectra.joinToString(",") { it.label })
        w.write("\n")

        // Data rows
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
