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
import kotlin.math.min
import kotlin.math.roundToInt
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
 * ZXing reads almost the whole picture. Once it has a barcode in sight, the window shrinks onto it
 * and holds on for a moment even if the barcode slips, then lets go and returns to the middle.
 */
class ScanFrameView(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val idleWidth = 300 * density
    private val idleHeight = 170 * density
    private val minWidth = 140 * density
    private val minHeight = 90 * density
    private val padding = 18 * density

    // What ZXing saw lately, in view coordinates
    private val seen = ArrayDeque<Pair<Long, PointF>>()
    private var window: RectF? = null
    private var lockTarget: RectF? = null
    private var lockedUntil = 0L

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Called by ZXing for every point it finds while decoding, in camera preview coordinates. */
    override fun addPossibleResultPoint(point: ResultPoint) {
        val preview = previewSize ?: return
        val p = PointF(point.x * width / preview.width, point.y * height / preview.height)
        seen.addLast(SystemClock.uptimeMillis() to p)
        postInvalidateOnAnimation()
    }

    /**
     * The barcode's outline, from picture rows where ZXing got all the way through a barcode:
     * start guard, the digits, end guard, three different spots. Anything that merely looks like a
     * start guard (edges, text, patterns) shows up as one spot, reported once per barcode format,
     * and is ignored, so the window keeps still until there really is a barcode.
     */
    private fun barcodeBounds(now: Long): RectF? {
        while (seen.isNotEmpty() && now - seen.first().first > ROW_MS) seen.removeFirst()
        val rows = seen.groupBy { (it.second.y / (2 * density)).roundToInt() }.values
            .map { row -> row.map { it.second } }
            .filter { row ->
                val spots = row.map { (it.x / (3 * density)).roundToInt() }.distinct()
                spots.size >= 3 && row.maxOf { it.x } - row.minOf { it.x } > 40 * density
            }
        if (rows.isEmpty()) return null
        val points = rows.flatten()
        return RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        if (framingRect == null) return
        val now = SystemClock.uptimeMillis()

        barcodeBounds(now)?.let { b ->
            // Some room around the bars, and never thinner than a barcode is
            val w = max(b.width() + 2 * padding, minWidth)
            val h = max(max(b.height() + 2 * padding, w * 0.45f), minHeight)
            lockTarget = RectF(b.centerX() - w / 2, b.centerY() - h / 2, b.centerX() + w / 2, b.centerY() + h / 2)
            lockedUntil = now + HOLD_MS
        }
        val locked = now < lockedUntil
        val target = lockTarget?.takeIf { locked }
            ?: RectF(width / 2f - idleWidth / 2, height / 2f - idleHeight / 2, width / 2f + idleWidth / 2, height / 2f + idleHeight / 2)

        // Snaps on quickly, lets go gently
        val win = window ?: RectF(target).also { window = it }
        val ease = if (locked) 0.3f else 0.15f
        win.left += (target.left - win.left) * ease
        win.top += (target.top - win.top) * ease
        win.right += (target.right - win.right) * ease
        win.bottom += (target.bottom - win.bottom) * ease
        val moving = abs(target.left - win.left) > 0.5f || abs(target.top - win.top) > 0.5f ||
            abs(target.right - win.right) > 0.5f || abs(target.bottom - win.bottom) > 0.5f
        if (moving || locked || seen.isNotEmpty()) postInvalidateOnAnimation()

        val radius = min(24 * density, win.height() / 3)
        val arm = min(28 * density, min(win.width() / 4, win.height() / 3))

        // Everything outside the rounded window gets dimmed, a little more once it holds a barcode
        dim.color = Color.argb(if (locked) 190 else 170, 0, 0, 0)
        canvas.save()
        canvas.clipOutPath(Path().apply { addRoundRect(win, radius, radius, Path.Direction.CW) })
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()

        // Four corner brackets that follow the window's rounding, bolder while locked on
        corners.strokeWidth = (if (locked) 5 else 4) * density
        val r = radius
        val path = Path().apply {
            with(win) {
                moveTo(left, top + arm); lineTo(left, top + r); quadTo(left, top, left + r, top); lineTo(left + arm, top)
                moveTo(right - arm, top); lineTo(right - r, top); quadTo(right, top, right, top + r); lineTo(right, top + arm)
                moveTo(right, bottom - arm); lineTo(right, bottom - r); quadTo(right, bottom, right - r, bottom); lineTo(right - arm, bottom)
                moveTo(left + arm, bottom); lineTo(left + r, bottom); quadTo(left, bottom, left, bottom - r); lineTo(left, bottom - arm)
            }
        }
        canvas.drawPath(path, corners)
    }
}

/** How long a sighting counts towards the barcode's outline. */
private const val ROW_MS = 350L

/** How long the window holds on after the barcode was last seen. */
private const val HOLD_MS = 900L
