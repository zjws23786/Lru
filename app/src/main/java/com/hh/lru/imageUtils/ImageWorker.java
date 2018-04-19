package com.hh.lru.imageUtils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.support.compat.BuildConfig;
import android.util.Log;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

/**
 * Created by Administrator on 2018/4/17 0017.
 */

public abstract class ImageWorker {
    private static final String TAG = "ImageWorker";
    private static final int FADE_IN_TIME = 200;
    private ImageCache mImageCache;
    private ImageCache.ImageCacheParams mImageCacheParams;
    private Bitmap mLoadingBitmap;
    private boolean mFadeInBitmap = true;
    private boolean mExitTasksEarly = false;
    protected boolean mPauseWork = false;
    private final Object mPauseWorkLock = new Object();
    protected Resources mResources;

    private static final int MESSAGE_CLEAR = 0;
    private static final int MESSAGE_INIT_DISK_CACHE = 1;
    private static final int MESSAGE_FLUSH = 2;
    private static final int MESSAGE_CLOSE = 3;

    protected ImageWorker(Context context) {
        mResources = context.getResources();
    }

    public void loadImage(Object data, ImageView imageView, OnImageLoadedListener listener) {
        if (data == null) return;
        BitmapDrawable bitmapDrawable = null;
        if (mImageCache != null) {
            bitmapDrawable = mImageCache.getBitmapFromMemCache(String.valueOf(data));
        }

        if (bitmapDrawable != null) {
            // Bitmap found in memory cache
            imageView.setImageDrawable(bitmapDrawable);
            if (listener != null) {
                listener.onImageLoaded(true);
            }
        }else if (cancelPotentialWork(data, imageView)) {
            final BitmapWorkerTask task = new BitmapWorkerTask(data, imageView, listener);
            final AsyncDrawable asyncDrawable = new AsyncDrawable(mResources, mLoadingBitmap, task);
            imageView.setImageDrawable(asyncDrawable);

            // NOTE: This uses a custom version of AsyncTask that has been pulled from the
            // framework and slightly modified. Refer to the docs at the top of the class
            // for more info on what was changed.
            task.executeOnExecutor(AsyncTask.DUAL_THREAD_EXECUTOR);
        }

    }

    protected ImageCache getImageCache() {
        return mImageCache;
    }

    public void loadImage(Object data, ImageView imageView) {
        loadImage(data, imageView, null);
    }

    public void setLoadingImage(Bitmap bitmap) {
        mLoadingBitmap = bitmap;
    }

    public void setLoadingImage(int resId) {
        mLoadingBitmap = BitmapFactory.decodeResource(mResources, resId);
    }

    public void addImageCache(ImageCache.ImageCacheParams cacheParams) {
        mImageCacheParams = cacheParams;
        mImageCache = ImageCache.getInstance(mImageCacheParams);
        new CacheAsyncTask().execute(MESSAGE_INIT_DISK_CACHE);
    }

    public void setImageFadeIn(boolean fadeIn) {
        mFadeInBitmap = fadeIn;
    }

    public void setExitTasksEarly(boolean exitTasksEarly) {
        mExitTasksEarly = exitTasksEarly;
        setPauseWork(false);
    }

    public void addImageCache(Context context, String diskCacheDirectoryName) {
        mImageCacheParams = new ImageCache.ImageCacheParams(context, diskCacheDirectoryName);
        mImageCache = ImageCache.getInstance(mImageCacheParams);
        new CacheAsyncTask().execute(MESSAGE_INIT_DISK_CACHE);
    }

    public static boolean cancelPotentialWork(Object data, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);
        if (bitmapWorkerTask != null) {
            final Object bitmapData = bitmapWorkerTask.mData;
            if (bitmapData == null || !bitmapData.equals(data)) {
                bitmapWorkerTask.cancel(true);
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "cancelPotentialWork - cancelled work for " + data);
                }
            } else {
                // The same work is already in progress.
                return false;
            }
        }
        return true;
    }


    public void setPauseWork(boolean pauseWork) {
        synchronized (mPauseWorkLock) {
            mPauseWork = pauseWork;
            if (!mPauseWork) {
                mPauseWorkLock.notifyAll();
            }
        }
    }

    protected class CacheAsyncTask extends AsyncTask<Object, Void, Void> {
        @Override
        protected Void doInBackground(Object... params) {
            int param = (int) params[0];
            switch (param) {
                case MESSAGE_CLEAR:
                    clearCacheInternal();
                    break;
                case MESSAGE_INIT_DISK_CACHE:
                    initDiskCacheInternal();
                    break;
                case MESSAGE_FLUSH:
                    flushCacheInternal();
                    break;
                case MESSAGE_CLOSE:
                    closeCacheInternal();
                    break;
            }
            return null;
        }
    }

    protected void initDiskCacheInternal() {
        if (mImageCache != null) {
            mImageCache.initDiskCache();
        }
    }

    protected void clearCacheInternal() {
        if (mImageCache != null) {
            mImageCache.clearCache();
        }
    }

    protected void flushCacheInternal() {
        if (mImageCache != null) {
            mImageCache.flush();
        }
    }

    protected void closeCacheInternal() {
        if (mImageCache != null) {
            mImageCache.close();
            mImageCache = null;
        }
    }

    private class BitmapWorkerTask extends AsyncTask<Void, Void, BitmapDrawable> {
        private Object mData;
        private final WeakReference<ImageView> imageViewWeakReference;
        private final OnImageLoadedListener mOnImageLoadedListener;


        /**
         * Background processing.
         */
        public BitmapWorkerTask(Object data, ImageView imageView) {
            mData = data;
            imageViewWeakReference = new WeakReference<ImageView>(imageView);
            mOnImageLoadedListener = null;
        }

        public BitmapWorkerTask(Object data, ImageView imageView, OnImageLoadedListener listener) {
            mData = data;
            imageViewWeakReference = new WeakReference<ImageView>(imageView);
            mOnImageLoadedListener = listener;
        }

        @Override
        protected BitmapDrawable doInBackground(Void... params) {
            //BEGIN_INCLUDE(load_bitmap_in_background)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "doInBackground - starting work");
            }
            final String dataString = String.valueOf(mData);
            Bitmap bitmap = null;
            BitmapDrawable drawable = null;

            // Wait here if work is paused and the task is not cancelled
            synchronized (mPauseWorkLock) {
                while (mPauseWork && !isCancelled()) {
                    try {
                        mPauseWorkLock.wait();
                    } catch (Exception e) {
                    }

                }
            }

            // If the image cache is available and this task has not been cancelled by another
            // thread and the ImageView that was originally bound to this task is still bound back
            // to this task and our "exit early" flag is not set then try and fetch the bitmap from
            // the cache
            if (mImageCache != null && !isCancelled() && getAttachedImageView() != null
                    && !mExitTasksEarly) {
                bitmap = mImageCache.getBitmapFromDiskCache(dataString);
            }

            // If the bitmap was not found in the cache and this task has not been cancelled by
            // another thread and the ImageView that was originally bound to this task is still
            // bound back to this task and our "exit early" flag is not set, then call the main
            // process method (as implemented by a subclass)
            if (bitmap == null && !isCancelled() && getAttachedImageView() != null
                    && !mExitTasksEarly) {
                bitmap = processBitmap(mData);
            }

            // If the bitmap was processed and the image cache is available, then add the processed
            // bitmap to the cache for future use. Note we don't check if the task was cancelled
            // here, if it was, and the thread is still running, we may as well add the processed
            // bitmap to our cache as it might be used again in the future
            if (bitmap != null) {
                if (SdkVersionUtils.hasHoneycomb()) {
                    // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                    drawable = new BitmapDrawable(mResources, bitmap);
                } else {
                    // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
                    // which will recycle automagically
                    drawable = new RecyclingBitmapDrawable(mResources, bitmap);
                }
                if (mImageCache != null) {
                    mImageCache.addBitmapToCache(dataString, drawable);
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "doInBackground - finished work");
            }
            return drawable;
        }

        @Override
        protected void onPostExecute(BitmapDrawable value) {

            boolean success = false;
            // if cancel was called on this task or the "exit early" flag is set then we're done
            if (isCancelled() || mExitTasksEarly) {
                value = null;
            }
            final ImageView imageView = getAttachedImageView();
            if (value != null && imageView != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onPostExecute - setting bitmap");
                }
                success = true;
                setImageDrawable(imageView, value);
            }

            if (mOnImageLoadedListener != null) {
                mOnImageLoadedListener.onImageLoaded(success);
            }
        }

        @Override
        protected void onCancelled(BitmapDrawable value) {
            super.onCancelled(value);
            synchronized (mPauseWorkLock) {
                mPauseWorkLock.notifyAll();
            }
        }

        private ImageView getAttachedImageView() {
            final ImageView imageView = imageViewWeakReference.get();
            final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);
            if (this == bitmapWorkerTask) {
                return imageView;
            }
            return null;
        }
    }

    private void setImageDrawable(ImageView imageView, Drawable drawable) {
        if (mFadeInBitmap) {
            // Transition drawable with a transparent drawable and the final drawable
            final TransitionDrawable td =
                    new TransitionDrawable(new Drawable[]{
                            new ColorDrawable(android.R.color.transparent),
                            drawable
                    });
            // Set background to loading bitmap 设置加载背景图
            imageView.setBackgroundDrawable(new BitmapDrawable(mResources, mLoadingBitmap));

            imageView.setImageDrawable(td);
            td.startTransition(FADE_IN_TIME);
        } else {
            imageView.setImageDrawable(drawable);
        }
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    private static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference =
                    new WeakReference<BitmapWorkerTask>(bitmapWorkerTask);
        }

        public BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    protected abstract Bitmap processBitmap(Object mData);


    public interface OnImageLoadedListener {
        /**
         * Called once the image has been loaded.
         *
         * @param success True if the image was loaded successfully, false if
         *                there was an error.
         */
        void onImageLoaded(boolean success);
    }

    public void clearCache() {
        new CacheAsyncTask().execute(MESSAGE_CLEAR);
    }

    public void flushCache() {
        new CacheAsyncTask().execute(MESSAGE_FLUSH);
    }

    public void closeCache() {
        new CacheAsyncTask().execute(MESSAGE_CLOSE);
    }
}
