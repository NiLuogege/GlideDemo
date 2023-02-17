package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.EngineResource.ResourceListener;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 添加到该缓存会有两种情况
 * - 命中内存缓存(LruResourceCache)  会给 ActiveResources 中添加一份 ，但是 内存缓存(LruResourceCache)中的会被删除掉 代码位置是在 Engine.getEngineResourceFromCache
 * - 没有命中内存缓存(LruResourceCache) ，会在图片加载变换完成后  给 ActiveResources 中添加一份 代码位置是在 Engine.onEngineJobComplete
 * <p>
 * 移除缓存的话只有一种情况
 * -在 对应的 EngineResource 没有一个被引用者的时候（其实就是对应的资源被替换or销毁的时候）会被移除
 * <p>
 * <p>
 * 其实也就是说这个缓存中存储的是当前正在显示（使用）的资源
 */
final class ActiveResources {
  private static final int MSG_CLEAN_REF = 1;

  private final boolean isActiveResourceRetentionAllowed;
  private final Handler mainHandler = new Handler(Looper.getMainLooper(), new Callback() {
    @Override
    public boolean handleMessage(Message msg) {
      if (msg.what == MSG_CLEAN_REF) {
        cleanupActiveReference((ResourceWeakReference) msg.obj);
        return true;
      }
      return false;
    }
  });

  //最近使用资源远程，一个 key（EngineKey） 和 弱引用的资源 映射表
  @VisibleForTesting final Map<Key, ResourceWeakReference> activeEngineResources = new HashMap<>();

  private ResourceListener listener;

  /**
   * Lazily instantiate to avoid exceptions if Glide is initialized on a background thread.
   *
   * @see <a href="https://github.com/bumptech/glide/issues/295">#295</a>
   */
  @Nullable
  private ReferenceQueue<EngineResource<?>> resourceReferenceQueue;
  @Nullable
  private Thread cleanReferenceQueueThread;
  private volatile boolean isShutdown;
  @Nullable
  private volatile DequeuedResourceCallback cb;

  ActiveResources(boolean isActiveResourceRetentionAllowed) {
    this.isActiveResourceRetentionAllowed = isActiveResourceRetentionAllowed;
  }

  void setListener(ResourceListener listener) {
    this.listener = listener;
  }

  //添加到 activeEngineResources 缓存中
  void activate(Key key, EngineResource<?> resource) {
    //构建一个弱引用的 资源类
    ResourceWeakReference toPut =
        new ResourceWeakReference(
            key,
            resource,
            getReferenceQueue(),
            isActiveResourceRetentionAllowed);

    //加入到 最近使用资源的缓存中
    ResourceWeakReference removed = activeEngineResources.put(key, toPut);
    if (removed != null) {
      removed.reset();
    }
  }

  void deactivate(Key key) {
    ResourceWeakReference removed = activeEngineResources.remove(key);
    if (removed != null) {
      //清空资源
      removed.reset();
    }
  }

  @Nullable
  EngineResource<?> get(Key key) {
    ResourceWeakReference activeRef = activeEngineResources.get(key);
    if (activeRef == null) {
      return null;
    }

    EngineResource<?> active = activeRef.get();
    if (active == null) {
      cleanupActiveReference(activeRef);
    }
    return active;
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void cleanupActiveReference(@NonNull ResourceWeakReference ref) {
    Util.assertMainThread();
    activeEngineResources.remove(ref.key);

    if (!ref.isCacheable || ref.resource == null) {
      return;
    }
    EngineResource<?> newResource =
        new EngineResource<>(ref.resource, /*isCacheable=*/ true, /*isRecyclable=*/ false);
    newResource.setResourceListener(ref.key, listener);
    listener.onResourceReleased(ref.key, newResource);
  }

  private ReferenceQueue<EngineResource<?>> getReferenceQueue() {
    if (resourceReferenceQueue == null) {
      resourceReferenceQueue = new ReferenceQueue<>();
      cleanReferenceQueueThread = new Thread(new Runnable() {
        @SuppressWarnings("InfiniteLoopStatement")
        @Override
        public void run() {
          Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
          cleanReferenceQueue();
        }
      }, "glide-active-resources");
      cleanReferenceQueueThread.start();
    }
    return resourceReferenceQueue;
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  void cleanReferenceQueue() {
    while (!isShutdown) {
      try {
        ResourceWeakReference ref = (ResourceWeakReference) resourceReferenceQueue.remove();
        mainHandler.obtainMessage(MSG_CLEAN_REF, ref).sendToTarget();

        // This section for testing only.
        DequeuedResourceCallback current = cb;
        if (current != null) {
          current.onResourceDequeued();
        }
        // End for testing only.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @VisibleForTesting
  void setDequeuedResourceCallback(DequeuedResourceCallback cb) {
    this.cb = cb;
  }

  @VisibleForTesting
  interface DequeuedResourceCallback {
    void onResourceDequeued();
  }

  @VisibleForTesting
  void shutdown() {
    isShutdown = true;
    if (cleanReferenceQueueThread == null) {
      return;
    }

    cleanReferenceQueueThread.interrupt();
    try {
      cleanReferenceQueueThread.join(TimeUnit.SECONDS.toMillis(5));
      if (cleanReferenceQueueThread.isAlive()) {
        throw new RuntimeException("Failed to join in time");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * '
   * 弱引用封装 内存不足的时候 会被回收
   */
  @VisibleForTesting
  static final class ResourceWeakReference extends WeakReference<EngineResource<?>> {
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    final Key key;
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    final boolean isCacheable;

    @Nullable
    @SuppressWarnings("WeakerAccess")
    @Synthetic
    Resource<?> resource;

    @Synthetic
    @SuppressWarnings("WeakerAccess")
    ResourceWeakReference(
        @NonNull Key key,
        @NonNull EngineResource<?> referent,
        @NonNull ReferenceQueue<? super EngineResource<?>> queue,
        boolean isActiveResourceRetentionAllowed) {
      super(referent, queue);
      this.key = Preconditions.checkNotNull(key);
      this.resource =
          referent.isCacheable() && isActiveResourceRetentionAllowed
              ? Preconditions.checkNotNull(referent.getResource()) : null;
      isCacheable = referent.isCacheable();
    }

    void reset() {
      resource = null;
      clear();
    }
  }
}
