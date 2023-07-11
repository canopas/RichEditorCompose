package com.canopas.editor.ui.data

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
internal data class ImageContentValue internal constructor(
    internal val uri: Uri,
) : ContentValue() {

    override val type: ContentType = ContentType.IMAGE
    override var isFocused: Boolean = false
}