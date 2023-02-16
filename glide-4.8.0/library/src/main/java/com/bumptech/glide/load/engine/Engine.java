package com.bumptech.glide.load.engine;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.Pools;
import android.util.Log;
import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import com.bumptech.glide.util.pool.FactoryPools;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for starting loads and managing active and cached resources.
 */
public class Engine implements EngineJobListener,
    MemoryCache.ResourceRemovedListener,
    EngineResource.ResourceListener {
  private static final String TAG = "Engine";
  private static final int JOB_POOL_SIZE = 150;
  private static final boolean VERBOSE_IS_LOGGABLE = Log.isLoggable(TAG, Log.VERBOSE);
  private final Jobs jobs;//默认为 Jobs 类
  private final EngineKeyFactory keyFactory;
  //内存缓存 默认为 LruResourceCache
  private final MemoryCache cache;
  //用于创建 EngineJob 的工厂
  private final EngineJobFactory engineJobFactory;
  private final ResourceRecycler resourceRecycler;
  //硬盘缓存的封装 硬盘缓存策略默认为 InternalCacheDiskCacheFactory
  private final LazyDiskCacheProvider diskCacheProvider;
  //用于创建 DecodeJob 的工厂
  private final DecodeJobFactory decodeJobFactory;
  //最近使用的缓存
  private final ActiveResources activeResources;

  public Engine(
      MemoryCache memoryCache,
      DiskCache.Factory diskCacheFactory,
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      boolean isActiveResourceRetentionAllowed) {
    this(
        memoryCache,
        diskCacheFactory,
        diskCacheExecutor,
        sourceExecutor,
        sourceUnlimitedExecutor,
        animationExecutor,
        /*jobs=*/ null,
        /*keyFactory=*/ null,
        /*activeResources=*/ null,
        /*engineJobFactory=*/ null,
        /*decodeJobFactory=*/ null,
        /*resourceRecycler=*/ null,
        isActiveResourceRetentionAllowed);
  }

  @VisibleForTesting
  Engine(MemoryCache cache,
      DiskCache.Factory diskCacheFactory,
      GlideExecutor diskCacheExecutor,
      GlideExecutor sourceExecutor,
      GlideExecutor sourceUnlimitedExecutor,
      GlideExecutor animationExecutor,
      Jobs jobs,
      EngineKeyFactory keyFactory,
      ActiveResources activeResources,
      EngineJobFactory engineJobFactory,
      DecodeJobFactory decodeJobFactory,
      ResourceRecycler resourceRecycler,
      boolean isActiveResourceRetentionAllowed //是否开启 正在活动的图片的缓存
  ) {
    //内存缓存 默认为 LruResourceCache
    this.cache = cache;
    //硬盘缓存的封装 硬盘缓存策略默认为 InternalCacheDiskCacheFactory
    this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

    //初始化正在活动的图片缓存
    if (activeResources == null) {
      activeResources = new ActiveResources(isActiveResourceRetentionAllowed);
    }
    this.activeResources = activeResources;
    activeResources.setListener(this);

    //创建图片唯一标识的 工程类
    if (keyFactory == null) {
      keyFactory = new EngineKeyFactory();
    }
    this.keyFactory = keyFactory;

    if (jobs == null) {
      jobs = new Jobs();
    }
    this.jobs = jobs;

    //用于管理线程池的
    if (engineJobFactory == null) {
      engineJobFactory =
          new EngineJobFactory(
              diskCacheExecutor, sourceExecutor, sourceUnlimitedExecutor, animationExecutor, this);
    }
    this.engineJobFactory = engineJobFactory;

    //创建解码的 DecodeJobFactory
    if (decodeJobFactory == null) {
      decodeJobFactory = new DecodeJobFactory(diskCacheProvider);
    }
    this.decodeJobFactory = decodeJobFactory;

    //初始化用于回收资源的类
    if (resourceRecycler == null) {
      resourceRecycler = new ResourceRecycler();
    }
    this.resourceRecycler = resourceRecycler;

    //设置资源回收监听 为 自己，当有资源回收是会调用 onResourceRemoved 方法
    cache.setResourceRemovedListener(this);
  }

  /**
   * Starts a load for the given arguments.
   *
   * <p>Must be called on the main thread.
   *
   * <p>The flow for any request is as follows:
   * <ul>
   *   <li>Check the current set of actively used resources, return the active resource if
   *   present, and move any newly inactive resources into the memory cache.</li>
   *   <li>Check the memory cache and provide the cached resource if present.</li>
   *   <li>Check the current set of in progress loads and add the cb to the in progress load if
   *   one is present.</li>
   *   <li>Start a new load.</li>
   * </ul>
   *
   * <p>Active resources are those that have been provided to at least one request and have not yet
   * been released. Once all consumers of a resource have released that resource, the resource then
   * goes to cache. If the resource is ever returned to a new consumer from cache, it is re-added to
   * the active resources. If the resource is evicted from the cache, its resources are recycled and
   * re-used if possible and the resource is discarded. There is no strict requirement that
   * consumers release their resources so active resources are held weakly.
   *
   * @param width  The target width in pixels of the desired resource.
   * @param height The target height in pixels of the desired resource.
   * @param cb     The callback that will be called when the load completes.
   */
  public <R> LoadStatus load(
      GlideContext glideContext,
      Object model,//在加载网络图片的时候就是 String 类型的 url
      Key signature,// 这次请求的签名，会用于 计算图片唯一id（缓存图片路径），一般为 EmptySignature
      int width,// 图片最终宽
      int height,// 图片最终高
      Class<?> resourceClass,//目前不知道干什么用的，默认为 Object.class
      Class<R> transcodeClass,//asDrawable() 流程时  transcodeClass 为 Class<Drawable>
      Priority priority,//优先级
      DiskCacheStrategy diskCacheStrategy,//硬盘缓存策略 默认为 DiskCacheStrategy.AUTOMATIC
      Map<Class<?>, Transformation<?>> transformations,// 用于转换 ，一般是有值得
      boolean isTransformationRequired,//是否要进行转换，一般是 false
      boolean isScaleOnlyOrNoTransform,//一般为true
      Options options,//具体配置
      boolean isMemoryCacheable,//是否使用内存缓存，一般为 true
      boolean useUnlimitedSourceExecutorPool,//使用没有限制的线程池， 默认为false
      boolean useAnimationPool,//默认为false
      boolean onlyRetrieveFromCache,//只在内存中获取 ，默认为 false
      ResourceCallback cb //对象为 SingleRequest，，当资源加载好 or 失败会回调到 SingleRequest的  onResourceReady or onLoadFailed
  ) {

    Log.e(TAG,"transformations size = "+transformations.size()+ "transformations= "+transformations);

    Util.assertMainThread();
    long startTime = VERBOSE_IS_LOGGABLE ? LogTime.getLogTime() : 0;

    //根据 model ,signature ,width ,height 等来构建一个 key ,用于标识 这个图片的 唯一key
    EngineKey key = keyFactory.buildKey(model, signature, width, height, transformations,
        resourceClass, transcodeClass, options);

    //先从 最近使用的 内存图片中查找 ，可以说是第一级 内存缓存
    EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
    if (active != null) {//找到了 直接调用 onResourceReady
      cb.onResourceReady(active, DataSource.MEMORY_CACHE);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from active resources", startTime, key);
      }
      return null;
    }

    //从内存缓存中获取 这个是 LruResourceCache
    EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
    if (cached != null) {//找到了 直接调用 onResourceReady
      cb.onResourceReady(cached, DataSource.MEMORY_CACHE);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Loaded resource from cache", startTime, key);
      }
      return null;
    }

    //从 jobs 中查找这次请求，第一次肯定是没有的
    EngineJob<?> current = jobs.get(key, onlyRetrieveFromCache);
    if (current != null) {//找到了直接设置回调
      current.addCallback(cb);
      if (VERBOSE_IS_LOGGABLE) {
        logWithTimeAndKey("Added to existing load", startTime, key);
      }
      return new LoadStatus(cb, current);
    }

    //创建 并初始化 EngineJob ，这是用于加载的
    EngineJob<R> engineJob =
        engineJobFactory.build(
            key,
            isMemoryCacheable,
            useUnlimitedSourceExecutorPool,
            useAnimationPool,
            onlyRetrieveFromCache);

    //创建 DecodeJob
    DecodeJob<R> decodeJob =
        decodeJobFactory.build(
            glideContext,
            model,
            key,
            signature,
            width,
            height,
            resourceClass,
            transcodeClass,
            priority,
            diskCacheStrategy,
            transformations,
            isTransformationRequired,
            isScaleOnlyOrNoTransform,
            onlyRetrieveFromCache,
            options,
            engineJob);

    //将这个 engineJob 进行缓存
    jobs.put(key, engineJob);

    //设置 回调 为 SingleRequest ， 当 图片加载完 解码完 会回调到 SingleRequest的  onResourceReady or onLoadFailed
    engineJob.addCallback(cb);

    //开始加载图片啦
    engineJob.start(decodeJob);

    if (VERBOSE_IS_LOGGABLE) {
      logWithTimeAndKey("Started new load", startTime, key);
    }
    return new LoadStatus(cb, engineJob);
  }

  private static void logWithTimeAndKey(String log, long startTime, Key key) {
    Log.v(TAG, log + " in " + LogTime.getElapsedMillis(startTime) + "ms, key: " + key);
  }

  @Nullable
  private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
    if (!isMemoryCacheable) {
      return null;
    }
    EngineResource<?> active = activeResources.get(key);
    if (active != null) {
      active.acquire();
    }

    return active;
  }

  private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
    if (!isMemoryCacheable) {
      return null;
    }

    EngineResource<?> cached = getEngineResourceFromCache(key);
    if (cached != null) {
      cached.acquire();
      activeResources.activate(key, cached);
    }
    return cached;
  }

  private EngineResource<?> getEngineResourceFromCache(Key key) {
    Resource<?> cached = cache.remove(key);

    final EngineResource<?> result;
    if (cached == null) {
      result = null;
    } else if (cached instanceof EngineResource) {
      // Save an object allocation if we've cached an EngineResource (the typical case).
      result = (EngineResource<?>) cached;
    } else {
      result = new EngineResource<>(cached, true /*isMemoryCacheable*/, true /*isRecyclable*/);
    }
    return result;
  }

  public void release(Resource<?> resource) {
    Util.assertMainThread();
    if (resource instanceof EngineResource) {
      ((EngineResource<?>) resource).release();
    } else {
      throw new IllegalArgumentException("Cannot release anything but an EngineResource");
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public void onEngineJobComplete(
      EngineJob<?> engineJob,
      Key key,//这次请求的 key 是一个 EngineKey
      EngineResource<?> resource//EngineResource 里面包含了 真正的图片位
  ) {
    Util.assertMainThread();
    // A null resource indicates that the load failed, usually due to an exception.
    if (resource != null) {
      //有添加监听为Engine 类
      resource.setResourceListener(key, this);

      //一般是true
      if (resource.isCacheable()) {
        //加入到 activeEngineResources 这个最近使用资源的 缓存中 是一个 HashMap 弱引用了 resource
        activeResources.activate(key, resource);
      }
    }
    //移除缓存的 engineJob
    jobs.removeIfCurrent(key, engineJob);
  }

  @Override
  public void onEngineJobCancelled(EngineJob<?> engineJob, Key key) {
    Util.assertMainThread();

    jobs.removeIfCurrent(key, engineJob);
  }

  @Override
  public void onResourceRemoved(@NonNull final Resource<?> resource) {
    Util.assertMainThread();
    resourceRecycler.recycle(resource);
  }

  @Override
  public void onResourceReleased(Key cacheKey, EngineResource<?> resource) {
    Util.assertMainThread();
    activeResources.deactivate(cacheKey);
    if (resource.isCacheable()) {
      cache.put(cacheKey, resource);
    } else {
      resourceRecycler.recycle(resource);
    }
  }

  public void clearDiskCache() {
    diskCacheProvider.getDiskCache().clear();
  }

  @VisibleForTesting
  public void shutdown() {
    engineJobFactory.shutdown();
    diskCacheProvider.clearDiskCacheIfCreated();
    activeResources.shutdown();
  }

  /**
   * Allows a request to indicate it no longer is interested in a given load.
   */
  public static class LoadStatus {
    private final EngineJob<?> engineJob;
    private final ResourceCallback cb;

    LoadStatus(ResourceCallback cb, EngineJob<?> engineJob) {
      this.cb = cb;
      this.engineJob = engineJob;
    }

    public void cancel() {
      engineJob.removeCallback(cb);
    }
  }

  private static class LazyDiskCacheProvider implements DecodeJob.DiskCacheProvider {

    private final DiskCache.Factory factory;
    private volatile DiskCache diskCache;

    LazyDiskCacheProvider(DiskCache.Factory factory) {
      this.factory = factory;
    }

    @VisibleForTesting
    synchronized void clearDiskCacheIfCreated() {
      if (diskCache == null) {
        return;
      }
      diskCache.clear();
    }

    @Override
    public DiskCache getDiskCache() {
      if (diskCache == null) {
        synchronized (this) {
          if (diskCache == null) {
            diskCache = factory.build();
          }
          if (diskCache == null) {
            diskCache = new DiskCacheAdapter();
          }
        }
      }
      return diskCache;
    }
  }

  @VisibleForTesting
  static class DecodeJobFactory {
    @Synthetic final DecodeJob.DiskCacheProvider diskCacheProvider;
    @Synthetic final Pools.Pool<DecodeJob<?>> pool =
        FactoryPools.simple(JOB_POOL_SIZE,
            new FactoryPools.Factory<DecodeJob<?>>() {
          @Override
          public DecodeJob<?> create() {
            return new DecodeJob<>(diskCacheProvider, pool);
          }
        });
    private int creationOrder;

    DecodeJobFactory(DecodeJob.DiskCacheProvider diskCacheProvider) {
      this.diskCacheProvider = diskCacheProvider;
    }

    @SuppressWarnings("unchecked")
    <R> DecodeJob<R> build(GlideContext glideContext,
        Object model,//在加载网络图片的时候就是 String 类型的 url
        EngineKey loadKey,//这里加载的key
        Key signature,// 这次请求的签名，会用于 计算图片唯一id（缓存图片路径），一般为 EmptySignature
        int width,// 图片最终宽
        int height,// 图片最终高
        Class<?> resourceClass,//目前不知道干什么用的，默认为 Object.class
        Class<R> transcodeClass,//asDrawable() 流程时  transcodeClass 为 Class<Drawable>
        Priority priority,//优先级
        DiskCacheStrategy diskCacheStrategy,//硬盘缓存策略 默认为 DiskCacheStrategy.AUTOMATIC
        Map<Class<?>, Transformation<?>> transformations,// 用于转换 ，一般是有值得
        boolean isTransformationRequired,//是否要进行转换，一般是 false
        boolean isScaleOnlyOrNoTransform,//一般为true
        boolean onlyRetrieveFromCache,//只在内存中获取 ，默认为 false
        Options options,//这次请求的配置
        DecodeJob.Callback<R> callback //解码的回调，是 EngineJob 对象，当图片解码完毕后会 调用 onResourceReady ， onLoadFailed 等方法
    ) {
      //从池子里获取一个DecodeJob ，没有就创建
      DecodeJob<R> result = Preconditions.checkNotNull((DecodeJob<R>) pool.acquire());
      //初始化
      return result.init(
          glideContext,
          model,
          loadKey,
          signature,
          width,
          height,
          resourceClass,
          transcodeClass,
          priority,
          diskCacheStrategy,
          transformations,
          isTransformationRequired,
          isScaleOnlyOrNoTransform,
          onlyRetrieveFromCache,
          options,
          callback,
          creationOrder++);
    }
  }

  @VisibleForTesting
  static class EngineJobFactory {
    @Synthetic final GlideExecutor diskCacheExecutor;
    @Synthetic final GlideExecutor sourceExecutor;
    @Synthetic final GlideExecutor sourceUnlimitedExecutor;
    @Synthetic final GlideExecutor animationExecutor;
    @Synthetic final EngineJobListener listener;// 为Engine类
    //一个 EngineJob 的池子
    @Synthetic final Pools.Pool<EngineJob<?>> pool =
        FactoryPools.simple(
            JOB_POOL_SIZE,
            new FactoryPools.Factory<EngineJob<?>>() {
              @Override
              public EngineJob<?> create() {
                return new EngineJob<>(
                    diskCacheExecutor,
                    sourceExecutor,
                    sourceUnlimitedExecutor,
                    animationExecutor,
                    listener,
                    pool);
              }
            });

    EngineJobFactory(
        GlideExecutor diskCacheExecutor,
        GlideExecutor sourceExecutor,
        GlideExecutor sourceUnlimitedExecutor,
        GlideExecutor animationExecutor,
        EngineJobListener listener // 为Engine类
    ) {
      this.diskCacheExecutor = diskCacheExecutor;
      this.sourceExecutor = sourceExecutor;
      this.sourceUnlimitedExecutor = sourceUnlimitedExecutor;
      this.animationExecutor = animationExecutor;
      this.listener = listener;
    }

    @VisibleForTesting
    void shutdown() {
      shutdownAndAwaitTermination(diskCacheExecutor);
      shutdownAndAwaitTermination(sourceExecutor);
      shutdownAndAwaitTermination(sourceUnlimitedExecutor);
      shutdownAndAwaitTermination(animationExecutor);
    }

    @SuppressWarnings("unchecked")
    <R> EngineJob<R> build(
        Key key,//这次请求的 key 是一个 EngineKey
        boolean isMemoryCacheable,//是否使用内存缓存，一般为 true
        boolean useUnlimitedSourceGeneratorPool,//使用没有限制的线程池， 默认为false
        boolean useAnimationPool,//默认为false
        boolean onlyRetrieveFromCache//只在内存中获取 ，默认为 false
    ) {
      //从池子中获取一个 EngineJob，池子中没有的话会 创建一个新的
      EngineJob<R> result = Preconditions.checkNotNull((EngineJob<R>) pool.acquire());
      //初始化
      return result.init(
          key,
          isMemoryCacheable,
          useUnlimitedSourceGeneratorPool,
          useAnimationPool,
          onlyRetrieveFromCache);
    }

    private static void shutdownAndAwaitTermination(ExecutorService pool) {
      long shutdownSeconds = 5;
      pool.shutdown();
      try {
        if (!pool.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
          pool.shutdownNow();
          if (!pool.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
            throw new RuntimeException("Failed to shutdown");
          }
        }
      } catch (InterruptedException ie) {
        throw new RuntimeException(ie);
      }
    }
  }
}
