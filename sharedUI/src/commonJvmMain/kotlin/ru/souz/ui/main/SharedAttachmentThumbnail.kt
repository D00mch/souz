package ru.souz.ui.main

import androidx.compose.ui.graphics.ImageBitmap

internal expect fun decodeSharedAttachmentThumbnail(bytes: ByteArray?): ImageBitmap?
