package com.everettpenne.holtzman.probe

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/** Reads every camera the phone lists, and the extensions it advertises, into a [ProbeReport]. Read-only: it opens no camera. */
object ProbeCollector {
    private const val JPEG_R = 0x1005   // ImageFormat.JPEG_R (Ultra HDR), API 34

    fun collect(context: Context): ProbeReport {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = manager.cameraIdList.map { read(manager, it) }
        return ProbeReport("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}", cameras)
    }

    private fun read(manager: CameraManager, id: String): ProbeCamera {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(format: Int) = map?.getOutputSizes(format)?.sortedByDescending { it.width.toLong() * it.height }?.map { "${it.width}x${it.height}" }.orEmpty()
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(::capabilityName).orEmpty()
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.map { profileName(it) }.orEmpty()
        val extensions = runCatching { manager.getCameraExtensionCharacteristics(id).supportedExtensions.map(::extensionName) }.getOrDefault(emptyList())
        val reprocessing = buildList {
            if ("PRIVATE_REPROCESSING" in caps) add("private")
            if ("YUV_REPROCESSING" in caps) add("yuv")
        }
        return ProbeCamera(
            id = id,
            facing = when (c.get(CameraCharacteristics.LENS_FACING)) { CameraCharacteristics.LENS_FACING_BACK -> "back"; CameraCharacteristics.LENS_FACING_FRONT -> "front"; else -> "external" },
            hardwareLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"; CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"; CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"; else -> "unknown" },
            capabilities = caps,
            physicalIds = c.physicalCameraIds.toList().sorted(),
            sensorMm = size?.let { it.width to it.height },
            pixelArray = pixels?.let { "${it.width}x${it.height}" },
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
            isoRange = iso?.let { "${it.lower}-${it.upper}" },
            exposureRange = exposure?.let { "${it.lower} ns - ${it.upper} ns" },
            jpegSizes = sizes(ImageFormat.JPEG),
            rawSizes = sizes(ImageFormat.RAW_SENSOR),
            ultraHdrSizes = sizes(JPEG_R),
            yuvMax = sizes(ImageFormat.YUV_420_888).firstOrNull(),
            extensions = extensions,
            tenBitProfiles = profiles,
            maxZoomRatio = zoom?.let { "%.1f to %.1f".format(java.util.Locale.ROOT, it.lower, it.upper) },
            pipelineMaxDepth = c.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt(),
            maxRawOutputs = c.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW),
            reprocessing = reprocessing,
            noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.map(::nrName).orEmpty(),
            edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.map(::edgeName).orEmpty(),
            tonemapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.map(::tonemapName).orEmpty(),
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION),
        )
    }

    private fun capabilityName(v: Int) = when (v) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
        else -> "capability#$v"
    }

    private fun extensionName(v: Int) = when (v) {
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTOMATIC"
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
        else -> "extension#$v"
    }

    private fun profileName(v: Long) = when (v) {
        1L -> "STANDARD"; 2L -> "HLG10"; 4L -> "HDR10"; 8L -> "HDR10_PLUS"; 16L -> "DOLBY_VISION_10B_HDR_REF"; 32L -> "DOLBY_VISION_10B_HDR_REF_PO"
        64L -> "DOLBY_VISION_10B_HDR_OEM"; 128L -> "DOLBY_VISION_10B_HDR_OEM_PO"; 256L -> "DOLBY_VISION_8B_HDR_REF"; 512L -> "DOLBY_VISION_8B_HDR_REF_PO"
        1024L -> "DOLBY_VISION_8B_HDR_OEM"; 2048L -> "DOLBY_VISION_8B_HDR_OEM_PO"; else -> "profile#$v"
    }

    private fun nrName(v: Int) = when (v) { 0 -> "OFF"; 1 -> "FAST"; 2 -> "HQ"; 3 -> "MINIMAL"; 4 -> "ZSL"; else -> "$v" }
    private fun edgeName(v: Int) = when (v) { 0 -> "OFF"; 1 -> "FAST"; 2 -> "HQ"; 3 -> "ZSL"; else -> "$v" }
    private fun tonemapName(v: Int) = when (v) { 0 -> "CONTRAST_CURVE"; 1 -> "FAST"; 2 -> "HQ"; 3 -> "GAMMA"; 4 -> "PRESET_CURVE"; else -> "$v" }
}
