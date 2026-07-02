package com.nuvio.app.features.iptv.match

import java.text.Normalizer

private val COMBINING_MARKS = Regex("\\p{Mn}+")

internal actual fun stripCombiningMarks(s: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
