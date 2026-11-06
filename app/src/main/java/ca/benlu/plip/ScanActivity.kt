package ca.benlu.plip

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ca.benlu.plip.ui.PlipTheme
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.ViewfinderView
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.ui.graphics.Color as ComposeColor

/** Plip's own scan screen in place of ZXing's: camera full screen, a barcode-shaped window, Plip's controls on top. */
class ScanActivity : ComponentActivity() {
    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        // ZXing writes its own prompt here; the overlay below has the real one.
        barcodeView.setStatusText("")
        // Read nearly the whole picture, not just the window, so the barcode needn't sit exactly inside it
        barcodeView.barcodeView.marginFraction = 0.05

        val hasTorch = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        findViewById<ComposeView>(R.id.scan_overlay).setContent {
            PlipTheme(dynamicColor = Store.current.settings.dynamicColor) {
                var torch by remember { mutableStateOf(false) }
                ScanOverlay(
                    torch = torch.takeIf { hasTorch },
                    onTorch = {
                        torch = !torch
                        if (torch) barcodeView.setTorchOn() else barcodeView.setTorchOff()
                    },
                    onClose = ::finish,
                )
            }
        }

        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    @Deprecated("CaptureManager asks for the camera the old way")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}

/** Close and title on top, hint and torch at the bottom. [torch] is null on phones without a flash. */
@androidx.compose.runtime.Composable
private fun ScanOverlay(torch: Boolean?, onTorch: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(containerColor = ComposeColor.Black.copy(alpha = 0.35f)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = ComposeColor.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.beverage_scan), style = MaterialTheme.typography.titleLarge, color = ComposeColor.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.beverage_scan_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = ComposeColor.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(ComposeColor.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (torch != null) {
                FilledTonalButton(onClick = onTorch) {
                    Icon(painterResource(R.drawable.ic_flashlight), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (torch) R.string.torch_off else R.string.torch_on))
                }
            }
        }
    }
}

/**
 * Dims everything but a rounded window and marks its corners. Replaces ZXing's laser line.
 * ZXing reads almost the whole picture, not just the window; when it spots a barcode somewhere,
 * the window glides over to it and back to the middle once it's lost, so a shaky hand doesn't matter.
 */
class ScanFrameView(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val windowWidth = 300 * density
    private val windowHeight = 170 * density

    // Where ZXing saw parts of a barcode lately, in view coordinates
    private val seen = ArrayDeque<Pair<Long, PointF>>()
    private var center: PointF? = null
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val radius = 24 * density
    private val arm = 28 * density

    /** Called by ZXing for every point it finds while decoding, in camera preview coordinates. */
    override fun addPossibleResultPoint(point: ResultPoint) {
        val preview = previewSize ?: return
        val p = PointF(point.x * width / preview.width, point.y * height / preview.height)
        seen.addLast(SystemClock.uptimeMillis() to p)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        val frame = framingRect ?: return

        // Aim at the barcode seen in the last moment, otherwise at the middle of the screen
        val now = SystemClock.uptimeMillis()
        while (seen.isNotEmpty() && now - seen.first().first > TRACK_MS) seen.removeFirst()
        val halfW = windowWidth / 2
        val halfH = windowHeight / 2
        val targetX = (if (seen.isEmpty()) width / 2f else seen.map { it.second.x }.average().toFloat())
            .coerceIn(frame.left + halfW, max(frame.left + halfW, frame.right - halfW))
        val targetY = (if (seen.isEmpty()) height / 2f else seen.map { it.second.y }.average().toFloat())
            .coerceIn(frame.top + halfH, max(frame.top + halfH, frame.bottom - halfH))
        val c = center ?: PointF(targetX, targetY).also { center = it }
        // Ease towards the target instead of jumping
        c.x += (targetX - c.x) * EASE
        c.y += (targetY - c.y) * EASE
        val window = RectF(c.x - halfW, c.y - halfH, c.x + halfW, c.y + halfH)
        if (seen.isNotEmpty() || abs(targetX - c.x) > 0.5f || abs(targetY - c.y) > 0.5f) postInvalidateOnAnimation()

        // Everything outside the rounded window gets dimmed
        canvas.save()
        canvas.clipOutPath(Path().apply { addRoundRect(window, radius, radius, Path.Direction.CW) })
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()

        // Four corner brackets that follow the window's rounding
        val r = radius
        val path = Path().apply {
            with(window) {
                moveTo(left, top + arm); lineTo(left, top + r); quadTo(left, top, left + r, top); lineTo(left + arm, top)
                moveTo(right - arm, top); lineTo(right - r, top); quadTo(right, top, right, top + r); lineTo(right, top + arm)
                moveTo(right, bottom - arm); lineTo(right, bottom - r); quadTo(right, bottom, right - r, bottom); lineTo(right - arm, bottom)
                moveTo(left + arm, bottom); lineTo(left + r, bottom); quadTo(left, bottom, left, bottom - r); lineTo(left, bottom - arm)
            }
        }
        canvas.drawPath(path, corners)
    }
}

private const val TRACK_MS = 600L
private const val EASE = 0.18f
