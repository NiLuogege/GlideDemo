package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.util.Log;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.util.LogTime;
import java.util.Collections;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from original source data
 * using registered {@link com.bumptech.glide.load.model.ModelLoader ModelLoaders} and the model
 * provided for the load.
 *
 * <p> Depending on the disk cache strategy, source data may first be written to disk and then
 * loaded from the cache file rather than returned directly. </p>
 */
class SourceGenerator implements DataFetcherGenerator,
    DataFetcher.DataCallback<Object>,
    DataFetcherGenerator.FetcherReadyCallback {
  private static final String TAG = "SourceGenerator";

  private final DecodeHelper<?> helper;
  private final FetcherReadyCallback cb;//回调实现类为 DecodeJob

  private int loadDataListIndex;
  private DataCacheGenerator sourceCacheGenerator;
  private Object dataToCache;//这是网络请求来的数据用于 缓存
  private volatile ModelLoader.LoadData<?> loadData;
  private DataCacheKey originalKey;

  SourceGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.helper = helper;
    this.cb = cb;
  }

  @Override
  public boolean startNext() {
    //如果dataToCache 不为空就会进行缓存 ,网络图片下载成功后会不为空
    if (dataToCache != null) {
      Object data = dataToCache;
      dataToCache = null;
      cacheData(data);
    }

    //当sourceCacheGenerator不为空会执行 sourceCacheGenerator.startNext(),网络图片下载成功后会不为空
    if (sourceCacheGenerator != null && sourceCacheGenerator.startNext()) {
      return true;
    }
    sourceCacheGenerator = null;

    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      loadData = helper.getLoadData().get(loadDataListIndex++);
      if (loadData != null
          && (helper.getDiskCacheStrategy().isDataCacheable(loadData.fetcher.getDataSource())
          || helper.hasLoadPath(loadData.fetcher.getDataClass()))) {
        started = true;
        //这里终于他妈的开始去网络请求加载图片了
        //对于 网络请求来说 loadData.fetcher 为 HttpUrlFetcher 所以会调用它的 loadData
        //因为回到设置的是自己 所以 当图片下载完成后（网络请求完成）会回调当前文件的 onDataReady
        loadData.fetcher.loadData(helper.getPriority(), this);
      }
    }
    return started;
  }

  private boolean hasNextModelLoader() {
    return loadDataListIndex < helper.getLoadData().size();
  }

  //缓存数据
  private void cacheData(Object dataToCache) {
    long startTime = LogTime.getLogTime();
    try {
      //获取编码器这里是 StreamEncoder
      Encoder<Object> encoder = helper.getSourceEncoder(dataToCache);
      DataCacheWriter<Object> writer =
          new DataCacheWriter<>(encoder, dataToCache, helper.getOptions());
      //创建原始key
      originalKey = new DataCacheKey(loadData.sourceKey, helper.getSignature());
      //将原始文件缓存到磁盘,最终会调用 DiskLruCacheWrapper.put 方法
      helper.getDiskCache().put(originalKey, writer);
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Finished encoding source to cache"
            + ", key: " + originalKey
            + ", data: " + dataToCache
            + ", encoder: " + encoder
            + ", duration: " + LogTime.getElapsedMillis(startTime));
      }
    } finally {
      //对于加载网络图片来说 loadData.fetcher HttpUrlFetcher 调用 cleanup清理工作
      loadData.fetcher.cleanup();
    }

    //创建 DataCacheGenerator
    sourceCacheGenerator =
        new DataCacheGenerator(Collections.singletonList(loadData.sourceKey), helper, this);
  }

  @Override
  public void cancel() {
    LoadData<?> local = loadData;
    if (local != null) {
      local.fetcher.cancel();
    }
  }

  /**
   * 当为加载网络图片时 图片下载完成后（在HttpUrlFetcher 中进行的）会回调到 这个方法 data类型为 InputStream
   */
  @Override
  public void onDataReady(Object data) {
    //获取磁盘缓存策略 默认为 DiskCacheStrategy.AUTOMATIC
    DiskCacheStrategy diskCacheStrategy = helper.getDiskCacheStrategy();
    //加载网络图片是 loadData.fetcher 为HttpUrlFetcher  loadData.fetcher.getDataSource() 为 DataSource.REMOTE
    //所以diskCacheStrategy.isDataCacheable 为true
    if (data != null && diskCacheStrategy.isDataCacheable(loadData.fetcher.getDataSource())) {
      //这里给 dataToCache 赋值
      dataToCache = data;
      // We might be being called back on someone else's thread. Before doing anything, we should
      // reschedule to get back onto Glide's thread.
      //调用 DecodeJob 的 reschedule
      cb.reschedule();
    } else {
      cb.onDataFetcherReady(loadData.sourceKey, data, loadData.fetcher,
          loadData.fetcher.getDataSource(), originalKey);
    }
  }

  @Override
  public void onLoadFailed(@NonNull Exception e) {
    cb.onDataFetcherFailed(originalKey, e, loadData.fetcher, loadData.fetcher.getDataSource());
  }

  @Override
  public void reschedule() {
    // We don't expect this to happen, although if we ever need it to we can delegate to our
    // callback.
    throw new UnsupportedOperationException();
  }

  // Called from source cache generator.
  @Override
  public void onDataFetcherReady(Key sourceKey, Object data, DataFetcher<?> fetcher,
      DataSource dataSource, Key attemptedKey) {
    // This data fetcher will be loading from a File and provide the wrong data source, so override
    // with the data source of the original fetcher
    ///对于 网络请求来说 loadData.fetcher 为 HttpUrlFetcher 所以 loadData.fetcher.getDataSource() 为 DataSource.REMOTE
    //cb 为 DecodeJob 所以会走 DecodeJob.onDataFetcherReady
    cb.onDataFetcherReady(sourceKey, data, fetcher, loadData.fetcher.getDataSource(), sourceKey);
  }

  @Override
  public void onDataFetcherFailed(Key sourceKey, Exception e, DataFetcher<?> fetcher,
      DataSource dataSource) {
    cb.onDataFetcherFailed(sourceKey, e, fetcher, loadData.fetcher.getDataSource());
  }
}
