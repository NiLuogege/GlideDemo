package com.bumptech.glide.request;

import android.content.Context;
import android.content.res.Resources.Theme;
import android.graphics.drawable.Drawable;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pools;
import android.util.Log;
import com.bumptech.glide.GlideContext;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.Engine;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.drawable.DrawableDecoderCompat;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.request.transition.TransitionFactory;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.Util;
import com.bumptech.glide.util.pool.FactoryPools;
import com.bumptech.glide.util.pool.StateVerifier;
import java.util.List;

/**
 * A {@link Request} that loads a {@link com.bumptech.glide.load.engine.Resource} into a given
 * {@link Target}.
 *
 * @param <R> The type of the resource that will be transcoded from the loaded resource.
 *            <p>
 *            默认的 Request
 *            <p>
 *            开始请求是在他的 begin 方法中
 */
public final class SingleRequest<R> implements Request,
    SizeReadyCallback,
    ResourceCallback,
    FactoryPools.Poolable {
  /**
   * Tag for logging internal events, not generally suitable for public use.
   */
  private static final String TAG = "Request";
  /**
   * Tag for logging externally useful events (request completion, timing etc).
   */
  private static final String GLIDE_TAG = "Glide";
  private static final Pools.Pool<SingleRequest<?>> POOL = FactoryPools.simple(150,
      new FactoryPools.Factory<SingleRequest<?>>() {
        @Override
        public SingleRequest<?> create() {
          return new SingleRequest<Object>();
        }
      });
  private boolean isCallingCallbacks;

  private static final boolean IS_VERBOSE_LOGGABLE =
      Log.isLoggable(TAG, Log.VERBOSE);

  private enum Status {
    /**
     * Created but not yet running.
     */
    PENDING,
    /**
     * In the process of fetching media.
     */
    RUNNING,
    /**
     * Waiting for a callback given to the Target to be called to determine target dimensions.
     */
    WAITING_FOR_SIZE,
    /**
     * Finished loading media successfully.
     */
    COMPLETE,
    /**
     * Failed to load media, may be restarted.
     */
    FAILED,
    /**
     * Cleared by the user with a placeholder set, may be restarted.
     */
    CLEARED,
  }

  @Nullable
  private final String tag = IS_VERBOSE_LOGGABLE ? String.valueOf(super.hashCode()) : null;
  private final StateVerifier stateVerifier = StateVerifier.newInstance();

  @Nullable
  private RequestListener<R> targetListener;
  private RequestCoordinator requestCoordinator;
  private Context context;
  private GlideContext glideContext;
  @Nullable
  private Object model;
  private Class<R> transcodeClass;
  private RequestOptions requestOptions;
  private int overrideWidth;
  private int overrideHeight;
  private Priority priority;
  private Target<R> target;
  @Nullable private List<RequestListener<R>> requestListeners;
  private Engine engine;
  private TransitionFactory<? super R> animationFactory;
  private Resource<R> resource;
  private Engine.LoadStatus loadStatus;
  private long startTime;
  private Status status;
  private Drawable errorDrawable;
  private Drawable placeholderDrawable;
  private Drawable fallbackDrawable;
  private int width;
  private int height;

  public static <R> SingleRequest<R> obtain(
      Context context,
      GlideContext glideContext,
      Object model,//在加载网络图片的时候就是 String 类型的 url
      Class<R> transcodeClass,//asDrawable() 流程时  transcodeClass 为 Class<Drawable>
      RequestOptions requestOptions,
      int overrideWidth,//图片宽度 没有设置的话为 -1
      int overrideHeight,//图片高度 没有设置的话为 -1
      Priority priority,//优先级
      Target<R> target,//asDrawable() 流程中 target 为 DrawableImageViewTarget
      RequestListener<R> targetListener,// targetListener 一般为 null
      @Nullable List<RequestListener<R>> requestListeners,// 一般为null
      RequestCoordinator requestCoordinator,// 一般为null
      Engine engine,//Engine
      TransitionFactory<? super R> animationFactory//为 NoAnimationFactory
  ) {
    @SuppressWarnings("unchecked")
        //从 缓冲池中 创建或者直接获取一个 SingleRequest
    SingleRequest<R> request = (SingleRequest<R>) POOL.acquire();
    //池子里没拿到就new 一个
    if (request == null) {
      request = new SingleRequest<>();
    }
    request.init(
        context,
        glideContext,
        model,
        transcodeClass,
        requestOptions,
        overrideWidth,
        overrideHeight,
        priority,
        target,
        targetListener,
        requestListeners,
        requestCoordinator,
        engine,
        animationFactory);
    return request;
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  SingleRequest() {
    // just create, instances are reused with recycle/init
  }

  private void init(
      Context context,
      GlideContext glideContext,
      Object model,//在加载网络图片的时候就是 String 类型的 url
      Class<R> transcodeClass,//asDrawable() 流程时  transcodeClass 为 Class<Drawable>
      RequestOptions requestOptions,
      int overrideWidth,//图片宽度 没有设置的话为 -1
      int overrideHeight,//图片高度 没有设置的话为 -1
      Priority priority,//优先级
      Target<R> target,//asDrawable() 流程中 target 为 DrawableImageViewTarget
      RequestListener<R> targetListener,// 一般为null
      @Nullable List<RequestListener<R>> requestListeners,// 一般为null
      RequestCoordinator requestCoordinator,// 一般为null
      Engine engine,//Engine
      TransitionFactory<? super R> animationFactory//为 NoAnimationFactory
  ) {
    this.context = context;
    this.glideContext = glideContext;
    this.model = model;
    this.transcodeClass = transcodeClass;
    this.requestOptions = requestOptions;
    this.overrideWidth = overrideWidth;
    this.overrideHeight = overrideHeight;
    this.priority = priority;
    this.target = target;
    this.targetListener = targetListener;
    this.requestListeners = requestListeners;
    this.requestCoordinator = requestCoordinator;
    this.engine = engine;
    this.animationFactory = animationFactory;
    //设置状态为 待开始
    status = Status.PENDING;
  }

  @NonNull
  @Override
  public StateVerifier getVerifier() {
    return stateVerifier;
  }

  @Override
  public void recycle() {
    assertNotCallingCallbacks();
    context = null;
    glideContext = null;
    model = null;
    transcodeClass = null;
    requestOptions = null;
    overrideWidth = -1;
    overrideHeight = -1;
    target = null;
    requestListeners = null;
    targetListener = null;
    requestCoordinator = null;
    animationFactory = null;
    loadStatus = null;
    errorDrawable = null;
    placeholderDrawable = null;
    fallbackDrawable = null;
    width = -1;
    height = -1;
    POOL.release(this);
  }

  //开始请求
  @Override
  public void begin() {
    assertNotCallingCallbacks();
    stateVerifier.throwIfRecycled();
    startTime = LogTime.getLogTime();
    if (model == null) {
      if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
        width = overrideWidth;
        height = overrideHeight;
      }
      // Only log at more verbose log levels if the user has set a fallback drawable, because
      // fallback Drawables indicate the user expects null models occasionally.
      // 加载失败 会显示 加载失败图片
      int logLevel = getFallbackDrawable() == null ? Log.WARN : Log.DEBUG;
      onLoadFailed(new GlideException("Received null model"), logLevel);
      return;
    }

    if (status == Status.RUNNING) {
      throw new IllegalArgumentException("Cannot restart a running request");
    }

    // If we're restarted after we're complete (usually via something like a notifyDataSetChanged
    // that starts an identical request into the same Target or View), we can simply use the
    // resource and size we retrieved the last time around and skip obtaining a new size, starting a
    // new load etc. This does mean that users who want to restart a load because they expect that
    // the view size has changed will need to explicitly clear the View or Target before starting
    // the new load.
    // 已经加载好了，不是主流程
    if (status == Status.COMPLETE) {
      onResourceReady(resource, DataSource.MEMORY_CACHE);
      return;
    }

    // Restarts for requests that are neither complete nor running can be treated as new requests
    // and can run again from the beginning.

    //开始对图片 尺寸做处理 ，最终都会走到 onSizeReady
    status = Status.WAITING_FOR_SIZE;
    //如果用户设置了 固定尺寸
    if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
      onSizeReady(overrideWidth, overrideHeight);
    } else {
      //自己取获取尺寸，尺寸计算好了会回调 本类的 onSizeReady 方法
      target.getSize(this);
    }

    if ((status == Status.RUNNING || status == Status.WAITING_FOR_SIZE)&& canNotifyStatusChanged()) {
      //开始加载了设置 占位图
      target.onLoadStarted(getPlaceholderDrawable());
    }
    if (IS_VERBOSE_LOGGABLE) {
      logV("finished run method in " + LogTime.getElapsedMillis(startTime));
    }
  }

  /**
   * Cancels the current load but does not release any resources held by the request and continues
   * to display the loaded resource if the load completed before the call to cancel.
   *
   * <p> Cancelled requests can be restarted with a subsequent call to {@link #begin()}. </p>
   *
   * @see #clear()
   */
  private void cancel() {
    assertNotCallingCallbacks();
    stateVerifier.throwIfRecycled();
    target.removeCallback(this);
    if (loadStatus != null) {
      loadStatus.cancel();
      loadStatus = null;
    }
  }

  // Avoids difficult to understand errors like #2413.
  private void assertNotCallingCallbacks() {
    if (isCallingCallbacks) {
      throw new IllegalStateException("You can't start or clear loads in RequestListener or"
          + " Target callbacks. If you're trying to start a fallback request when a load fails, use"
          + " RequestBuilder#error(RequestBuilder). Otherwise consider posting your into() or"
          + " clear() calls to the main thread using a Handler instead.");
    }
  }

  /**
   * Cancels the current load if it is in progress, clears any resources held onto by the request
   * and replaces the loaded resource if the load completed with the placeholder.
   *
   * <p> Cleared requests can be restarted with a subsequent call to {@link #begin()} </p>
   *
   * @see #cancel()
   */
  @Override
  public void clear() {
    Util.assertMainThread();
    assertNotCallingCallbacks();
    stateVerifier.throwIfRecycled();
    if (status == Status.CLEARED) {
      return;
    }
    cancel();
    // Resource must be released before canNotifyStatusChanged is called.
    if (resource != null) {
      releaseResource(resource);
    }
    if (canNotifyCleared()) {
      target.onLoadCleared(getPlaceholderDrawable());
    }

    status = Status.CLEARED;
  }

  private void releaseResource(Resource<?> resource) {
    engine.release(resource);
    this.resource = null;
  }

  @Override
  public boolean isRunning() {
    return status == Status.RUNNING || status == Status.WAITING_FOR_SIZE;
  }

  @Override
  public boolean isComplete() {
    return status == Status.COMPLETE;
  }

  @Override
  public boolean isResourceSet() {
    return isComplete();
  }

  @Override
  public boolean isCleared() {
    return status == Status.CLEARED;
  }

  @Override
  public boolean isFailed() {
    return status == Status.FAILED;
  }

  private Drawable getErrorDrawable() {
    if (errorDrawable == null) {
      errorDrawable = requestOptions.getErrorPlaceholder();
      if (errorDrawable == null && requestOptions.getErrorId() > 0) {
        errorDrawable = loadDrawable(requestOptions.getErrorId());
      }
    }
    return errorDrawable;
  }

  private Drawable getPlaceholderDrawable() {
    if (placeholderDrawable == null) {
      placeholderDrawable = requestOptions.getPlaceholderDrawable();
      if (placeholderDrawable == null && requestOptions.getPlaceholderId() > 0) {
        placeholderDrawable = loadDrawable(requestOptions.getPlaceholderId());
      }
    }
    return placeholderDrawable;
  }

  private Drawable getFallbackDrawable() {
    if (fallbackDrawable == null) {
      fallbackDrawable = requestOptions.getFallbackDrawable();
      if (fallbackDrawable == null && requestOptions.getFallbackId() > 0) {
        fallbackDrawable = loadDrawable(requestOptions.getFallbackId());
      }
    }
    return fallbackDrawable;
  }

  private Drawable loadDrawable(@DrawableRes int resourceId) {
    Theme theme = requestOptions.getTheme() != null
        ? requestOptions.getTheme() : context.getTheme();
    return DrawableDecoderCompat.getDrawable(glideContext, resourceId, theme);
  }

  private void setErrorPlaceholder() {
    if (!canNotifyStatusChanged()) {
      return;
    }

    Drawable error = null;
    if (model == null) {
      error = getFallbackDrawable();
    }
    // Either the model isn't null, or there was no fallback drawable set.
    if (error == null) {
      error = getErrorDrawable();
    }
    // The model isn't null, no fallback drawable was set or no error drawable was set.
    if (error == null) {
      error = getPlaceholderDrawable();
    }
    target.onLoadFailed(error);
  }

  /**
   * A callback method that should never be invoked directly.
   */
  @Override
  public void onSizeReady(int width, int height) {
    stateVerifier.throwIfRecycled();
    if (IS_VERBOSE_LOGGABLE) {
      logV("Got onSizeReady in " + LogTime.getElapsedMillis(startTime));
    }
    if (status != Status.WAITING_FOR_SIZE) {
      return;
    }
    status = Status.RUNNING;

    float sizeMultiplier = requestOptions.getSizeMultiplier();
    this.width = maybeApplySizeMultiplier(width, sizeMultiplier);
    this.height = maybeApplySizeMultiplier(height, sizeMultiplier);

    if (IS_VERBOSE_LOGGABLE) {
      logV("finished setup for calling load in " + LogTime.getElapsedMillis(startTime));
    }

    //开始执行加载
    loadStatus = engine.load(
        glideContext,
        model,
        requestOptions.getSignature(),
        this.width,
        this.height,
        requestOptions.getResourceClass(),
        transcodeClass,
        priority,
        requestOptions.getDiskCacheStrategy(),
        requestOptions.getTransformations(),
        requestOptions.isTransformationRequired(),
        requestOptions.isScaleOnlyOrNoTransform(),
        requestOptions.getOptions(),
        requestOptions.isMemoryCacheable(),
        requestOptions.getUseUnlimitedSourceGeneratorsPool(),
        requestOptions.getUseAnimationPool(),
        requestOptions.getOnlyRetrieveFromCache(),
        this);

    // This is a hack that's only useful for testing right now where loads complete synchronously
    // even though under any executor running on any thread but the main thread, the load would
    // have completed asynchronously.
    if (status != Status.RUNNING) {
      loadStatus = null;
    }
    if (IS_VERBOSE_LOGGABLE) {
      logV("finished onSizeReady in " + LogTime.getElapsedMillis(startTime));
    }
  }

  private static int maybeApplySizeMultiplier(int size, float sizeMultiplier) {
    return size == Target.SIZE_ORIGINAL ? size : Math.round(sizeMultiplier * size);
  }

  private boolean canSetResource() {
    return requestCoordinator == null || requestCoordinator.canSetImage(this);
  }

  private boolean canNotifyCleared() {
    return requestCoordinator == null || requestCoordinator.canNotifyCleared(this);
  }

  private boolean canNotifyStatusChanged() {
    return requestCoordinator == null || requestCoordinator.canNotifyStatusChanged(this);
  }

  private boolean isFirstReadyResource() {
    return requestCoordinator == null || !requestCoordinator.isAnyResourceSet();
  }

  private void notifyLoadSuccess() {
    if (requestCoordinator != null) {
      requestCoordinator.onRequestSuccess(this);
    }
  }

  private void notifyLoadFailed() {
    if (requestCoordinator != null) {
      requestCoordinator.onRequestFailed(this);
    }
  }

  /**
   * A callback method that should never be invoked directly.
   */
  @SuppressWarnings("unchecked")
  @Override
  public void onResourceReady(Resource<?> resource, DataSource dataSource) {
    stateVerifier.throwIfRecycled();
    loadStatus = null;
    if (resource == null) {
      GlideException exception = new GlideException("Expected to receive a Resource<R> with an "
          + "object of " + transcodeClass + " inside, but instead got null.");
      onLoadFailed(exception);
      return;
    }

    Object received = resource.get();
    if (received == null || !transcodeClass.isAssignableFrom(received.getClass())) {
      releaseResource(resource);
      GlideException exception = new GlideException("Expected to receive an object of "
          + transcodeClass + " but instead" + " got "
          + (received != null ? received.getClass() : "") + "{" + received + "} inside" + " "
          + "Resource{" + resource + "}."
          + (received != null ? "" : " " + "To indicate failure return a null Resource "
          + "object, rather than a Resource object containing null data."));
      onLoadFailed(exception);
      return;
    }

    if (!canSetResource()) {
      releaseResource(resource);
      // We can't put the status to complete before asking canSetResource().
      status = Status.COMPLETE;
      return;
    }

    onResourceReady((Resource<R>) resource, (R) received, dataSource);
  }

  /**
   * Internal {@link #onResourceReady(Resource, DataSource)} where arguments are known to be safe.
   *
   * @param resource original {@link Resource}, never <code>null</code>
   * @param result   object returned by {@link Resource#get()}, checked for type and never
   *                 <code>null</code>
   */
  private void onResourceReady(Resource<R> resource, R result, DataSource dataSource) {
    // We must call isFirstReadyResource before setting status.
    boolean isFirstResource = isFirstReadyResource();
    status = Status.COMPLETE;
    this.resource = resource;

    if (glideContext.getLogLevel() <= Log.DEBUG) {
      Log.d(GLIDE_TAG, "Finished loading " + result.getClass().getSimpleName() + " from "
          + dataSource + " for " + model + " with size [" + width + "x" + height + "] in "
          + LogTime.getElapsedMillis(startTime) + " ms");
    }

    isCallingCallbacks = true;
    try {
      boolean anyListenerHandledUpdatingTarget = false;
      if (requestListeners != null) {
        for (RequestListener<R> listener : requestListeners) {
          anyListenerHandledUpdatingTarget |=
              listener.onResourceReady(result, model, target, dataSource, isFirstResource);
        }
      }
      anyListenerHandledUpdatingTarget |=
          targetListener != null
              && targetListener.onResourceReady(result, model, target, dataSource, isFirstResource);

      if (!anyListenerHandledUpdatingTarget) {
        Transition<? super R> animation =
            animationFactory.build(dataSource, isFirstResource);
        target.onResourceReady(result, animation);
      }
    } finally {
      isCallingCallbacks = false;
    }

    notifyLoadSuccess();
  }

  /**
   * A callback method that should never be invoked directly.
   */
  @Override
  public void onLoadFailed(GlideException e) {
    onLoadFailed(e, Log.WARN);
  }

  private void onLoadFailed(GlideException e, int maxLogLevel) {
    stateVerifier.throwIfRecycled();
    int logLevel = glideContext.getLogLevel();
    if (logLevel <= maxLogLevel) {
      Log.w(GLIDE_TAG, "Load failed for " + model + " with size [" + width + "x" + height + "]", e);
      if (logLevel <= Log.INFO) {
        e.logRootCauses(GLIDE_TAG);
      }
    }

    loadStatus = null;
    status = Status.FAILED;

    isCallingCallbacks = true;
    try {
      //TODO: what if this is a thumbnail request?
      boolean anyListenerHandledUpdatingTarget = false;
      if (requestListeners != null) {
        for (RequestListener<R> listener : requestListeners) {
          anyListenerHandledUpdatingTarget |=
              listener.onLoadFailed(e, model, target, isFirstReadyResource());
        }
      }
      anyListenerHandledUpdatingTarget |=
          targetListener != null
              && targetListener.onLoadFailed(e, model, target, isFirstReadyResource());

      if (!anyListenerHandledUpdatingTarget) {
        setErrorPlaceholder();
      }
    } finally {
      isCallingCallbacks = false;
    }

    notifyLoadFailed();
  }

  /**
   * 判断两个请求是否等价（一模一样）
   *
   * 当图片大小，model（加载方式 Sting类型的url 资源 等待），变换 ，请求配置 ，优先级都相等的时候就 是true
   */
  @Override
  public boolean isEquivalentTo(Request o) {
    if (o instanceof SingleRequest) {
      SingleRequest<?> that = (SingleRequest<?>) o;
      return overrideWidth == that.overrideWidth
          && overrideHeight == that.overrideHeight
          && Util.bothModelsNullEquivalentOrEquals(model, that.model)
          && transcodeClass.equals(that.transcodeClass)
          && requestOptions.equals(that.requestOptions)
          && priority == that.priority
          // We do not want to require that RequestListeners implement equals/hashcode, so we don't
          // compare them using equals(). We can however, at least assert that the same amount of
          // request listeners are present in both requests
          && listenerCountEquals(this, that);
    }
    return false;
  }

  private static boolean listenerCountEquals(SingleRequest<?> first, SingleRequest<?> second) {
    int firstListenerCount = first.requestListeners == null ? 0 : first.requestListeners.size();
    int secondListenerCount = second.requestListeners == null ? 0 : second.requestListeners.size();
    return firstListenerCount == secondListenerCount;
  }

  private void logV(String message) {
    Log.v(TAG, message + " this: " + tag);
  }
}
