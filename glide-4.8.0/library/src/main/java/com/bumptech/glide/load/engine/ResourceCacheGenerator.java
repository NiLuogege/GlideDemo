package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import java.io.File;
import java.util.List;

/**
 * Generates {@link com.bumptech.glide.load.data.DataFetcher DataFetchers} from cache files
 * containing downsampled/transformed resource data.
 */
class ResourceCacheGenerator implements DataFetcherGenerator,
    DataFetcher.DataCallback<Object> {

  private final FetcherReadyCallback cb;
  private final DecodeHelper<?> helper;

  private int sourceIdIndex;
  private int resourceClassIndex = -1;
  private Key sourceKey;
  private List<ModelLoader<File, ?>> modelLoaders;
  private int modelLoaderIndex;
  private volatile LoadData<?> loadData;
  // PMD is wrong here, this File must be an instance variable because it may be used across
  // multiple calls to startNext.
  @SuppressWarnings("PMD.SingularField")
  private File cacheFile;
  private ResourceCacheKey currentKey;

  ResourceCacheGenerator(DecodeHelper<?> helper, FetcherReadyCallback cb) {
    this.helper = helper;
    this.cb = cb;
  }

  // See TODO below.
  @SuppressWarnings("PMD.CollapsibleIfStatements")
  @Override
  public boolean startNext() {
    //他妈的这个方法 做了太多事情了，主要是去注册表中找能处理当前任务的 ModelLoader,然后封装为 LoadData （可能是多个） ，
    // 并分会他们的 sourceKey 集合 ，对于 加载网络图片来说 这个 sourceIds 其实是 List<GlideUrl> ，而且应该只有一个
    List<Key> sourceIds = helper.getCacheKeys();
    if (sourceIds.isEmpty()) {
      return false;
    }
    List<Class<?>> resourceClasses = helper.getRegisteredResourceClasses();
    if (resourceClasses.isEmpty()) {
      if (File.class.equals(helper.getTranscodeClass())) {
        return false;
      }
      // TODO(b/73882030): This case gets triggered when it shouldn't. With this assertion it causes
      // all loads to fail. Without this assertion it causes loads to miss the disk cache
      // unnecessarily
      // throw new IllegalStateException(
      //    "Failed to find any load path from " + helper.getModelClass() + " to "
      //        + helper.getTranscodeClass());
    }
    while (modelLoaders == null || !hasNextModelLoader()) {
      resourceClassIndex++;
      if (resourceClassIndex >= resourceClasses.size()) {
        sourceIdIndex++;
        if (sourceIdIndex >= sourceIds.size()) {
          return false;
        }
        resourceClassIndex = 0;
      }

      //加载网络图片的话 sourceId 为一个 GlideUrl
      Key sourceId = sourceIds.get(sourceIdIndex);
      Class<?> resourceClass = resourceClasses.get(resourceClassIndex);
      Transformation<?> transformation = helper.getTransformation(resourceClass);
      // PMD.AvoidInstantiatingObjectsInLoops Each iteration is comparatively expensive anyway,
      // we only run until the first one succeeds, the loop runs for only a limited
      // number of iterations on the order of 10-20 in the worst case.
      //获取经过变化的图片的key
      currentKey =
          new ResourceCacheKey(// NOPMD AvoidInstantiatingObjectsInLoops
              helper.getArrayPool(),
              sourceId,
              helper.getSignature(),
              helper.getWidth(),
              helper.getHeight(),
              transformation,
              resourceClass,
              helper.getOptions());
      //从磁盘中获取变化过的缓存文件 ，而 DataCacheGenerator 中获取的是原始文件，是没有经过变换的
      cacheFile = helper.getDiskCache().get(currentKey);
      if (cacheFile != null) {
        sourceKey = sourceId;
        modelLoaders = helper.getModelLoaders(cacheFile);
        modelLoaderIndex = 0;
      }
    }

    //一般不会走到这个下面
    loadData = null;
    boolean started = false;
    while (!started && hasNextModelLoader()) {
      ModelLoader<File, ?> modelLoader = modelLoaders.get(modelLoaderIndex++);
      loadData = modelLoader.buildLoadData(cacheFile,
          helper.getWidth(), helper.getHeight(), helper.getOptions());
      if (loadData != null && helper.hasLoadPath(loadData.fetcher.getDataClass())) {
        started = true;
        loadData.fetcher.loadData(helper.getPriority(), this);
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
    cb.onDataFetcherReady(sourceKey, data, loadData.fetcher, DataSource.RESOURCE_DISK_CACHE,
        currentKey);
  }

  @Override
  public void onLoadFailed(@NonNull Exception e) {
    cb.onDataFetcherFailed(currentKey, e, loadData.fetcher, DataSource.RESOURCE_DISK_CACHE);
  }
}
