package com.example.arcoreapp.opengl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.example.arcoreapp.MainActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var backgroundRenderer = BackgroundRenderer()
    private var customBox: CustomBoundingBox? = null
    private var customCylinder: CustomCylinder? = null
    private var program = -1
    private var session: Session? = null
    
    private var mvpMatrix = FloatArray(16)
    private var projectionMatrix = FloatArray(16)
    private var viewMatrix = FloatArray(16)
    private var anchorMatrix = FloatArray(16)
    private var smoothedMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val poseTranslationAlpha = 0.20f
    private val poseRotationAlpha = 0.18f
    private var poseInitialized = false
    private val smoothedTranslation = FloatArray(3)
    private val smoothedQuaternion = FloatArray(4) // [x,y,z,w]

    private var positionHandle = -1
    private var colorHandle = -1
    private var mvpMatrixHandle = -1
    private var tintHandle = -1

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
          gl_Position = uMVPMatrix * vPosition;
          vColor = aColor; 
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        uniform vec4 uTint;
        varying vec4 vColor;
        void main() {
          gl_FragColor = vColor * uTint;
        }
    """.trimIndent()

    // Transform properties (centimeters) - MARKED VOLATILE FOR THREAD SAFETY
    @Volatile var mScaleX = 7.0f
    @Volatile var mScaleY = 15.0f
    @Volatile var mScaleZ = 7.0f
    @Volatile var mRotationX = 0.0f
    @Volatile var mRotationY = 0.0f
    @Volatile var mRotationZ = 0.0f
    @Volatile var mTranslationX = 0.0f
    @Volatile var mTranslationY = 0.0f
    @Volatile var mTranslationZ = 0.0f // This will now act as our "Depth" control
    @Volatile var mManualDepth = 50.0f // Initial placement distance in cm
    @Volatile var isCameraLocked = false // Handheld stability mode
    @Volatile var isAutoDepthEnabled = false // Continuous depth tracking
    @Volatile var isCylinderMode = false // Toggle between Box and Cylinder
    @Volatile var isRecordingCapture = false
    @Volatile var enableLiveMarkerFollow = false // Disabled for production stability; use anchor lock instead.
    private var markerRelockCandidateFrames = 0
    private var lastMarkerRelockMs = 0L
    private val markerRelockFrameThreshold = 4
    private val markerRelockCooldownMs = 1200L
    private val markerRelockDistanceThresholdM = 0.012f // 1.2 cm
    private val markerRelockAngleThresholdDeg = 4.0f

    // Bounding Box Color [R, G, B, A]
    @Volatile var mBoxColor = floatArrayOf(1.0f, 0.0f, 0.0f, 0.3f) // Default Red

    fun updateBoxColor(color: FloatArray) {
        mBoxColor = color
    }

    @Volatile var currentAnchor: Anchor? = null
        private set
    
    @Volatile var trackedImage: com.google.ar.core.AugmentedImage? = null

    @Synchronized
    fun resetAnchor() {
        currentAnchor?.detach()
        currentAnchor = null
        markerRelockCandidateFrames = 0
    }

    @Synchronized
    fun pinCurrentTrackedImageAsAnchor() {
        val image = trackedImage
        if (image != null && image.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            resetAnchor()
            currentAnchor = image.createAnchor(image.centerPose)
            resetPoseSmoothing()
            image.centerPose.toMatrix(smoothedMatrix, 0)
            trackedImage = null
        }
    }

    @Synchronized
    fun resetPoseSmoothing() {
        poseInitialized = false
        Matrix.setIdentityM(smoothedMatrix, 0)
    }

    private fun normalizeQuat(q: FloatArray): FloatArray {
        val n = kotlin.math.sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat()
        if (n <= 1e-8f) return floatArrayOf(0f, 0f, 0f, 1f)
        return floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    private fun slerpQuat(q0In: FloatArray, q1In: FloatArray, t: Float): FloatArray {
        var q0 = normalizeQuat(q0In)
        var q1 = normalizeQuat(q1In)
        var dot = q0[0] * q1[0] + q0[1] * q1[1] + q0[2] * q1[2] + q0[3] * q1[3]

        if (dot < 0f) {
            dot = -dot
            q1 = floatArrayOf(-q1[0], -q1[1], -q1[2], -q1[3])
        }

        if (dot > 0.9995f) {
            val out = floatArrayOf(
                q0[0] + t * (q1[0] - q0[0]),
                q0[1] + t * (q1[1] - q0[1]),
                q0[2] + t * (q1[2] - q0[2]),
                q0[3] + t * (q1[3] - q0[3])
            )
            return normalizeQuat(out)
        }

        val theta0 = kotlin.math.acos(dot.toDouble()).toFloat()
        val sinTheta0 = kotlin.math.sin(theta0.toDouble()).toFloat().coerceAtLeast(1e-8f)
        val theta = theta0 * t
        val sinTheta = kotlin.math.sin(theta.toDouble()).toFloat()
        val s0 = kotlin.math.cos(theta.toDouble()).toFloat() - dot * sinTheta / sinTheta0
        val s1 = sinTheta / sinTheta0
        return floatArrayOf(
            s0 * q0[0] + s1 * q1[0],
            s0 * q0[1] + s1 * q1[1],
            s0 * q0[2] + s1 * q1[2],
            s0 * q0[3] + s1 * q1[3]
        )
    }

    private fun updateSmoothedPose(targetPose: Pose): Pose {
        val t = targetPose.translation
        val q = targetPose.rotationQuaternion // [x,y,z,w]
        if (!poseInitialized) {
            smoothedTranslation[0] = t[0]
            smoothedTranslation[1] = t[1]
            smoothedTranslation[2] = t[2]
            smoothedQuaternion[0] = q[0]
            smoothedQuaternion[1] = q[1]
            smoothedQuaternion[2] = q[2]
            smoothedQuaternion[3] = q[3]
            poseInitialized = true
        } else {
            smoothedTranslation[0] += poseTranslationAlpha * (t[0] - smoothedTranslation[0])
            smoothedTranslation[1] += poseTranslationAlpha * (t[1] - smoothedTranslation[1])
            smoothedTranslation[2] += poseTranslationAlpha * (t[2] - smoothedTranslation[2])
            val qOut = slerpQuat(smoothedQuaternion, q, poseRotationAlpha)
            smoothedQuaternion[0] = qOut[0]
            smoothedQuaternion[1] = qOut[1]
            smoothedQuaternion[2] = qOut[2]
            smoothedQuaternion[3] = qOut[3]
        }
        val pT = Pose.makeTranslation(smoothedTranslation[0], smoothedTranslation[1], smoothedTranslation[2])
        val pR = Pose.makeRotation(smoothedQuaternion[0], smoothedQuaternion[1], smoothedQuaternion[2], smoothedQuaternion[3])
        return pT.compose(pR)
    }

    fun placeInAir(useDepth: Boolean = true) {
        val session = this.session ?: return
        try {
            val frame = session.update()
            val cameraPose = frame.camera.pose
            
            var distanceInMeters = mManualDepth / 100f
            
            if (useDepth) {
                // Better Depth API usage: Sample center of screen
                val hits = frame.hitTest(frame.camera.imageIntrinsics.imageDimensions[0] / 2f, 
                                        frame.camera.imageIntrinsics.imageDimensions[1] / 2f)
                val depthHit = hits.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }
                if (depthHit != null) {
                    distanceInMeters = depthHit.hitPose.tz().let { Math.abs(it) } 
                    mManualDepth = distanceInMeters * 100f
                }
            }

            val relativePose = com.google.ar.core.Pose.makeTranslation(0f, 0f, -distanceInMeters)
            val airPose = cameraPose.compose(relativePose)
            
            resetAnchor()
            synchronized(this) {
                currentAnchor = session.createAnchor(airPose)
            }
            
            (context as MainActivity).runOnUiThread {
                context.onAnchorPlaced()
            }
        } catch (e: Exception) {}
    }

    private val queuedTaps = java.util.concurrent.ArrayBlockingQueue<android.view.MotionEvent>(16)

    fun onTouch(event: android.view.MotionEvent) {
        queuedTaps.offer(event)
    }

    fun setArSession(session: Session) {
        this.session = session
    }

    fun snapToImage(image: com.google.ar.core.AugmentedImage) {
        if (this.session == null) return
        // Create an anchor at the center of the image
        resetAnchor()
        synchronized(this) {
            currentAnchor = image.createAnchor(image.centerPose)
            resetPoseSmoothing()
            image.centerPose.toMatrix(smoothedMatrix, 0)
        }
    }

    private fun distance3(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun rotationDeltaDeg(qAIn: FloatArray, qBIn: FloatArray): Float {
        val qA = normalizeQuat(qAIn)
        val qB = normalizeQuat(qBIn)
        val dotRaw = qA[0] * qB[0] + qA[1] * qB[1] + qA[2] * qB[2] + qA[3] * qB[3]
        val dot = kotlin.math.abs(dotRaw).coerceIn(0f, 1f)
        return (2.0 * kotlin.math.acos(dot.toDouble()) * 180.0 / Math.PI).toFloat()
    }

    @Synchronized
    fun tryCorrectAnchorWithMarker(image: AugmentedImage): Boolean {
        val anchor = currentAnchor ?: return false
        if (image.trackingState != com.google.ar.core.TrackingState.TRACKING) return false
        if (image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) return false
        if (anchor.trackingState != com.google.ar.core.TrackingState.TRACKING) return false

        val now = System.currentTimeMillis()
        if (now - lastMarkerRelockMs < markerRelockCooldownMs) return false

        val anchorPose = anchor.pose
        val imagePose = image.centerPose

        val distanceErrorM = distance3(anchorPose.translation, imagePose.translation)
        val angleErrorDeg = rotationDeltaDeg(anchorPose.rotationQuaternion, imagePose.rotationQuaternion)
        val shouldRelock =
            distanceErrorM > markerRelockDistanceThresholdM || angleErrorDeg > markerRelockAngleThresholdDeg

        if (!shouldRelock) {
            markerRelockCandidateFrames = 0
            return false
        }

        markerRelockCandidateFrames += 1
        if (markerRelockCandidateFrames < markerRelockFrameThreshold) return false

        resetAnchor()
        currentAnchor = image.createAnchor(image.centerPose)
        resetPoseSmoothing()
        image.centerPose.toMatrix(smoothedMatrix, 0)
        lastMarkerRelockMs = now
        markerRelockCandidateFrames = 0
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        backgroundRenderer.createOnGlThread()
        customBox = CustomBoundingBox()
        customCylinder = CustomCylinder(50)

        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val displayRotation = (context as MainActivity).windowManager.defaultDisplay.rotation
        session?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return
        
        try {
            session.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session.update()
            val camera = frame.camera

            backgroundRenderer.draw(frame)
            
            // Continuous Depth Sync logic
            if (isAutoDepthEnabled) {
                try {
                    val hits = frame.hitTest(frame.camera.imageIntrinsics.imageDimensions[0] / 2f, 
                                            frame.camera.imageIntrinsics.imageDimensions[1] / 2f)
                    val depthHit = hits.firstOrNull { it.trackable is com.google.ar.core.DepthPoint }
                    if (depthHit != null) {
                        val distanceInMeters = depthHit.hitPose.tz().let { Math.abs(it) }
                        mManualDepth = distanceInMeters * 100f
                    }
                } catch (e: Exception) {}
            }

            handleTaps(frame, camera)

            if (camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                camera.getViewMatrix(viewMatrix, 0)

                // --- DYNAMIC TARGET LOGIC (Follow-the-Marker) ---
                val activeImage = if (isRecordingCapture) null else trackedImage
                if (
                    enableLiveMarkerFollow &&
                    activeImage != null &&
                    activeImage.trackingState == com.google.ar.core.TrackingState.TRACKING &&
                    activeImage.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                ) {
                    // Pose-level smoothing avoids matrix shearing/drift artifacts.
                    val filteredPose = updateSmoothedPose(activeImage.centerPose)
                    filteredPose.toMatrix(smoothedMatrix, 0)

                    val userOffset = FloatArray(16)
                    Matrix.setIdentityM(userOffset, 0)
                    Matrix.translateM(userOffset, 0, mTranslationX / 100f, mTranslationY / 100f, mTranslationZ / 100f)
                    Matrix.rotateM(userOffset, 0, mRotationX, 1f, 0f, 0f)
                    Matrix.rotateM(userOffset, 0, mRotationY, 0f, 1f, 0f)
                    Matrix.rotateM(userOffset, 0, mRotationZ, 0f, 0f, 1f)
                    Matrix.scaleM(userOffset, 0, mScaleX / 100f, mScaleY / 100f, mScaleZ / 100f)

                    Matrix.multiplyMM(anchorMatrix, 0, smoothedMatrix, 0, userOffset, 0)
                    val viewModelMatrix = FloatArray(16)
                    Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, anchorMatrix, 0)
                    drawBox(projectionMatrix, viewModelMatrix)
                    
                } else if (isCameraLocked) {
                    resetPoseSmoothing()
                    val directModelView = FloatArray(16)
                    Matrix.setIdentityM(directModelView, 0)
                    val totalZMeter = -(mManualDepth + mTranslationZ) / 100f
                    Matrix.translateM(directModelView, 0, mTranslationX / 100f, mTranslationY / 100f, totalZMeter)
                    Matrix.rotateM(directModelView, 0, mRotationX, 1f, 0f, 0f)
                    Matrix.rotateM(directModelView, 0, mRotationY, 0f, 1f, 0f)
                    Matrix.rotateM(directModelView, 0, mRotationZ, 0f, 0f, 1f)
                    Matrix.scaleM(directModelView, 0, mScaleX / 100f, mScaleY / 100f, mScaleZ / 100f)

                    // ZERO-DRIFT DRAW
                    drawBox(projectionMatrix, directModelView)

                    // EXPORT ANNOTATION MATRIX
                    val cameraPoseMatrix = FloatArray(16)
                    camera.pose.toMatrix(cameraPoseMatrix, 0)
                    Matrix.multiplyMM(anchorMatrix, 0, cameraPoseMatrix, 0, directModelView, 0)
                } else {
                    resetPoseSmoothing()
                    // --- WORLD ANCHOR MODE (Standard ARCore Surface Tracking) ---
                    val anchor = currentAnchor
                    if (anchor != null && anchor.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                        // 1. Get the raw anchor pose
                        anchor.pose.toMatrix(anchorMatrix, 0)
                        
                        // 2. Apply transformations DIRECTLY to the anchor matrix (Standard Practice)
                        Matrix.translateM(anchorMatrix, 0, mTranslationX / 100f, mTranslationY / 100f, mTranslationZ / 100f)
                        Matrix.rotateM(anchorMatrix, 0, mRotationX, 1f, 0f, 0f)
                        Matrix.rotateM(anchorMatrix, 0, mRotationY, 0f, 1f, 0f)
                        Matrix.rotateM(anchorMatrix, 0, mRotationZ, 0f, 0f, 1f)
                        Matrix.scaleM(anchorMatrix, 0, mScaleX / 100f, mScaleY / 100f, mScaleZ / 100f)

                        // 3. Draw
                        val viewModelMatrix = FloatArray(16)
                        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, anchorMatrix, 0)
                        drawBox(projectionMatrix, viewModelMatrix)
                    }
                }
            }
            
            // Callback to Activity for frame processing if needed
            (context as MainActivity).onFrameRendered(frame, anchorMatrix, viewMatrix)

        } catch (t: Throwable) {
            // Avoid crashing
        }
    }

    private fun handleTaps(frame: com.google.ar.core.Frame, camera: com.google.ar.core.Camera) {
        val tap = queuedTaps.poll() ?: return
        if (camera.trackingState != com.google.ar.core.TrackingState.TRACKING) return

        val hits = frame.hitTest(tap)
        // PRIORITY: STRICTLY HORIZONTAL PLANES ONLY for Data Recording Stability
        val bestHit = hits.firstOrNull { hit ->
            val t = hit.trackable
            (t is com.google.ar.core.Plane && t.trackingState == com.google.ar.core.TrackingState.TRACKING && 
             t.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING)
        }
        // Removed fallback to DepthPoint to prevent drift.

        if (bestHit != null) {
            val hitPose = bestHit.hitPose
            
            // For ground planes, we force UPRIGHT to prevent annoying tilt on placement.
            // For depth points, we use the original hitPose for better handheld alignment.
            val finalPose = if (bestHit.trackable is com.google.ar.core.Plane) {
                 com.google.ar.core.Pose.makeTranslation(hitPose.tx(), hitPose.ty(), hitPose.tz())
            } else {
                hitPose
            }
            
            // GL Thread safe update
            resetAnchor()
            synchronized(this) {
                currentAnchor = bestHit.trackable.createAnchor(finalPose)
            }
            
            (context as MainActivity).runOnUiThread {
                context.onAnchorPlaced()
            }
        }
    }


    private fun drawBox(proj: FloatArray, viewModel: FloatArray) {
        Matrix.multiplyMM(mvpMatrix, 0, proj, 0, viewModel, 0)
        
        GLES30.glUseProgram(program)
        positionHandle = GLES30.glGetAttribLocation(program, "vPosition")
        colorHandle = GLES30.glGetAttribLocation(program, "aColor")
        mvpMatrixHandle = GLES30.glGetUniformLocation(program, "uMVPMatrix")
        tintHandle = GLES30.glGetUniformLocation(program, "uTint")

        // Set Tint
        GLES30.glUniform4fv(tintHandle, 1, mBoxColor, 0)

        if (isCylinderMode) {
            customCylinder?.draw(positionHandle, mvpMatrixHandle, mvpMatrix)
        } else {
            customBox?.draw(positionHandle, colorHandle, mvpMatrixHandle, mvpMatrix)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        return shader
    }
    
    fun getTextureId(): Int = backgroundRenderer.textureId
    
    fun hasTrackingPlane(): Boolean {
        val session = this.session ?: return false
        return session.getAllTrackables(com.google.ar.core.Plane::class.java).any { 
            it.trackingState == com.google.ar.core.TrackingState.TRACKING &&
            it.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING
        }
    }
}
