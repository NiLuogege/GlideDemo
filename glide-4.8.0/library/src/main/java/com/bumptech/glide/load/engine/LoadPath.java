package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.support.v4.util.Pools.Pool;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.util.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * For a given {@link com.bumptech.glide.load.data.DataFetcher} for a given data class, attempts to
 * fetch the data and then run it through one or more
 * {@link com.bumptech.glide.load.engine.DecodePath}s.
 *
 * @param <Data>         The type of data that will be fetched.
 * @param <ResourceType> The type of intermediate resource that will be decoded within one of the
 *                       {@link com.bumptech.glide.load.engine.DecodePath}s.
 * @param <Transcode>    The type of resource that will be returned as the result if the load and
 *                       one of the decode paths succeeds.
 */
public class LoadPath<Data, ResourceType, Transcode> {
  private final Class<Data> dataClass;
  private final Pool<List<Throwable>> listPool;
  private final List<? extends DecodePath<Data, ResourceType, Transcode>> decodePaths;
  private final String failureMessage;

  public LoadPath(Class<Data> dataClass, Class<ResourceType> resourceClass,
      Class<Transcode> transcodeClass,
      List<DecodePath<Data, ResourceType, Transcode>> decodePaths, Pool<List<Throwable>> listPool) {
    this.dataClass = dataClass;
    this.listPool = listPool;
    this.decodePaths = Preconditions.checkNotEmpty(decodePaths);
    failureMessage = "Failed LoadPath{" + dataClass.getSimpleName() + "->"
        + resourceClass.getSimpleName() + "->" + transcodeClass.getSimpleName() + "}";
  }

  public Resource<Transcode> load(DataRewinder<Data> rewinder, @NonNull Options options, int width,
      int height, DecodePath.DecodeCallback<ResourceType> decodeCallback//为 DecodeJob 中创建的 DecodeCallback
  ) throws GlideException {
    List<Throwable> throwables = Preconditions.checkNotNull(listPool.acquire());
    try {
      //会返回一个 LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      return loadWithExceptionList(rewinder, options, width, height, decodeCallback, throwables);
    } finally {
      //释放
      listPool.release(throwables);
    }
  }

  private Resource<Transcode> loadWithExceptionList(DataRewinder<Data> rewinder,
      @NonNull Options options,
      int width, int height, DecodePath.DecodeCallback<ResourceType> decodeCallback,//为 DecodeJob 中创建的 DecodeCallback
      List<Throwable> exceptions) throws GlideException {
    Resource<Transcode> result = null;
    //noinspection ForLoopReplaceableByForEach to improve perf
    //这里会循环调用 decodePaths 中每一项的  decode 方法
    //对于加载网络图片来说 decodePaths=[
    //	DecodePath{ dataClass=class java.nio.DirectByteBuffer, decoders=[com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder@54fb243], transcoder=com.bumptech.glide.load.resource.transcode.UnitTranscoder@e1180c0},
    //	DecodePath{ dataClass=class java.nio.DirectByteBuffer, decoders=[com.bumptech.glide.load.resource.bitmap.ByteBufferBitmapDecoder@72e81f9], transcoder=com.bumptech.glide.load.resource.transcode.BitmapDrawableTranscoder@9fd653e},
    //	DecodePath{ dataClass=class java.nio.DirectByteBuffer, decoders=[com.bumptech.glide.load.resource.bitmap.BitmapDrawableDecoder@1b12f9f], transcoder=com.bumptech.glide.load.resource.transcode.UnitTranscoder@e1180c0}]}
    for (int i = 0, size = decodePaths.size(); i < size; i++) {
      DecodePath<Data, ResourceType, Transcode> path = decodePaths.get(i);
      try {
        // 会返回一个 LazyBitmapDrawableResource 对象，这个流程中会解码出一个符合要求尺寸（ImageView大小或者指定大小）的Bitmap ，然后进行变换 会生成一个新的 Bitmap
        result = path.decode(rewinder, width, height, options, decodeCallback);
      } catch (GlideException e) {
        exceptions.add(e);
      }
      if (result != null) {
        break;
      }
    }

    if (result == null) {
      throw new GlideException(failureMessage, new ArrayList<>(exceptions));
    }

    return result;
  }

  public Class<Data> getDataClass() {
    return dataClass;
  }

  @Override
  public String toString() {
    return "LoadPath{" + "decodePaths=" + Arrays.toString(decodePaths.toArray()) + '}';
  }
}
