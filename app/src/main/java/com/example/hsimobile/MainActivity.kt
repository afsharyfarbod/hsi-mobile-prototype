package com.example.hsimobile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jhdf.HdfFile
import io.jhdf.api.Dataset
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class HsiData(
    val flatCube: FloatArray,
    val wavelengths: FloatArray,
    val height: Int,
    val width: Int,
    val bands: Int
) {
    fun bandValue(x: Int, y: Int, band: Int): Float =
        flatCube[(y * width + x) * bands + band]

    fun spectrumAt(x: Int, y: Int): FloatArray {
        val out = FloatArray(bands)
        System.arraycopy(flatCube, (y * width + x) * bands, out, 0, bands)
        return out
    }
}

data class Pixel(val x: Int, val y: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
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
    var selectedBand by remember { mutableIntStateOf(0) }
    var tappedPixel by remember { mutableStateOf<Pixel?>(null) }
    var spectrum by remember { mutableStateOf<FloatArray?>(null) }
    var exportMsg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loading = true
            error = null
            exportMsg = null
            Thread {
                try {
                    val data = loadH5FromUri(context, uri)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        hsi = data
                        selectedBand = 0
                        tappedPixel = null
                        spectrum = null
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
                title = { Text("HSI Mobile", fontWeight = FontWeight.Bold) }
            )
        }
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
                Text(if (loading) "Loading…" else "Select .h5 File")
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text("Error: $it", color = Color.Red, fontSize = 13.sp)
            }

            hsi?.let { data ->
                Spacer(Modifier.height(12.dp))
                Text("Cube: ${data.height} × ${data.width} × ${data.bands}", fontSize = 14.sp)
                Text(
                    "Band ${selectedBand + 1} / ${data.bands}   λ = " +
                            "%.1f".format(data.wavelengths[selectedBand]) + " nm",
                    fontSize = 14.sp
                )
                Slider(
                    value = selectedBand.toFloat(),
                    onValueChange = {
                        selectedBand = it.toInt().coerceIn(0, data.bands - 1)
                    },
                    valueRange = 0f..(data.bands - 1).toFloat().coerceAtLeast(1f),
                    steps = if (data.bands > 2) data.bands - 2 else 0
                )

                HeatmapCanvas(
                    data = data,
                    band = selectedBand,
                    tapped = tappedPixel,
                    onTap = { p ->
                        tappedPixel = p
                        spectrum = data.spectrumAt(p.x, p.y)
                        exportMsg = null
                    }
                )

                tappedPixel?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Tapped pixel: x=${it.x}, y=${it.y}", fontSize = 13.sp)
                }

                spectrum?.let { spec ->
                    Spacer(Modifier.height(12.dp))
                    Text("Spectrum", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    SpectrumCanvas(spec)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val path = saveCsv(context, data, spec, tappedPixel)
                            exportMsg = "Saved: $path"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Spectrum CSV")
                    }
                    exportMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 12.sp, color = Color(0xFF388E3C))
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
    band: Int,
    tapped: Pixel?,
    onTap: (Pixel) -> Unit
) {
    val range = remember(data, band) {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (y in 0 until data.height) {
            for (x in 0 until data.width) {
                val v = data.bandValue(x, y, band)
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        if (mx - mn < 1e-8f) mn to (mn + 1f) else mn to mx
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(data.width.toFloat() / data.height.toFloat())
            .pointerInput(data, band) {
                val cw = size.width.toFloat()
                val ch = size.height.toFloat()
                detectTapGestures { offset ->
                    val px = (offset.x / cw * data.width).toInt()
                        .coerceIn(0, data.width - 1)
                    val py = (offset.y / ch * data.height).toInt()
                        .coerceIn(0, data.height - 1)
                    onTap(Pixel(px, py))
                }
            }
    ) {
        val cellW = size.width / data.width
        val cellH = size.height / data.height
        val (mn, mx) = range
        val span = (mx - mn).coerceAtLeast(1e-6f)
        for (y in 0 until data.height) {
            for (x in 0 until data.width) {
                val v = (data.bandValue(x, y, band) - mn) / span
                drawRect(
                    color = jetColor(v),
                    topLeft = Offset(x * cellW, y * cellH),
                    size = Size(cellW + 1f, cellH + 1f)
                )
            }
        }
        tapped?.let {
            val cx = (it.x + 0.5f) * cellW
            val cy = (it.y + 0.5f) * cellH
            drawCircle(Color.Red, radius = 10f, center = Offset(cx, cy))
            drawCircle(
                Color.White, radius = 10f, center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
        }
    }
}

@Composable
fun SpectrumCanvas(spectrum: FloatArray) {
    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        drawRect(
            color = Color.LightGray,
            topLeft = Offset.Zero,
            size = size,
            style = Stroke(width = 2f)
        )
        val mn = spectrum.minOrNull() ?: 0f
        val mx = spectrum.maxOrNull() ?: 1f
        val span = (mx - mn).coerceAtLeast(1e-6f)
        val path = Path()
        val n = spectrum.size
        for (i in 0 until n) {
            val x = if (n > 1) i.toFloat() / (n - 1) * size.width else 0f
            val y = size.height - ((spectrum[i] - mn) / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color(0xFF1565C0), style = Stroke(width = 3f))
    }
}

fun jetColor(v: Float): Color {
    val t = v.coerceIn(0f, 1f)
    val r: Float
    val g: Float
    val b: Float
    when {
        t < 0.25f -> { r = 0f; g = 4 * t; b = 1f }
        t < 0.5f -> { r = 0f; g = 1f; b = 1f - 4 * (t - 0.25f) }
        t < 0.75f -> { r = 4 * (t - 0.5f); g = 1f; b = 0f }
        else -> { r = 1f; g = 1f - 4 * (t - 0.75f); b = 0f }
    }
    return Color(r, g, b)
}

fun findDataset(hdf: HdfFile, name: String): Dataset {
    // Try finding the dataset by name, then with a leading slash
    val node = try { hdf.getByPath(name) } catch (e: Exception) { null }
        ?: try { hdf.getByPath("/$name") } catch (e: Exception) { null }
        ?: throw IllegalArgumentException("Dataset '$name' not found in file root")
        
    return node as? Dataset
        ?: throw IllegalArgumentException("'$name' exists but is not a dataset")
}

fun loadH5FromUri(context: Context, uri: Uri): HsiData {
    val tmp = File(context.cacheDir, "input_${System.currentTimeMillis()}.h5")
    context.contentResolver.openInputStream(uri)?.use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Cannot open file from picker")

    try {
        HdfFile(tmp.toPath()).use { hdf ->
            val cubeDs = findDataset(hdf, "cube")
            val wlDs = findDataset(hdf, "wavelengths")

            val raw = cubeDs.data
            val cube3 = raw as? Array<*>
                ?: throw IllegalStateException("cube is not a 3D array")

            val h = cube3.size
            if (h == 0) throw IllegalStateException("cube is empty")
            val row0 = cube3[0] as? Array<*>
                ?: throw IllegalStateException("cube rows are not 2D")
            val w = row0.size
            if (w == 0) throw IllegalStateException("cube has 0 columns")
            val bandArr0 = row0[0] as? FloatArray
                ?: throw IllegalStateException("cube bands are not float32")
            val b = bandArr0.size

            val flat = FloatArray(h * w * b)
            for (y in 0 until h) {
                val row = cube3[y] as Array<*>
                for (x in 0 until w) {
                    val arr = row[x] as FloatArray
                    System.arraycopy(arr, 0, flat, (y * w + x) * b, b)
                }
            }

            val wlRaw = wlDs.data
            val wls = when (wlRaw) {
                is FloatArray -> wlRaw
                is DoubleArray -> FloatArray(wlRaw.size) { wlRaw[it].toFloat() }
                else -> throw IllegalStateException("wavelengths must be 1D float")
            }
            if (wls.size != b) {
                throw IllegalStateException(
                    "Mismatch: ${wls.size} wavelengths vs $b bands"
                )
            }

            return HsiData(flat, wls, h, w, b)
        }
    } finally {
        tmp.delete()
    }
}

fun saveCsv(context: Context, data: HsiData, spectrum: FloatArray, tapped: Pixel?): String {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val name = "spectrum_${tapped?.x ?: 0}_${tapped?.y ?: 0}_${System.currentTimeMillis()}.csv"
    val file = File(baseDir, name)
    file.bufferedWriter().use { w ->
        w.write("wavelength_nm,intensity\n")
        for (i in spectrum.indices) {
            w.write("${data.wavelengths[i]},${spectrum[i]}\n")
        }
    }
    return file.absolutePath
}
