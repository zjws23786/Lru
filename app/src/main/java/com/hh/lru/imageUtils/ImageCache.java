package com.hh.lru.imageUtils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Environment;
import android.os.StatFs;
import android.support.v4.BuildConfig;
import android.support.v4.util.LruCache;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Created by Administrator on 2018/4/17 0017.
 */

public class ImageCache {
    private static final String TAG = "ImageCache";
    //默认缓存大小
    private static final int DEFAULT_MEM_CACHE_SIZE = 1024 * 5; // 5KB
    //默认磁盘缓存大小为字节
    private static final int DEFAULT_DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB

    //将图像写入磁盘缓存时的压缩设置
    private static final Bitmap.CompressFormat DEFAULT_COMPRESS_FORMAT = Bitmap.CompressFormat.JPEG;
    //默认的压缩质量比例
    private static final int DEFAULT_COMPRESS_QUALITY = 70;
    //磁盘缓存索引
    private static final int DISK_CACHE_INDEX = 0;

    //可以轻松切换各种缓存的常量
    //默认的MEM缓存
    private static final boolean DEFAULT_MEM_CACHE_ENABLED = true;
    //默认磁盘缓存
    private static final boolean DEFAULT_DISK_CACHE_ENABLED = true;
    //创建默认init磁盘缓存
    private static final boolean DEFAULT_INIT_DISK_CACHE_ON_CREATE = false;

    private DiskLruCache mDiskLruCache;
    private LruCache<String,BitmapDrawable> mMemoryCache;
    private ImageCacheParams mCacheParams;
    private final Object mDiskCacheLock = new Object();
    //默认磁盘缓存
    private boolean mDiskCacheStarting = true;

    //软引用集合
    private Set<SoftReference<Bitmap>> mReusableBitmaps;

    private volatile static ImageCache imageCache;

    public static ImageCache getInstance(ImageCacheParams cacheParams) {
        if (imageCache == null) {
            synchronized (ImageCache.class) {
                if (imageCache == null) {
                    imageCache = new ImageCache(cacheParams);
                }
            }
        }
        return imageCache;
    }

    private ImageCache(ImageCacheParams cacheParams){
        init(cacheParams);
    }

    private void init(ImageCacheParams cacheParams) {
        mCacheParams = cacheParams;
        if (mCacheParams.memoryCacheEnabled){
            if (BuildConfig.DEBUG){
                Log.d(TAG, "Memory cache created (size = " + mCacheParams.memCacheSize + ")");
            }
        }

        if (SdkVersionUtils.hasHoneycomb()){
            mReusableBitmaps  = Collections.synchronizedSet(new HashSet<SoftReference<Bitmap>>());
        }

        mMemoryCache = new LruCache<String, BitmapDrawable>(mCacheParams.memCacheSize) {
            @Override
            protected void entryRemoved(boolean evicted, String key, BitmapDrawable oldValue, BitmapDrawable newValue) {
                if (RecyclingBitmapDrawable.class.isInstance(oldValue)){
                    ((RecyclingBitmapDrawable) oldValue).setIsCached(false);
                }else{
                    if (SdkVersionUtils.hasHoneycomb()){
                        mReusableBitmaps.add(new SoftReference<Bitmap>(oldValue.getBitmap()));
                    }
                }
            }

            @Override
            protected int sizeOf(String key, BitmapDrawable value) {
                final int bitmapSize = getBitmapSize(value) / 1024;
                return bitmapSize == 0 ? 1 : bitmapSize;
            }
        };
    }

    public static int getBitmapSize(BitmapDrawable value) {
        Bitmap bitmap = value.getBitmap();

        if (SdkVersionUtils.hasKitKat()){
            return bitmap.getAllocationByteCount();
        }

        if (SdkVersionUtils.hasHoneycombMR1()){
            return bitmap.getByteCount();
        }

        return bitmap.getRowBytes() * bitmap.getHeight();
    }

    protected Bitmap getBitmapFromReusableSet(BitmapFactory.Options options) {
        Bitmap bitmap = null;
        if (mReusableBitmaps != null && !mReusableBitmaps.isEmpty()) {
            synchronized (mReusableBitmaps) {
                final Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps.iterator();
                Bitmap item;

                while (iterator.hasNext()) {
                    item = iterator.next().get();

                    if (null != item && item.isMutable()) {
                        // Check to see it the item can be used for inBitmap
                        if (canUseForInBitmap(item, options)) {
                            bitmap = item;

                            // Remove from reusable set so it can't be used again
                            iterator.remove();
                            break;
                        }
                    } else {
                        // Remove from the set if the reference has been cleared.
                        iterator.remove();
                    }
                }
            }
        }
        return bitmap;
    }

    public void addBitmapToCache(String data, BitmapDrawable value) {
        if (data == null || value == null) {
            return;
        }

        // Add to memory cache
        if (mMemoryCache != null) {
            if (RecyclingBitmapDrawable.class.isInstance(value)) {
                // The removed entry is a recycling drawable, so notify it
                // that it has been added into the memory cache
                ((RecyclingBitmapDrawable) value).setIsCached(true);
            }
            mMemoryCache.put(data, value);
        }

        synchronized (mDiskCacheLock) {
            //Add to disk cache
            if (mDiskLruCache != null) {
                final String key = hashKeyForDisk(data);
                OutputStream out = null;
                try {
                    DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
                    if (snapshot == null) {
                        final DiskLruCache.Editor editor = mDiskLruCache.edit(key);
                        if (editor != null) {
                            out = editor.newOutputStream(DISK_CACHE_INDEX);
                            value.getBitmap().compress(
                                    mCacheParams.compressFormat, mCacheParams.compressQuality, out);
                            editor.commit();
                            out.close();
                        }
                    } else {
                        snapshot.getInputStream(DISK_CACHE_INDEX).close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "addBitmapToCache - " + e);
                } finally {
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (IOException e) {
                    }
                }
            }
        }

    }

    public void initDiskCache() {
        // Set up disk cache
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
                File diskCacheDir = mCacheParams.diskCacheDir;
                if (mCacheParams.diskCacheEnabled && diskCacheDir != null) {
                    if (!diskCacheDir.exists()) {
                        diskCacheDir.mkdirs();
                    }
                    if (getUsableSpace(diskCacheDir) > mCacheParams.diskCacheSize) {
                        try {
                            mDiskLruCache = DiskLruCache.open(
                                    diskCacheDir, 1, 1, mCacheParams.diskCacheSize);
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Disk cache initialized");
                            }
                        } catch (final IOException e) {
                            mCacheParams.diskCacheDir = null;
                            Log.e(TAG, "initDiskCache - " + e);
                        }
                    }
                }
            }
            mDiskCacheStarting = false;
            mDiskCacheLock.notifyAll();
        }
    }

    public void clearCache() {
        if (mMemoryCache != null) {
            mMemoryCache.evictAll();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Memory cache cleared");
            }
        }

        synchronized (mDiskCacheLock) {
            mDiskCacheStarting = true;
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.delete();
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Disk cache cleared");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "clearCache - " + e);
                }
                mDiskLruCache = null;
                initDiskCache();
            }
        }
    }

    /**
     * Flushes the disk cache associated with this ImageCache object. Note that this includes
     * disk access so this should not be executed on the main/UI thread.
     */
    public void flush() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    mDiskLruCache.flush();
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Disk cache flushed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "flush - " + e);
                }
            }
        }
    }

    /**
     * Closes the disk cache associated with this ImageCache object. Note that this includes
     * disk access so this should not be executed on the main/UI thread.
     */
    public void close() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    if (!mDiskLruCache.isClosed()) {
                        mDiskLruCache.close();
                        mDiskLruCache = null;
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Disk cache closed");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "close - " + e);
                }
            }
        }
    }

    public static class ImageCacheParams{
        public int memCacheSize = DEFAULT_MEM_CACHE_SIZE;
        public int diskCacheSize = DEFAULT_DISK_CACHE_SIZE;
        public File diskCacheDir;
        public Bitmap.CompressFormat compressFormat = DEFAULT_COMPRESS_FORMAT;
        public int compressQuality = DEFAULT_COMPRESS_QUALITY;
        public boolean memoryCacheEnabled = DEFAULT_MEM_CACHE_ENABLED;
        public boolean diskCacheEnabled = DEFAULT_DISK_CACHE_ENABLED;
        public boolean initDiskCacheOnCreate = DEFAULT_INIT_DISK_CACHE_ON_CREATE;

        public ImageCacheParams(Context context, String diskCacheDirectoryName){
            diskCacheDir = getDiskCacheDir(context, diskCacheDirectoryName);
        }

        //设置内存缓存大小百分比
        public void setMemCacheSizePercent(float percent){
            if (percent < 0.01f || percent > 0.8f) {
                throw new IllegalArgumentException("setMemCacheSizePercent - percent must be "
                        + "between 0.01 and 0.8 (inclusive)");
            }
            memCacheSize = Math.round(percent * Runtime.getRuntime().maxMemory() / 1024);
        }
    }

    /**
     * 获取磁盘路径
     * @param context
     * @param uniqueName 目录名字
     * @return
     */
    public static File getDiskCacheDir(Context context, String uniqueName) {
        final String cachePath = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !isExternalStorageRemovable()
                ? context.getExternalCacheDir().getPath()
                : context.getCacheDir().getPath();
        return new File(cachePath + File.separator + uniqueName);
    }

    public static long getUsableSpace(File path) {
        if (SdkVersionUtils.hasGingerbread()){
            return path.getUsableSpace();
        }

        final StatFs stats = new StatFs(path.getPath());
        return (long)stats.getBlockSize() * (long) stats.getAvailableBlocks();
    }

    //外部存储(SD卡)是否被移除
    public static boolean isExternalStorageRemovable() {
        return Environment.isExternalStorageRemovable();
    }

    //得到外部存储的存储目录
    public static File getExternalCacheDir(Context context) {
        return context.getExternalCacheDir();
    }

    public BitmapDrawable getBitmapFromMemCache(String data) {
        BitmapDrawable memValue = null;
        if (mMemoryCache != null){
            memValue = mMemoryCache.get(data);
        }

        if (BuildConfig.DEBUG && memValue != null) {
            Log.d(TAG, "Memory cache hit");
        }
        return memValue;
    }

    public Bitmap getBitmapFromDiskCache(String data){
        final String key = hashKeyForDisk(data);
        Bitmap bitmap = null;
        synchronized (mDiskCacheLock){
            while (mDiskCacheStarting){
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if (mDiskLruCache != null){
                InputStream inputStream = null;
                try{
                    final DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
                    if (snapshot != null){
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Disk cache hit");
                        }
                        inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
                        if (inputStream != null){
                            FileDescriptor fd = ((FileInputStream) inputStream).getFD();

                            bitmap = ImageResizer.decodeSampledBitmapFromDescriptor(
                                    fd, Integer.MAX_VALUE, Integer.MAX_VALUE, this);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }finally {
                    try {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                    } catch (Exception e) {

                    }
                }
            }
        }
        return bitmap;
    }

    /**
     * 用hash将url转换成对应的key值
     * @param key
     * @return
     */
    public static String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            //消息摘要，用MD5算法【常用算法有MD5、SHA、CRC】
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            //更新摘要内容
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }

    /**
     * 将字节数组 转换成 十六进制 的字符串
     * @param bytes 摘要内容
     * @return
     */
    private static String bytesToHexString(byte[] bytes) {
        // http://stackoverflow.com/questions/332079
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private static boolean canUseForInBitmap(Bitmap candidate,
                                             BitmapFactory.Options targetOptions) {
        if (SdkVersionUtils.hasKitKat()){
            return candidate.getWidth() == targetOptions.outWidth
                    && candidate.getHeight() == targetOptions.outHeight
                    && targetOptions.inSampleSize == 1;
        }

        // From Android 4.4 (KitKat) onward we can re-use if the byte size of the new bitmap
        // is smaller than the reusable bitmap candidate allocation byte count.
        int width = targetOptions.outWidth / targetOptions.inSampleSize;
        int height = targetOptions.outHeight / targetOptions.inSampleSize;
        int byteCount = width * height * getBytesPerPixel(candidate.getConfig());
        return byteCount <= candidate.getAllocationByteCount();
    }

    private static int getBytesPerPixel(Bitmap.Config config) {
        if (config == Bitmap.Config.ARGB_8888) {
            return 4;
        } else if (config == Bitmap.Config.RGB_565) {
            return 2;
        } else if (config == Bitmap.Config.ARGB_4444) {
            return 2;
        } else if (config == Bitmap.Config.ALPHA_8) {
            return 1;
        }
        return 1;
    }
}
