package me.rerere.rikkahub.data.files

import android.os.Build
import coil3.ComponentRegistry
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder

internal fun ComponentRegistry.Builder.addMediaImageDecoders() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        add(AnimatedImageDecoder.Factory())
    } else {
        add(GifDecoder.Factory())
    }
    add(SvgDecoder.Factory(scaleToDensity = true))
}
