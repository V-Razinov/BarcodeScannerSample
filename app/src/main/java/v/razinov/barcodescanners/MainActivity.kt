package v.razinov.barcodescanners

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import v.razinov.barcodescanners.ui.theme.BarCodeScannersTheme
import java.security.Permission
import java.security.Permissions
import java.util.concurrent.Executors

sealed interface Screen {
    val path: String

    object Selection : Screen {
        override val path: String = "selection"
    }

    object MLKit : Screen {
        override val path: String = "ml_kit"
    }

    object ZXing : Screen {
        override val path: String = "zxing"
    }
}

@ExperimentalGetImage
private class CodeAnalyzer(
    private val onCodeScanned: (Barcode) -> Unit,
) : ImageAnalysis.Analyzer {

    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13)
        .build()

    val scanner = BarcodeScanning.getClient(options)

    private var locked = false
    private val handler = Handler(Looper.myLooper()!!)

    override fun analyze(image: ImageProxy) {
        if (locked) {
            image.close()
            return
        }
        locked = true

        val img = image.image
        if (img == null) {
            image.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(img, image.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.let(onCodeScanned)
            }
            .addOnFailureListener {
                Log.d("MyLogs", "process error: ${it.message}")
            }
            .addOnCompleteListener {
                image.close()
                handler.postDelayed(
                    { locked = false },
                    1000L
                )
            }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BarCodeScannersTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Selection.path
                    ) {
                        composable(Screen.Selection.path) {
                            Selection(
                                onMLKitClick = { navController.navigate(route = Screen.MLKit.path) },
                                onZXingClick = { navController.navigate(route = Screen.ZXing.path) },
                            )
                        }
                        composable(Screen.MLKit.path) { MLKit() }
                        composable(Screen.ZXing.path) { ZXing() }
                    }
                }
            }
        }
    }

    private val cameraPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {  }

    override fun onStart() {
        super.onStart()
        val persmissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!persmissionGranted) {
            cameraPermissionsLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

@Composable
private fun Selection(
    onMLKitClick: () -> Unit,
    onZXingClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = onMLKitClick) {
            Text(text = "ML Kit")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onZXingClick) {
            Text(text = "ZXing")
        }
    }
}

@Composable
private fun MLKit() {
    Box(modifier = Modifier.fillMaxSize()) {
        val snackbarHost = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PreviewView(context).apply PreviewView@{
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    val codeAnalyzer = CodeAnalyzer(
                        onCodeScanned = { barcode ->
                            scope.launch {
                                snackbarHost.currentSnackbarData?.dismiss()
                                snackbarHost.showSnackbar(
                                    barcode.rawValue.toString(),
                                    withDismissAction = true
                                )
                            }
                        }
                    )

                    cameraProviderFuture.addListener(
                        {
                            val cameraProvider = cameraProviderFuture.get()

                            // Preview
                            val preview = Preview.Builder()
                                .build()
                                .also {
                                    it.setSurfaceProvider(this@PreviewView.surfaceProvider)
                                }

                            // Image analyzer
                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(
                                        Executors.newSingleThreadExecutor(),
                                        codeAnalyzer
                                    )
                                }

                            // Select back camera as a default
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.bindToLifecycle(
                                    this@PreviewView.findViewTreeLifecycleOwner()!!,
                                    cameraSelector,
                                    preview,
                                    imageAnalyzer
                                )

                            } catch (e: Exception) {
                                Log.d("MyLogs", "bindError: $e")
                            }
                        },
                        ContextCompat.getMainExecutor(context)
                    )
                }
            },
        )
        SnackbarHost(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            hostState = snackbarHost,
        ) { Snackbar(snackbarData = it) }
    }
}

@Composable
private fun ZXing() {
    Box(modifier = Modifier.fillMaxSize()) {
        val snackbarHost = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                DecoratedBarcodeView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.EAN_13))

                    var locked = false
                    resume()
                    decodeContinuous { result ->
                        if (locked) return@decodeContinuous
                        locked = true
                        scope.launch {
                            snackbarHost.currentSnackbarData?.dismiss()
                            snackbarHost.showSnackbar(
                                result.text.toString(),
                                withDismissAction = true,
                            )
                        }
                        scope.launch {
                            delay(1000L)
                            locked = false
                        }
                    }
                }
            },
            onRelease = { it.pause() }
        )
        SnackbarHost(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            hostState = snackbarHost
        ) { data -> Snackbar(data) }
    }
}
