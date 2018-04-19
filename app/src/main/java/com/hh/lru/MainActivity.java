package com.hh.lru;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import com.hh.lru.constant.Constant;
import com.hh.lru.imageUtils.ImageCache;
import com.hh.lru.imageUtils.ImageFetcher;
import com.hh.lru.utils.DpUtil;

public class MainActivity extends AppCompatActivity {
    private ImageView mImage;
    private ImageView mImage2;
    private ImageView mImage3;
    private ImageFetcher mImageFetcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mImage = (ImageView) findViewById(R.id.image);
        mImage2 = (ImageView) findViewById(R.id.image2);
        mImage3 = (ImageView) findViewById(R.id.image3);
        loadImage();
    }

    private void loadImage() {
        //设置图片缓存相关参数
        ImageCache.ImageCacheParams cacheParams = new ImageCache.ImageCacheParams(this, ImageFetcher.IMAGE_CACHE_DIR);
        //将内存缓存设置为应用程序内存的25%
        cacheParams.setMemCacheSizePercent(0.25f);// Set memory cache to 25% of app memory

        // The ImageFetcher takes care of loading images into our ImageView children asynchronously
        mImageFetcher = new ImageFetcher(this, (int) DpUtil.dp2px(this, 100));
        mImageFetcher.setLoadingImage(R.mipmap.img_default_bg);
        mImageFetcher.addImageCache(cacheParams);

        mImageFetcher.loadImage(Constant.imageUrls[0],mImage);
        mImageFetcher.loadImage(Constant.imageUrls[1],mImage2);
        mImageFetcher.loadImage(Constant.imageUrls[2],mImage3);
    }

    @Override
    protected void onPause() {
        mImageFetcher.flushCache(); //写入缓存
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mImageFetcher.closeCache();
        super.onDestroy();
    }
}
