/*
 * Copyright (c) 2013. Bump Technologies Inc. All Rights Reserved.
 */

package com.bumptech.glide.load.engine.cache;

import android.util.Log;
import com.bumptech.glide.disklrucache.DiskLruCache;
import com.bumptech.glide.disklrucache.DiskLruCache.Value;
import com.bumptech.glide.load.Key;
import java.io.File;
import java.io.IOException;

/**
 * The default DiskCache implementation. There must be no more than one active instance for a given
 * directory at a time.
 *
 * @see #get(java.io.File, long)
 *
 * 添加到该缓存会有两种情况
 *  - 当原始资源加载完毕后（比如网络图片下载解码完毕后）会进行缓存，具体代码位置在 SourceGenerator.cacheData .其实原始图片只会在加载网络图片的时候生效
 *  - 当资源变换以后 会进行缓存 ，具体代码位置在 DecodeJob.DeferredEncodeManager.encode ,
 *      其实缓存变换后的图片的只会发生在加载本地文件（资源文件 or sd卡文件）的时候 因为在 DiskCacheStrategy.AUTOMATIC.isResourceCacheable 中明确指定了
 *      dataSource == DataSource.LOCAL 而且   encodeStrategy == EncodeStrategy.TRANSFORMED 也就是说 需要是本地文件（资源文件 or sd卡文件）而且是经过变换的
 *也就是网路图片只会缓存源文件， 本底图片只会缓存转换后的文件 ，为什么网络图片不缓存转换后的文件呢？ 应该是考虑到磁盘容量的问题吧，因为LruCache的容量是固定的，如果存储了
 * 大量转换后的图片那就增加了未能命中网络图片的概率，降低了加载速度
 * 移除缓存的话只有一种情况
 *  - 缓存容量不够的时候
 */
public class DiskLruCacheWrapper implements DiskCache {
  private static final String TAG = "DiskLruCacheWrapper";

  private static final int APP_VERSION = 1;
  private static final int VALUE_COUNT = 1;
  private static DiskLruCacheWrapper wrapper;

  private final SafeKeyGenerator safeKeyGenerator;
  private final File directory;
  private final long maxSize;
  private final DiskCacheWriteLocker writeLocker = new DiskCacheWriteLocker();
  private DiskLruCache diskLruCache;

  /**
   * Get a DiskCache in the given directory and size. If a disk cache has already been created with
   * a different directory and/or size, it will be returned instead and the new arguments will be
   * ignored.
   *
   * @param directory The directory for the disk cache
   * @param maxSize   The max size for the disk cache
   * @return The new disk cache with the given arguments, or the current cache if one already exists
   *
   * @deprecated Use {@link #create(File, long)} to create a new cache with the specified arguments.
   */
  @SuppressWarnings("deprecation")
  @Deprecated
  public static synchronized DiskCache get(File directory, long maxSize) {
    // TODO calling twice with different arguments makes it return the cache for the same
    // directory, it's public!
    if (wrapper == null) {
      wrapper = new DiskLruCacheWrapper(directory, maxSize);
    }
    return wrapper;
  }

  /**
   * Create a new DiskCache in the given directory with a specified max size.
   *
   * @param directory The directory for the disk cache
   * @param maxSize   The max size for the disk cache
   * @return The new disk cache with the given arguments
   */
  @SuppressWarnings("deprecation")
  public static DiskCache create(File directory, long maxSize) {
    return new DiskLruCacheWrapper(directory, maxSize);
  }

  /**
   * @deprecated Do not extend this class.
   */
  @Deprecated
  // Deprecated public API.
  @SuppressWarnings({"WeakerAccess", "DeprecatedIsStillUsed"})
  protected DiskLruCacheWrapper(File directory, long maxSize) {
    this.directory = directory;
    this.maxSize = maxSize;
    this.safeKeyGenerator = new SafeKeyGenerator();
  }

  private synchronized DiskLruCache getDiskCache() throws IOException {
    if (diskLruCache == null) {
      diskLruCache = DiskLruCache.open(directory, APP_VERSION, VALUE_COUNT, maxSize);
    }
    return diskLruCache;
  }

  @Override
  public File get(Key key) {
    String safeKey = safeKeyGenerator.getSafeKey(key);
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      Log.v(TAG, "Get: Obtained: " + safeKey + " for for Key: " + key);
    }
    File result = null;
    try {
      // It is possible that the there will be a put in between these two gets. If so that shouldn't
      // be a problem because we will always put the same value at the same key so our input streams
      // will still represent the same data.
      final DiskLruCache.Value value = getDiskCache().get(safeKey);
      if (value != null) {
        result = value.getFile(0);
      }
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.WARN)) {
        Log.w(TAG, "Unable to get from disk cache", e);
      }
    }
    return result;
  }

  @Override
  public void put(Key key, Writer writer) {
    // We want to make sure that puts block so that data is available when put completes. We may
    // actually not write any data if we find that data is written by the time we acquire the lock.
    String safeKey = safeKeyGenerator.getSafeKey(key);
    writeLocker.acquire(safeKey);
    try {
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Put: Obtained: " + safeKey + " for for Key: " + key);
      }
      try {
        // We assume we only need to put once, so if data was written while we were trying to get
        // the lock, we can simply abort.
        DiskLruCache diskCache = getDiskCache();
        Value current = diskCache.get(safeKey);
        if (current != null) {
          return;
        }

        DiskLruCache.Editor editor = diskCache.edit(safeKey);
        if (editor == null) {
          throw new IllegalStateException("Had two simultaneous puts for: " + safeKey);
        }
        try {
          File file = editor.getFile(0);
          if (writer.write(file)) {
            editor.commit();
          }
        } finally {
          editor.abortUnlessCommitted();
        }
      } catch (IOException e) {
        if (Log.isLoggable(TAG, Log.WARN)) {
          Log.w(TAG, "Unable to put to disk cache", e);
        }
      }
    } finally {
      writeLocker.release(safeKey);
    }
  }

  @Override
  public void delete(Key key) {
    String safeKey = safeKeyGenerator.getSafeKey(key);
    try {
      getDiskCache().remove(safeKey);
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.WARN)) {
        Log.w(TAG, "Unable to delete from disk cache", e);
      }
    }
  }

  @Override
  public synchronized void clear() {
    try {
      getDiskCache().delete();
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.WARN)) {
        Log.w(TAG, "Unable to clear disk cache or disk cache cleared externally", e);
      }
    } finally {
      // Delete can close the cache but still throw. If we don't null out the disk cache here, every
      // subsequent request will try to act on a closed disk cache and fail. By nulling out the disk
      // cache we at least allow for attempts to open the cache in the future. See #2465.
      resetDiskCache();
    }
  }

  private synchronized void resetDiskCache() {
    diskLruCache = null;
  }
}
