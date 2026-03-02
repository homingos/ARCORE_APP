package com.example.arcoreapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.PixelCopy
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.arcoreapp.databinding.ActivityMainBinding
import com.example.arcoreapp.opengl.ARRenderer
import com.google.ar.core.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ARRenderer
    private var arSession: Session? = null
    
    private lateinit var captureManager: CaptureManager
    private var frameCount = 0

    // Transform properties (Manual fitting) - values in centimeters
    private var scaleX = 7.0f
    private var scaleY = 15f
    private var scaleZ = 7.0f
    private var rotX = 0f
    private var rotY = 0f
    private var rotZ = 0f
    private var transX = 0f
    private var transY = 0.0f
    private var transZ = 0f

    private var isRecording = false
    private var isProcessingFrame = false
    private var lastFrameTime = 0L
    private var selectedCategory = "red" // Default to red coke
    private var stableMarkerFrames = 0

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val CAPTURE_SIZE = 512
        private const val MARKER_STABLE_FRAMES_REQUIRED = 8
    }

    private var installRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        glSurfaceView = binding.glSurfaceView
        captureManager = CaptureManager(this)

        updateProgressUI()
        setupRenderer()
        setupControls()
    }

    private fun setupRenderer() {
        renderer = ARRenderer(this)
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glSurfaceView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                renderer.onTouch(event)
            }
            true
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Do nothing, onResume will handle session setup
            } else {
                Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupARSession() {
        if (arSession != null) return

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> {}
            }

            if (!checkCameraPermission()) {
                requestCameraPermission()
                return
            }

            arSession = Session(this)
            val config = Config(arSession)
            if (arSession!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            config.instantPlacementMode = Config.InstantPlacementMode.DISABLED // Force stable Plane tracking for dataset recording
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.BLOCKING
            
            // --- AUGMENTED IMAGES (Marker-Based Snap) ---
            val imageDatabase = AugmentedImageDatabase(arSession)
            try {
                val inputStream = assets.open("markers/coke_marker.png")
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                // We assume the marker is 10cm wide physically on the can
                imageDatabase.addImage("coke_marker", bitmap, 0.10f)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load marker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            config.augmentedImageDatabase = imageDatabase
            
            arSession!!.configure(config)
            
            renderer.setArSession(arSession!!)
        } catch (e: Exception) {
            val message = when (e) {
                is com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException -> "Please install ARCore"
                is com.google.ar.core.exceptions.UnavailableApkTooOldException -> "Please update ARCore"
                is com.google.ar.core.exceptions.UnavailableSdkTooOldException -> "Please update this app"
                is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                else -> "Failed to create AR session: " + e.message
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    fun onAnchorPlaced() {
        binding.statusText.text = "Box anchored. Adjust fit below."
    }

    private var lastStatusState = ""

    fun onFrameRendered(frame: Frame, model: FloatArray, view: FloatArray) {
        if (isRecording && !isProcessingFrame) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime >= 33) { // ~30 FPS
                processFrameForRecording(frame, model, view)
                lastFrameTime = currentTime
            }
        }
        
        val updatedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
        val markerImage =
            updatedImages.firstOrNull {
                it.name == "coke_marker" &&
                    it.trackingState == TrackingState.TRACKING &&
                    it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
            }

        if (markerImage != null) {
            renderer.trackedImage = markerImage
            if (renderer.currentAnchor == null) {
                stableMarkerFrames += 1
                if (stableMarkerFrames >= MARKER_STABLE_FRAMES_REQUIRED) {
                    renderer.snapToImage(markerImage)
                    renderer.trackedImage = null
                    stableMarkerFrames = 0
                    updateStatusTextOnce("Marker locked. Box anchored to can.")
                } else {
                    updateStatusTextOnce("Tracking marker... (${stableMarkerFrames}/${MARKER_STABLE_FRAMES_REQUIRED})")
                }
            } else {
                stableMarkerFrames = 0
                val relocked = renderer.tryCorrectAnchorWithMarker(markerImage)
                if (relocked) {
                    updateStatusTextOnce("Anchor corrected using marker relocalization.")
                }
            }
        } else {
            stableMarkerFrames = 0
            renderer.trackedImage = null
        }

        // Update UI status based on tracking state
        if (renderer.currentAnchor == null && renderer.trackedImage == null) {
            // Check if we have any valid planes for feedback
            val isPlaneDetected = renderer.hasTrackingPlane()
            if (isPlaneDetected) {
                updateStatusTextOnce("Surface Detected! Tap to Place.")
            } else {
                updateStatusTextOnce("Scanning... Move device to detect floor.")
            }
        } else if (renderer.currentAnchor != null && renderer.trackedImage == null) {
            updateStatusTextOnce("Box anchored. Adjust fit below.")
        }
    }

    private fun updateStatusTextOnce(text: String) {
        if (lastStatusState == text) return
        lastStatusState = text
        runOnUiThread {
            binding.statusText.text = text
        }
    }

    private fun processFrameForRecording(frame: Frame, model: FloatArray, view: FloatArray) {
        if (arSession == null) return
        isProcessingFrame = true
        val camera = frame.camera
        
        // 1. Acquire Camera Image (YUV-420-888)
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            null
        }
        
        if (image == null) {
            isProcessingFrame = false
            return
        }

        // 2. Convert to Bitmap (640x480 Landscape) using robust YuvImage approach
        val rawBitmap = try {
            val bmp = YuvToRgbConverter.yuvToBitmap(image)
            image.close() // Release YUV image immediately
            bmp
        } catch (e: Exception) {
            image.close()
            e.printStackTrace()
            isProcessingFrame = false
            return
        }

        if (rawBitmap == null) {
            isProcessingFrame = false
            return
        }

        // 3. Convert camera image to square 512x512 by center-crop + resize.
        val srcW = rawBitmap.width
        val srcH = rawBitmap.height
        val cropSide = minOf(srcW, srcH)
        val cropLeft = (srcW - cropSide) / 2
        val cropTop = (srcH - cropSide) / 2
        val croppedBitmap = Bitmap.createBitmap(rawBitmap, cropLeft, cropTop, cropSide, cropSide)
        val finalBitmap = Bitmap.createScaledBitmap(croppedBitmap, CAPTURE_SIZE, CAPTURE_SIZE, true)
        if (croppedBitmap !== rawBitmap) {
            croppedBitmap.recycle()
        }
        rawBitmap.recycle()

        // 4. Intrinsics remap for crop+resize to 512x512.
        val intrinsics = camera.imageIntrinsics
        val oldLx = intrinsics.imageDimensions[0]
        val oldLy = intrinsics.imageDimensions[1]
        val oldFx = intrinsics.focalLength[0]
        val oldFy = intrinsics.focalLength[1]
        val oldCx = intrinsics.principalPoint[0]
        val oldCy = intrinsics.principalPoint[1]
        val cropScale = CAPTURE_SIZE.toFloat() / cropSide.toFloat()
        val newFx = oldFx * cropScale
        val newFy = oldFy * cropScale
        val newCx = (oldCx - cropLeft) * cropScale
        val newCy = (oldCy - cropTop) * cropScale
        val newW = CAPTURE_SIZE
        val newH = CAPTURE_SIZE

        // Use sensor view matrix directly (no artificial 90-degree rotation transform).
        val newViewMatrix = FloatArray(16)
        camera.pose.inverse().toMatrix(newViewMatrix, 0)

        val frameId = frameCount++
        val imageName = String.format("%06d.png", frameId)
        
        val entry = AnnotationGenerator.createEntry(
            frameId, imageName, model, newViewMatrix,
            newFx, newFy,
            newCx, newCy,
            newW, newH
        )

        // Save unified 512x512 frame.
        captureManager.saveFrame(finalBitmap, entry)
        isProcessingFrame = false
    }

    override fun onResume() {
        super.onResume()
        setupARSession()
        try {
            arSession?.resume()
        } catch (e: Exception) {
            arSession = null
            setupARSession()
        }
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        arSession = null
    }

    private fun setupControls() {
        // High precision steps: 0.1cm for linear, 1.0 degree for rotation
        setupRow(binding.ctrlScaleX, "Scale X", scaleX, 0.1f) { scaleX = it; renderer.mScaleX = it }
        setupRow(binding.ctrlScaleY, "Scale Y", scaleY, 0.1f) { scaleY = it; renderer.mScaleY = it }
        setupRow(binding.ctrlScaleZ, "Scale Z", scaleZ, 0.1f) { scaleZ = it; renderer.mScaleZ = it }

        setupRow(binding.ctrlRotX, "Rot X", rotX, 1.0f) { rotX = it; renderer.mRotationX = it }
        setupRow(binding.ctrlRotY, "Rot Y", rotY, 1.0f) { rotY = it; renderer.mRotationY = it }
        setupRow(binding.ctrlRotZ, "Rot Z", rotZ, 1.0f) { rotZ = it; renderer.mRotationZ = it }

        setupRow(binding.ctrlTransX, "Trans X", transX, 0.1f) { transX = it; renderer.mTranslationX = it }
        setupRow(binding.ctrlTransY, "Trans Y", transY, 0.1f) { transY = it; renderer.mTranslationY = it }
        setupRow(binding.ctrlTransZ, "Depth / Z", transZ, 0.1f) { transZ = it; renderer.mTranslationZ = it }

        binding.swCamLock.setOnCheckedChangeListener { _, isChecked ->
            renderer.isCameraLocked = isChecked
            if (isChecked) {
                binding.statusText.text = "Locked to Camera! (Zero Drift Mode)"
            } else {
                binding.statusText.text = "Switched to World Anchor mode."
            }
        }

        binding.swAutoDepth.setOnCheckedChangeListener { _, isChecked ->
            renderer.isAutoDepthEnabled = isChecked
            if (isChecked) {
                binding.statusText.text = "Auto-Depth Sync Active"
            }
        }

        binding.swCylinderMode.setOnCheckedChangeListener { _, isChecked ->
            renderer.isCylinderMode = isChecked
            if (isChecked) {
                binding.statusText.text = "Visualizing as Cylinder (Wireframe)"
            } else {
                binding.statusText.text = "Visualizing as Box (Objectron)"
            }
        }

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnAir.setOnClickListener { 
            renderer.placeInAir(useDepth = true)
            binding.statusText.text = "Snapped to Depth. Adjust fit manually."
        }
        binding.btnExport.setOnClickListener {
            val capturePath = captureManager.getCapturePath()
            val directory = File(capturePath)
            if (directory.exists() && !isRecording) {
                val zipFile = File(directory.parent, "${directory.name}.zip")
                ZipUtils.zipDirectory(directory, zipFile)
                Toast.makeText(this, "Dataset zipped to: ${zipFile.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Stop recording first or capture data.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnReset.setOnClickListener {
            resetTransforms()
        }

        // --- CATEGORY LISTENERS ---
        binding.rgCategory.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.rbRed.id -> {
                    selectedCategory = "red"
                    renderer.updateBoxColor(floatArrayOf(1.0f, 0.4f, 0.4f, 1.0f))
                    binding.statusText.text = "Mode: RED COKE"
                }
                binding.rbBlue.id -> {
                    selectedCategory = "blue"
                    renderer.updateBoxColor(floatArrayOf(0.4f, 0.4f, 1.0f, 1.0f))
                    binding.statusText.text = "Mode: BLUE THUMSUP"
                }
                binding.rbSilver.id -> {
                    selectedCategory = "silver"
                    renderer.updateBoxColor(floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f))
                    binding.statusText.text = "Mode: SILVER DIET COKE"
                }
            }
        }
    }

    private fun updateProgressUI() {
        val red = captureManager.getCount("red")
        val blue = captureManager.getCount("blue")
        val silver = captureManager.getCount("silver")

        binding.tvProgressRed.text = "Red: $red/25"
        binding.tvProgressBlue.text = "Blue: $blue/10"
        binding.tvProgressSilver.text = "Silver: $silver/10"

        // Highlight completion in green
        if (red >= 25) binding.tvProgressRed.setTextColor(0xFF00FF00.toInt())
        if (blue >= 10) binding.tvProgressBlue.setTextColor(0xFF00FF00.toInt())
        if (silver >= 10) binding.tvProgressSilver.setTextColor(0xFF00FF00.toInt())
    }

    private fun resetTransforms() {
        scaleX = 7.0f; scaleY = 15f; scaleZ = 7.0f
        rotX = 0f; rotY = 0f; rotZ = 0f
        transX = 0f; transY = 0.0f; transZ = 0f
        
        renderer.mScaleX = scaleX; renderer.mScaleY = scaleY; renderer.mScaleZ = scaleZ
        renderer.mRotationX = rotX; renderer.mRotationY = rotY; renderer.mRotationZ = rotZ
        renderer.mTranslationX = transX; renderer.mTranslationY = transY; renderer.mTranslationZ = transZ
        
        renderer.resetAnchor()
        stableMarkerFrames = 0
        
        binding.statusText.text = "Transforms reset. Tap to re-anchor."
        Toast.makeText(this, "All values reset to defaults", Toast.LENGTH_SHORT).show()
    }

    private fun setupRow(rowBinding: com.example.arcoreapp.databinding.LayoutControlRowBinding, label: String, initialValue: Float, step: Float = 0.5f, onUpdate: (Float) -> Unit) {
        var current = initialValue
        rowBinding.label.text = label
        rowBinding.valueText.text = String.format("%.1f", current)
        
        rowBinding.btnPlus.setOnClickListener {
            current += step
            // Clean up float precision issues
            current = Math.round(current * 100f) / 100f
            rowBinding.valueText.text = String.format("%.1f", current)
            onUpdate(current)
        }
        rowBinding.btnMinus.setOnClickListener {
            current -= step
            if (label.contains("Scale") && current < 0.1f) current = 0.1f
            current = Math.round(current * 100f) / 100f
            rowBinding.valueText.text = String.format("%.1f", current)
            onUpdate(current)
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            if (renderer.currentAnchor == null) {
                val marker = renderer.trackedImage
                if (marker != null &&
                    marker.trackingState == TrackingState.TRACKING &&
                    marker.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                ) {
                    renderer.snapToImage(marker)
                    renderer.trackedImage = null
                } else {
                    Toast.makeText(this, "Place box first!", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            renderer.pinCurrentTrackedImageAsAnchor()
            renderer.isRecordingCapture = true
            isRecording = true
            frameCount = 0
            captureManager.startNewSequence(selectedCategory, arSession)
            binding.btnRecord.text = "STOP RECORDING"
            binding.fpsText.text = "Status: RECORDING ($selectedCategory)"
        } else {
            renderer.isRecordingCapture = false
            renderer.resetPoseSmoothing()
            isRecording = false
            captureManager.finishSequence(selectedCategory, arSession)
            updateProgressUI()
            binding.btnRecord.text = "START RECORDING"
            binding.fpsText.text = "Status: IDLE (Saved ${frameCount} frames)"
        }
    }
}
