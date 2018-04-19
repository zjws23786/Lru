package com.hh.lru.imageUtils;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.support.compat.BuildConfig;
import android.util.Log;

/**
 * Created by Administrator on 2018/4/17 0017.
 * 回收缓存数据
 */

public class RecyclingBitmapDrawable extends BitmapDrawable{
    static final String TAG = "CountingBitmapDrawable";

    private int mCacheRefCount = 0;
    private int mDisplayRefCount = 0;

    private boolean mHasBeenDisplayed;

    public RecyclingBitmapDrawable(Resources res, Bitmap bitmap) {
        super(res, bitmap);
    }

    public void setIsDisplayed(boolean isDisplayed) {
        //BEGIN_INCLUDE(set_is_displayed)
        synchronized (this) {
            if (isDisplayed) {
                mDisplayRefCount++;
                mHasBeenDisplayed = true;
            } else {
                mDisplayRefCount--;
            }
        }

        // Check to see if recycle() can be called
        checkState();
        //END_INCLUDE(set_is_displayed)
    }

    public void setIsCached(boolean isCached) {
        //BEGIN_INCLUDE(set_is_cached)
        synchronized (this) {
            if (isCached) {
                mCacheRefCount++;
            } else {
                mCacheRefCount--;
            }
        }

        // Check to see if recycle() can be called
        checkState();
        //END_INCLUDE(set_is_cached)
    }

    private synchronized void checkState() {
        //BEGIN_INCLUDE(check_state)
        // If the drawable cache and display ref counts = 0, and this drawable
        // has been displayed, then recycle
        if (mCacheRefCount <= 0 && mDisplayRefCount <= 0 && mHasBeenDisplayed
                && hasValidBitmap()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No longer being used or cached so recycling. "
                        + toString());
            }

            getBitmap().recycle();
        }
        //END_INCLUDE(check_state)
    }

    private synchronized boolean hasValidBitmap() {
        Bitmap bitmap = getBitmap();
        return bitmap != null && !bitmap.isRecycled();
    }

}
