package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.support.v4.util.Pools.Pool;
import android.util.Log;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.util.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Attempts to decode and transcode  resource type from a given data type.
 *
 * @param <DataType>     The type of data ResourceType that will be decoded from.
 * @param <ResourceType> The type of intermediate resource that will be decoded.
 * @param <Transcode>    The final type of resource that will be transcoded from ResourceType and
 *                       returned to the caller.
 */
public class DecodePath<DataType, ResourceType, Transcode> {
  private static final String TAG = "DecodePath";
  private final Class<DataType> dataClass;
  private final List<? extends ResourceDecoder<DataType, ResourceType>> decoders;
  private final ResourceTranscoder<ResourceType, Transcode> transcoder;
  private final Pool<List<Throwable>> listPool;
  private final String failureMessage;

  public DecodePath(
      Class<DataType> dataClass, //原始数据类型
      Class<ResourceType> resourceClass,// 需要的资源类型
      Class<Transcode> transcodeClass,//需要转换到的资源类型
      List<? extends ResourceDecoder<DataType, ResourceType>> decoders,// 需要用到的解码器
      ResourceTranscoder<ResourceType, Transcode> transcoder, //需要用到的转换器
      Pool<List<Throwable>> listPool
  ) {
    this.dataClass = dataClass;//对于加载网络图片来说 ByteBuffer
    this.decoders = decoders;//对于加载网络图片来说 ByteBufferBitmapDecoder
    this.transcoder = transcoder;//对于加载网络图片来说 BitmapDrawableTranscoder
    this.listPool = listPool;//对于加载网络图片来说 FactoryPools$FactoryPool
    failureMessage = "Failed DecodePath{" + dataClass.getSimpleName() + "->"
        + resourceClass.getSimpleName() + "->" + transcodeClass.getSimpleName() + "}";

//    Log.e(TAG,"dataClass="+dataClass+""
//        + " decoders="+decoders
//        + " transcoder="+transcoder
//        + " listPool="+listPool
//    );
  }

  public Resource<Transcode> decode(DataRewinder<DataType> rewinder, int width, int height,
      @NonNull Options options, DecodeCallback<ResourceType> callback//为 DecodeJob 中创建的 DecodeCallback
  ) throws GlideException {
    // 对于加载网络图片来说 返回一个具体类型为 BitmapResource ，里面包含了 一个 Bitmap
    Resource<ResourceType> decoded = decodeResource(rewinder, width, height, options);
    //callback 为 DecodeJob 中创建的 DecodeCallback, 所以会调用到 DecodeJob 的 onResourceDecoded 这里面会对 图片进行 转换 （CenterCrop ,黑白化 等）
    Resource<ResourceType> transformed = callback.onResourceDecoded(decoded);
    //对于加载网络图片来说 BitmapDrawableTranscoder 回到用他的 transcode 方法 ，会返回一个 LazyBitmapDrawableResource
    return transcoder.transcode(transformed, options);
  }

  @NonNull
  private Resource<ResourceType> decodeResource(DataRewinder<DataType> rewinder, int width,
      int height, @NonNull Options options) throws GlideException {
    List<Throwable> exceptions = Preconditions.checkNotNull(listPool.acquire());
    try {
      // 对于加载网络图片来说
      //返回一个具体类型为 BitmapResource ，里面包含了 一个 Bitmap
      return decodeResourceWithList(rewinder, width, height, options, exceptions);
    } finally {
      listPool.release(exceptions);
    }
  }

  @NonNull
  private Resource<ResourceType> decodeResourceWithList(DataRewinder<DataType> rewinder, int width,
      int height, @NonNull Options options, List<Throwable> exceptions) throws GlideException {
    Resource<ResourceType> result = null;
    //noinspection ForLoopReplaceableByForEach to improve perf
    //遍历 decoders 对于加载网络图片来说 decoders 为 [com.bumptech.glide.load.resource.bitmap.ByteBufferBitmapDecoder@72e81f9]
    for (int i = 0, size = decoders.size(); i < size; i++) {
      ResourceDecoder<DataType, ResourceType> decoder = decoders.get(i);
      try {
        DataType data = rewinder.rewindAndGet();
        //判断这个 decoder 是否可以用一次处理这个 任务
        if (decoder.handles(data, options)) {
          data = rewinder.rewindAndGet();
          //可以的话 就调用 decode 方法进行解码
          //对于 对于加载网络图片来说 decoder ByteBufferBitmapDecoder 会生成一个Bitmap 并封装为
          // BitmapResource 返回出来 所以  result的具体类型就是 BitmapResource
          result = decoder.decode(data, width, height, options);
          Log.e(TAG, "decoder= " + decoder);
        }
        // Some decoders throw unexpectedly. If they do, we shouldn't fail the entire load path, but
        // instead log and continue. See #2406 for an example.
      } catch (IOException | RuntimeException | OutOfMemoryError e) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
          Log.v(TAG, "Failed to decode data for " + decoder, e);
        }
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

  @Override
  public String toString() {
    return "DecodePath{" + " dataClass=" + dataClass + ", decoders=" + decoders + ", transcoder="
        + transcoder + '}';
  }

  interface DecodeCallback<ResourceType> {
    @NonNull
    Resource<ResourceType> onResourceDecoded(@NonNull Resource<ResourceType> resource);
  }
}
