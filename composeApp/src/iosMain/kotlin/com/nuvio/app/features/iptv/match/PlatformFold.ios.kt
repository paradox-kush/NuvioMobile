package com.nuvio.app.features.iptv.match

import platform.Foundation.NSString
import platform.Foundation.NSStringTransformStripCombiningMarks
import platform.Foundation.stringByApplyingTransform

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun stripCombiningMarks(s: String): String =
    (s as NSString).stringByApplyingTransform(NSStringTransformStripCombiningMarks, reverse = false) ?: s
