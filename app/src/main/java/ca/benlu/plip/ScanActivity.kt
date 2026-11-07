package ca.benlu.plip

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
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
 * Dims everything but a fixed rounded window in the middle and marks its corners.
 * Replaces ZXing's laser line. ZXing still reads almost the whole picture, the window is only a guide.
 */
class ScanFrameView(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val frameWidth = 300 * density
    private val frameHeight = 170 * density

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4 * density
    }

    // ZXing's possible result points are ignored, nothing moves
    override fun addPossibleResultPoint(point: ResultPoint) {}

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        if (framingRect == null) return
        val win = RectF(width / 2f - frameWidth / 2, height / 2f - frameHeight / 2, width / 2f + frameWidth / 2, height / 2f + frameHeight / 2)
        val r = 24 * density
        val arm = 28 * density

        canvas.save()
        canvas.clipOutPath(Path().apply { addRoundRect(win, r, r, Path.Direction.CW) })
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()

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
