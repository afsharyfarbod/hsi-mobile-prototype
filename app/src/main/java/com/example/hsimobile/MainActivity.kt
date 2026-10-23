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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
        
        // INITIALIZE PYTHON
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

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
                Text(if (loading) "Processing (Auto-binning)..." else "Select .h5 File")
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text("Error: $it", color = Color.Red, fontSize = 13.sp)
            }

            hsi?.let { data ->
                Spacer(Modifier.height(8.dp))
                
                if (data.spatialBin > 1) {
                    Text(
                        "⚠️ Auto-binned ${data.spatialBin}×${data.spatialBin} on-device. (Original: ${data.origHeight}×${data.origWidth} → New: ${data.height}×${data.width})",
                        color = Color(0xFFD32F2F), fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                
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

                Text("Spectra Viewer", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                SpectrumCanvas(data = data, spectra = spectra)
                
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
                                "Large files will be automatically binned using the on-device Python engine.",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ... (Keep HeatmapCanvas, SpectrumCanvas, findClosestBand, jetColor, saveCsv exactly as they are in your current file) ...
// NOTE: I am omitting them here to save space, but DO NOT delete them from your file. 
// Copy them from your previous MainActivity.kt and paste them below this function.

// ---------- File Loading & Saving (UPDATED TO USE PYTHON) ----------
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
        // 1. Run the Python script to bin the file natively in C-memory
        val py = Python.getInstance()
        val pyObj = py.getModule("hsi_processor")
        val binFactor = pyObj.callAttr("bin_if_needed", tmpInput.absolutePath, tmpBinned.absolutePath).toInt()

        // 2. Load the small binned file using Java (no memory crash now)
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

        // We don't know the original size easily without re-reading, but we can approximate 
        // for the UI warning based on the bin factor.
        val origH = h * binFactor
        val origW = w * binFactor

        return HsiData(flatCube!!, wls!!, h, w, b, binFactor, origH, origW)
    } finally {
        tmpInput.delete()
        tmpBinned.delete()
    }
}
