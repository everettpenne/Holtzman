package com.everettpenne.ix

import kotlin.math.roundToInt

/** A colour with components in 0..1. Alpha is kept because the matrix pipeline preserves it. */
public data class IxColor(
    public val r: Float,
    public val g: Float,
    public val b: Float,
    public val a: Float = 1f,
) {
    init {
        require(r in 0f..1f && g in 0f..1f && b in 0f..1f && a in 0f..1f) {
            "colour components must be 0..1, was ($r, $g, $b, $a)"
        }
    }

    public fun toArgb(): Int {
        val ai = (a * 255f).roundToInt().coerceIn(0, 255)
        val ri = (r * 255f).roundToInt().coerceIn(0, 255)
        val gi = (g * 255f).roundToInt().coerceIn(0, 255)
        val bi = (b * 255f).roundToInt().coerceIn(0, 255)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    public companion object {
        /** The ARGB integer layout Android and most image buffers use. */
        public fun fromArgb(argb: Int): IxColor = IxColor(
            r = ((argb ushr 16) and 0xFF) / 255f,
            g = ((argb ushr 8) and 0xFF) / 255f,
            b = (argb and 0xFF) / 255f,
            a = ((argb ushr 24) and 0xFF) / 255f,
        )
    }
}

/**
 * A 4x5 affine colour matrix, row-major: each row has R, G, B, A and an offset.
 *
 * This is the pure part of Ix's look: it can be tested without a camera, a bitmap or Android.
 */
public class IxColorMatrix private constructor(private val values: FloatArray) {
    init {
        require(values.size == MATRIX_SIZE) { "a colour matrix has $MATRIX_SIZE values, was ${values.size}" }
    }

    public operator fun get(row: Int, column: Int): Float {
        require(row in 0..3 && column in 0..4) { "matrix cell ($row, $column) is out of range" }
        return values[row * 5 + column]
    }

    public fun toFloatArray(): FloatArray = values.copyOf()

    /** Linear interpolation from identity (strength 0) to this matrix (strength 1). */
    public fun blend(strength: Float): IxColorMatrix {
        require(strength in 0f..1f) { "strength must be 0..1, was $strength" }
        val out = FloatArray(MATRIX_SIZE) { i -> IDENTITY.values[i] * (1f - strength) + values[i] * strength }
        return IxColorMatrix(out)
    }

    /** `this * other` applies [other] first, then this. */
    public operator fun times(other: IxColorMatrix): IxColorMatrix {
        val out = FloatArray(MATRIX_SIZE)
        for (row in 0..3) {
            for (column in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += values[row * 5 + k] * other.values[k * 5 + column]
                out[row * 5 + column] = sum
            }
            var offset = values[row * 5 + 4]
            for (k in 0..3) offset += values[row * 5 + k] * other.values[k * 5 + 4]
            out[row * 5 + 4] = offset
        }
        return IxColorMatrix(out)
    }

    /** Applies the matrix and clamps the result to a displayable colour. */
    public fun apply(colour: IxColor): IxColor {
        fun row(index: Int): Float =
            values[index * 5] * colour.r +
                values[index * 5 + 1] * colour.g +
                values[index * 5 + 2] * colour.b +
                values[index * 5 + 3] * colour.a +
                values[index * 5 + 4]

        return IxColor(
            r = row(0).coerceIn(0f, 1f),
            g = row(1).coerceIn(0f, 1f),
            b = row(2).coerceIn(0f, 1f),
            a = row(3).coerceIn(0f, 1f),
        )
    }

    public companion object {
        private const val MATRIX_SIZE = 20

        public val IDENTITY: IxColorMatrix = IxColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        /** Builds a matrix from 20 values in row-major order. */
        public fun of(vararg values: Float): IxColorMatrix {
            require(values.size == MATRIX_SIZE) { "a colour matrix has $MATRIX_SIZE values, was ${values.size}" }
            return IxColorMatrix(values.copyOf())
        }
    }
}

/** The building blocks the presets compose. All amounts are multipliers or absolute offsets. */
public object IxColorMath {
    /** Luma weights for sRGB, used by [saturation]. */
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /** [amount] 0 = greyscale, 1 = unchanged, above 1 = more saturated. */
    public fun saturation(amount: Float): IxColorMatrix {
        require(amount >= 0f) { "saturation must not be negative, was $amount" }
        val inverse = 1f - amount
        return IxColorMatrix.of(
            LUMA_R * inverse + amount, LUMA_G * inverse, LUMA_B * inverse, 0f, 0f,
            LUMA_R * inverse, LUMA_G * inverse + amount, LUMA_B * inverse, 0f, 0f,
            LUMA_R * inverse, LUMA_G * inverse, LUMA_B * inverse + amount, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** [amount] 1 = unchanged; below 1 lowers contrast, above 1 raises it. */
    public fun contrast(amount: Float): IxColorMatrix {
        require(amount >= 0f) { "contrast must not be negative, was $amount" }
        val offset = 0.5f * (1f - amount)
        return IxColorMatrix.of(
            amount, 0f, 0f, 0f, offset,
            0f, amount, 0f, 0f, offset,
            0f, 0f, amount, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Multiplies each colour channel independently. */
    public fun scale(red: Float, green: Float, blue: Float): IxColorMatrix {
        require(red >= 0f && green >= 0f && blue >= 0f) { "channel scales must not be negative" }
        return IxColorMatrix.of(
            red, 0f, 0f, 0f, 0f,
            0f, green, 0f, 0f, 0f,
            0f, 0f, blue, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Adds a constant to all three colour channels, lifting the blacks. */
    public fun lift(amount: Float): IxColorMatrix = IxColorMatrix.of(
        1f, 0f, 0f, 0f, amount,
        0f, 1f, 0f, 0f, amount,
        0f, 0f, 1f, 0f, amount,
        0f, 0f, 0f, 1f, 0f,
    )
}
