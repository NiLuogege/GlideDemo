package com.bumptech.glide.load.engine;

import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.util.Pools;
import android.util.Log;
import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.EncodeStrategy;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.pool.FactoryPools.Poolable;
import com.bumptech.glide.util.pool.GlideTrace;
import com.bumptech.glide.util.pool.StateVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A class responsible for decoding resources either from cached data or from the original source
 * and applying transformations and transcodes.
 *
 * <p>Note: this class has a natural ordering that is inconsistent with equals.
 *
 * @param <R> The type of resource that will be transcoded from the decoded and transformed
 *            resource.
 */
class DecodeJob<R> implements DataFetcherGenerator.FetcherReadyCallback,
    Runnable,
    Comparable<DecodeJob<?>>,
    Poolable {
  private static final String TAG = "DecodeJob";

  private final DecodeHelper<R> decodeHelper = new DecodeHelper<>();
  private final List<Throwable> throwables = new ArrayList<>();
  private final StateVerifier stateVerifier = StateVerifier.newInstance();
  //硬盘缓存的封装 硬盘缓存策略默认为 InternalCacheDiskCacheFactory
  private final DiskCacheProvider diskCacheProvider;
  private final Pools.Pool<DecodeJob<?>> pool;
  private final DeferredEncodeManager<?> deferredEncodeManager = new DeferredEncodeManager<>();
  private final ReleaseManager releaseManager = new ReleaseManager();

  private GlideContext glideContext;
  private Key signature;
  private Priority priority;
  private EngineKey loadKey;
  private int width;
  private int height;
  private DiskCacheStrategy diskCacheStrategy;//硬盘缓存策略 默认为 DiskCacheStrategy.AUTOMATIC
  private Options options;
  private Callback<R> callback;//解码的回调，是 EngineJob 对象，当图片解码完毕后会 调用 onResourceReady ， onLoadFailed 等方法
  private int order;
  private Stage stage;
  //默认状态为 RunReason.INITIALIZE
  private RunReason runReason;
  private long startFetchTime;
  private boolean onlyRetrieveFromCache;
  private Object model;

  private Thread currentThread;
  private Key currentSourceKey;
  private Key currentAttemptingKey;
  private Object currentData;//加载好的数据
  private DataSource currentDataSource;
  private DataFetcher<?> currentFetcher;

  private volatile DataFetcherGenerator currentGenerator;
  private volatile boolean isCallbackNotified;
  private volatile boolean isCancelled;

  DecodeJob(DiskCacheProvider diskCacheProvider, Pools.Pool<DecodeJob<?>> pool) {
    this.diskCacheProvider = diskCacheProvider;
    this.pool = pool;
  }

  DecodeJob<R> init(
      GlideContext glideContext,
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
      Callback<R> callback,//解码的回调，是 EngineJob 对象，当图片解码完毕后会 调用 onResourceReady ， onLoadFailed 等方法
      int order) {
    decodeHelper.init(
        glideContext,
        model,
        signature,
        width,
        height,
        diskCacheStrategy,
        resourceClass,
        transcodeClass,
        priority,
        options,
        transformations,
        isTransformationRequired,
        isScaleOnlyOrNoTransform,
        diskCacheProvider);
    this.glideContext = glideContext;
    this.signature = signature;
    this.priority = priority;
    this.loadKey = loadKey;
    this.width = width;
    this.height = height;
    this.diskCacheStrategy = diskCacheStrategy;
    this.onlyRetrieveFromCache = onlyRetrieveFromCache;
    this.options = options;
    this.callback = callback;
    this.order = order;
    //默认状态为 RunReason.INITIALIZE
    this.runReason = RunReason.INITIALIZE;
    this.model = model;
    return this;
  }

  /**
   * Returns true if this job will attempt to decode a resource from the disk cache, and false if it
   * will always decode from source.
   */
  boolean willDecodeFromCache() {
    Stage firstStage = getNextStage(Stage.INITIALIZE);
    return firstStage == Stage.RESOURCE_CACHE || firstStage == Stage.DATA_CACHE;
  }

  /**
   * Called when this object is no longer in use externally.
   *
   * @param isRemovedFromQueue {@code true} if we've been removed from the queue and {@link #run} is
   *                           neither in progress nor will ever be called again.
   */
  void release(boolean isRemovedFromQueue) {
    if (releaseManager.release(isRemovedFromQueue)) {
      releaseInternal();
    }
  }

  /**
   * Called when we've finished encoding (either because the encode process is complete, or because
   * we don't have anything to encode).
   */
  private void onEncodeComplete() {
    if (releaseManager.onEncodeComplete()) {
      releaseInternal();
    }
  }

  /**
   * Called when the load has failed due to a an error or a series of errors.
   */
  private void onLoadFailed() {
    if (releaseManager.onFailed()) {
      releaseInternal();
    }
  }

  private void releaseInternal() {
    releaseManager.reset();
    deferredEncodeManager.clear();
    decodeHelper.clear();
    isCallbackNotified = false;
    glideContext = null;
    signature = null;
    options = null;
    priority = null;
    loadKey = null;
    callback = null;
    stage = null;
    currentGenerator = null;
    currentThread = null;
    currentSourceKey = null;
    currentData = null;
    currentDataSource = null;
    currentFetcher = null;
    startFetchTime = 0L;
    isCancelled = false;
    model = null;
    throwables.clear();
    pool.release(this);
  }

  @Override
  public int compareTo(@NonNull DecodeJob<?> other) {
    int result = getPriority() - other.getPriority();
    if (result == 0) {
      result = order - other.order;
    }
    return result;
  }

  private int getPriority() {
    return priority.ordinal();
  }

  public void cancel() {
    isCancelled = true;
    DataFetcherGenerator local = currentGenerator;
    if (local != null) {
      local.cancel();
    }
  }

  @Override
  public void run() {
    // This should be much more fine grained, but since Java's thread pool implementation silently
    // swallows all otherwise fatal exceptions, this will at least make it obvious to developers
    // that something is failing.
    GlideTrace.beginSectionFormat("DecodeJob#run(model=%s)", model);
    // Methods in the try statement can invalidate currentFetcher, so set a local variable here to
    // ensure that the fetcher is cleaned up either way.
    //对于加载网络图片来说 数据加载成功后会在当前类得 onDataFetcherReady 方法中对
    // currentFetcher进行赋值 为 ByteBufferFileLoader$ByteBufferFetcher
    DataFetcher<?> localFetcher = currentFetcher;
    try {
      //如果已经被取消了，那就回调 onLoadFailed
      if (isCancelled) {
        notifyFailed();
        return;
      }
      //进入这个核心方法
      runWrapped();
    } catch (Throwable t) {
      // Catch Throwable and not Exception to handle OOMs. Throwables are swallowed by our
      // usage of .submit() in GlideExecutor so we're not silently hiding crashes by doing this. We
      // are however ensuring that our callbacks are always notified when a load fails. Without this
      // notification, uncaught throwables never notify the corresponding callbacks, which can cause
      // loads to silently hang forever, a case that's especially bad for users using Futures on
      // background threads.
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "DecodeJob threw unexpectedly"
            + ", isCancelled: " + isCancelled
            + ", stage: " + stage, t);
      }
      // When we're encoding we've already notified our callback and it isn't safe to do so again.
      if (stage != Stage.ENCODE) {
        throwables.add(t);
        notifyFailed();
      }
      if (!isCancelled) {
        throw t;
      }
    } finally {
      // Keeping track of the fetcher here and calling cleanup is excessively paranoid, we call
      // close in all cases anyway.
      if (localFetcher != null) {
        localFetcher.cleanup();
      }
      GlideTrace.endSection();
    }
  }

  private void runWrapped() {
    switch (runReason) {
      case INITIALIZE://初始状态
        //默认情况下 这里 stage 是 Stage.RESOURCE_CACHE 代表从缓存中解码
        stage = getNextStage(Stage.INITIALIZE);
        //这里的 currentGenerator 为 ResourceCacheGenerator
        currentGenerator = getNextGenerator();
        runGenerators();
        break;
      case SWITCH_TO_SOURCE_SERVICE:
        runGenerators();
        break;
      case DECODE_DATA://数据加载后会调用本类的 onDataFetcherReady 方法，然后将 runReason 赋值为 DECODE_DATA
        //对检索到的数据进行解码
        decodeFromRetrievedData();
        break;
      default:
        throw new IllegalStateException("Unrecognized run reason: " + runReason);
    }
  }

  //获取解码的数据源，是缓存 还是 网络
  private DataFetcherGenerator getNextGenerator() {
    switch (stage) {
      case RESOURCE_CACHE:
        //这里从磁盘缓存中 读取 转换过的数据
        return new ResourceCacheGenerator(decodeHelper, this);
      case DATA_CACHE:
        //这里从磁盘缓存中 读取 原始数据 回到为当前类如果
        return new DataCacheGenerator(decodeHelper, this);
      case SOURCE:
        //这里是走网络 ,回到为 当前类 让网络图片OK以后 会回调本类的 reschedule
        return new SourceGenerator(decodeHelper, this);
      case FINISHED:
        return null;
      default:
        throw new IllegalStateException("Unrecognized stage: " + stage);
    }
  }

  private void runGenerators() {
    currentThread = Thread.currentThread();
    startFetchTime = LogTime.getLogTime();
    boolean isStarted = false;
    while (!isCancelled && currentGenerator != null
        && !(isStarted = currentGenerator.startNext()//这里会调用 currentGenerator.startNext 的方法
    )) {
      //这里stage的顺序为 RESOURCE_CACHE -》 DATA_CACHE -》SOURCE
      stage = getNextStage(stage);
      //这里 currentGenerator 的顺序为 ResourceCacheGenerator-》DataCacheGenerator-》SourceGenerator
      currentGenerator = getNextGenerator();

      if (stage == Stage.SOURCE) {
        reschedule();
        return;
      }
    }
    // We've run out of stages and generators, give up.
    if ((stage == Stage.FINISHED || isCancelled) && !isStarted) {
      notifyFailed();
    }

    // Otherwise a generator started a new load and we expect to be called back in
    // onDataFetcherReady.
  }

  private void notifyFailed() {
    setNotifiedOrThrow();
    GlideException e = new GlideException("Failed to load resource", new ArrayList<>(throwables));
    callback.onLoadFailed(e);
    onLoadFailed();
  }

  private void notifyComplete(
      Resource<R> resource, //在加载网络图片流程中，为LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      DataSource dataSource//对于加载网络图片来说 为DataSource.REMOTE
  ) {
    setNotifiedOrThrow();
    //callback 为 EngineJob，调用到 EngineJob.onResourceReady
    callback.onResourceReady(resource, dataSource);
  }

  private void setNotifiedOrThrow() {
    stateVerifier.throwIfRecycled();
    if (isCallbackNotified) {
      throw new IllegalStateException("Already notified");
    }
    isCallbackNotified = true;
  }

  private Stage getNextStage(Stage current) {
    switch (current) {
      case INITIALIZE:
        //diskCacheStrategy.decodeCachedResource() 默认会调用到 DiskCacheStrategy.AUTOMATIC
        // 这个内部类的 decodeCachedResource 方法 返回 true
        // 所以返回的就是 Stage.RESOURCE_CACHE 代表从缓存中解码
        Stage stage = diskCacheStrategy.decodeCachedResource()
            ? Stage.RESOURCE_CACHE : getNextStage(Stage.RESOURCE_CACHE);
        Log.e(TAG, "diskCacheStrategy= " + diskCacheStrategy + " stage =" + stage);

        return stage;
      case RESOURCE_CACHE:
        // diskCacheStrategy.decodeCachedResource() 默认会调用到 DiskCacheStrategy.AUTOMATIC
        // 这个内部类的 decodeCachedResource 方法 返回 true
        // 所以返回的就是 DATA_CACHE
        return diskCacheStrategy.decodeCachedData()
            ? Stage.DATA_CACHE : getNextStage(Stage.DATA_CACHE);
      case DATA_CACHE:
        // Skip loading from source if the user opted to only retrieve the resource from cache.
        return onlyRetrieveFromCache ? Stage.FINISHED : Stage.SOURCE;
      case SOURCE:
      case FINISHED:
        return Stage.FINISHED;
      default:
        throw new IllegalArgumentException("Unrecognized stage: " + current);
    }
  }

  @Override
  public void reschedule() {
    //这里会将 runReason 标记为 SWITCH_TO_SOURCE_SERVICE
    runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
    //回调到 EngineJob 的 reschedule
    callback.reschedule(this);
  }

  @Override
  public void onDataFetcherReady(Key sourceKey,  //对于获取网络图片来说这里是 GlideUrl
      Object data, //加载好的数据
      DataFetcher<?> fetcher,//对于加载网络图片来说 loadData.fetcher 为 ByteBufferFileLoader$ByteBufferFetcher
      DataSource dataSource,//对于加载网络图片来说 为DataSource.REMOTE
      Key attemptedKey//和 sourceKey 一样 都是 对于获取网络图片来说这里是 GlideUrl
  ) {
    this.currentSourceKey = sourceKey;
    this.currentData = data;
    this.currentFetcher = fetcher;
    this.currentDataSource = dataSource;
    this.currentAttemptingKey = attemptedKey;

    Log.e(TAG, "线程是否相同=" + (Thread.currentThread() != currentThread));
    //一般为 false
    if (Thread.currentThread() != currentThread) {
      //设置 runReason 为 RunReason.DECODE_DATA;
      runReason = RunReason.DECODE_DATA;
      //callback 为 EngineJob，所以会回调到 EngineJob 的 reschedule
      //最终会再次触发 当前类的 run 方法
      callback.reschedule(this);
    } else {
      GlideTrace.beginSection("DecodeJob.decodeFromRetrievedData");
      try {
        decodeFromRetrievedData();
      } finally {
        GlideTrace.endSection();
      }
    }
  }

  @Override
  public void onDataFetcherFailed(Key attemptedKey, Exception e, DataFetcher<?> fetcher,
      DataSource dataSource) {
    fetcher.cleanup();
    GlideException exception = new GlideException("Fetching data failed", e);
    exception.setLoggingDetails(attemptedKey, dataSource, fetcher.getDataClass());
    throwables.add(exception);
    if (Thread.currentThread() != currentThread) {
      runReason = RunReason.SWITCH_TO_SOURCE_SERVICE;
      callback.reschedule(this);
    } else {
      runGenerators();
    }
  }

  private void decodeFromRetrievedData() {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      logWithTimeAndKey("Retrieved data", startFetchTime,
          "data: " + currentData
              + ", cache key: " + currentSourceKey
              + ", fetcher: " + currentFetcher);
    }
    Resource<R> resource = null;
    try {
      //开始解码 会返回一个 LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      resource = decodeFromData(currentFetcher, currentData, currentDataSource);
    } catch (GlideException e) {
      e.setLoggingDetails(currentAttemptingKey, currentDataSource);
      throwables.add(e);
    }
    if (resource != null) {
      //开始编码和释放资源
      notifyEncodeAndRelease(resource, currentDataSource);
    } else {
      runGenerators();
    }
  }

  private void notifyEncodeAndRelease(
      Resource<R> resource,//在加载网络图片流程中，为LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      DataSource dataSource//对于加载网络图片来说是 DataSource.DATA_DISK_CACHE
  ) {
    if (resource instanceof Initializable) {
      ((Initializable) resource).initialize();
    }

    Resource<R> result = resource;
    LockedResource<R> lockedResource = null;
    //对于加载网络图片来说是 false 所以不进这个 if
    if (deferredEncodeManager.hasResourceToEncode()) {
      lockedResource = LockedResource.obtain(resource);
      result = lockedResource;
    }

    //告诉回调 资源加载完成了
    notifyComplete(result, dataSource);

    //
    stage = Stage.ENCODE;
    try {
      //对于加载网络图片来说是 false 所以不进这个 if
      Log.e(TAG, "deferredEncodeManager.hasResourceToEncode()=" + (deferredEncodeManager
          .hasResourceToEncode()));
      if (deferredEncodeManager.hasResourceToEncode()) {
//        Log.e(TAG,"deferredEncodeManager.hasResourceToEncode()="+(deferredEncodeManager.hasResourceToEncode()));
        deferredEncodeManager.encode(diskCacheProvider, options);
      }
    } finally {
      if (lockedResource != null) {
        lockedResource.unlock();
      }
    }
    // Call onEncodeComplete outside the finally block so that it's not called if the encode process
    // throws.
    onEncodeComplete();
  }

  private <Data> Resource<R> decodeFromData(
      DataFetcher<?> fetcher,//对于加载网络图片来说 loadData.fetcher 为 ByteBufferFileLoader$ByteBufferFetcher
      Data data,//加载好的数据 对于加载网络图片来说是 ByteBuffer
      DataSource dataSource//对于加载网络图片来说 为DataSource.REMOTE
  ) throws GlideException {
    try {
      if (data == null) {
        return null;
      }
      long startTime = LogTime.getLogTime();
      //解码 会返回一个 LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      Resource<R> result = decodeFromFetcher(data, dataSource);
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        logWithTimeAndKey("Decoded result " + result, startTime);
      }
      return result;
    } finally {
      fetcher.cleanup();
    }
  }

  @SuppressWarnings("unchecked")
  private <Data> Resource<R> decodeFromFetcher(
      Data data, //加载好的数据 对于加载网络图片来说是 ByteBuffer
      DataSource dataSource//对于加载网络图片来说 为DataSource.REMOTE
  )
      throws GlideException {
    //从注册表中查询到可以处理 ByteBuffer 类型数据的 LoadPath
    //对于加载网络图片来说 path 为 	LoadPath{
    //	decodePaths=[
    //	DecodePath{ dataClass=class java.nio.DirectByteBuffer, decoders=[com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder@54fb243], transcoder=com.bumptech.glide.load.resource.transcode.UnitTranscoder@e1180c0},
    //	DecodePath{ dataClass=class java.nio.DirectByteBuffer, decoders=[com.bumptech.glide.load.resource.bitmap.ByteBufferBitmapDecoder@72e81f9], transcoder=com.bumptech.glide.load.resource.transcode.BitmapDrawableTranscoder@9fd653e},
    //	DecodePath{ dataClass=class java.nio.DirectByteBuffer, decoders=[com.bumptech.glide.load.resource.bitmap.BitmapDrawableDecoder@1b12f9f], transcoder=com.bumptech.glide.load.resource.transcode.UnitTranscoder@e1180c0}]}
    LoadPath<Data, ?, R> path = decodeHelper.getLoadPath((Class<Data>) data.getClass());
    Log.e(TAG, "path=" + path);
    //会返回一个 LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
    return runLoadPath(data, dataSource, path);
  }

  @NonNull
  private Options getOptionsWithHardwareConfig(DataSource dataSource) {
    Options options = this.options;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return options;
    }

    boolean isHardwareConfigSafe =
        dataSource == DataSource.RESOURCE_DISK_CACHE || decodeHelper.isScaleOnlyOrNoTransform();
    Boolean isHardwareConfigAllowed = options.get(Downsampler.ALLOW_HARDWARE_CONFIG);

    // If allow hardware config is defined, we can use it if it's set to false or if it's safe to
    // use the hardware config for the request.
    if (isHardwareConfigAllowed != null && (!isHardwareConfigAllowed || isHardwareConfigSafe)) {
      return options;
    }

    // If allow hardware config is undefined or is set to true but it's unsafe for us to use the
    // hardware config for this request, we need to override the config.
    options = new Options();
    options.putAll(this.options);
    options.set(Downsampler.ALLOW_HARDWARE_CONFIG, isHardwareConfigSafe);

    return options;
  }

  private <Data, ResourceType> Resource<R> runLoadPath(
      Data data, //加载好的数据 对于加载网络图片来说是 ByteBuffer
      DataSource dataSource,//对于加载网络图片来说 为DataSource.REMOTE
      LoadPath<Data, ResourceType, R> path
  ) throws GlideException {
    Options options = getOptionsWithHardwareConfig(dataSource);
    DataRewinder<Data> rewinder = glideContext.getRegistry().getRewinder(data);
    try {
      // ResourceType in DecodeCallback below is required for compilation to work with gradle.
      //会返回一个 LazyBitmapDrawableResource 里面包含了 经过转换后的 BitmapResource
      return path.load(
          rewinder, options, width, height, new DecodeCallback<ResourceType>(dataSource));
    } finally {
      rewinder.cleanup();
    }
  }

  private void logWithTimeAndKey(String message, long startTime) {
    logWithTimeAndKey(message, startTime, null /*extraArgs*/);
  }

  private void logWithTimeAndKey(String message, long startTime, String extraArgs) {
    Log.v(TAG, message + " in " + LogTime.getElapsedMillis(startTime) + ", load key: " + loadKey
        + (extraArgs != null ? ", " + extraArgs : "") + ", thread: "
        + Thread.currentThread().getName());
  }

  @NonNull
  @Override
  public StateVerifier getVerifier() {
    return stateVerifier;
  }

  /**
   * 这个方法中会对图片做 变换操作，比如圆角，黑白化 等
   */
  @Synthetic
  @NonNull
  <Z> Resource<Z> onResourceDecoded(
      DataSource dataSource,//对于加载网络图片来说 为DataSource.REMOTE
      @NonNull Resource<Z> decoded//对于加载网络图片来说 返回一个具体类型为 BitmapResource
  ) {
    @SuppressWarnings("unchecked")
    //对于加载网络图片来说是 Class<Bitmap>
        Class<Z> resourceSubClass = (Class<Z>) decoded.get().getClass();
    Transformation<Z> appliedTransformation = null;
    Resource<Z> transformed = decoded;
    if (dataSource != DataSource.RESOURCE_DISK_CACHE) {
      //获取匹配的 变换操作 ,对于加载网络图片来说 默认是 FitCenter 继承自 BitmapTransformation
      appliedTransformation = decodeHelper.getTransformation(resourceSubClass);
      //执行他的 transform 操作 返回转换后的 BitmapResource
      transformed = appliedTransformation.transform(glideContext, decoded, width, height);
    }
    // TODO: Make this the responsibility of the Transformation.
    if (!decoded.equals(transformed)) {
      //这里将原始 的 BitmapResource 进行回收，因为下面就要使用 转换过得了
      decoded.recycle();
    }

    final EncodeStrategy encodeStrategy;
    final ResourceEncoder<Z> encoder;
    //判断是否存在 针对 BitmapResource 的编码器
    if (decodeHelper.isResourceEncoderAvailable(transformed)) {
      //对于加载网络图片来说是 com.bumptech.glide.load.resource.bitmap.BitmapEncoder
      encoder = decodeHelper.getResultEncoder(transformed);
      Log.e(TAG, "encoder=" + encoder);

      //BitmapEncoder 返回的是 EncodeStrategy.TRANSFORMED
      encodeStrategy = encoder.getEncodeStrategy(options);
    } else {
      encoder = null;
      encodeStrategy = EncodeStrategy.NONE;
    }

    Resource<Z> result = transformed;
    //对于获取网络图片来说  currentSourceKey 是 GlideUrl , isFromAlternateCacheKey 为 false
    boolean isFromAlternateCacheKey = !decodeHelper.isSourceKey(currentSourceKey);
    //默认为 DiskCacheStrategy.AUTOMATIC 因为 isFromAlternateCacheKey = false这个 if 整体返回为 false 所以不进入这个 if
    // 只有本地圖片（资源文件 or sd卡文件） 而且 变换以后才会走这个流程，也就是才会硬盘缓存 变换后的图片，
    // 也就是说网络图片默认不会 硬盘缓存变换后的图片
    if (diskCacheStrategy.isResourceCacheable(isFromAlternateCacheKey, dataSource,
        encodeStrategy)) {
      Log.e(TAG,"到底进到这个里面了没? 没进来~~~");
      if (encoder == null) {
        throw new Registry.NoResultEncoderAvailableException(transformed.get().getClass());
      }
      final Key key;
      switch (encodeStrategy) {
        case SOURCE:
          key = new DataCacheKey(currentSourceKey, signature);
          break;
        case TRANSFORMED:
          key =
              new ResourceCacheKey(
                  decodeHelper.getArrayPool(),
                  currentSourceKey,
                  signature,
                  width,
                  height,
                  appliedTransformation,
                  resourceSubClass,
                  options);
          break;
        default:
          throw new IllegalArgumentException("Unknown strategy: " + encodeStrategy);
      }

      LockedResource<Z> lockedResult = LockedResource.obtain(transformed);
      deferredEncodeManager.init(key, encoder, lockedResult);
      result = lockedResult;
    }
    //返回经过转换的 BitmapResource
    return result;
  }

  private final class DecodeCallback<Z> implements DecodePath.DecodeCallback<Z> {

    private final DataSource dataSource;//对于加载网络图片来说 为DataSource.REMOTE

    @Synthetic
    DecodeCallback(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @NonNull
    @Override
    public Resource<Z> onResourceDecoded(@NonNull Resource<Z> decoded
//对于加载网络图片来说 返回一个具体类型为 BitmapResource
    ) {
      //会调用到 本文件中的 onResourceDecoded 方法
      return DecodeJob.this.onResourceDecoded(dataSource, decoded);
    }
  }

  /**
   * Responsible for indicating when it is safe for the job to be cleared and returned to the pool.
   */
  private static class ReleaseManager {
    private boolean isReleased;
    private boolean isEncodeComplete;
    private boolean isFailed;

    @Synthetic
    ReleaseManager() { }

    synchronized boolean release(boolean isRemovedFromQueue) {
      isReleased = true;
      return isComplete(isRemovedFromQueue);
    }

    synchronized boolean onEncodeComplete() {
      isEncodeComplete = true;
      return isComplete(false /*isRemovedFromQueue*/);
    }

    synchronized boolean onFailed() {
      isFailed = true;
      return isComplete(false /*isRemovedFromQueue*/);
    }

    synchronized void reset() {
      isEncodeComplete = false;
      isReleased = false;
      isFailed = false;
    }

    private boolean isComplete(boolean isRemovedFromQueue) {
      return (isFailed || isRemovedFromQueue || isEncodeComplete) && isReleased;
    }
  }

  /**
   * Allows transformed resources to be encoded after the transcoded result is already delivered to
   * requestors.
   */
  private static class DeferredEncodeManager<Z> {
    private Key key;
    private ResourceEncoder<Z> encoder;
    private LockedResource<Z> toEncode;

    @Synthetic
    DeferredEncodeManager() { }

    // We just need the encoder and resource type to match, which this will enforce.
    @SuppressWarnings("unchecked")
    <X> void init(Key key, ResourceEncoder<X> encoder, LockedResource<X> toEncode) {
      this.key = key;
      this.encoder = (ResourceEncoder<Z>) encoder;
      this.toEncode = (LockedResource<Z>) toEncode;
    }

    void encode(DiskCacheProvider diskCacheProvider, Options options) {
      GlideTrace.beginSection("DecodeJob.encode");
      try {
        diskCacheProvider.getDiskCache().put(key,
            new DataCacheWriter<>(encoder, toEncode, options));
      } finally {
        toEncode.unlock();
        GlideTrace.endSection();
      }
    }

    boolean hasResourceToEncode() {
      return toEncode != null;
    }

    void clear() {
      key = null;
      encoder = null;
      toEncode = null;
    }
  }

  interface Callback<R> {

    void onResourceReady(Resource<R> resource, DataSource dataSource);

    void onLoadFailed(GlideException e);

    void reschedule(DecodeJob<?> job);
  }

  interface DiskCacheProvider {
    DiskCache getDiskCache();
  }

  /**
   * Why we're being executed again.
   */
  private enum RunReason {
    /**
     * The first time we've been submitted.
     */
    INITIALIZE,
    /**
     * We want to switch from the disk cache service to the source executor.
     */
    SWITCH_TO_SOURCE_SERVICE,
    /**
     * We retrieved some data on a thread we don't own and want to switch back to our thread to
     * process the data.
     * 开始解析数据
     */
    DECODE_DATA,
  }

  /**
   * Where we're trying to decode data from.
   */
  private enum Stage {
    /**
     * The initial stage.
     */
    INITIALIZE,
    /**
     * Decode from a cached resource.
     */
    RESOURCE_CACHE,
    /**
     * Decode from cached source data.
     */
    DATA_CACHE,
    /**
     * Decode from retrieved source.
     */
    SOURCE,
    /**
     * Encoding transformed resources after a successful load.
     * //编码
     */
    ENCODE,
    /**
     * No more viable stages.
     */
    FINISHED,
  }
}
