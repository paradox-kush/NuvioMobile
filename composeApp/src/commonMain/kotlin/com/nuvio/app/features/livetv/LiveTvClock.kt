package com.nuvio.app.features.livetv

/** Local-time "HH:mm" for an epoch-ms instant (24h). Used for the guide's time axis + now/next. */
expect fun liveClockLabel(epochMs: Long): String

/** Local-time short day label, e.g. "Mon" / "Today". */
expect fun liveDayLabel(epochMs: Long): String
