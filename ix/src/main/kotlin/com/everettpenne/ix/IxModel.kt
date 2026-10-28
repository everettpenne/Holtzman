package com.everettpenne.ix

/** What the camera captured, before Ix decides how to encode it. */
public enum class IxSourceFormat(public val label: String) {
    JPEG("JPEG"),
    RAW("RAW"),
    ULTRA_HDR("Ultra HDR"),
    YUV("YUV"),
}

/** What the person asked Ix to produce. */
public enum class IxOutput(public val id: String, public val label: String) {
    JPEG("jpeg", "JPEG"),
    RAW("raw", "RAW (DNG)"),
    RAW_JPEG("raw_jpeg", "RAW + JPEG"),
    ULTRA_HDR("ultra_hdr", "Ultra HDR"),
}

/** Which camera took the picture. */
public enum class IxLensFacing { BACK, FRONT, EXTERNAL }

/** Facts about the capture that belong in the file. */
public data class IxCaptureInfo(
    public val timestampMillis: Long,
    public val orientationDegrees: Int = 0,
    public val lensFacing: IxLensFacing = IxLensFacing.BACK,
    public val iso: Int? = null,
    public val exposureNanos: Long? = null,
) {
    init {
        require(orientationDegrees in 0..359) { "orientationDegrees must be 0..359, was $orientationDegrees" }
        require(iso == null || iso > 0) { "iso must be positive when present" }
        require(exposureNanos == null || exposureNanos > 0) { "exposureNanos must be positive when present" }
    }
}

/**
 * The bytes handed to Ix, with the facts needed to decode them.
 *
 * [bytes] is not copied. The caller owns the array and must not modify it while [IxEngine.render]
 * is running; the engine returns new arrays for its outputs.
 */
public class IxSource(
    public val bytes: ByteArray,
    public val format: IxSourceFormat,
    public val width: Int,
    public val height: Int,
) {
    init {
        require(bytes.isNotEmpty()) { "source bytes must not be empty" }
        require(width > 0 && height > 0) { "source dimensions must be positive, was ${width}x$height" }
    }

    /** Content equality for tests; [bytes] is otherwise identity-compared. */
    public fun contentEquals(other: IxSource): Boolean =
        format == other.format && width == other.width && height == other.height && bytes.contentEquals(other.bytes)
}

/** A file Ix produced. */
public data class IxFile(
    public val name: String,
    public val bytes: ByteArray,
    public val kind: IxFileKind,
    public val primary: Boolean = true,
) {
    public val mimeType: String get() = kind.mimeType

    init {
        require(name.isNotBlank()) { "file name must not be blank" }
        require(bytes.isNotEmpty()) { "file bytes must not be empty" }
    }
}

/** The kinds of file Ix can return. */
public enum class IxFileKind(public val mimeType: String, public val extension: String) {
    JPEG("image/jpeg", ".jpg"),
    ULTRA_HDR("image/jpeg", ".jpg"),
    DNG("image/x-adobe-dng", ".dng"),
}

/** A request to produce one or more files. */
public data class IxRequest(
    public val source: IxSource,
    public val capture: IxCaptureInfo,
    public val output: IxOutput,
    public val style: IxStyle? = null,
    public val baseName: String = IxNaming.baseName(capture.timestampMillis),
) {
    init {
        require(baseName.isNotBlank()) { "baseName must not be blank" }
    }
}

/** The result of a render: files, and every compromise the engine had to make. */
public data class IxResult(
    public val files: List<IxFile>,
    public val warnings: List<IxWarning> = emptyList(),
    public val engineVersion: String = IX_VERSION,
) {
    public val primary: IxFile? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()

    init {
        require(files.isNotEmpty() || warnings.isNotEmpty()) {
            "a result with no files must explain itself with at least one warning"
        }
    }
}

/** Something the engine could not do exactly as asked. */
public sealed interface IxWarning {
    public val message: String

    /** A format was unsupported and the engine saved a different one. */
    public data class OutputFallback(public val requested: IxOutput, public val used: IxOutput) : IxWarning {
        override val message: String = "Saved ${used.label} instead of ${requested.label}."
    }

    /** A style was requested but not applied. */
    public data class StyleSkipped(public val reason: String) : IxWarning {
        override val message: String = "Style skipped: $reason"
    }

    /** The source format cannot be handled by this engine (yet). */
    public data class SourceUnsupported(public val format: IxSourceFormat) : IxWarning {
        override val message: String = "Source format ${format.label} is not supported yet."
    }
}
