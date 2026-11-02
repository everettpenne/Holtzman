package com.everettpenne.ix

import kotlin.math.max
import kotlin.math.min

/**
 * Chooses what a picture is about from a per-pixel class map (the 21 PASCAL VOC classes of a DeepLab v3 segmentation, 0 = background), for the camera's
 * autofocus. Before this the camera aimed at a face when it saw one and otherwise at the middle of the frame, so a cat, a cup or a person standing to one
 * side was left to whatever the middle held (often the background behind them).
 *
 * Every connected patch of one class is a candidate. Its score is its size, weighted toward the middle of the frame (a subject is rarely at the edge) and by
 * how much the class tends to be the subject (people and animals most, furniture that fills a room least). Patches smaller than [MIN_COVER] of the frame are
 * noise, and patches larger than [MAX_COVER] are the room, not something in it.
 */
public object IxSubject {
    /** PASCAL VOC class names, index = class id. */
    public val LABELS: Array<String> = arrayOf("background", "aeroplane", "bicycle", "bird", "boat", "bottle", "bus", "car", "cat", "chair", "cow",
        "dining table", "dog", "horse", "motorbike", "person", "potted plant", "sheep", "sofa", "train", "tv")
    /** How likely each class is to be what the photo is about. */
    private val PRIOR = floatArrayOf(0f, 1f, 1f, 1.4f, 1f, 1f, 0.9f, 1f, 1.4f, 0.6f, 1.3f, 0.4f, 1.4f, 1.3f, 1f, 1.5f, 1f, 1.3f, 0.4f, 0.9f, 0.8f)
    public const val MIN_COVER: Float = 0.006f
    public const val MAX_COVER: Float = 0.7f

    /** A chosen subject: its class, its box as fractions of the map's width and height (left, top, right, bottom), and the share of the frame it covers. */
    public class Subject(public val label: Int, public val left: Float, public val top: Float, public val right: Float, public val bottom: Float, public val cover: Float) {
        public val name: String get() = LABELS.getOrElse(label) { "object" }
        /**
         * Where focus should look inside the box: the whole box, except for a person or an animal, where the upper part (head and shoulders) is what
         * should be sharp; a full-length person focused on the middle of the box gets a sharp belt and soft eyes.
         */
        public fun focusBox(): FloatArray = if (label == 15 || label == 3 || label == 8 || label == 10 || label == 12 || label == 13 || label == 17)
            floatArrayOf(left, top, right, top + (bottom - top) * 0.4f) else floatArrayOf(left, top, right, bottom)
    }

    /** The subject of class map [labels] ([w] x [h], row by row), or null when nothing in it is a likely subject. */
    public fun pick(labels: ByteArray, w: Int, h: Int): Subject? {
        require(labels.size >= w * h)
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var best: Subject? = null; var bestScore = 0f
        val total = (w * h).toFloat()
        for (start in 0 until w * h) {
            val k = labels[start].toInt() and 0xFF
            if (k == 0 || k >= PRIOR.size || seen[start]) continue
            // flood the patch (4-connected) and gather its size, box and centre weighting
            var sp = 0; stack[sp++] = start; seen[start] = true
            var n = 0; var weighted = 0f; var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                n++
                val dx = (x + 0.5f) / w - 0.5f; val dy = (y + 0.5f) / h - 0.5f
                weighted += 1f - 1.6f * (dx * dx + dy * dy)   // 1 in the middle, 0.6 at the middle of an edge, 0.2 in a corner
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && !seen[i - 1] && (labels[i - 1].toInt() and 0xFF) == k) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && !seen[i + 1] && (labels[i + 1].toInt() and 0xFF) == k) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && !seen[i - w] && (labels[i - w].toInt() and 0xFF) == k) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && !seen[i + w] && (labels[i + w].toInt() and 0xFF) == k) { seen[i + w] = true; stack[sp++] = i + w }
            }
            val cover = n / total
            if (cover < MIN_COVER || cover > MAX_COVER) continue
            val score = weighted / total * PRIOR[k]
            if (score > bestScore) { bestScore = score; best = Subject(k, x0.toFloat() / w, y0.toFloat() / h, (x1 + 1f) / w, (y1 + 1f) / h, cover) }
        }
        return best
    }

    /**
     * Keeps a subject from flickering between frames: a new subject replaces [previous] only if it is the same class and overlaps it (then the box eases
     * toward the new one), or if it has now been seen [confirmations] times running. [streak] is how many frames running [candidate] has been seen.
     */
    public fun steady(previous: Subject?, candidate: Subject?, streak: Int, confirmations: Int = 2): Subject? {
        if (candidate == null) return if (streak >= confirmations) null else previous
        if (previous != null && previous.label == candidate.label && overlap(previous, candidate) > 0.2f) {
            val a = 0.5f
            return Subject(candidate.label, previous.left + (candidate.left - previous.left) * a, previous.top + (candidate.top - previous.top) * a,
                previous.right + (candidate.right - previous.right) * a, previous.bottom + (candidate.bottom - previous.bottom) * a, candidate.cover)
        }
        return if (streak >= confirmations) candidate else previous
    }

    /** Intersection over union of two subjects' boxes. */
    public fun overlap(a: Subject, b: Subject): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left); val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter)
    }
}
