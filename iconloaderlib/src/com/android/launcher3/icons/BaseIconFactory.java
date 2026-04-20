package com.android.launcher3.icons;

import static android.graphics.Color.BLACK;
import static android.graphics.Paint.ANTI_ALIAS_FLAG;
import static android.graphics.Paint.DITHER_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;
import static android.graphics.drawable.AdaptiveIconDrawable.getExtraInsetFraction;

import static com.android.launcher3.icons.BitmapInfo.FLAG_INSTANT;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;
import static com.android.launcher3.icons.ShadowGenerator.BLUR_FACTOR;
import static com.android.launcher3.icons.ShadowGenerator.ICON_SCALE_FOR_SHADOWS;

import static com.android.launcher3.icons.ShadowGenerator.ENABLE_SHADOWS;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.UserHandle;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.launcher3.Flags;
import com.android.launcher3.icons.BitmapInfo.Extender;
import com.android.launcher3.util.FlagOp;
import com.android.launcher3.util.UserIconInfo;

import java.lang.annotation.Retention;

import app.lawnchair.icons.CustomAdaptiveIconDrawable;
import app.lawnchair.icons.ExtendedBitmapDrawable;
import app.lawnchair.icons.FixedScaleDrawable;
import app.lawnchair.icons.IconPreferencesKt;

/**
 * This class will be moved to androidx library. There shouldn't be any dependency outside
 * this package.
 */
public class BaseIconFactory implements AutoCloseable {

    public static final int DEFAULT_WRAPPER_BACKGROUND = Color.WHITE;
    public static final float LEGACY_ICON_SCALE = .7f * (1f / (1 + 2 * getExtraInsetFraction()));

    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ALPHA = 1;
    public static final int MODE_WITH_SHADOW = 2;
    public static final int MODE_HARDWARE = 3;
    public static final int MODE_HARDWARE_WITH_SHADOW = 4;

    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ALPHA, MODE_WITH_SHADOW, MODE_HARDWARE_WITH_SHADOW, MODE_HARDWARE})
    @interface BitmapGenerationMode {
    }

    private static final float ICON_BADGE_SCALE = 0.444f;

    // -----------------------------------------------------------------------
    // AdaptiveIconGenerator constants — ported from old Lawnchair 2019 code
    // -----------------------------------------------------------------------

    // Average number of derived colors (based on averages with ~100 icons and performance testing)
    private static final int NUMBER_OF_COLORS_GUESSTIMATION = 45;

    // Scale applied when an icon is detected as full-bleed (fills its entire bounds, no padding)
    private static final float FULL_BLEED_ICON_SCALE = 1.44f;

    // Scale applied when an icon is squarish and opaque enough that no color mix-in is needed
    private static final float NO_MIXIN_ICON_SCALE = 1.40f;

    // Icons with this many unique posterized colors or fewer are treated as "single color"
    private static final int SINGLE_COLOR_LIMIT = 5;

    // Alpha threshold: pixels at or above this value are considered fully opaque for analysis.
    // Using 0xEF (239) matches the original AdaptiveIconGenerator behavior exactly, which is
    // stricter than IconNormalizer's threshold of 40 — intentional.
    private static final int ADAPTIVE_MIN_VISIBLE_ALPHA = 0xEF;

    // -----------------------------------------------------------------------

    @NonNull
    private final Rect mOldBounds = new Rect();

    @NonNull
    private final SparseArray<UserIconInfo> mCachedUserInfo = new SparseArray<>();

    @NonNull
    protected final Context mContext;

    @NonNull
    private final Canvas mCanvas;

    @NonNull
    private final PackageManager mPm;

    protected final int mFullResIconDpi;
    protected final int mIconBitmapSize;

    protected IconThemeController mThemeController;

    @Nullable
    private ShadowGenerator mShadowGenerator;

    /** Shadow bitmap used as background for theme icons */
    private Bitmap mWhiteShadowLayer;
    /** Bitmap used for {@link BitmapShader} to mask Adaptive Icons when drawing */
    private Bitmap mShaderBitmap;

    private int mWrapperBackgroundColor = DEFAULT_WRAPPER_BACKGROUND;

    private static int PLACEHOLDER_BACKGROUND_COLOR = Color.rgb(245, 245, 245);

    protected BaseIconFactory(Context context, int fullResIconDpi, int iconBitmapSize,
            boolean unused) {
        this(context, fullResIconDpi, iconBitmapSize);
    }

    public BaseIconFactory(Context context, int fullResIconDpi, int iconBitmapSize) {
        mContext = context.getApplicationContext();
        mFullResIconDpi = fullResIconDpi;
        mIconBitmapSize = iconBitmapSize;

        mPm = mContext.getPackageManager();

        mCanvas = new Canvas();
        mCanvas.setDrawFilter(new PaintFlagsDrawFilter(DITHER_FLAG, FILTER_BITMAP_FLAG));
        clear();
    }

    protected void clear() {
        mWrapperBackgroundColor = DEFAULT_WRAPPER_BACKGROUND;
    }

    @NonNull
    public ShadowGenerator getShadowGenerator() {
        if (mShadowGenerator == null) {
            mShadowGenerator = new ShadowGenerator(mIconBitmapSize);
            ENABLE_SHADOWS = IconPreferencesKt.shouldShadowBGIcons(mContext);
        }
        return mShadowGenerator;
    }

    @Nullable
    public IconThemeController getThemeController() {
        return mThemeController;
    }

    public int getFullResIconDpi() {
        return mFullResIconDpi;
    }

    public int getIconBitmapSize() {
        return mIconBitmapSize;
    }

    @SuppressWarnings("deprecation")
    public BitmapInfo createIconBitmap(Intent.ShortcutIconResource iconRes) {
        try {
            Resources resources = mPm.getResourcesForApplication(iconRes.packageName);
            if (resources != null) {
                final int id = resources.getIdentifier(iconRes.resourceName, null, null);
                // do not stamp old legacy shortcuts as the app may have already forgotten about it
                return createBadgedIconBitmap(resources.getDrawableForDensity(id, mFullResIconDpi));
            }
        } catch (Exception e) {
            // Icon not found.
        }
        return null;
    }

    /**
     * Create a placeholder icon using the passed in text.
     *
     * @param placeholder used for foreground element in the icon bitmap
     * @param color       used for the foreground text color
     */
    public BitmapInfo createIconBitmap(String placeholder, int color) {
        AdaptiveIconDrawable drawable = new AdaptiveIconDrawable(
                new ColorDrawable(PLACEHOLDER_BACKGROUND_COLOR),
                new CenterTextDrawable(placeholder, color));
        Bitmap icon = createIconBitmap(drawable, ICON_VISIBLE_AREA_FACTOR);
        return BitmapInfo.of(icon, color);
    }

    public BitmapInfo createIconBitmap(Bitmap icon) {
        if (mIconBitmapSize != icon.getWidth() || mIconBitmapSize != icon.getHeight()) {
            icon = createIconBitmap(new BitmapDrawable(mContext.getResources(), icon), 1f);
        }

        return BitmapInfo.of(icon, ColorExtractor.findDominantColorByHue(icon));
    }

    /**
     * Creates an icon from the bitmap cropped to the current device icon shape
     */
    @NonNull
    public AdaptiveIconDrawable createShapedAdaptiveIcon(Bitmap iconBitmap) {
        Drawable drawable = new FixedSizeBitmapDrawable(iconBitmap);
        float inset = getExtraInsetFraction();
        inset = inset / (1 + 2 * inset);
        return new AdaptiveIconDrawable(new ColorDrawable(BLACK),
                new InsetDrawable(drawable, inset, inset, inset, inset)
        );
    }

    @NonNull
    public BitmapInfo createBadgedIconBitmap(@NonNull Drawable icon) {
        return createBadgedIconBitmap(icon, null);
    }

    /**
     * Creates bitmap using the source drawable and various parameters.
     * The bitmap is visually normalized with other icons and has enough spacing to add shadow.
     *
     * @param icon source of the icon
     * @return a bitmap suitable for displaying as an icon at various system UIs.
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @NonNull
    public BitmapInfo createBadgedIconBitmap(@NonNull Drawable icon,
            @Nullable IconOptions options) {
        float[] scale = new float[1];
        Drawable tempIcon = icon;
        if (options != null
                && options.mIsArchived
                && icon instanceof BitmapDrawable bitmapDrawable) {
            // b/358123888
            // Pre-archived apps can have BitmapDrawables without insets.
            // Need to convert to Adaptive Icon with insets to avoid cropping.
            tempIcon = createShapedAdaptiveIcon(bitmapDrawable.getBitmap());
        }
        Drawable adaptiveIcon = normalizeAndWrapToAdaptiveIcon(tempIcon, scale);
        Bitmap bitmap = createIconBitmap(adaptiveIcon, scale[0],
                options == null ? MODE_WITH_SHADOW : options.mGenerationMode);
        int color = (options != null && options.mExtractedColor != null)
                ? options.mExtractedColor : ColorExtractor.findDominantColorByHue(bitmap);
        BitmapInfo info = BitmapInfo.of(bitmap, color);

        if (adaptiveIcon instanceof Extender extender) {
            info = extender.getExtendedInfo(bitmap, color, this, scale[0]);
        } else if (IconProvider.ATLEAST_T && mThemeController != null && adaptiveIcon instanceof AdaptiveIconDrawable aid) {
            info.setThemedBitmap(
                    mThemeController.createThemedBitmap(
                        aid,
                        info,
                        this,
                        options == null ? null : options.mSourceHint
                    )
            );
        }
        FlagOp flagOp = getBitmapFlagOp(options);
        if (adaptiveIcon instanceof WrappedAdaptiveIcon) {
            flagOp = flagOp.addFlag(BitmapInfo.FLAG_WRAPPED_NON_ADAPTIVE);
        }
        info = info.withFlags(flagOp);
        return info;
    }

    @NonNull
    public FlagOp getBitmapFlagOp(@Nullable IconOptions options) {
        FlagOp op = FlagOp.NO_OP;
        if (options != null) {
            if (options.mIsInstantApp) {
                op = op.addFlag(FLAG_INSTANT);
            }

            UserIconInfo info = options.mUserIconInfo;
            if (info == null && options.mUserHandle != null) {
                info = getUserInfo(options.mUserHandle);
            }
            if (info != null) {
                op = info.applyBitmapInfoFlags(op);
            }
        }
        return op;
    }

    @NonNull
    protected UserIconInfo getUserInfo(@NonNull UserHandle user) {
        int key = user.hashCode();
        UserIconInfo info = mCachedUserInfo.get(key);
        /*
         * We do not have the ability to distinguish between different badged users here.
         * As such all badged users will have the work profile badge applied.
         */
        if (info == null) {
            // Simple check to check if the provided user is work profile or not based on badging
            NoopDrawable d = new NoopDrawable();
            boolean isWork = (d != mPm.getUserBadgedIcon(d, user));
            info = new UserIconInfo(user, isWork ? UserIconInfo.TYPE_WORK : UserIconInfo.TYPE_MAIN);
            mCachedUserInfo.put(key, info);
        }
        return info;
    }

    @NonNull
    public Path getShapePath(AdaptiveIconDrawable drawable, Rect iconBounds) {
        return drawable.getIconMask();
    }

    @NonNull
    public Bitmap getWhiteShadowLayer() {
        if (mWhiteShadowLayer == null) {
            mWhiteShadowLayer = createScaledBitmap(
                    new AdaptiveIconDrawable(new ColorDrawable(Color.WHITE), null),
                    MODE_HARDWARE_WITH_SHADOW);
        }
        return mWhiteShadowLayer;
    }

    /**
     * Takes an {@link AdaptiveIconDrawable} and uses it to create a new Shader Bitmap.
     * {@link mShaderBitmap} will be used to create a {@link BitmapShader} for masking,
     * such as for icon shapes. Will reuse underlying Bitmap where possible.
     *
     * @param adaptiveIcon AdaptiveIconDrawable to draw with shader
     */
    @NonNull
    private Bitmap getAdaptiveShaderBitmap(AdaptiveIconDrawable adaptiveIcon) {
        Rect bounds = adaptiveIcon.getBounds();
        int iconWidth = bounds.width();
        int iconHeight = bounds.width();

        BitmapRenderer shaderRenderer = new BitmapRenderer() {
            @Override
            public void draw(Canvas canvas) {
                canvas.translate(-bounds.left, -bounds.top);
                canvas.drawColor(BLACK);
                if (adaptiveIcon.getBackground() != null) {
                    adaptiveIcon.getBackground().draw(canvas);
                }
                if (adaptiveIcon.getForeground() != null) {
                    adaptiveIcon.getForeground().draw(canvas);
                }
            }
        };
        if (mShaderBitmap == null || iconWidth != mShaderBitmap.getWidth()
                || iconHeight != mShaderBitmap.getHeight()) {
            mShaderBitmap = BitmapRenderer.createSoftwareBitmap(iconWidth, iconHeight,
                    shaderRenderer);
        } else {
            shaderRenderer.draw(new Canvas(mShaderBitmap));
        }
        return mShaderBitmap;
    }

    @NonNull
    public Bitmap createScaledBitmap(@NonNull Drawable icon, @BitmapGenerationMode int mode) {
        float[] scale = new float[1];
        icon = normalizeAndWrapToAdaptiveIcon(icon, scale);
        return createIconBitmap(icon, Math.min(scale[0], ICON_SCALE_FOR_SHADOWS), mode);
    }

    /**
     * Sets the background color used for wrapped adaptive icon
     */
    public void setWrapperBackgroundColor(final int color) {
        mWrapperBackgroundColor = (Color.alpha(color) < 255) ? DEFAULT_WRAPPER_BACKGROUND : color;
    }

    // -----------------------------------------------------------------------
    // AdaptiveIconGenerator — pixel analysis helpers
    // -----------------------------------------------------------------------

    /**
     * Reduces color complexity by grouping nearby RGB values together (posterization).
     * Ported verbatim from the old Lawnchair ColorExtractor.posterize().
     *
     * The old code packed r/g/b into a 24-bit int with 4 bits per channel (16 buckets per
     * channel), which produced stable histogram keys even for slight color variations.
     *
     * Returns a non-negative int suitable as a histogram key, or -1 on underflow guard.
     */
    private static int posterizeColor(int pixel) {
        // Extract 8-bit channels (no alpha)
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        // Quantize each channel to 4 bits (0-15 range, step of 16)
        r = r >> 4;
        g = g >> 4;
        b = b >> 4;
        // Pack back into a single int using the same layout the old code expected.
        // This matches the old ColorExtractor.posterize() output for histogram keying.
        int result = (r << 8) | (g << 4) | b;
        if (result < 0) {
            return -1;
        }
        return result;
    }

    /**
     * Returns true if the given drawable consists entirely (or almost entirely) of a single
     * opaque color. Used to detect whether a background layer is plain white before deciding
     * whether to recolor an adaptive icon.
     *
     * Ported from old Lawnchair ColorExtractor.isSingleColor().
     */
    private static boolean isSingleColor(@Nullable Drawable drawable, int color) {
        if (drawable == null) {
            return false;
        }
        if (drawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getColor() == color;
        }
        // For more complex drawables, rasterize and sample
        int width = Math.max(drawable.getIntrinsicWidth(), 1);
        int height = Math.max(drawable.getIntrinsicHeight(), 1);
        // Cap to a small size for performance — we only need to know if it's a solid color
        width = Math.min(width, 64);
        height = Math.min(height, 64);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        for (int pixel : pixels) {
            int alpha = (pixel >> 24) & 0xFF;
            if (alpha < ADAPTIVE_MIN_VISIBLE_ALPHA) {
                continue;
            }
            // Compare RGB only (ignore alpha channel for the color comparison)
            if ((pixel & 0x00FFFFFF) != (color & 0x00FFFFFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Core pixel-level analysis that replicates the old AdaptiveIconGenerator.loop() logic.
     *
     * This method rasterizes the given drawable at mIconBitmapSize, then:
     *   1. Counts transparent pixels to classify the icon as full-bleed or not.
     *   2. Checks whether the icon is squarish (aspect ratio close to 1:1) using the
     *      normalizer-computed bounding rect.
     *   3. Builds a posterized RGB histogram to find the dominant color.
     *   4. Applies HSL-based blending to produce a readable, contrast-appropriate background.
     *
     * Results are written into the provided {@link AdaptiveIconAnalysis} output object.
     *
     * @param extractee  The drawable to analyze. Should be the raw legacy icon (not yet wrapped),
     *                   or the foreground layer of an adaptive icon.
     * @param out        Output object that receives isFullBleed, noMixinNeeded,
     *                   backgroundColor, and the normalizer scale.
     * @param extractColor  Whether to run color extraction at all. If false, backgroundColor
     *                      will be set to DEFAULT_WRAPPER_BACKGROUND (white).
     */
    private void analyzeIconPixels(
            @NonNull Drawable extractee,
            @NonNull AdaptiveIconAnalysis out,
            boolean extractColor) {

        // Step 1: measure bounds via IconNormalizer so we know visible area dimensions.
        // We use IconNormalizer only to get the scale; we then derive approximate bounds from it.
        // The old code used a 5-arg getScale() with a RectF out-param that no longer exists
        // in the new IconNormalizer. We replicate the bounds by rasterizing directly.
        final int size = mIconBitmapSize;
        final float normScale = new IconNormalizer(size).getScale(extractee);
        out.normalizerScale = normScale;

        // Rasterize the extractee at icon bitmap size to count pixels
        final int width;
        final int height;
        int intrinsicW = extractee.getIntrinsicWidth();
        int intrinsicH = extractee.getIntrinsicHeight();
        if (intrinsicW > 0 && intrinsicH > 0) {
            width = intrinsicW;
            height = intrinsicH;
        } else {
            width = size;
            height = size;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        extractee.setBounds(0, 0, width, height);
        extractee.draw(canvas);

        if (!bitmap.hasAlpha()) {
            // No alpha channel at all — definitely full bleed
            out.isFullBleed = true;
            out.fullBleedChecked = true;
        }

        final int totalPixels = width * height;
        final int[] pixels = new int[totalPixels];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();

        // Compute the visible bounding box so we can check squarishness and measure
        // the amount of "real" padding (transparent margin) around the icon.
        int bLeft = width, bRight = -1, bTop = height, bBottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                int alpha = (pixel >> 24) & 0xFF;
                if (alpha >= ADAPTIVE_MIN_VISIBLE_ALPHA) {
                    if (x < bLeft) bLeft = x;
                    if (x > bRight) bRight = x;
                    if (y < bTop) bTop = y;
                    if (y > bBottom) bBottom = y;
                }
            }
        }

        // If nothing visible was found at all, bail out with white background
        if (bRight < 0 || bBottom < 0) {
            out.backgroundColor = DEFAULT_WRAPPER_BACKGROUND;
            return;
        }

        // aWidth / aHeight = visible (non-padded) dimensions
        final float aWidth = (bRight - bLeft + 1);
        final float aHeight = (bBottom - bTop + 1);

        // Squarishness check — matching old logic exactly
        final float ratio = aHeight / aWidth;
        final boolean isSquareish = ratio > 0.999f && ratio < 1.0001f;
        final boolean almostSquarish = isSquareish || (ratio > 0.97f && ratio < 1.005f);
        if (!isSquareish && !out.fullBleedChecked) {
            out.isFullBleed = false;
            out.fullBleedChecked = true;
        }

        // Compute padding pixel counts to correct the transparency threshold.
        // This mirrors the "addPixels" calculation in the old loop():
        //   l  = bounds.left  * width  * adjHeight
        //   top = bounds.top  * height * width
        //   r  = bounds.right * width  * adjHeight
        //   bottom = bounds.bottom * height * width
        // We derive fractional bounds from the pixel-level bounding box.
        final float fracLeft   = (float) bLeft   / width;
        final float fracTop    = (float) bTop    / height;
        final float fracRight  = (float) (width  - 1 - bRight)  / width;
        final float fracBottom = (float) (height - 1 - bBottom) / height;
        final float adjHeight  = height * (1f - fracTop - fracBottom);
        final float paddingPixels =
                fracLeft   * width * adjHeight
              + fracTop    * height * width
              + fracRight  * width * adjHeight
              + fracBottom * height * width;
        final int addPixels = Math.round(paddingPixels);

        // Any icon with less than 10% transparent pixels (excluding padding) = full bleed
        final int maxTransparent = (int) (Math.round(totalPixels * 0.10f) + addPixels);
        // Any icon with less than 27% transparent pixels doesn't need a color mix-in
        final int noMixinScore  = (int) (Math.round(totalPixels * 0.27f) + addPixels);

        // Step 2: single-pass pixel scan — count transparency, build color histogram
        SparseIntArray rgbScoreHistogram = new SparseIntArray(NUMBER_OF_COLORS_GUESSTIMATION);
        int highScore = 0;
        int bestRGB = 0;
        int transparentScore = 0;

        for (int pixel : pixels) {
            int alpha = (pixel >> 24) & 0xFF;
            if (alpha < ADAPTIVE_MIN_VISIBLE_ALPHA) {
                transparentScore++;
                if (transparentScore > maxTransparent && !out.fullBleedChecked) {
                    out.isFullBleed = false;
                    out.fullBleedChecked = true;
                    if (!extractColor) {
                        // No need to keep scanning for color
                        break;
                    }
                }
                continue;
            }
            // Reduce color complexity via posterization
            int rgb = posterizeColor(pixel & 0x00FFFFFF);
            if (rgb < 0) {
                continue;
            }
            int currentScore = rgbScoreHistogram.get(rgb) + 1;
            rgbScoreHistogram.append(rgb, currentScore);
            if (currentScore > highScore) {
                highScore = currentScore;
                bestRGB = rgb;
            }
        }

        // Restore full alpha channel on the best color
        bestRGB |= 0xFF << 24;

        // If fullBleed was never definitively set to false, and this is not a known adaptive
        // icon (isBackgroundWhite would be true in the adaptive path), then it is full bleed.
        // Here we are always called with non-adaptive icons, so:
        //   not yet checked = not set to false = must be full bleed
        if (!out.fullBleedChecked) {
            out.isFullBleed = true;
        }

        // Step 3: no-mixin shortcut — squarish + mostly opaque means we can skip blending
        out.noMixinNeeded = !out.isFullBleed
                && almostSquarish
                && (transparentScore <= noMixinScore);

        if (out.isFullBleed || out.noMixinNeeded) {
            out.backgroundColor = bestRGB;
            // Store visible dimensions so the caller can compute scale upfactors
            out.aWidth  = aWidth;
            out.aHeight = aHeight;
            out.iconWidth  = width;
            out.iconHeight = height;
            return;
        }

        // Step 4: if we are not extracting color, use plain white
        if (!extractColor) {
            out.backgroundColor = DEFAULT_WRAPPER_BACKGROUND;
            return;
        }

        // Step 5: HSL-based color mixing — replicates old AdaptiveIconGenerator exactly
        final int numColors = rgbScoreHistogram.size();
        final boolean singleColor = numColors <= SINGLE_COLOR_LIMIT;

        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(bestRGB, hsl);
        final float lightness = hsl[2];

        final boolean light     = lightness > 0.5f;
        final boolean veryLight = lightness > 0.75f && singleColor; // mostly white → dark bg
        final boolean veryDark  = lightness < 0.35f && singleColor; // mostly dark  → light bg

        final int opaqueSize = totalPixels - transparentScore;
        final float pxPerColor = opaqueSize / (float) numColors;
        // mixRatio in [0.15, 0.70] — higher ratio = more fill color blended in
        float mixRatio = Math.min(Math.max(pxPerColor / highScore, 0.15f), 0.70f);

        // Choose fill direction: blend toward white for dark icons, dark for light icons
        int fill = ((light && !veryLight) || veryDark) ? 0xFFFFFFFF : 0xFF333333;
        out.backgroundColor = ColorUtils.blendARGB(bestRGB, fill, mixRatio);

        out.aWidth  = aWidth;
        out.aHeight = aHeight;
        out.iconWidth  = width;
        out.iconHeight = height;
    }

    /**
     * Simple value object used to pass analysis results out of {@link #analyzeIconPixels}.
     */
    private static class AdaptiveIconAnalysis {
        boolean isFullBleed       = false;
        boolean fullBleedChecked  = false;
        boolean noMixinNeeded     = false;
        int     backgroundColor   = DEFAULT_WRAPPER_BACKGROUND;
        float   normalizerScale   = 1f;
        // Visible (non-padded) dimensions — populated when isFullBleed or noMixinNeeded is true
        float   aWidth  = 0f;
        float   aHeight = 0f;
        int     iconWidth  = 0;
        int     iconHeight = 0;
    }

    // -----------------------------------------------------------------------

    @Nullable
    protected Drawable normalizeAndWrapToAdaptiveIcon(
            @Nullable Drawable icon, @NonNull final float[] outScale) {
        if (icon == null) {
            return null;
        }

        boolean isFromIconPack = ExtendedBitmapDrawable.isFromIconPack(icon);
        boolean shouldWrapAdaptive = !isFromIconPack && IconPreferencesKt.shouldWrapAdaptive(mContext);
        boolean shrinkNonAdaptiveIcons = IconProvider.ATLEAST_OREO && shouldWrapAdaptive;

        // Read the new pref flags that control the depth of the adaptive generation behavior.
        // pref_colorizedLegacyTreatment — enables full pixel analysis + smart color extraction
        //     (replaces the simple Palette-based getWrapperBackgroundColor path)
        // pref_enableWhiteOnlyTreatment — additionally recolors adaptive icons whose background
        //     is a solid plain white ColorDrawable, by extracting color from the foreground
        boolean colorizeBackground  = IconPreferencesKt.shouldColorizeBackground(mContext);
        boolean treatWhiteAdaptive  = colorizeBackground && IconPreferencesKt.shouldTreatWhiteAdaptive(mContext);

        float scale;

        if (shrinkNonAdaptiveIcons && !(icon instanceof AdaptiveIconDrawable)) {
            // ----------------------------------------------------------------
            // NON-ADAPTIVE ICON PATH
            // ----------------------------------------------------------------
            // When colorizeBackground is enabled, run the full pixel-level analysis
            // from the old AdaptiveIconGenerator. Otherwise fall back to the simple
            // Palette-based background color selection that was here before.
            // ----------------------------------------------------------------

            if (colorizeBackground) {
                // Run full analysis
                AdaptiveIconAnalysis analysis = new AdaptiveIconAnalysis();
                analyzeIconPixels(icon, analysis, true);

                FixedScaleDrawable foreground = new FixedScaleDrawable();
                foreground.setDrawable(icon);

                if (analysis.isFullBleed || analysis.noMixinNeeded) {
                    // For full-bleed and no-mixin icons, apply the aggressive upscaling from
                    // the old code so the icon fills the shape without a large empty border.
                    if (analysis.aWidth > 0 && analysis.aHeight > 0
                            && analysis.iconWidth > 0 && analysis.iconHeight > 0) {
                        float upScale;
                        if (analysis.noMixinNeeded) {
                            // Squarish opaque icon: scale to just fit (min of both axes)
                            upScale = Math.min(
                                    analysis.iconWidth  / analysis.aWidth,
                                    analysis.iconHeight / analysis.aHeight);
                            foreground.setScale(NO_MIXIN_ICON_SCALE * upScale);
                        } else {
                            // Full-bleed icon: scale to fill completely (max of both axes)
                            upScale = Math.max(
                                    analysis.iconWidth  / analysis.aWidth,
                                    analysis.iconHeight / analysis.aHeight);
                            foreground.setScale(FULL_BLEED_ICON_SCALE * upScale);
                        }
                    } else {
                        // Fallback: visible dims not populated (e.g. all-opaque icon)
                        foreground.setScale(analysis.noMixinNeeded
                                ? NO_MIXIN_ICON_SCALE
                                : FULL_BLEED_ICON_SCALE);
                    }
                } else {
                    // Regular legacy icon: use the scale the normalizer gave us
                    foreground.setScale(analysis.normalizerScale);
                }

                CustomAdaptiveIconDrawable wrapper = new CustomAdaptiveIconDrawable(
                        new ColorDrawable(analysis.backgroundColor),
                        foreground);

                // Final scale normalisation pass — same double-normalise the old code did
                scale = new IconNormalizer(mIconBitmapSize).getScale(wrapper);
                outScale[0] = scale;
                return wrapper;

            } else {
                // Original simple path — Palette-based background color
                scale = new IconNormalizer(mIconBitmapSize).getScale(icon);

                int wrapperBackgroundColor = IconPreferencesKt.getWrapperBackgroundColor(
                        mContext, icon);

                FixedScaleDrawable foreground = new FixedScaleDrawable();
                foreground.setDrawable(icon);
                foreground.setScale(scale);

                CustomAdaptiveIconDrawable wrapper = new CustomAdaptiveIconDrawable(
                        new ColorDrawable(wrapperBackgroundColor),
                        foreground);

                scale = new IconNormalizer(mIconBitmapSize).getScale(wrapper);
                outScale[0] = scale;
                return wrapper;
            }

        } else {
            // ----------------------------------------------------------------
            // ADAPTIVE ICON PATH (or wrapping disabled)
            // ----------------------------------------------------------------
            if (icon instanceof AdaptiveIconDrawable aid) {
                // When treatWhiteAdaptive is enabled, check if this adaptive icon has a plain
                // white background. If so, extract color from the foreground and recolor it —
                // this replicates the old AdaptiveIconGenerator treatWhite path.
                if (treatWhiteAdaptive) {
                    Drawable background  = aid.getBackground();
                    Drawable foreground  = aid.getForeground();

                    if (isSingleColor(background, Color.WHITE) && foreground != null) {
                        // Run color extraction on the foreground layer only
                        AdaptiveIconAnalysis analysis = new AdaptiveIconAnalysis();
                        analyzeIconPixels(foreground, analysis, true);

                        int recoloredBg = analysis.backgroundColor;

                        // Attempt an in-place mutation first (same as old genResult() logic)
                        if (background instanceof ColorDrawable) {
                            AdaptiveIconDrawable mutated = (AdaptiveIconDrawable) aid.mutate();
                            ((ColorDrawable) mutated.getBackground()).setColor(recoloredBg);
                            outScale[0] = ICON_VISIBLE_AREA_FACTOR;
                            return mutated;
                        } else {
                            // Background is not a ColorDrawable — reconstruct with new bg
                            CustomAdaptiveIconDrawable rebuilt = new CustomAdaptiveIconDrawable(
                                    new ColorDrawable(recoloredBg), foreground);
                            outScale[0] = ICON_VISIBLE_AREA_FACTOR;
                            return rebuilt;
                        }
                    }
                }

                outScale[0] = ICON_VISIBLE_AREA_FACTOR;
                return icon;
            }

            if (shouldWrapAdaptive) {
                outScale[0] = ICON_VISIBLE_AREA_FACTOR;
                return wrapToAdaptiveIcon(icon);
            } else {
                scale = new IconNormalizer(mIconBitmapSize).getScale(icon);
                outScale[0] = scale;
                return icon;
            }
        }
    }

    /**
     * Returns a drawable which draws the original drawable at a fixed scale
     */
    private Drawable createScaledDrawable(@NonNull Drawable main, float scale) {
        float h = main.getIntrinsicHeight();
        float w = main.getIntrinsicWidth();
        float scaleX = scale;
        float scaleY = scale;
        if (h > w && w > 0) {
            scaleX *= w / h;
        } else if (w > h && h > 0) {
            scaleY *= h / w;
        }
        scaleX = (1 - scaleX) / 2;
        scaleY = (1 - scaleY) / 2;
        return new InsetDrawable(main, scaleX, scaleY, scaleX, scaleY);
    }

    /**
     * Wraps the provided icon in an adaptive icon drawable
     */
    public AdaptiveIconDrawable wrapToAdaptiveIcon(@NonNull Drawable icon) {
        if (icon instanceof AdaptiveIconDrawable aid) {
            return aid;
        } else {
            int wrapperBackgroundColor = IconPreferencesKt.getWrapperBackgroundColor(mContext, icon);

            float scale = new IconNormalizer(mIconBitmapSize).getScale(icon);
            CustomAdaptiveIconDrawable dr = new CustomAdaptiveIconDrawable(
                    new ColorDrawable(wrapperBackgroundColor), createScaledDrawable(icon, scale * LEGACY_ICON_SCALE));
            dr.setBounds(0, 0, 1, 1);

            return dr;
        }
    }

    @NonNull
    public Bitmap createIconBitmap(@Nullable final Drawable icon, final float scale) {
        return createIconBitmap(icon, scale, MODE_DEFAULT);
    }

    @NonNull
    public Bitmap createIconBitmap(@Nullable final Drawable icon, final float scale,
            @BitmapGenerationMode int bitmapGenerationMode) {
        final int size = mIconBitmapSize;
        final Bitmap bitmap;
        switch (bitmapGenerationMode) {
            case MODE_ALPHA:
                bitmap = Bitmap.createBitmap(size, size, Config.ALPHA_8);
                break;
            case MODE_HARDWARE:
            case MODE_HARDWARE_WITH_SHADOW: {
                return BitmapRenderer.createHardwareBitmap(size, size, canvas ->
                        drawIconBitmap(canvas, icon, scale, bitmapGenerationMode, null));
            }
            case MODE_WITH_SHADOW:
            default:
                bitmap = Bitmap.createBitmap(size, size, Config.ARGB_8888);
                break;
        }
        if (icon == null) {
            return bitmap;
        }
        mCanvas.setBitmap(bitmap);
        drawIconBitmap(mCanvas, icon, scale, bitmapGenerationMode, bitmap);
        mCanvas.setBitmap(null);
        return bitmap;
    }

    private void drawIconBitmap(@NonNull Canvas canvas, @Nullable Drawable icon,
            final float scale, @BitmapGenerationMode int bitmapGenerationMode,
            @Nullable Bitmap targetBitmap) {
        final int size = mIconBitmapSize;
        mOldBounds.set(icon.getBounds());
        if (icon instanceof AdaptiveIconDrawable aid) {
            // We are ignoring KEY_SHADOW_DISTANCE because regular icons ignore this at the
            // moment b/298203449
            int offset = Math.max((int) Math.ceil(BLUR_FACTOR * size),
                    Math.round(size * (1 - scale) / 2));
            // b/211896569: AdaptiveIconDrawable do not work properly for non top-left bounds
            int newBounds = size - offset * 2;
            icon.setBounds(0, 0, newBounds, newBounds);
            Path shapePath = getShapePath(aid, icon.getBounds());
            int count = canvas.save();
            canvas.translate(offset, offset);
            if (bitmapGenerationMode == MODE_WITH_SHADOW
                    || bitmapGenerationMode == MODE_HARDWARE_WITH_SHADOW) {
                getShadowGenerator().addPathShadow(shapePath, canvas);
            }

            if (icon instanceof Extender) {
                ((Extender) icon).drawForPersistence(canvas);
            } else {
                drawAdaptiveIcon(canvas, aid, shapePath);
            }

            canvas.restoreToCount(count);
        } else {
            if (icon instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
                Bitmap b = bitmapDrawable.getBitmap();
                if (b != null && b.getDensity() == Bitmap.DENSITY_NONE) {
                    bitmapDrawable.setTargetDensity(mContext.getResources().getDisplayMetrics());
                }
            }
            int width = size;
            int height = size;

            int intrinsicWidth = icon.getIntrinsicWidth();
            int intrinsicHeight = icon.getIntrinsicHeight();
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                // Scale the icon proportionally to the icon dimensions
                final float ratio = (float) intrinsicWidth / intrinsicHeight;
                if (intrinsicWidth > intrinsicHeight) {
                    height = (int) (width / ratio);
                } else if (intrinsicHeight > intrinsicWidth) {
                    width = (int) (height * ratio);
                }
            }
            final int left = (size - width) / 2;
            final int top = (size - height) / 2;
            icon.setBounds(left, top, left + width, top + height);

            canvas.save();
            canvas.scale(scale, scale, size / 2, size / 2);
            icon.draw(canvas);
            canvas.restore();

            if (bitmapGenerationMode == MODE_WITH_SHADOW && targetBitmap != null) {
                // Shadow extraction only works in software mode
                getShadowGenerator().drawShadow(targetBitmap, canvas);

                // Draw the icon again on top:
                canvas.save();
                canvas.scale(scale, scale, size / 2, size / 2);
                icon.draw(canvas);
                canvas.restore();
            }
        }
        icon.setBounds(mOldBounds);
    }

    /**
     * Draws AdaptiveIconDrawable onto canvas using provided Path
     * and {@link mShaderBitmap} as a shader.
     *
     * @param canvas    canvas to draw on
     * @param drawable  AdaptiveIconDrawable to draw
     * @param shapePath path to clip icon with for shapes
     */
    protected void drawAdaptiveIcon(
            @NonNull Canvas canvas,
            @NonNull AdaptiveIconDrawable drawable,
            @NonNull Path shapePath
    ) {
        Drawable background = drawable.getBackground();
        Drawable foreground = drawable.getForeground();
        if (!Flags.enableLauncherIconShapes() || (background == null && foreground == null)) {
            drawable.draw(canvas);
            return;
        }
        Bitmap shaderBitmap = getAdaptiveShaderBitmap(drawable);
        Paint paint = new Paint();
        paint.setShader(new BitmapShader(shaderBitmap, TileMode.CLAMP, TileMode.CLAMP));
        canvas.drawPath(shapePath, paint);
    }

    @Override
    public void close() {
        clear();
    }

    @NonNull
    public BitmapInfo makeDefaultIcon(IconProvider iconProvider) {
        return createBadgedIconBitmap(iconProvider.getFullResDefaultActivityIcon(mFullResIconDpi));
    }

    /**
     * Returns the correct badge size given an icon size
     */
    public static int getBadgeSizeForIconSize(final int iconSize) {
        return (int) (ICON_BADGE_SCALE * iconSize);
    }

    public static class IconOptions {

        boolean mIsInstantApp;

        boolean mIsArchived;

        @BitmapGenerationMode
        int mGenerationMode = MODE_WITH_SHADOW;

        @Nullable
        UserHandle mUserHandle;
        @Nullable
        UserIconInfo mUserIconInfo;

        @ColorInt
        @Nullable
        Integer mExtractedColor;

        @Nullable
        SourceHint mSourceHint;

        /**
         * User for this icon, in case of badging
         */
        @NonNull
        public IconOptions setUser(@Nullable final UserHandle user) {
            mUserHandle = user;
            return this;
        }

        /**
         * User for this icon, in case of badging
         */
        @NonNull
        public IconOptions setUser(@Nullable final UserIconInfo user) {
            mUserIconInfo = user;
            return this;
        }

        /**
         * If this icon represents an instant app
         */
        @NonNull
        public IconOptions setInstantApp(final boolean instantApp) {
            mIsInstantApp = instantApp;
            return this;
        }

        /**
         * If the icon represents an archived app
         */
        public IconOptions setIsArchived(boolean isArchived) {
            mIsArchived = isArchived;
            return this;
        }

        /**
         * Disables auto color extraction and overrides the color to the provided value
         */
        @NonNull
        public IconOptions setExtractedColor(@ColorInt int color) {
            mExtractedColor = color;
            return this;
        }

        /**
         * Sets the bitmap generation mode to use for the bitmap info. Note that some generation
         * modes do not support color extraction, so consider setting a extracted color manually
         * in those cases.
         */
        public IconOptions setBitmapGenerationMode(@BitmapGenerationMode int generationMode) {
            mGenerationMode = generationMode;
            return this;
        }

        /**
         * User for this icon, in case of badging
         */
        @NonNull
        public IconOptions setSourceHint(@Nullable SourceHint sourceHint) {
            mSourceHint = sourceHint;
            return this;
        }
    }

    /**
     * An extension of {@link BitmapDrawable} which returns the bitmap pixel size as intrinsic size.
     * This allows the badging to be done based on the action bitmap size rather than
     * the scaled bitmap size.
     */
    private static class FixedSizeBitmapDrawable extends BitmapDrawable {

        public FixedSizeBitmapDrawable(@Nullable final Bitmap bitmap) {
            super(null, bitmap);
        }

        @Override
        public int getIntrinsicHeight() {
            return getBitmap().getWidth();
        }

        @Override
        public int getIntrinsicWidth() {
            return getBitmap().getWidth();
        }
    }

    private static class NoopDrawable extends ColorDrawable {
        @Override
        public int getIntrinsicHeight() {
            return 1;
        }

        @Override
        public int getIntrinsicWidth() {
            return 1;
        }
    }

    private static class CenterTextDrawable extends ColorDrawable {

        @NonNull
        private final Rect mTextBounds = new Rect();

        @NonNull
        private final Paint mTextPaint = new Paint(ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG);

        @NonNull
        private final String mText;

        CenterTextDrawable(@NonNull final String text, final int color) {
            mText = text;
            mTextPaint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            mTextPaint.setTextSize(bounds.height() / 3f);
            mTextPaint.getTextBounds(mText, 0, mText.length(), mTextBounds);
            canvas.drawText(mText,
                    bounds.exactCenterX() - mTextBounds.exactCenterX(),
                    bounds.exactCenterY() - mTextBounds.exactCenterY(),
                    mTextPaint);
        }
    }

    private static class WrappedAdaptiveIcon extends AdaptiveIconDrawable {

        WrappedAdaptiveIcon(Drawable backgroundDrawable, Drawable foregroundDrawable) {
            super(backgroundDrawable, foregroundDrawable);
        }
    }
}
