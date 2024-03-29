package com.originalstocks.facedetectionmlkit

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFace
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import com.originalstocks.facedetectormlkit.Helper.RectangleOverlay
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val REQUEST_CODE_PERMISSIONS = 10

class MainActivity : AppCompatActivity() {

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var viewFinder: TextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        viewFinder = findViewById(R.id.view_finder)

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post {
                graphic_overlay.clear()
                startCamera()
            }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewFinder.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            updateTransform()
        }


    }// onCreate closes


    private fun runFaceDetector(bitmap: Bitmap?) {

        val options = FirebaseVisionFaceDetectorOptions.Builder()
            .setContourMode(FirebaseVisionFaceDetectorOptions.ALL_CONTOURS)
            .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
            .setClassificationMode(FirebaseVisionFaceDetectorOptions.ALL_CLASSIFICATIONS)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()

        val metadata = FirebaseVisionImageMetadata.Builder()
            .setWidth(480) // 480x360 is typically sufficient for
            .setHeight(360) // image recognition
            .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
            .build()

        val image = FirebaseVisionImage.fromBitmap(bitmap!!)
        // val streamPath = FirebaseVisionImage.fromFilePath(this, Uri.fromFile(file))
        val detector = FirebaseVision.getInstance().getVisionFaceDetector(options)

        detector.detectInImage(image)
            .addOnSuccessListener { result -> processFaceDetectionResult(result) }
            .addOnFailureListener { e ->
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }

    }

    private fun processFaceDetectionResult(result: List<FirebaseVisionFace>) {

        var count = 0
        for (face in result) {
            val bounds = face.boundingBox
            val rectOverlay = RectangleOverlay(graphic_overlay, bounds)

            graphic_overlay.add(rectOverlay)
            count++
        }
        Log.d("CameraFaceDetection", " = $count")
        Toast.makeText(this, String.format("Detected %d faces", count), Toast.LENGTH_SHORT).show()

    }

    private fun startCamera() {
        // Implementing CameraX operations

        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetResolution(Size(1920, 1080))
        }.build()


        // Build the viewfinder use case
        val preview = Preview(previewConfig)


        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        // Create configuration object for the image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()

        // Build the image capture use case and attach button click listener
        val imageCapture = ImageCapture(imageCaptureConfig)

        findViewById<ImageButton>(R.id.capture_button).setOnClickListener {

            val file = File(
                externalMediaDirs.first(),
                "${System.currentTimeMillis()}.jpg"
            )

            imageCapture.takePicture(file, executor,
                object : ImageCapture.OnImageSavedListener {

                    override fun onImageSaved(file: File) {
                        val msg = "Photo capture succeeded: ${file.absolutePath}"
                        Log.d("CameraXApp", msg)
                        viewFinder.post {
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            val bitmap = getBitmap(file.absolutePath)

                            // val dummyBitmap = BitmapFactory.decodeResource(resources, R.drawable.user6)
                            if (bitmap != null){
                                runFaceDetector(bitmap)
                            }else{
                                Toast.makeText(this@MainActivity, "Bitmap is null", Toast.LENGTH_LONG).show()
                            }

                        }
                    }

                    override fun onError(
                        imageCaptureError: ImageCapture.ImageCaptureError,
                        message: String,
                        exc: Throwable?
                    ) {
                        val msg = "Photo capture failed: $message"
                        Log.e("CameraXApp", msg, exc)
                        viewFinder.post {
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        }
                    }

                })
        }

        // Setup image analysis pipeline that computes average pixel luminance
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE
            )
        }.build()

        // Build the image analysis use case and instantiate our analyzer
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            setAnalyzer(executor, LuminosityAnalyzer())
        }

        // binding cameraX to lifecycle aware workflow
        CameraX.bindToLifecycle(
            this, preview, imageCapture, analyzerUseCase
        )
    }

    private fun updateTransform() {
        // Implementing camera viewfinder transformations
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 1f
        val centerY = viewFinder.height / 1f

        // Correct preview output to account for display rotation
        val rotationDegrees = when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private class LuminosityAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L

        /**
         * Helper extension function used to extract a byte array from an
         * image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            val currentTimestamp = System.currentTimeMillis()
            // Calculate the average luma no more often than every second
            if (currentTimestamp - lastAnalyzedTimestamp >=
                TimeUnit.SECONDS.toMillis(1)
            ) {
                // Since format in ImageAnalysis is YUV, image.planes[0]
                // contains the Y (luminance) plane
                val buffer = image.planes[0].buffer
                // Extract image data from callback object
                val data = buffer.toByteArray()
                // Convert the data into an array of pixel values
                val pixels = data.map { it.toInt() and 0xFF }
                // Compute average luminance for the image
                val luma = pixels.average()
                // Log the new luma value
                Log.d("CameraXApp", "Average luminosity: $luma")
                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp
            }
        }
    }

    private fun getBitmap(path: String?): Bitmap? {
        return try {
            var bitmap: Bitmap? = null
            val f = File(path)
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            bitmap = BitmapFactory.decodeStream(FileInputStream(f), null, options)
            // capturedImageView.setImageBitmap(bitmap)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


}
