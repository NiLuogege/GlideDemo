package com.bumptech.glide.load.engine;

import android.util.Log;
import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.DecodeJob.DiskCacheProvider;
import com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoader.LoadData;
import com.bumptech.glide.load.resource.UnitTransformation;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

final class DecodeHelper<Transcode> {

  private final List<LoadData<?>> loadData = new ArrayList<>();
  private final List<Key> cacheKeys = new ArrayList<>();

  private GlideContext glideContext;
  private Object model;
  private int width;
  private int height;
  private Class<?> resourceClass;
  private DecodeJob.DiskCacheProvider diskCacheProvider;
  private Options options;
  private Map<Class<?>, Transformation<?>> transformations;
  private Class<Transcode> transcodeClass;
  private boolean isLoadDataSet;
  private boolean isCacheKeysSet;
  private Key signature;
  private Priority priority;
  private DiskCacheStrategy diskCacheStrategy;
  private boolean isTransformationRequired;
  private boolean isScaleOnlyOrNoTransform;

  @SuppressWarnings("unchecked")
  <R> void init(
      GlideContext glideContext,
      Object model,//在加载网络图片的时候就是 String 类型的 url
      Key signature,// 这次请求的签名，会用于 计算图片唯一id（缓存图片路径），一般为 EmptySignature
      int width,// 图片最终宽
      int height,// 图片最终高
      DiskCacheStrategy diskCacheStrategy,//硬盘缓存策略 默认为 DiskCacheStrategy.AUTOMATIC
      Class<?> resourceClass,//目前不知道干什么用的，默认为 Object.class
      Class<R> transcodeClass,//asDrawable() 流程时  transcodeClass 为 Class<Drawable>
      Priority priority,//优先级
      Options options,//这次请求的配置
      Map<Class<?>, Transformation<?>> transformations,// 用于转换 ，一般是有值得
      boolean isTransformationRequired,//是否要进行转换，一般是 false
      boolean isScaleOnlyOrNoTransform,//一般为true
      DiskCacheProvider diskCacheProvider//硬盘缓存的封装 硬盘缓存策略默认为 InternalCacheDiskCacheFactory
  ) {
    this.glideContext = glideContext;
    this.model = model;
    this.signature = signature;
    this.width = width;
    this.height = height;
    this.diskCacheStrategy = diskCacheStrategy;
    this.resourceClass = resourceClass;
    this.diskCacheProvider = diskCacheProvider;
    this.transcodeClass = (Class<Transcode>) transcodeClass;
    this.priority = priority;
    this.options = options;
    this.transformations = transformations;
    this.isTransformationRequired = isTransformationRequired;
    this.isScaleOnlyOrNoTransform = isScaleOnlyOrNoTransform;

  }

  void clear() {
    glideContext = null;
    model = null;
    signature = null;
    resourceClass = null;
    transcodeClass = null;
    options = null;
    priority = null;
    transformations = null;
    diskCacheStrategy = null;

    loadData.clear();
    isLoadDataSet = false;
    cacheKeys.clear();
    isCacheKeysSet = false;
  }

  DiskCache getDiskCache() {
    return diskCacheProvider.getDiskCache();
  }

  DiskCacheStrategy getDiskCacheStrategy() {
    return diskCacheStrategy;
  }

  Priority getPriority() {
    return priority;
  }

  Options getOptions() {
    return options;
  }

  Key getSignature() {
    return signature;
  }

  int getWidth() {
    return width;
  }

  int getHeight() {
    return height;
  }

  ArrayPool getArrayPool() {
    return glideContext.getArrayPool();
  }

  Class<?> getTranscodeClass() {
    return transcodeClass;
  }

  Class<?> getModelClass() {
    return model.getClass();
  }

  List<Class<?>> getRegisteredResourceClasses() {
    return glideContext.getRegistry()
        .getRegisteredResourceClasses(model.getClass(), resourceClass, transcodeClass);
  }

  boolean hasLoadPath(Class<?> dataClass) {
    return getLoadPath(dataClass) != null;
  }

  <Data> LoadPath<Data, ?, Transcode> getLoadPath(Class<Data> dataClass) {
    return glideContext.getRegistry().getLoadPath(dataClass, resourceClass, transcodeClass);
  }

  boolean isScaleOnlyOrNoTransform() {
    return isScaleOnlyOrNoTransform;
  }

  @SuppressWarnings("unchecked")
  <Z> Transformation<Z> getTransformation(Class<Z> resourceClass) {
    Transformation<Z> result = (Transformation<Z>) transformations.get(resourceClass);
    if (result == null) {
      for (Entry<Class<?>, Transformation<?>> entry : transformations.entrySet()) {
        if (entry.getKey().isAssignableFrom(resourceClass)) {
          result = (Transformation<Z>) entry.getValue();
          break;
        }
      }
    }

    if (result == null) {
      if (transformations.isEmpty() && isTransformationRequired) {
        throw new IllegalArgumentException(
            "Missing transformation for " + resourceClass + ". If you wish to"
                + " ignore unknown resource types, use the optional transformation methods.");
      } else {
        return UnitTransformation.get();
      }
    }
    return result;
  }

  boolean isResourceEncoderAvailable(Resource<?> resource) {
    return glideContext.getRegistry().isResourceEncoderAvailable(resource);
  }

  <Z> ResourceEncoder<Z> getResultEncoder(Resource<Z> resource) {
    return glideContext.getRegistry().getResultEncoder(resource);
  }

  List<ModelLoader<File, ?>> getModelLoaders(File file)
      throws Registry.NoModelLoaderAvailableException {
    return glideContext.getRegistry().getModelLoaders(file);
  }

  boolean isSourceKey(Key key) {
    List<LoadData<?>> loadData = getLoadData();
    //noinspection ForLoopReplaceableByForEach to improve perf
    for (int i = 0, size = loadData.size(); i < size; i++) {
      LoadData<?> current = loadData.get(i);
      if (current.sourceKey.equals(key)) {
        return true;
      }
    }
    return false;
  }

  List<LoadData<?>> getLoadData() {
    //loadData 没有设置的话 就进行设置
    if (!isLoadDataSet) {
      isLoadDataSet = true;
      loadData.clear();
      //☆☆☆☆☆☆ 返回可以处理此 model的所有 ModelLoader 这里很重要欧 ☆☆☆☆☆☆
      // 在处理 string类型的http url 的时候就返回的是如下三个 ，真正处理的保存在 StringLoader.uriLoader 中
      // com.bumptech.glide.load.model.StringLoader@df6302c,
      // com.bumptech.glide.load.model.StringLoader@2881af5,
      // com.bumptech.glide.load.model.StringLoader@aa8508a]
      List<ModelLoader<Object, ?>> modelLoaders = glideContext.getRegistry().getModelLoaders(model);
      Log.e("DecodeHelper","modelLoaders="+modelLoaders);

      //noinspection ForLoopReplaceableByForEach to improve perf
      for (int i = 0, size = modelLoaders.size(); i < size; i++) {
        ModelLoader<Object, ?> modelLoader = modelLoaders.get(i);
        //调用每一个 ModelLoader 的 buildLoadData 方法
        //对于 string类型的http url 就是调用  StringLoader 的 buildLoadData
        // 这里最终回调用到 HttpGlideUrlLoader 的 buildLoadData 方法 会返回一个 LoadData
        LoadData<?> current =
            modelLoader.buildLoadData(model, width, height, options);
        if (current != null) {
          //加入到 loadData 中
          loadData.add(current);
        }
      }
    }
    return loadData;
  }

  List<Key> getCacheKeys() {
    //cacheKeys 没有设置的话 就进行设置
    if (!isCacheKeysSet) {
      isCacheKeysSet = true;
      cacheKeys.clear();
      List<LoadData<?>> loadData = getLoadData();
      //noinspection ForLoopReplaceableByForEach to improve perf
      for (int i = 0, size = loadData.size(); i < size; i++) {
        LoadData<?> data = loadData.get(i);
        if (!cacheKeys.contains(data.sourceKey)) {
          cacheKeys.add(data.sourceKey);
        }
        //一般为 data.alternateKeys 为空数组
        for (int j = 0; j < data.alternateKeys.size(); j++) {
          if (!cacheKeys.contains(data.alternateKeys.get(j))) {
            cacheKeys.add(data.alternateKeys.get(j));
          }
        }
      }
    }
    return cacheKeys;
  }

  <X> Encoder<X> getSourceEncoder(X data) throws Registry.NoSourceEncoderAvailableException {
    return glideContext.getRegistry().getSourceEncoder(data);
  }
}
