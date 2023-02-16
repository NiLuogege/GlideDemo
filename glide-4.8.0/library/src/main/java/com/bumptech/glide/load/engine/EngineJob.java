package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.Pools;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import com.bumptech.glide.util.pool.FactoryPools.Poolable;
import com.bumptech.glide.util.pool.StateVerifier;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that manages a load by adding and removing callbacks for for the load and notifying
 * callbacks when the load completes.
 */
class EngineJob<R> implements DecodeJob.Callback<R>,
    Poolable {
  private static final EngineResourceFactory DEFAULT_FACTORY = new EngineResourceFactory();
  private static final Handler MAIN_THREAD_HANDLER =
      new Handler(Looper.getMainLooper(), new MainThreadCallback());

  private static final int MSG_COMPLETE = 1;
  private static final int MSG_EXCEPTION = 2;
  // Used when we realize we're cancelled on a background thread in reschedule and can recycle
  // immediately rather than waiting for a result or an error.
  private static final int MSG_CANCELLED = 3;

  private final List<ResourceCallback> cbs = new ArrayList<>(2);
  private final StateVerifier stateVerifier = StateVerifier.newInstance();
  private final Pools.Pool<EngineJob<?>> pool;
  private final EngineResourceFactory engineResourceFactory;//默认为 当前类的 DEFAULT_FACTORY 对象
  private final EngineJobListener listener;
  private final GlideExecutor diskCacheExecutor;
  private final GlideExecutor sourceExecutor;
  private final GlideExecutor sourceUnlimitedExecutor;
  private final GlideExecutor animationExecutor;

  private Key key;//这次请求的 key 是一个 EngineKey
  private boolean isCacheable;//是否使用内存缓存，一般为 true
  private boolean useUnlimitedSourceGeneratorPool;//使用没有限制的线程池， 默认为false
  private boolean useAnimationPool;//默认为false
  private boolean onlyRetrieveFromCache;//只在内存中获取 ，默认为 false
  private Resource<?> resource; //在加载网络图片流程中，为LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
  private DataSource dataSource;//对于加载网络图片来说 为DataSource.REMOTE
  private boolean hasResource;
  private GlideException exception;
  private boolean hasLoadFailed;
  // A put of callbacks that are removed while we're notifying other callbacks of a change in
  // status.
  private List<ResourceCallback> ignoredCallbacks;//被忽略的回调
  private EngineResource<?> engineResource;
  private DecodeJob<R> decodeJob;

  // Checked primarily on the main thread, but also on other threads in reschedule.
  private volatile boolean isCancelled;

  EngineJob(
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      EngineJobListener listener,// 为Engine类
      Pools.Pool<EngineJob<?>> pool) {
    this(
        diskCacheExecutor,
        sourceExecutor,
        sourceUnlimitedExecutor,
        animationExecutor,
        listener,
        pool,
        DEFAULT_FACTORY);
  }

  @VisibleForTesting
  EngineJob(
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      EngineJobListener listener,// 为Engine类
      Pools.Pool<EngineJob<?>> pool,
      EngineResourceFactory engineResourceFactory) {
    this.diskCacheExecutor = diskCacheExecutor;
    this.sourceExecutor = sourceExecutor;
    this.sourceUnlimitedExecutor = sourceUnlimitedExecutor;
    this.animationExecutor = animationExecutor;
    this.listener = listener;
    this.pool = pool;
    this.engineResourceFactory = engineResourceFactory;
  }

  @VisibleForTesting
  EngineJob<R> init(
      Key key,//这次请求的 key 是一个 EngineKey
      boolean isCacheable,//是否使用内存缓存，一般为 true
      boolean useUnlimitedSourceGeneratorPool,//使用没有限制的线程池， 默认为false
      boolean useAnimationPool,//默认为false
      boolean onlyRetrieveFromCache//只在内存中获取 ，默认为 false
  ) {
    this.key = key;
    this.isCacheable = isCacheable;
    this.useUnlimitedSourceGeneratorPool = useUnlimitedSourceGeneratorPool;
    this.useAnimationPool = useAnimationPool;
    this.onlyRetrieveFromCache = onlyRetrieveFromCache;
    return this;
  }

  public void start(DecodeJob<R> decodeJob) {
    //记录用于解码的 job
    this.decodeJob = decodeJob;
    //是否可以直接从 硬盘中的缓存解码？ 可以的话直接 使用 diskCacheExecutor 这个线程池
    //不行的话使用其他线程池，默认配置的话是 sourceExecutor 这个线程池
    GlideExecutor executor = decodeJob.willDecodeFromCache()
        ? diskCacheExecutor
        : getActiveSourceExecutor();
    //开始执行  decodeJob ,会调用到 DecodeJob 的run方法
    executor.execute(decodeJob);
  }

  /**
   * 添加一个回调
   * 在加载网络图片的时候 会添加一个 SingleRequest 到里面
   */
  void addCallback(ResourceCallback cb) {
    Util.assertMainThread();
    stateVerifier.throwIfRecycled();
    if (hasResource) {
      //如果资源已经准备好了直接 调用 onResourceReady
      cb.onResourceReady(engineResource, dataSource);
    } else if (hasLoadFailed) {
      cb.onLoadFailed(exception);
    } else {
      //加入到集合中
      cbs.add(cb);
    }
  }

  void removeCallback(ResourceCallback cb) {
    Util.assertMainThread();
    stateVerifier.throwIfRecycled();
    if (hasResource || hasLoadFailed) {
      addIgnoredCallback(cb);
    } else {
      cbs.remove(cb);
      if (cbs.isEmpty()) {
        cancel();
      }
    }
  }

  boolean onlyRetrieveFromCache() {
    return onlyRetrieveFromCache;
  }

  private GlideExecutor getActiveSourceExecutor() {
    return useUnlimitedSourceGeneratorPool
        ? sourceUnlimitedExecutor : (useAnimationPool ? animationExecutor : sourceExecutor);
  }

  // We cannot remove callbacks while notifying our list of callbacks directly because doing so
  // would cause a ConcurrentModificationException. However, we need to obey the cancellation
  // request such that if notifying a callback early in the callbacks list cancels a callback later
  // in the request list, the cancellation for the later request is still obeyed. Using a put of
  // ignored callbacks allows us to avoid the exception while still meeting the requirement.
  private void addIgnoredCallback(ResourceCallback cb) {
    if (ignoredCallbacks == null) {
      ignoredCallbacks = new ArrayList<>(2);
    }
    if (!ignoredCallbacks.contains(cb)) {
      ignoredCallbacks.add(cb);
    }
  }

  private boolean isInIgnoredCallbacks(ResourceCallback cb) {
    return ignoredCallbacks != null && ignoredCallbacks.contains(cb);
  }

  // Exposed for testing.
  void cancel() {
    if (hasLoadFailed || hasResource || isCancelled) {
      return;
    }

    isCancelled = true;
    decodeJob.cancel();
    // TODO: Consider trying to remove jobs that have never been run before from executor queues.
    // Removing jobs that have run before can break things. See #1996.
    listener.onEngineJobCancelled(this, key);
  }

  // Exposed for testing.
  boolean isCancelled() {
    return isCancelled;
  }

  @Synthetic
  void handleResultOnMainThread() {
    stateVerifier.throwIfRecycled();
    if (isCancelled) {
      resource.recycle();
      release(false /*isRemovedFromQueue*/);
      return;
    } else if (cbs.isEmpty()) {
      throw new IllegalStateException("Received a resource without any callbacks to notify");
    } else if (hasResource) {
      throw new IllegalStateException("Already have resource");
    }
    //会创建一个 EngineResource 然后将 resource 和 isCacheable 记录起来
    engineResource = engineResourceFactory.build(resource, isCacheable);
    hasResource = true;

    // Hold on to resource for duration of request so we don't recycle it in the middle of
    // notifying if it synchronously released by one of the callbacks.
    //增加engineResource 的使用数量
    engineResource.acquire();

    //listener 为 Engine 类 ,调用到 Engine.onEngineJobComplete
    listener.onEngineJobComplete(this, key, engineResource);

    //noinspection ForLoopReplaceableByForEach to improve perf
    //对于 加载网络图片 cbs 中只有一个元素 那就是 SingleRequest
    for (int i = 0, size = cbs.size(); i < size; i++) {
      ResourceCallback cb = cbs.get(i);
      //一般不会被忽略，所以会进入这个 if
      if (!isInIgnoredCallbacks(cb)) {
        //调用者+1
        engineResource.acquire();
        //回到到 SingleRequest的 onResourceReady
        cb.onResourceReady(engineResource, dataSource);
      }
    }
    // Our request is complete, so we can release the resource.
    //释放资源啦，调用到 EngineResource 的 release
    engineResource.release();

    //释放无用资源啦
    release(false /*isRemovedFromQueue*/);
  }

  @Synthetic
  void handleCancelledOnMainThread() {
    stateVerifier.throwIfRecycled();
    if (!isCancelled) {
      throw new IllegalStateException("Not cancelled");
    }
    listener.onEngineJobCancelled(this, key);
    release(false /*isRemovedFromQueue*/);
  }

  private void release(boolean isRemovedFromQueue//为false
  ) {
    Util.assertMainThread();
    cbs.clear();
    key = null;
    engineResource = null;
    resource = null;
    if (ignoredCallbacks != null) {
      ignoredCallbacks.clear();
    }
    hasLoadFailed = false;
    isCancelled = false;
    hasResource = false;
    decodeJob.release(isRemovedFromQueue);
    decodeJob = null;
    exception = null;
    dataSource = null;
    pool.release(this);
  }

  @Override
  public void onResourceReady(
      Resource<R> resource, //在加载网络图片流程中，为LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      DataSource dataSource//对于加载网络图片来说 为DataSource.REMOTE
  ) {
    this.resource = resource;
    this.dataSource = dataSource;
    //这里会切换到主线程 去handleMessage方法中看
    MAIN_THREAD_HANDLER.obtainMessage(MSG_COMPLETE, this).sendToTarget();
  }

  @Override
  public void onLoadFailed(GlideException e) {
    this.exception = e;
    MAIN_THREAD_HANDLER.obtainMessage(MSG_EXCEPTION, this).sendToTarget();
  }

  @Override
  public void reschedule(DecodeJob<?> job) {
    // Even if the job is cancelled here, it still needs to be scheduled so that it can clean itself
    // up.
    //开始进行解码 会再次执行 DecodeJob 的 run方法
    getActiveSourceExecutor().execute(job);
  }

  @Synthetic
  void handleExceptionOnMainThread() {
    stateVerifier.throwIfRecycled();
    if (isCancelled) {
      release(false /*isRemovedFromQueue*/);
      return;
    } else if (cbs.isEmpty()) {
      throw new IllegalStateException("Received an exception without any callbacks to notify");
    } else if (hasLoadFailed) {
      throw new IllegalStateException("Already failed once");
    }
    hasLoadFailed = true;

    listener.onEngineJobComplete(this, key, null);

    for (ResourceCallback cb : cbs) {
      if (!isInIgnoredCallbacks(cb)) {
        cb.onLoadFailed(exception);
      }
    }

    release(false /*isRemovedFromQueue*/);
  }

  @NonNull
  @Override
  public StateVerifier getVerifier() {
    return stateVerifier;
  }

  @VisibleForTesting
  static class EngineResourceFactory {
    public <R> EngineResource<R> build(
        Resource<R> resource, //在加载网络图片流程中，为LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
        boolean isMemoryCacheable//内存缓存是否可用，默认为true
    ) {
      return new EngineResource<>(resource, isMemoryCacheable, /*isRecyclable=*/ true);
    }
  }

  private static class MainThreadCallback implements Handler.Callback {

    @Synthetic
    @SuppressWarnings("WeakerAccess")
    MainThreadCallback() { }

    @Override
    public boolean handleMessage(Message message) {
      EngineJob<?> job = (EngineJob<?>) message.obj;
      switch (message.what) {
        case MSG_COMPLETE://资源解码成功,已将解码为Bitmap了
          //在主线程汇总操作
          job.handleResultOnMainThread();
          break;
        case MSG_EXCEPTION:
          job.handleExceptionOnMainThread();
          break;
        case MSG_CANCELLED:
          job.handleCancelledOnMainThread();
          break;
        default:
          throw new IllegalStateException("Unrecognized message: " + message.what);
      }
      return true;
    }
  }
}
