package com.example.arcoreapp

import com.google.gson.annotations.SerializedName

data class AnnotationEntry(
    @SerializedName("frame_id") val frameId: Int,
    @SerializedName("image") val image: String,
    @SerializedName("orientation_pitch_deg") val orientationPitchDeg: Float,
    @SerializedName("orientation_label") val orientationLabel: String,
    @SerializedName("keypoints_2d") val keypoints2d: List<List<Float>>,
    @SerializedName("keypoints_3d") val keypoints3d: List<List<Float>>,
    @SerializedName("visibility") val visibility: List<Float>,
    @SerializedName("keypoints_2d_visibility") val keypoints2dVisibility: List<Float>,
    @SerializedName("camera_intrinsics") val cameraIntrinsics: CameraIntrinsics,
    @SerializedName("view_matrix") val viewMatrix: List<Float>,
    @SerializedName("model_matrix") val modelMatrix: List<Float>,
    @SerializedName("pose_6dof") val pose6dof: Pose6Dof,
    @SerializedName("pose_9dof") val pose9dof: Pose9Dof,
    @SerializedName("environment") val environment: EnvironmentMeta
)

data class CameraIntrinsics(
    @SerializedName("fx") val fx: Float,
    @SerializedName("fy") val fy: Float,
    @SerializedName("cx") val cx: Float,
    @SerializedName("cy") val cy: Float,
    @SerializedName("image_width") val imageWidth: Int,
    @SerializedName("image_height") val imageHeight: Int
)

data class Pose6Dof(
    @SerializedName("translation") val translation: List<Float>,
    @SerializedName("rotation") val rotation: List<Float>
)

data class Pose9Dof(
    @SerializedName("translation") val translation: List<Float>,
    @SerializedName("rotation") val rotation: List<Float>,
    @SerializedName("scale") val scale: List<Float>
)

data class EnvironmentMeta(
    @SerializedName("hdri_count_available") val hdriCountAvailable: Int,
    @SerializedName("hdri_path") val hdriPath: String
)

object AnnotationGenerator {

    private val UNIT_CUBE_POINTS = listOf(
        floatArrayOf(0f, 0.5f, 0f),      // 0: Centroid (Middle of the box)
        floatArrayOf(-0.5f, 0.0f, 0.5f), // 1: Front-Bottom-Left
        floatArrayOf(0.5f, 0.0f, 0.5f),  // 2: Front-Bottom-Right
        floatArrayOf(0.5f, 1.0f, 0.5f),  // 3: Front-Top-Right
        floatArrayOf(-0.5f, 1.0f, 0.5f), // 4: Front-Top-Left
        floatArrayOf(-0.5f, 0.0f, -0.5f),// 5: Back-Bottom-Left
        floatArrayOf(0.5f, 0.0f, -0.5f), // 6: Back-Bottom-Right
        floatArrayOf(0.5f, 1.0f, -0.5f), // 7: Back-Top-Right
        floatArrayOf(-0.5f, 1.0f, -0.5f) // 8: Back-Top-Left
    )

    private fun computeRotationAndScale(modelMatrix: FloatArray): Pair<List<Float>, List<Float>> {
        val c0 = floatArrayOf(modelMatrix[0], modelMatrix[1], modelMatrix[2])
        val c1 = floatArrayOf(modelMatrix[4], modelMatrix[5], modelMatrix[6])
        val c2 = floatArrayOf(modelMatrix[8], modelMatrix[9], modelMatrix[10])

        val sx = kotlin.math.sqrt(c0[0] * c0[0] + c0[1] * c0[1] + c0[2] * c0[2]).coerceAtLeast(1e-8f)
        val sy = kotlin.math.sqrt(c1[0] * c1[0] + c1[1] * c1[1] + c1[2] * c1[2]).coerceAtLeast(1e-8f)
        val sz = kotlin.math.sqrt(c2[0] * c2[0] + c2[1] * c2[1] + c2[2] * c2[2]).coerceAtLeast(1e-8f)

        val rot = listOf(
            c0[0] / sx, c1[0] / sy, c2[0] / sz,
            c0[1] / sx, c1[1] / sy, c2[1] / sz,
            c0[2] / sx, c1[2] / sy, c2[2] / sz
        )
        return Pair(rot, listOf(sx, sy, sz))
    }

    fun createEntry(
        frameId: Int,
        imageName: String,
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        width: Int, height: Int
    ): AnnotationEntry {
        val keypoints3d = mutableListOf<List<Float>>()
        val keypoints2d = mutableListOf<List<Float>>()
        val visibility = mutableListOf<Float>()

        for (localPt in UNIT_CUBE_POINTS) {
            val worldPt4 = FloatArray(4)
            android.opengl.Matrix.multiplyMV(worldPt4, 0, modelMatrix, 0, floatArrayOf(localPt[0], localPt[1], localPt[2], 1.0f), 0)
            val worldPt = floatArrayOf(worldPt4[0], worldPt4[1], worldPt4[2])

            val pointCamera4 = FloatArray(4)
            android.opengl.Matrix.multiplyMV(pointCamera4, 0, viewMatrix, 0, worldPt4, 0)
            
            // Objectron compatible Left-Hand coordinates
            keypoints3d.add(listOf(pointCamera4[0], pointCamera4[1], -pointCamera4[2])) 

            val proj = MathUtils.projectPoint(worldPt, viewMatrix, fx, fy, cx, cy, width, height)
            keypoints2d.add(listOf(proj[0], proj[1], proj[2]))

            val isVisible = proj[2] > 0 && proj[0] in 0.0..1.0 && proj[1] in 0.0..1.0
            visibility.add(if (isVisible) 1.0f else 0.0f)
        }

        val translation = listOf(modelMatrix[12], modelMatrix[13], modelMatrix[14])
        val (rotation, scale) = computeRotationAndScale(modelMatrix)

        return AnnotationEntry(
            frameId = frameId,
            image = imageName,
            orientationPitchDeg = 0f,
            orientationLabel = "arcore_capture",
            keypoints2d = keypoints2d,
            keypoints3d = keypoints3d,
            visibility = visibility,
            keypoints2dVisibility = visibility,
            cameraIntrinsics = CameraIntrinsics(fx, fy, cx, cy, width, height),
            viewMatrix = viewMatrix.toList(),
            modelMatrix = modelMatrix.toList(),
            pose6dof = Pose6Dof(translation = translation, rotation = rotation),
            pose9dof = Pose9Dof(translation = translation, rotation = rotation, scale = scale),
            environment = EnvironmentMeta(hdriCountAvailable = 0, hdriPath = "arcore_capture")
        )
    }
}
