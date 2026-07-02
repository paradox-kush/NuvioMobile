package com.nuvio.app.features.iptv.match

/**
 * NFD-decompose and strip combining marks ("é" -> "e", "أ" -> "ا"). Kotlin common has no
 * Unicode normalizer, so each platform supplies its own (java.text.Normalizer / CFString).
 */
internal expect fun stripCombiningMarks(s: String): String
