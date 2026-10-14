package dev.androidjtools.playback

private val address = Regex("""(?i)\b(?:https?|file|content)://\S+""")

/** Player error text can embed the media URL; never show a server address (or a device path) to the person. */
internal fun scrubUrls(text: String?): String =
    text?.replace(address, "<address>")?.trim().orEmpty().ifEmpty { "Playback failed." }
