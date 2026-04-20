package app.lawnchair.icons

import android.app.ActivityThread
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.android.launcher3.icons.BaseIconFactory.DEFAULT_WRAPPER_BACKGROUND
import com.android.launcher3.util.ComponentKey
import org.json.JSONObject

private const val SHARED_PREFERENCES_KEY: String = "com.android.launcher3.prefs"

val Context.prefs: SharedPreferences get() = applicationContext.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

// -----------------------------------------------------------------------
// Existing preference accessors (unchanged)
// -----------------------------------------------------------------------

fun shouldWrapAdaptive(context: Context) = context.prefs.getBoolean("prefs_wrapAdaptive", true)
fun Context.shouldTransparentBGIcons(): Boolean = prefs.getBoolean("prefs_transparentIconBackground", false)
fun Context.shouldShadowBGIcons(): Boolean = prefs.getBoolean("pref_shadowBGIcons", true)

fun Context.isThemedIconsEnabled(): Boolean = prefs.getBoolean("themed_icons", false)
fun Context.shouldTintIconPackBackgrounds(): Boolean = prefs.getBoolean("tint_icon_pack_backgrounds", false)

val prefsNoContext: SharedPreferences get() = ActivityThread.currentApplication()
    .getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

fun shouldForceMonochrome(): Boolean {
    val prefs = prefsNoContext

    return prefs.getBoolean("pref_forceIconMonochrome", false)
}

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
 * When false (default before this feature was added):
 *   - Background color is determined by the Palette API (dominant color + lightness pref).
 *
 * Corresponds to the old Lawnchair 2019 pref: prefs_colorizedLegacyTreatment
 * UI label suggestion: "Smart icon backgrounds" or "Colorized backgrounds"
 * Default: false (preserves existing behavior until user opts in)
 */
fun Context.shouldColorizeBackground(): Boolean =
    prefs.getBoolean("pref_colorizedLegacyTreatment", false)

/**
 * Whether to recolor adaptive icons whose background is a solid plain-white ColorDrawable.
 *
 * Many older apps shipped adaptive icons with a white background layer and a colored foreground.
 * On dark wallpapers the white background is jarring. When this option is enabled, Lawnchair
 * extracts the dominant color from the foreground layer and uses it to replace the white
 * background — making the icon look intentionally colored rather than accidental.
 *
 * This option only has an effect when [shouldColorizeBackground] is also true. The dependency
 * is intentional: you need the pixel-analysis path enabled before recoloring adaptive icons.
 *
 * Corresponds to the old Lawnchair 2019 pref: pref_enableWhiteOnlyTreatment
 * UI label suggestion: "Recolor white adaptive icon backgrounds"
 * Default: false
 */
fun Context.shouldTreatWhiteAdaptive(): Boolean =
    prefs.getBoolean("pref_enableWhiteOnlyTreatment", false)

// -----------------------------------------------------------------------
// Custom app name map
// -----------------------------------------------------------------------

private fun getCustomAppNameMap(): Map<ComponentKey, String> {
    val prefs = prefsNoContext

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

fun getCustomAppNameForComponent(info: LauncherActivityInfo): CharSequence? {
    val key = ComponentKey(info.componentName, info.user)
    val customLabel = getCustomAppNameMap()[key]
    if (!customLabel.isNullOrEmpty()) {
        return customLabel
    }
    return info.label
}

// -----------------------------------------------------------------------
// Background color helpers
// -----------------------------------------------------------------------

/**
 * Returns a background color for the given icon using the Palette API.
 * This is the simple path used when [shouldColorizeBackground] is false.
 *
 * The dominant color is extracted and then its lightness is forced to the value stored in
 * pref_coloredBackgroundLightness (default 1.0 = full white). At 100% lightness every dominant
 * color becomes white, which is the original behavior. Lowering the slider gives colored
 * backgrounds that are lighter than the icon's dominant color.
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

fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) {
        return drawable.bitmap
    }

    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
