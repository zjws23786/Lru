package com.hh.lru.imageUtils;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.support.compat.BuildConfig;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Created by Administrator on 2018/4/18 0018.
 */

public class ImageFetcher extends ImageResizer{
    private static final String TAG = "ImageFetcher";
    private static final int HTTP_CACHE_SIZE = 10 * 1024 * 1024; // 10MB
    public static final String IMAGE_CACHE_DIR = "thumbs";
    private static final int IO_BUFFER_SIZE = 8 * 1024;

    private DiskLruCache mDiskLruCache;
    private File mImageCacheDir;
    private boolean mDiskCacheStarting = true;
    private final Object mDiskCacheLock = new Object();
    private static final int DISK_CACHE_INDEX = 0;

    protected ImageFetcher(Context context, int imageWidth, int imageHeight) {
        super(context, imageWidth, imageHeight);
        init(context);
    }

    public ImageFetcher(Context context, int imageSize) {
        super(context, imageSize);
        init(context);
    }

    private void init(Context context) {
        checkConnection(context);
        mImageCacheDir = ImageCache.getDiskCacheDir(context, IMAGE_CACHE_DIR);
    }

    @Override
    protected void initDiskCacheInternal() {
        super.initDiskCacheInternal();
        initHttpDiskCache();
    }

    private void initHttpDiskCache() {
        if (!mImageCacheDir.exists()) {
            mImageCacheDir.mkdirs();
        }
        synchronized (mDiskCacheLock) {
            if (ImageCache.getUsableSpace(mImageCacheDir) > HTTP_CACHE_SIZE) {
                try {
                    mDiskLruCache = DiskLruCache.open(mImageCacheDir, 1, 1, HTTP_CACHE_SIZE);
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "HTTP cache initialized");
                    }
                } catch (IOException e) {
                    mDiskLruCache = null;
                }
            }
            mDiskCacheStarting = false;
            mDiskCacheLock.notifyAll();
        }
    }

    @Override
    protected void clearCacheInternal() {
        super.clearCacheInternal();
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.delete();
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "HTTP cache cleared");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "clearCacheInternal - " + e);
                }
                mDiskLruCache = null;
                mDiskCacheStarting = true;
                initHttpDiskCache();
            }
        }
    }

    @Override
    protected void flushCacheInternal() {
        super.flushCacheInternal();
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    mDiskLruCache.flush();
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "HTTP cache flushed");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "flush - " + e);
                }
            }
        }
    }

    @Override
    protected void closeCacheInternal() {
        super.closeCacheInternal();
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    if (!mDiskLruCache.isClosed()) {
                        mDiskLruCache.close();
                        mDiskLruCache = null;
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "HTTP cache closed");
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "closeCacheInternal - " + e);
                }
            }
        }
    }

    private void checkConnection(Context context) {
        final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        if (networkInfo == null || !networkInfo.isConnectedOrConnecting()) {
            Toast.makeText(context, "no_network_connection_toast", Toast.LENGTH_LONG).show();
            Log.e(TAG, "checkConnection - no connection found");
        }
    }

    /**
     * bitmap相关操作
     * @param data 网络图片url
     * @return
     */
    private Bitmap processBitmap(String data) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "processBitmap - " + data);
        }

        final String key = ImageCache.hashKeyForDisk(data);  //通过URL获取key值
        FileDescriptor fileDescriptor = null;
        FileInputStream fileInputStream = null;
        DiskLruCache.Snapshot snapshot;
        synchronized (mDiskCacheLock) {
            // Wait for disk cache to initialize
            while (mDiskCacheStarting) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                }
            }

            if (mDiskLruCache != null) {
                try {
                    //通过DiskLruCache的get方法 得到 Snapshot对象
                    snapshot = mDiskLruCache.get(key);
                    //snapshot不存在，则下载并保持到DiskLruCache
                    if (snapshot == null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "processBitmap, not found in http cache, downloading...");
                        }
                        DiskLruCache.Editor editor = mDiskLruCache.edit(key); //对DiskLruCache进行操作
                        if (editor != null) {
                            //下载图片
                            if (downloadUrlToStream(data,editor.newOutputStream(DISK_CACHE_INDEX))) {
                                editor.commit(); //提交
                            } else {
                                editor.abort(); //中断
                            }
                        }
                        snapshot = mDiskLruCache.get(key);
                    }
                    //存在则直接获取
                    if (snapshot != null) {
                        fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                        fileDescriptor = fileInputStream.getFD();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "processBitmap - " + e);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "processBitmap - " + e);
                } finally {
                    if (fileDescriptor == null && fileInputStream != null) {
                        try {
                            fileInputStream.close();
                        } catch (IOException e) {
                        }
                    }
                }
            }
        }
        Bitmap bitmap = null;
        if (fileDescriptor != null) {
            bitmap = decodeSampledBitmapFromDescriptor(fileDescriptor, mImageWidth,mImageHeight, getImageCache());
        }
        if (fileInputStream != null) {
            try {
                fileInputStream.close();
            } catch (IOException e) {
            }
        }
        return bitmap;
    }


    @Override
    protected Bitmap processBitmap(Object data) {
        return processBitmap(String.valueOf(data));
    }

    /**
     * 下载URL到流
     * @param urlString 网络图片地址
     * @param outputStream  写到的流中
     * @return
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        disableConnectionReuseIfNecessary();
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in downloadBitmap - " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
            }
        }
        return false;
    }

    public static void disableConnectionReuseIfNecessary() {
        // HTTP connection reuse which was buggy pre-froyo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }
}
