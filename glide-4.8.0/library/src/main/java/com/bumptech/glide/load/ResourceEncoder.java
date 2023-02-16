package com.bumptech.glide.load;

import android.support.annotation.NonNull;
import com.bumptech.glide.load.engine.Resource;

/**
 * An interface for writing data from a resource to some persistent data store (i.e. a local File
 * cache).
 *
 * @param <T> The type of the data contained by the resource.
 *
 * 用于将 T 类型写到缓存里 一般 T类型为 InputStream、ByteBuffer、Resource<T> 等
 */
public interface ResourceEncoder<T> extends Encoder<Resource<T>> {
  // specializing the generic arguments
  @NonNull
  EncodeStrategy getEncodeStrategy(@NonNull Options options);
}
