package app.lawnchair.icons;

import static com.android.launcher3.icons.BaseIconFactory.LEGACY_ICON_SCALE;

import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.AttributeSet;

import org.xmlpull.v1.XmlPullParser;

/**
 * Extension of {@link DrawableWrapper} which scales the child drawables by a fixed amount.
 */
public class FixedScaleDrawable extends DrawableWrapper {

    private float mScaleX, mScaleY;
    private float mOffsetX, mOffsetY;

    public FixedScaleDrawable() {
        super(new ColorDrawable());
        mScaleX = LEGACY_ICON_SCALE;
        mScaleY = LEGACY_ICON_SCALE;
    }

    @Override
    public void draw(Canvas canvas) {
        int saveCount = canvas.save();
        Rect bounds = getBounds();
        canvas.scale(mScaleX, mScaleY,
                bounds.exactCenterX(), bounds.exactCenterY());
        if (mOffsetX != 0f || mOffsetY != 0f) {
            canvas.translate(mOffsetX * bounds.width(), mOffsetY * bounds.height());
        }
        super.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs) { }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme) { }

    public void setScale(float scale) {
        float h = getIntrinsicHeight();
        float w = getIntrinsicWidth();
        mScaleX = scale * LEGACY_ICON_SCALE;
        mScaleY = scale * LEGACY_ICON_SCALE;
        if (h > w && w > 0) {
            mScaleX *= w / h;
        } else if (w > h && h > 0) {
            mScaleY *= h / w;
        }
    }

    /**
     * Sets a normalized translation offset (in fractions of drawable width/height) applied before
     * scaling so off-center visible icon content is centered within the mask viewport.
     */
    public void setOffset(float offsetX, float offsetY) {
        mOffsetX = offsetX;
        mOffsetY = offsetY;
    }
}
