package com.bumptech.glide.load.engine.cache;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.util.LruCache;

/**
 * An LRU in memory cache for {@link com.bumptech.glide.load.engine.Resource}s.
 *
 * 添加到该缓存只有一种情况
 *  - 当ActiveResources 中对应的资源被回收的时候（也就是说对应的资源不可见了） 会添加到这个缓存中
 *
 * 移除缓存的话只有一种情况
 *  - 当LruCache 容量不够的时候
 *
 * 这个缓存可以说是 第二级内存缓存，缓存的是之前用过但是现在没用的资源
 */
public class LruResourceCache extends LruCache<Key, Resource<?>> implements MemoryCache {
  private ResourceRemovedListener listener;

  /**
   * Constructor for LruResourceCache.
   *
   * @param size The maximum size in bytes the in memory cache can use.
   */
  public LruResourceCache(long size) {
    super(size);
  }

  @Override
  public void setResourceRemovedListener(@NonNull ResourceRemovedListener listener) {
    this.listener = listener;
  }

  @Override
  protected void onItemEvicted(@NonNull Key key, @Nullable Resource<?> item) {
    if (listener != null && item != null) {
      listener.onResourceRemoved(item);
    }
  }

  @Override
  protected int getSize(@Nullable Resource<?> item) {
    if (item == null) {
      return super.getSize(null);
    } else {
      return item.getSize();
    }
  }

  @SuppressLint("InlinedApi")
  @Override
  public void trimMemory(int level) {
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
      // Entering list of cached background apps
      // Evict our entire bitmap cache
      clearMemory();
    } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
      // The app's UI is no longer visible, or app is in the foreground but system is running
      // critically low on memory
      // Evict oldest half of our bitmap cache
      trimToSize(getMaxSize() / 2);
    }
  }
}
