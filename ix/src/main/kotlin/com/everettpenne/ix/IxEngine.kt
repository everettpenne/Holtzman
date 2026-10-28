package com.everettpenne.ix

/** The engine version reported in capture metadata and in About. */
public const val IX_VERSION: String = "0.1.0-skeleton"

/**
 * Ix turns a capture into files.
 *
 * The contract is intentionally narrow: bytes and capture facts go in, files and warnings come out.
 * There is no [android.content.Context], no `Uri` and no UI type here — the camera decides where
 * the bytes are written. Implementations may be called on a background thread.
 */
public interface IxEngine {
    /** The engine version, for metadata and diagnostics. */
    public val version: String

    /**
     * Renders [request].
     *
     * A result may carry no files when the source cannot be handled at all; in that case it must
     * carry at least one warning explaining why. Implementations must never silently downgrade an
     * output format: a fallback is always reported as an [IxWarning.OutputFallback].
     */
    public fun render(request: IxRequest): IxResult
}
