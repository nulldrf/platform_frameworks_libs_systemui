package app.lawnchair.icons

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.android.launcher3.icons.BaseIconFactory.DEFAULT_WRAPPER_BACKGROUND
import com.android.launcher3.util.ComponentKey
import org.json.JSONObject

private const val SHARED_PREFERENCES_KEY: String = "com.android.launcher3.prefs"

val Context.prefs: SharedPreferences
    get() = applicationContext.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

// -----------------------------------------------------------------------
// Existing preference accessors (unchanged)
// -----------------------------------------------------------------------

fun shouldWrapAdaptive(context: Context) = context.prefs.getBoolean("prefs_wrapAdaptive", true)
fun Context.shouldTransparentBGIcons(): Boolean = prefs.getBoolean("prefs_transparentIconBackground", false)
fun Context.shouldShadowBGIcons(): Boolean = prefs.getBoolean("pref_shadowBGIcons", true)

fun Context.isThemedIconsEnabled(): Boolean = prefs.getBoolean("themed_icons", false)
fun Context.shouldTintIconPackBackgrounds(): Boolean = prefs.getBoolean("tint_icon_pack_backgrounds", false)

fun Context.shouldForceMonochrome(): Boolean = prefs.getBoolean("pref_forceIconMonochrome", false)

// -----------------------------------------------------------------------
// New preference accessors — AdaptiveIconGenerator feature parity
// -----------------------------------------------------------------------

/**
 * Whether to run full pixel-level color analysis on legacy (non-adaptive) icons instead of
 * the simpler Palette-based background color selection.
 *
 * When true:
 *   - The icon is rasterized and each pixel is examined.
 *   - Full-bleed icons (those that fill their entire bounds with no padding) are detected
 *     and scaled more aggressively so they fill the shape mask.
 *   - Squarish, mostly-opaque icons are detected and scaled to fit snugly without a color
 *     mix-in (the "no mixin" case).
 *   - All other icons get an intelligently blended background color derived from the icon's
 *     dominant color, adjusted for lightness contrast.
 *
 * When false: background color is determined by the Palette API (dominant color + lightness pref).
 *
 * Default: false (preserves existing behavior until user opts in)
 */
fun Context.shouldColorizeBackground(): Boolean =
    prefs.getBoolean("pref_colorizedLegacyTreatment", false)

/**
 * Whether to use the dominant foreground color as the background for foreground-only
 * adaptive icons (instead of plain white).
 *
 * This setting ONLY affects adaptive icons whose background layer is null or fully
 * transparent. These are "foreground-only" icons where the developer provided only
 * the icon art and expected the launcher to supply a background color.
 *
 * Adaptive icons that have a REAL background (any non-transparent color or drawable)
 * are NEVER touched by this setting — the developer's intended background is preserved.
 *
 * Behavior:
 *   - [shouldColorizeBackground] ON, this OFF:
 *       Foreground-only adaptive icons get a white background.
 *   - [shouldColorizeBackground] ON, this ON:
 *       Foreground-only adaptive icons get a background colored by the dominant
 *       color extracted from the foreground using the full pixel analysis.
 *
 * This option only has an effect when [shouldColorizeBackground] is also true.
 *
 * Default: false
 */
fun Context.shouldTreatWhiteAdaptive(): Boolean =
    prefs.getBoolean("pref_enableWhiteOnlyTreatment", false)

/**
 * Whether to apply smart adaptive backgrounds to third-party icon pack icons
 * that land on Case 2 (transparent-background adaptive) or Case 3 (legacy PNG).
 *
 * Case 1 (full adaptive with real background) is always left untouched.
 * Only meaningful when [shouldColorizeBackground] is also true.
 *
 * Default: false
 */
fun Context.shouldColorizeIconPackBackground(): Boolean =
    prefs.getBoolean("pref_colorizeIconPackBackground", false)

// -----------------------------------------------------------------------
// Custom app name map
// -----------------------------------------------------------------------

private fun getCustomAppNameMap(context: Context): Map<ComponentKey, String> {
    val prefs = context.prefs

    val customLabel = prefs.getString("pref_appNameMap", "{}")
    if (customLabel.isNullOrEmpty()) return emptyMap()

    val map = mutableMapOf<ComponentKey, String>()
    val obj = JSONObject(customLabel)
    obj.keys().forEach {
        val componentKey = ComponentKey.fromString(it)
        if (componentKey != null) {
            map[componentKey] = obj.getString(it)
        }
    }
    return map
}

fun getCustomAppNameForComponent(context: Context, info: LauncherActivityInfo): CharSequence? {
    val key = ComponentKey(info.componentName, info.user)
    val customLabel = getCustomAppNameMap(context)[key]
    if (!customLabel.isNullOrEmpty()) {
        return customLabel
    }
    return info.label
}

// -----------------------------------------------------------------------
// Background color helpers
// -----------------------------------------------------------------------

/**
 * Returns a background color based solely on lightness — no color extracted from the icon.
 *
 * Used when "Smart icon backgrounds" is ON but "Colorize foreground-only" is OFF.
 * The user expects the lightness slider to control brightness only (white → gray → black),
 * not introduce any hue from the icon. At 100% (default) this returns white.
 */
fun getMonochromeBackgroundColor(context: Context): Int {
    val lightness = context.prefs.getFloat("pref_coloredBackgroundLightness", 1f)
    if (lightness >= 1f) return DEFAULT_WRAPPER_BACKGROUND
    val outHsl = floatArrayOf(0f, 0f, lightness)
    return ColorUtils.HSLToColor(outHsl)
}

/**
 * Returns a background color for the given icon using the Palette API.
 * This is the colorized path used when [shouldColorizeBackground] AND
 * [shouldTreatWhiteAdaptive] are both true.
 *
 * The dominant color is extracted and then its lightness is forced to the value stored in
 * pref_coloredBackgroundLightness (default 1.0 = full white). At 100% lightness every dominant
 * color becomes white, which is the original behavior.
 */
fun getWrapperBackgroundColor(context: Context, icon: Drawable): Int {
    val lightness = context.prefs.getFloat("pref_coloredBackgroundLightness", 1f)
    val palette = Palette.Builder(drawableToBitmap(icon)).generate()
    val dominantColor = palette.getDominantColor(DEFAULT_WRAPPER_BACKGROUND)
    return setLightness(dominantColor, lightness)
}

private fun setLightness(color: Int, lightness: Float): Int {
    if (color == DEFAULT_WRAPPER_BACKGROUND) {
        return color
    }
    val outHsl = floatArrayOf(0f, 0f, 0f)
    ColorUtils.colorToHSL(color, outHsl)
    outHsl[2] = lightness
    return ColorUtils.HSLToColor(outHsl)
}

/**
 * Rasterizes the given drawable to a Bitmap for color sampling.
 *
 * BOUNDS CONTRACT: This method saves and restores the drawable's bounds around
 * the rasterization call.
 *
 * Without this, setBounds() mutates the drawable's shared state, leaving it at the
 * sample dimensions (intrinsicWidth × intrinsicHeight) after this function returns.
 * On Android 11 (API 30) and older, this caused pixelation across all wrapped icons:
 * BaseIconFactory.drawIconBitmap() saves mOldBounds = icon.getBounds() at the start
 * of every draw call. If drawableToBitmap() had previously set the bounds to a small
 * intrinsic size, mOldBounds would capture that wrong value. The FixedScaleDrawable
 * foreground layer was then drawn at the sampling resolution rather than the correct
 * icon bitmap size, producing a pixelated upscale artifact visible on all icon shapes.
 *
 * This mirrors the same fix applied to analyzeIconPixels() and isSingleColor() in
 * BaseIconFactory for the identical root cause.
 */
fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) {
        return drawable.bitmap
    }

    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Prefer a ConstantState copy so the original drawable's bounds are never mutated.
    // If we set bounds on the original and later restore to empty (Rect(0,0,0,0)),
    // any FixedScaleDrawable that holds the same drawable reference would wrap a
    // zero-bounds drawable and draw nothing — causing blank icons.
    val copy = drawable.constantState?.newDrawable()?.mutate()
    if (copy != null) {
        copy.setBounds(0, 0, width, height)
        copy.draw(canvas)
    } else {
        // No ConstantState — fall back to mutating the original bounds,
        // but only restore if they were non-empty before we touched them.
        val savedBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        if (!savedBounds.isEmpty) {
            drawable.setBounds(savedBounds)
        }
    }

    return bitmap
}
