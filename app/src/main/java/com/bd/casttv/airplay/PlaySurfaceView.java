package com.bd.casttv.airplay;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * A {@link SurfaceView} that keeps the aspect ratio of the mirrored screen
 * (letterboxing/pillarboxing to fit the TV panel).
 *
 * Adapted from caijianxiong/AirplayAndroidReceiver (MIT).
 */
public class PlaySurfaceView extends SurfaceView {

    private float mWidth;
    private float mHeight;

    public PlaySurfaceView(Context context) {
        super(context);
    }

    public PlaySurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PlaySurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PlaySurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Set the source video's native width/height so aspect ratio can be preserved. */
    public void setMeasure(float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
        post(() -> {
            requestLayout();
            invalidate();
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int specWidth = MeasureSpec.getSize(widthMeasureSpec);
        int specHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (mWidth > 0 && mHeight > 0 && specWidth > 0 && specHeight > 0) {
            float videoRatio = mWidth / mHeight;
            float screenRatio = (float) specWidth / specHeight;

            int finalWidth;
            int finalHeight;
            if (videoRatio > screenRatio) {
                finalWidth = specWidth;
                finalHeight = (int) (specWidth / videoRatio);
            } else {
                finalHeight = specHeight;
                finalWidth = (int) (specHeight * videoRatio);
            }
            setMeasuredDimension(finalWidth, finalHeight);
        } else {
            setMeasuredDimension(specWidth, specHeight);
        }
    }
}
