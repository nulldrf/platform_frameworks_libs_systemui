package app.lawnchair.icons

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper

/**
 * Implemented by non-adaptive icon drawables (e.g. custom icon pack PNGs) that can supply an
 * independent monochrome layer for themed surfaces without altering the unthemed colored icon.
 */
interface MonochromeDrawableProvider {
    val monochromeDrawable: Drawable?
}

class MonochromeDrawableWrapper(
    drawable: Drawable,
    override val monochromeDrawable: Drawable?,
) : DrawableWrapper(drawable), MonochromeDrawableProvider

open class ExtendedBitmapDrawable(
    res: Resources,
    bitmap: Bitmap,
    val isFromIconPack: Boolean,
    override var monochromeDrawable: Drawable? = null,
) : BitmapDrawable(res, bitmap), MonochromeDrawableProvider {

    companion object {

        fun wrap(res: Resources, drawable: Drawable?, isFromIconPack: Boolean): Drawable? {
            return if (drawable is BitmapDrawable) {
                ExtendedBitmapDrawable(res, drawable.bitmap, isFromIconPack)
            } else {
                drawable
            }
        }

        @JvmStatic
        val Drawable.isFromIconPack: Boolean
            get() = when (this) {
                is ExtendedBitmapDrawable -> isFromIconPack
                is MonochromeDrawableWrapper -> drawable?.isFromIconPack ?: false
                else -> false
            }
    }
}
