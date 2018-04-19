package com.hh.lru.imageUtils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.compat.BuildConfig;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * Created by Administrator on 2018/4/17 0017.
 */

public class ImageResizer extends ImageWorker{
    private static final String TAG = "ImageResizer";
    protected int mImageWidth;
    protected int mImageHeight;

    protected ImageResizer(Context context, int imageWidth, int imageHeight) {
        super(context);
        setImageSize(imageWidth, imageHeight);
    }

    public ImageResizer(Context context, int imageSize) {
        super(context);
        setImageSize(imageSize);
    }

    /**
     * 设置图片大小
     * @param size 这里单位是像素
     */
    public void setImageSize(int size) {
        setImageSize(size, size);
    }

    private void setImageSize(int imageWidth, int imageHeight) {
        this.mImageWidth = imageWidth;
        this.mImageHeight = imageHeight;
    }

    /**
     * 对图片进行缩放
     * @param fileDescriptor FileDescriptor是文件描述符，若需要通过FileDescriptor对该文件进行操作，
     *                          则需要新创建FileDescriptor对应的FileOutputStream，
     *                          再对文件进行操作
     * @param reqWidth   用户期待图片宽
     * @param reqHeight 用户期待图片高
     * @param cache
     * @return
     */
    public static Bitmap decodeSampledBitmapFromDescriptor(
            FileDescriptor fileDescriptor, int reqWidth, int reqHeight, ImageCache cache) {

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);

        // 计算 inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;

        // If we're running on Honeycomb or newer, try to use inBitmap
        if (SdkVersionUtils.hasHoneycomb()) {
            addInBitmapOptions(options, cache);
        }

        return BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
    }

    @Override
    protected Bitmap processBitmap(Object data) {
        return processBitmap(Integer.parseInt(String.valueOf(data)));
    }

    private Bitmap processBitmap(int resId) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "processBitmap - " + resId);
        }
        return decodeSampledBitmapFromResource(mResources, resId, mImageWidth,
                mImageHeight, getImageCache());
    }

    private Bitmap decodeSampledBitmapFromResource(Resources res,
                                                   int resId, int reqWidth, int reqHeight, ImageCache cache) {
// First decode with inJustDecodeBounds=true to check dimensions

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // If we're running on Honeycomb or newer, try to use inBitmap
        if (SdkVersionUtils.hasHoneycomb()) {
            addInBitmapOptions(options, cache);
        }

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);

    }

    /**
     * 缩放比率
     * @param options
     * @param reqWidth   用户期待图片宽
     * @param reqHeight 用户期待图片高
     * @return
     */
    public static int calculateInSampleSize(BitmapFactory.Options options,
                                            int reqWidth, int reqHeight) {
        // 原始图像的高度和宽度
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }

            // This offers some additional logic in case the image has a strange
            // aspect ratio. For example, a panorama may have a much larger
            // width than height. In these cases the total pixels might still
            // end up being too large to fit comfortably in memory, so we should
            // be more aggressive with sample down the image (=larger inSampleSize).
            long totalPixels = width * height / inSampleSize;
            // Anything more than 2x the requested pixels we'll sample down further
            final long totalReqPixelsCap = reqHeight * reqWidth * 2;
            while (totalPixels > totalReqPixelsCap) {
                inSampleSize *= 2;
                totalPixels /= 2;
            }
        }
        return inSampleSize;
    }

    private static void addInBitmapOptions(BitmapFactory.Options options, ImageCache cache) {

        // inBitmap only works with mutable bitmaps(可变的位图) so force the decoder to
        // return mutable bitmaps.
        options.inMutable = true;
        if (cache != null) {
            // Try and find a bitmap to use for inBitmap
            Bitmap inBitmap = cache.getBitmapFromReusableSet(options);
            if (inBitmap != null) {
                options.inBitmap = inBitmap;
            }
        }
    }
}
