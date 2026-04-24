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
    // AdaptiveIconGenerator constants
    // -----------------------------------------------------------------------

    private static final int NUMBER_OF_COLORS_GUESSTIMATION = 45;

    // Scale applied when an icon fills its entire bounds with no transparent padding.
    private static final float FULL_BLEED_ICON_SCALE = 1.44f;

    // Scale applied when an icon is squarish and opaque enough to skip color mixing.
    private static final float NO_MIXIN_ICON_SCALE = 1.40f;

    // Icons with this many unique posterized colors or fewer are treated as "single color".
    private static final int SINGLE_COLOR_LIMIT = 5;

    // Pixels at or above this alpha are considered fully opaque for analysis purposes.
    // Intentionally stricter than IconNormalizer's threshold (40) to match the original
    // AdaptiveIconGenerator behavior precisely.
    private static final int ADAPTIVE_MIN_VISIBLE_ALPHA = 0xEF;

    // If more than this fraction of pixels are transparent, the icon is foreground art on
    // a transparent canvas — it has no natural background color. In that case we skip color
    // blending and use plain white instead.
    //
    // Without this guard, the dominant color extracted from thin colored strokes (e.g. the
    // blue robot body in Apktool M, or the colored shapes in transparent Google app icons)
    // gets blended toward 0xFF333333 because its lightness is < 0.5, producing a
    // deep-blue or near-black background that looks obviously wrong.
    private static final float TRANSPARENT_BACKGROUND_THRESHOLD = 0.50f;

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
                // Fill with TRANSPARENT instead of BLACK so that areas outside the
                // child layers (the extra-inset zone used for parallax/animation effects)
                // are transparent rather than black. This prevents black corner artifacts
                // when the user's chosen icon shape (e.g. teardrop) clips into that zone.
                // Matches the same fix applied to CustomAdaptiveIconDrawable.draw().
                canvas.drawColor(Color.TRANSPARENT);
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
     *
     * Quantizes each R/G/B channel to 4 bits (16 buckets per channel, step of 16), then
     * packs them into a single int used as a histogram key. Nearby colors map to the same
     * bucket so one truly dominant color wins cleanly even in photos with slight gradients.
     * Returns -1 as a defensive guard for underflow.
     */
    private static int posterizeColor(int pixel) {
        int r = ((pixel >> 16) & 0xFF) >> 4;
        int g = ((pixel >> 8)  & 0xFF) >> 4;
        int b = (pixel         & 0xFF) >> 4;
        int result = (r << 8) | (g << 4) | b;
        return result < 0 ? -1 : result;
    }

    /**
     * Returns true if the given drawable is entirely (or almost entirely) a single opaque color.
     *
     * Used to detect whether an adaptive icon's background is plain white before deciding
     * whether to recolor it. For ColorDrawable inputs this is a direct integer comparison.
    /**
     * Returns true if the given drawable is entirely (or almost entirely) a single opaque color.
     *
     * Used to detect whether an adaptive icon's background is plain white before deciding
     * whether to recolor it. For ColorDrawable inputs this is a direct integer comparison.
     * For other drawables we rasterize to a 64x64 thumbnail and scan every opaque pixel.
     *
     * BOUNDS CONTRACT: To avoid mutating the original drawable's bounds (which caused blank
     * icons when the drawable was later used inside FixedScaleDrawable), we obtain a fresh
     * copy via getConstantState().newDrawable(). The copy starts with empty bounds, we set
     * them only on the copy, and the original is never touched. If constantState is null,
     * we fall back to the save/restore approach but only restore to non-empty bounds.
     */
    private static boolean isSingleColor(@Nullable Drawable drawable, int color) {
        if (drawable == null) {
            return false;
        }
        if (drawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getColor() == color;
        }

        // Use a fresh copy so we never set bounds on the original drawable.
        Drawable rasterTarget = null;
        final Drawable.ConstantState cs = drawable.getConstantState();
        if (cs != null) {
            rasterTarget = cs.newDrawable().mutate();
        }

        final int sampleSize = 64;
        Bitmap bitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        if (rasterTarget != null) {
            // Draw the copy — original bounds are untouched.
            rasterTarget.setBounds(0, 0, sampleSize, sampleSize);
            rasterTarget.draw(canvas);
        } else {
            // constantState unavailable — save and restore original bounds.
            // Only restore to non-empty bounds to avoid breaking drawable state.
            final Rect savedBounds = new Rect(drawable.getBounds());
            drawable.setBounds(0, 0, sampleSize, sampleSize);
            drawable.draw(canvas);
            if (!savedBounds.isEmpty()) {
                drawable.setBounds(savedBounds);
            }
        }

        int[] pixels = new int[sampleSize * sampleSize];
        bitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize);
        bitmap.recycle();

        for (int pixel : pixels) {
            int alpha = (pixel >> 24) & 0xFF;
            if (alpha < ADAPTIVE_MIN_VISIBLE_ALPHA) {
                continue;
            }
            if ((pixel & 0x00FFFFFF) != (color & 0x00FFFFFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Core pixel-level analysis replicating the old AdaptiveIconGenerator.loop() logic.
     *
     * Steps:
     *   1. Rasterize the drawable to a bitmap using its intrinsic size.
     *   2. Walk pixels to find the visible bounding box and check squarishness.
     *   3. Compute padding-corrected transparency thresholds (10% = full-bleed,
     *      27% = no-mixin) matching the original AdaptiveIconGenerator constants.
     *   4. If the icon is mostly transparent (> TRANSPARENT_BACKGROUND_THRESHOLD),
     *      it has no natural background — mark isMostlyTransparent=true and use white.
     *      Caller decides the final color based on recolor preference.
     *   5. Build a posterized RGB histogram in the same pass to find dominant color.
     *   6. For full-bleed / noMixin icons: backgroundColor = white (same as default).
     *      bestRGB is tracked but only controls SCALE, never background color.
     *      See the comment in normalizeAndWrapToAdaptiveIcon for the full rationale.
     *   7. For normal icons with padding, always return white. Caller applies
     *      getWrapperBackgroundColor() if recolor is enabled.
     *
     * NOTE: The HSL-blend-toward-0xFF333333 path from the old implementation has been
     * removed entirely. That path caused FDM, Hunter, and other dark-logo icons to get
     * deep-blue or charcoal backgrounds. Background coloring for normal icons now always
     * goes through Palette + forced-lightness (getWrapperBackgroundColor), which the user
     * can tune via pref_coloredBackgroundLightness.
     *
     * BOUNDS CONTRACT: This method MUST NOT mutate the original drawable's bounds.
     * See the detailed comment in the old implementation for the full explanation.
     *
     * @param extractee    Raw legacy icon or adaptive foreground layer to analyze.
     * @param out          Receives isFullBleed, noMixinNeeded, isMostlyTransparent,
     *                     backgroundColor, normalizerScale, and visible dimensions.
     */
    private void analyzeIconPixels(
            @NonNull Drawable extractee,
            @NonNull AdaptiveIconAnalysis out) {

        // Step 0: normalizer scale (used by caller for the default scale path).
        // IconNormalizer.getScale() also calls setBounds() on the drawable, but it does
        // so only on AdaptiveIconDrawable sub-types (returns early). For non-adaptive
        // drawables it uses its OWN internal Bitmap canvas and a fresh setBounds call
        // without disturbing the passed-in drawable's external bounds state.
        out.normalizerScale = new IconNormalizer(mIconBitmapSize).getScale(extractee);

        // Step 1: determine raster dimensions.
        final int width;
        final int height;
        int intrinsicW = extractee.getIntrinsicWidth();
        int intrinsicH = extractee.getIntrinsicHeight();
        if (intrinsicW > 0 && intrinsicH > 0) {
            width  = intrinsicW;
            height = intrinsicH;
        } else {
            width  = mIconBitmapSize;
            height = mIconBitmapSize;
        }

        // Obtain a drawable to rasterize WITHOUT mutating the original's bounds.
        // We use ConstantState.newDrawable() to get a fresh independent copy.
        // If ConstantState is unavailable (e.g. certain custom drawables), we fall
        // back to setting bounds on the original — but only restore to non-empty
        // saved bounds, since empty bounds == "never set" and should be left alone.
        Bitmap bitmap;
        {
            final Drawable.ConstantState cs = extractee.getConstantState();
            if (cs != null) {
                // Rasterize a fresh copy — original extractee bounds are untouched.
                final Drawable copy = cs.newDrawable().mutate();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(bitmap);
                copy.setBounds(0, 0, width, height);
                copy.draw(canvas);
                // copy goes out of scope and is GC'd; no cleanup needed.
            } else {
                // Fallback: mutate original bounds, restore only if they were non-empty.
                final Rect savedBounds = new Rect(extractee.getBounds());
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(bitmap);
                extractee.setBounds(0, 0, width, height);
                extractee.draw(canvas);
                if (!savedBounds.isEmpty()) {
                    extractee.setBounds(savedBounds);
                }
                // If savedBounds was empty, leave bounds at (0,0,width,height).
                // This is a BitmapDrawable or similar that has never had bounds set by
                // the caller — leaving non-empty bounds here is harmless because
                // drawIconBitmap() will set its own bounds via mOldBounds/setBounds.
            }
        }

        if (!bitmap.hasAlpha()) {
            out.isFullBleed      = true;
            out.fullBleedChecked = true;
        }

        final int totalPixels = width * height;
        final int[] pixels = new int[totalPixels];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();

        // Step 2: visible bounding box.
        int bLeft = width, bRight = -1, bTop = height, bBottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (pixels[y * width + x] >> 24) & 0xFF;
                if (alpha >= ADAPTIVE_MIN_VISIBLE_ALPHA) {
                    if (x < bLeft)   bLeft   = x;
                    if (x > bRight)  bRight  = x;
                    if (y < bTop)    bTop    = y;
                    if (y > bBottom) bBottom = y;
                }
            }
        }

        if (bRight < 0 || bBottom < 0) {
            // Entirely transparent — white background, nothing else to do.
            out.backgroundColor = DEFAULT_WRAPPER_BACKGROUND;
            return;
        }

        final float aWidth  = bRight  - bLeft   + 1;
        final float aHeight = bBottom - bTop    + 1;

        // Squarishness check — matches old AdaptiveIconGenerator exactly.
        final float ratio            = aHeight / aWidth;
        final boolean isSquareish    = ratio > 0.999f && ratio < 1.0001f;
        final boolean almostSquarish = isSquareish || (ratio > 0.97f && ratio < 1.005f);

        if (!isSquareish && !out.fullBleedChecked) {
            out.isFullBleed      = false;
            out.fullBleedChecked = true;
        }

        // Step 3: padding-corrected transparency thresholds.
        // Fractional bounds derived from the pixel bounding box, matching the original
        // AdaptiveIconGenerator "addPixels" calculation (bounds.left/top/right/bottom).
        final float fracLeft   = (float) bLeft                  / width;
        final float fracTop    = (float) bTop                   / height;
        final float fracRight  = (float) (width  - 1 - bRight)  / width;
        final float fracBottom = (float) (height - 1 - bBottom) / height;
        final float adjHeight  = height * (1f - fracTop - fracBottom);
        final float paddingPixels =
                fracLeft   * width  * adjHeight
              + fracTop    * height * width
              + fracRight  * width  * adjHeight
              + fracBottom * height * width;
        final int addPixels = Math.round(paddingPixels);

        final int maxTransparent = (int) (Math.round(totalPixels * 0.10f) + addPixels);
        final int noMixinScore   = (int) (Math.round(totalPixels * 0.27f) + addPixels);

        // Step 4: single-pass scan — transparency count + RGB histogram.
        SparseIntArray rgbScoreHistogram = new SparseIntArray(NUMBER_OF_COLORS_GUESSTIMATION);
        int highScore        = 0;
        int bestRGB          = 0;
        int transparentScore = 0;

        for (int pixel : pixels) {
            int alpha = (pixel >> 24) & 0xFF;
            if (alpha < ADAPTIVE_MIN_VISIBLE_ALPHA) {
                transparentScore++;
                if (transparentScore > maxTransparent && !out.fullBleedChecked) {
                    out.isFullBleed      = false;
                    out.fullBleedChecked = true;
                }
                continue;
            }
            int rgb = posterizeColor(pixel & 0x00FFFFFF);
            if (rgb < 0) {
                continue;
            }
            int currentScore = rgbScoreHistogram.get(rgb) + 1;
            rgbScoreHistogram.append(rgb, currentScore);
            if (currentScore > highScore) {
                highScore = currentScore;
                bestRGB   = rgb;
            }
        }

        // Restore full alpha on the winning color.
        bestRGB |= 0xFF << 24;

        // If fullBleed was never definitively set to false, the icon must be full bleed.
        if (!out.fullBleedChecked) {
            out.isFullBleed = true;
        }

        // Step 5: transparent-background guard.
        // If the majority of pixels are transparent, the icon is foreground art on a
        // transparent canvas. Mark it so the caller can decide the background (white).
        // We do NOT attempt to extract a representative color from thin strokes because
        // that produces wrong tints (deep-blue on Apktool M, dark tints on Google icons).
        final float transparentFraction = (float) transparentScore / totalPixels;
        if (transparentFraction > TRANSPARENT_BACKGROUND_THRESHOLD) {
            out.isMostlyTransparent = true;
            out.backgroundColor = DEFAULT_WRAPPER_BACKGROUND;
            out.aWidth     = aWidth;
            out.aHeight    = aHeight;
            out.iconWidth  = width;
            out.iconHeight = height;
            return;
        }

        // Step 6: no-mixin shortcut.
        out.noMixinNeeded = !out.isFullBleed
                && almostSquarish
                && (transparentScore <= noMixinScore);

        // Background color is ALWAYS white for full-bleed and noMixin icons.
        //
        // Previous code used bestRGB here, reasoning that a color-matched background
        // would look "seamless" at the shape-mask corners. In practice this produced the
        // exact opposite effect: icons whose dominant pixel color is a strong hue
        // (Mobile JKN = solid blue, Camera = red, LetsVPN = purple gradient) got a
        // brightly-colored background that was clearly visible at the corners of circle
        // or squircle shapes — what the user described as the "deep blue mask".
        //
        // The correct behavior (per the design spec) is:
        //   "if the icon completely fills the canvas, don't do anything" — i.e. the
        //   background is irrelevant for truly full-bleed icons because no gap is visible.
        //   For icons with a tiny gap (noMixin), white is the safe universal default.
        //
        // bestRGB is still tracked in case callers need it in the future, but it is NOT
        // written into out.backgroundColor. The background decision is left to the caller,
        // which applies getWrapperBackgroundColor() (white forced to lightness 1.0 by
        // default, or a tinted color when recolor is enabled).
        if (out.isFullBleed || out.noMixinNeeded) {
            out.backgroundColor = DEFAULT_WRAPPER_BACKGROUND;
            out.aWidth     = aWidth;
            out.aHeight    = aHeight;
            out.iconWidth  = width;
            out.iconHeight = height;
            return;
        }

        // Step 7: normal icon with visible padding — always white.
        // Caller applies getWrapperBackgroundColor() when recolor is enabled.
        out.backgroundColor = DEFAULT_WRAPPER_BACKGROUND;
        out.aWidth     = aWidth;
        out.aHeight    = aHeight;
        out.iconWidth  = width;
        out.iconHeight = height;
    }

    /**
     * Value object carrying the results of {@link #analyzeIconPixels}.
     */
    private static class AdaptiveIconAnalysis {
        boolean isFullBleed         = false;
        boolean fullBleedChecked    = false;
        boolean noMixinNeeded       = false;
        // True when > TRANSPARENT_BACKGROUND_THRESHOLD of pixels are transparent.
        // Indicates the icon is thin line-art on a transparent canvas — no natural
        // background color can be extracted, so caller should use white.
        boolean isMostlyTransparent = false;
        int     backgroundColor     = DEFAULT_WRAPPER_BACKGROUND;
        float   normalizerScale     = 1f;
        // Visible (non-padded) dimensions — used to compute per-type upscale factors.
        float   aWidth     = 0f;
        float   aHeight    = 0f;
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

        boolean isFromIconPack     = ExtendedBitmapDrawable.isFromIconPack(icon);
        boolean shouldWrapAdaptive = !isFromIconPack && IconPreferencesKt.shouldWrapAdaptive(mContext);
        boolean shrinkNonAdaptiveIcons = IconProvider.ATLEAST_OREO && shouldWrapAdaptive;

        boolean colorizeBackground = IconPreferencesKt.shouldColorizeBackground(mContext);
        // treatWhiteAdaptive: when ON, foreground-only adaptive icons (transparent background)
        // get a dominant color extracted from the foreground instead of plain white.
        // It has no effect on adaptive icons that have a real (non-transparent) background —
        // those are always returned untouched regardless of this flag.
        boolean treatWhiteAdaptive = colorizeBackground && IconPreferencesKt.shouldTreatWhiteAdaptive(mContext);

        float scale;

        // ----------------------------------------------------------------
        // FIX for 720p pixelation:
        // BitmapDrawable loaded from apps at mdpi density (48px) on hdpi devices (72px)
        // produces a pixelated result when upscaled inside FixedScaleDrawable.
        // Calling setTargetDensity() ensures the drawable reports the correct intrinsic
        // size for the display, so FixedScaleDrawable wraps it at the right dimensions.
        // We only do this for non-adaptive BitmapDrawable since adaptive icons handle
        // their own density scaling internally.
        if (icon instanceof BitmapDrawable bmd && !(icon instanceof AdaptiveIconDrawable)) {
            android.graphics.Bitmap b = bmd.getBitmap();
            if (b != null && b.getDensity() != android.graphics.Bitmap.DENSITY_NONE) {
                bmd.setTargetDensity(mContext.getResources().getDisplayMetrics());
            }
        }

        if (shrinkNonAdaptiveIcons && !(icon instanceof AdaptiveIconDrawable)) {
            // ----------------------------------------------------------------
            // CASE 3 — LEGACY (non-adaptive) ICON
            //
            // When Smart Backgrounds (colorizeBackground) is ON:
            //   Run pixel analysis to detect fullBleed/noMixin for accurate scale
            //   and use Palette for background color.
            //
            // When Smart Backgrounds is OFF:
            //   Use the original simple path (normalizerScale + Palette background).
            //   Do NOT run analyzeIconPixels — doing so changes scale for full-bleed
            //   icons (Camera, LetsVPN, etc.) even though the user disabled the feature,
            //   causing those icons to look different from stock Lawnchair behavior.
            // ----------------------------------------------------------------

            if (colorizeBackground) {
                // --- Smart Backgrounds ON: full pixel analysis path ---
                AdaptiveIconAnalysis analysis = new AdaptiveIconAnalysis();
                analyzeIconPixels(icon, analysis);

                FixedScaleDrawable foreground = new FixedScaleDrawable();
                foreground.setDrawable(icon);

                if (analysis.isFullBleed || analysis.noMixinNeeded) {
                    if (analysis.aWidth > 0 && analysis.aHeight > 0
                            && analysis.iconWidth > 0 && analysis.iconHeight > 0) {
                        float upScale;
                        if (analysis.noMixinNeeded) {
                            upScale = Math.min(
                                    analysis.iconWidth  / analysis.aWidth,
                                    analysis.iconHeight / analysis.aHeight);
                            foreground.setScale(NO_MIXIN_ICON_SCALE * upScale);
                        } else {
                            upScale = Math.max(
                                    analysis.iconWidth  / analysis.aWidth,
                                    analysis.iconHeight / analysis.aHeight);
                            foreground.setScale(FULL_BLEED_ICON_SCALE * upScale);
                        }
                    } else {
                        foreground.setScale(analysis.noMixinNeeded
                                ? NO_MIXIN_ICON_SCALE
                                : FULL_BLEED_ICON_SCALE);
                    }
                } else {
                    foreground.setScale(analysis.normalizerScale);
                }

                // Background: white/gray based on lightness when colorize is OFF.
                // Colored (Palette + lightness) only when treatWhiteAdaptive is ON.
                // isFullBleed/noMixin affect SCALE only, not background color.
                final int bgColor;
                if (analysis.isMostlyTransparent) {
                    bgColor = DEFAULT_WRAPPER_BACKGROUND;
                } else if (treatWhiteAdaptive) {
                    // Colorize ON: extract dominant color from icon, apply lightness pref.
                    bgColor = IconPreferencesKt.getWrapperBackgroundColor(mContext, icon);
                } else {
                    // Colorize OFF: apply lightness to neutral white only (no hue from icon).
                    // Lightness 100% = white, 50% = gray, 0% = black.
                    bgColor = IconPreferencesKt.getMonochromeBackgroundColor(mContext);
                }

                CustomAdaptiveIconDrawable wrapper = new CustomAdaptiveIconDrawable(
                        new ColorDrawable(bgColor), foreground);
                scale = new IconNormalizer(mIconBitmapSize).getScale(wrapper);
                outScale[0] = scale;
                return wrapper;

            } else {
                // --- Smart Backgrounds OFF: original simple Palette path ---
                scale = new IconNormalizer(mIconBitmapSize).getScale(icon);

                int wrapperBackgroundColor = IconPreferencesKt.getWrapperBackgroundColor(
                        mContext, icon);

                FixedScaleDrawable foreground = new FixedScaleDrawable();
                foreground.setDrawable(icon);
                foreground.setScale(scale);

                CustomAdaptiveIconDrawable wrapper = new CustomAdaptiveIconDrawable(
                        new ColorDrawable(wrapperBackgroundColor), foreground);
                scale = new IconNormalizer(mIconBitmapSize).getScale(wrapper);
                outScale[0] = scale;
                return wrapper;
            }

        } else {
            // ----------------------------------------------------------------
            // ADAPTIVE ICON PATH (or wrapping disabled)
            // ----------------------------------------------------------------
            if (icon instanceof AdaptiveIconDrawable aid) {
                if (colorizeBackground) {
                    // RULE (per Android adaptive icon spec):
                    //
                    // An adaptive icon has two layers:
                    //   - background: fills the full 108x108dp canvas, provides the colored base
                    //   - foreground: the actual icon content (logo, illustration, etc.)
                    //
                    // If the icon has a REAL background (non-null, non-transparent ColorDrawable
                    // or any drawable that renders visible content), the developer intentionally
                    // designed the icon with that background — we MUST NOT change it. Replacing
                    // a developer-designed background destroys the intended look (e.g. FDM's dark
                    // teal, ColorNote's yellow, Camera's red, YouTube's red).
                    //
                    // If the background is null or fully transparent, the icon is "foreground-only"
                    // — the developer provided only icon art with no background, expecting the
                    // launcher to supply one. In this case we should apply a background:
                    //   - wrapAdaptive ON only: white background (DEFAULT_WRAPPER_BACKGROUND)
                    //   - wrapAdaptive + recolor ON: dominant color extracted from the foreground

                    Drawable background = aid.getBackground();
                    Drawable foreground = aid.getForeground();

                    if (isBackgroundTransparent(background) && foreground != null) {
                        // ----------------------------------------------------------------
                        // CASE 2 — PARTIAL ADAPTIVE (foreground-only) ICON
                        //
                        // The developer provided only the foreground layer and expected the
                        // launcher to supply a background. Most Google apps follow this pattern.
                        //
                        //   • recolor OFF → white background (DEFAULT_WRAPPER_BACKGROUND).
                        //   • recolor ON  → Palette dominant color from the foreground layer
                        //                   forced to pref_coloredBackgroundLightness.
                        //                   At default lightness 1.0 this is still white;
                        //                   lowering it gives a softly tinted background.
                        //
                        // We do NOT run analyzeIconPixels here — Palette is fast enough for
                        // the partial-adaptive case and avoids an extra rasterization pass.
                        // ----------------------------------------------------------------
                        final int newBg;
                        if (treatWhiteAdaptive) {
                            // Colorize ON: Palette dominant color from foreground at lightness pref.
                            newBg = IconPreferencesKt.getWrapperBackgroundColor(mContext, foreground);
                        } else {
                            // Colorize OFF: lightness-only gray/white, no hue from icon.
                            // At default 100% lightness this is plain white.
                            newBg = IconPreferencesKt.getMonochromeBackgroundColor(mContext);
                        }

                        if (background instanceof ColorDrawable) {
                            AdaptiveIconDrawable mutated = (AdaptiveIconDrawable) aid.mutate();
                            ((ColorDrawable) mutated.getBackground()).setColor(newBg);
                            outScale[0] = ICON_VISIBLE_AREA_FACTOR;
                            return mutated;
                        } else {
                            CustomAdaptiveIconDrawable rebuilt = new CustomAdaptiveIconDrawable(
                                    new ColorDrawable(newBg), foreground);
                            outScale[0] = ICON_VISIBLE_AREA_FACTOR;
                            return rebuilt;
                        }
                    }
                    // Real background → CASE 1: return completely untouched.
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
     * Returns true if the given background drawable is null or effectively transparent —
     * meaning the adaptive icon has no real background and expects the launcher to supply one.
     *
     * A background is considered transparent if:
     *   - It is null (no background layer was provided)
     *   - It is a ColorDrawable with alpha == 0
     *   - It rasterizes to > 95% transparent pixels (complex drawable that happens to be empty)
     *
     * We do NOT consider near-transparent or semi-transparent backgrounds as "transparent"
     * because that would cause us to replace an intentionally subtle background.
     */
    private static boolean isBackgroundTransparent(@Nullable Drawable background) {
        if (background == null) {
            return true;
        }
        if (background instanceof ColorDrawable cd) {
            // ColorDrawable with zero alpha = transparent
            return Color.alpha(cd.getColor()) == 0;
        }
        // For complex drawables: rasterize a small sample and check pixel transparency.
        // If >= 95% of pixels are fully transparent, we consider this a "no background" case.
        final int sampleSize = 32;
        final Drawable.ConstantState cs = background.getConstantState();
        final Drawable rasterTarget = (cs != null) ? cs.newDrawable().mutate() : background;
        Bitmap bitmap = Bitmap.createBitmap(sampleSize, sampleSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        rasterTarget.setBounds(0, 0, sampleSize, sampleSize);
        rasterTarget.draw(canvas);
        if (rasterTarget == background) {
            // We mutated the original — restore bounds to prevent downstream issues.
            background.setBounds(0, 0, 0, 0);
        }
        int[] pixels = new int[sampleSize * sampleSize];
        bitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize);
        bitmap.recycle();
        int transparentCount = 0;
        for (int pixel : pixels) {
            if (((pixel >> 24) & 0xFF) < 10) {
                transparentCount++;
            }
        }
        // > 95% transparent pixels = effectively no background
        return (float) transparentCount / pixels.length > 0.95f;
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
        // ANTI_ALIAS_FLAG is critical for smooth edges on round/squircle shapes.
        // Without it, the path clip is computed with no sub-pixel blending, producing
        // jagged staircase artifacts on curved masks — visually obvious on low-density
        // screens (720p) and any shape that is not axis-aligned (circle, squircle, etc.).
        // FILTER_BITMAP_FLAG enables bilinear filtering on the BitmapShader, which
        // prevents blocky pixels when the shader bitmap is sampled at a non-integer scale.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
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

        @NonNull
        public IconOptions setUser(@Nullable final UserHandle user) {
            mUserHandle = user;
            return this;
        }

        @NonNull
        public IconOptions setUser(@Nullable final UserIconInfo user) {
            mUserIconInfo = user;
            return this;
        }

        @NonNull
        public IconOptions setInstantApp(final boolean instantApp) {
            mIsInstantApp = instantApp;
            return this;
        }

        public IconOptions setIsArchived(boolean isArchived) {
            mIsArchived = isArchived;
            return this;
        }

        @NonNull
        public IconOptions setExtractedColor(@ColorInt int color) {
            mExtractedColor = color;
            return this;
        }

        public IconOptions setBitmapGenerationMode(@BitmapGenerationMode int generationMode) {
            mGenerationMode = generationMode;
            return this;
        }

        @NonNull
        public IconOptions setSourceHint(@Nullable SourceHint sourceHint) {
            mSourceHint = sourceHint;
            return this;
        }
    }

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
