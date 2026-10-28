package com.everettpenne.ix

/** The photographic looks offered in the camera. The id is what goes into metadata. */
public enum class IxStylePreset(public val id: String, public val label: String) {
    STANDARD("standard", "Standard"),
    WARM("warm", "Warm"),
    COOL("cool", "Cool"),
    RICH("rich", "Rich"),
    SOFT("soft", "Soft"),
}

/** A preset and how strongly it is applied. */
public data class IxStyle(
    public val preset: IxStylePreset,
    public val strength: Float = 1f,
) {
    init {
        require(strength in 0f..1f) { "style strength must be 0..1, was $strength" }
    }

    /** The matrix for this preset, interpolated from identity by [strength]. */
    public fun matrix(): IxColorMatrix = IxStylePresets.matrix(preset).blend(strength)
}

/** The colour maths behind each preset. Deliberately gentle: a style should not destroy skin tones. */
public object IxStylePresets {
    public fun matrix(preset: IxStylePreset): IxColorMatrix = when (preset) {
        IxStylePreset.STANDARD -> IxColorMath.contrast(1.02f)
        IxStylePreset.WARM -> IxColorMath.saturation(1.02f) * IxColorMath.scale(1.06f, 1.01f, 0.95f)
        IxStylePreset.COOL -> IxColorMath.saturation(1.02f) * IxColorMath.scale(0.95f, 1.00f, 1.07f)
        IxStylePreset.RICH -> IxColorMath.contrast(1.06f) * IxColorMath.saturation(1.18f)
        IxStylePreset.SOFT -> IxColorMath.lift(0.015f) * IxColorMath.contrast(0.94f) * IxColorMath.saturation(0.94f)
    }
}

/**
 * Applies a style to an ARGB buffer. This is the first concrete piece of the engine: it needs no
 * Android types, so it runs in the module's JVM tests and can be swapped for a faster renderer later.
 */
public object IxStyleRenderer {
    public fun apply(argb: IntArray, width: Int, height: Int, matrix: IxColorMatrix): IntArray {
        require(width > 0 && height > 0) { "dimensions must be positive, was ${width}x$height" }
        require(argb.size == width * height) { "expected ${width * height} pixels, was ${argb.size}" }
        return IntArray(argb.size) { index -> matrix.apply(IxColor.fromArgb(argb[index])).toArgb() }
    }
}
