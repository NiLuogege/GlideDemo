package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.util.Log;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import java.io.File;
import java.util.List;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from cache files
 * containing original unmodified source data.
 */
class DataCacheGenerator implements DataFetcherGenerator,
    DataFetcher.DataCallback<Object> {

  private final List<Key> cacheKeys;
  private final DecodeHelper<?> helper;
  //加载网络图片的话 为 SourceGenerator 类
  private final FetcherReadyCallback cb;

  private int sourceIdIndex = -1;
  private Key sourceKey;
  private List<ModelLoader<File, ?>> modelLoaders;
  private int modelLoaderIndex;
  private volatile LoadData<?> loadData;
  // PMD is wrong here, this File must be an instance variable because it may be used across
  // multiple calls to startNext.
  @SuppressWarnings("PMD.SingularField")
  private File cacheFile;

  /**
   *
   * @param helper
   * @param cb 为 DecodeJob 类
   */
  DataCacheGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this(helper.getCacheKeys(), helper, cb);
  }

  // In some cases we may want to load a specific cache key (when loading from source written to
  // cache), so we accept a list of keys rather than just obtain the list from the helper.
  //加载网络图片的话 会走这个 构造方法 cb 为 SourceGenerator
  DataCacheGenerator(List<Key> cacheKeys, DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.cacheKeys = cacheKeys;
    this.helper = helper;
    this.cb = cb;
  }

  @Override
  public boolean startNext() {
    while (modelLoaders == null || !hasNextModelLoader()) {
      sourceIdIndex++;
      if (sourceIdIndex >= cacheKeys.size()) {
        return false;
      }

      //对于获取网络图片来说这里是 GlideUrl
      Key sourceId = cacheKeys.get(sourceIdIndex);
      // PMD.AvoidInstantiatingObjectsInLoops The loop iterates a limited number of times
      // and the actions it performs are much more expensive than a single allocation.
      //获取到原始 key 这里就只包含 GlideUrl 和 Signature ，所以这里获取的是原始图片，是没有经过变化的
      //而 ResourceCacheGenerator 中获取的是 经过变化的图片
      @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
      Key originalKey = new DataCacheKey(sourceId, helper.getSignature());
      //从磁盘缓存中获取 cacheFile
      cacheFile = helper.getDiskCache().get(originalKey);
      //当原始图片缓存到磁盘后会 不为null
      if (cacheFile != null) {
        this.sourceKey = sourceId;
        //获取到可以处理file 输入数据类型的 ModelLoader
        //可以处理 file 类型的 modelLoaders=[
        // com.bumptech.glide.load.model.ByteBufferFileLoader@611bcaf,
        // com.bumptech.glide.load.model.FileLoader@b64d8bc,
        // com.bumptech.glide.load.model.FileLoader@161c045,
        // com.bumptech.glide.load.model.UnitModelLoader@992139a]
        modelLoaders = helper.getModelLoaders(cacheFile);
        Log.e("DataCacheGenerator","可以处理 file 类型的 modelLoaders="+modelLoaders);
        modelLoaderIndex = 0;
      }
    }

    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      //这里还是调用可以处理File类型的 ModelLoader 的 buildLoadData
      ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
      loadData =
          modelLoader.buildLoadData(cacheFile, helper.getWidth(), helper.getHeight(),
              helper.getOptions());
      if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
        started = true;
        //调用 loadData.fetcher.loadData 方法  回调设置的是当前类，如果处理完毕会 会调到当前类的  onDataReady or onLoadFailed
        loadData.fetcher.loadData(helper.getPriority(), this);
        //对于加载网络图片来说 loadData.fetcher 为 ByteBufferFileLoader$ByteBufferFetcher
        Log.e("DataCacheGenerator","loadData.fetcher="+loadData.fetcher);
      }
    }
    return started;
  }

  private boolean hasNextModelLoader() {
    return modelLoaderIndex < modelLoaders.size();
  }

  @Override
  public void cancel() {
    LoadData<?> local = loadData;
    if (local != null) {
      local.fetcher.cancel();
    }
  }

  @Override
  public void onDataReady(Object data) {
    //加载网络图片的话 为 SourceGenerator 类,会调到 SourceGenerator 的 onDataFetcherReady
    cb.onDataFetcherReady(sourceKey, data, loadData.fetcher, DataSource.DATA_DISK_CACHE, sourceKey);
  }

  @Override
  public void onLoadFailed(@NonNull Exception e) {
    //cb 为 DecodeJob 类,会调到 DecodeJob 的 onDataFetcherFailed
    cb.onDataFetcherFailed(sourceKey, e, loadData.fetcher, DataSource.DATA_DISK_CACHE);
  }
}
