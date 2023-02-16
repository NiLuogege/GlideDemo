package com.bumptech.glide.load.engine;

import android.os.Looper;
import android.support.annotation.NonNull;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.util.Preconditions;

/**
 * A wrapper resource that allows reference counting a wrapped {@link
 * com.bumptech.glide.load.engine.Resource} interface.
 *
 * @param <Z> The type of data returned by the wrapped {@link Resource}.
 */
class EngineResource<Z> implements Resource<Z> {
  private final boolean isCacheable;//内存缓存是否可用，默认为true
  private final boolean isRecyclable;
  private ResourceListener listener;//为Engine 类
  private Key key;//这次请求的 key 是一个 EngineKey
  private int acquired;//用来标记当前资源 被使用的个数（相同的图片可以显示到多个ImageView上），一般只有在  acquired 为 0的时候才能去回收资源
  private boolean isRecycled;
  private final Resource<Z> resource;

  interface ResourceListener {
    void onResourceReleased(Key key, EngineResource<?> resource);
  }

  EngineResource(
      Resource<Z> toWrap,//在加载网络图片流程中，为 LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      boolean isCacheable, //内存缓存是否可用，默认为true
      boolean isRecyclable//一般为 true
  ) {
    resource = Preconditions.checkNotNull(toWrap);
    this.isCacheable = isCacheable;
    this.isRecyclable = isRecyclable;
  }

  void setResourceListener(Key key, ResourceListener listener) {
    this.key = key;
    this.listener = listener;
  }

  Resource<Z> getResource() {
    return resource;
  }

  boolean isCacheable() {
    return isCacheable;
  }

  @NonNull
  @Override
  public Class<Z> getResourceClass() {
    return resource.getResourceClass();
  }

  @NonNull
  @Override
  public Z get() {
    //resource 在加载网络图片流程中，为 LazyBitmapDrawableResource 所以调用 LazyBitmapDrawableResource.get()
    return resource.get();
  }

  @Override
  public int getSize() {
    return resource.getSize();
  }

  @Override
  public void recycle() {
    if (acquired > 0) {
      throw new IllegalStateException("Cannot recycle a resource while it is still acquired");
    }
    if (isRecycled) {
      throw new IllegalStateException("Cannot recycle a resource that has already been recycled");
    }
    isRecycled = true;
    if (isRecyclable) {
      resource.recycle();
    }
  }

  /**
   * Increments the number of consumers using the wrapped resource. Must be called on the main
   * thread.
   *
   * <p> This must be called with a number corresponding to the number of new consumers each time
   * new consumers begin using the wrapped resource. It is always safer to call acquire more often
   * than necessary. Generally external users should never call this method, the framework will take
   * care of this for you. </p>
   */
  void acquire() {
    if (isRecycled) {
      throw new IllegalStateException("Cannot acquire a recycled resource");
    }
    if (!Looper.getMainLooper().equals(Looper.myLooper())) {
      throw new IllegalThreadStateException("Must call acquire on the main thread");
    }
    ++acquired;
  }

  /**
   * Decrements the number of consumers using the wrapped resource. Must be called on the main
   * thread.
   *
   * <p>This must only be called when a consumer that called the {@link #acquire()} method is now
   * done with the resource. Generally external users should never call this method, the framework
   * will take care of this for you.
   */
  void release() {
    if (acquired <= 0) {
      throw new IllegalStateException("Cannot release a recycled or not yet acquired resource");
    }
    if (!Looper.getMainLooper().equals(Looper.myLooper())) {
      throw new IllegalThreadStateException("Must call release on the main thread");
    }
    //acquired 会减1 ，一般会进入这个if
    if (--acquired == 0) {
      //为Engine 类
      listener.onResourceReleased(key, this);
    }
  }

  @Override
  public String toString() {
    return "EngineResource{"
        + "isCacheable=" + isCacheable
        + ", listener=" + listener
        + ", key=" + key
        + ", acquired=" + acquired
        + ", isRecycled=" + isRecycled
        + ", resource=" + resource
        + '}';
  }
}
