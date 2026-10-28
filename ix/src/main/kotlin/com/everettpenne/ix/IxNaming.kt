package com.everettpenne.ix

/** File names Ix proposes. The caller may still change them to avoid an existing file. */
public object IxNaming {
    private val FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.ROOT)

    public fun baseName(timestampMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        require(timestampMillis >= 0) { "timestamp must not be negative, was $timestampMillis" }
        val local = java.time.Instant.ofEpochMilli(timestampMillis).atZone(zone)
        return "IMG_" + FORMATTER.format(local)
    }

    public fun jpeg(baseName: String): String = "${requireBase(baseName)}.jpg"

    public fun dng(baseName: String): String = "${requireBase(baseName)}.dng"

    private fun requireBase(baseName: String): String {
        require(baseName.isNotBlank()) { "base name must not be blank" }
        return baseName
    }
}
