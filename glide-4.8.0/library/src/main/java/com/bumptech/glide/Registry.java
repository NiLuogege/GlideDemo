package com.bumptech.glide;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pools.Pool;
import android.util.Log;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.ImageHeaderParser;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.load.data.DataRewinderRegistry;
import com.bumptech.glide.load.engine.DecodePath;
import com.bumptech.glide.load.engine.LoadPath;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.ModelLoaderRegistry;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.load.resource.transcode.TranscoderRegistry;
import com.bumptech.glide.provider.EncoderRegistry;
import com.bumptech.glide.provider.ImageHeaderParserRegistry;
import com.bumptech.glide.provider.LoadPathCache;
import com.bumptech.glide.provider.ModelToResourceClassCache;
import com.bumptech.glide.provider.ResourceDecoderRegistry;
import com.bumptech.glide.provider.ResourceEncoderRegistry;
import com.bumptech.glide.util.pool.FactoryPools;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages component registration to extend or replace Glide's default loading, decoding, and
 * encoding logic.
 * <p>
 * 注册表，这里注册了 所有用于加载，编码，解码的 工具，一般可以通过一个key 或者 多个key获取指定的 类
 * <p>
 * 这是一个主注册表，会将配置分发到其他自注册表中
 * <p>
 * 值得注意的是 Registry 中注册的所有组件我们都可以在自定义配置中进行 替换或者添加新的
 */
// Public API.
@SuppressWarnings({"WeakerAccess", "unused"})
public class Registry {
  public static final String BUCKET_GIF = "Gif";
  public static final String BUCKET_BITMAP = "Bitmap";
  public static final String BUCKET_BITMAP_DRAWABLE = "BitmapDrawable";
  private static final String BUCKET_PREPEND_ALL = "legacy_prepend_all";
  private static final String BUCKET_APPEND_ALL = "legacy_append";

  /**
   * 注册ModelLoader 数据加载模块
   */
  private final ModelLoaderRegistry modelLoaderRegistry;
  /**
   * 注册Encoder 编码存储模块，提供将数据持久化存储到磁盘文件中的功能
   */
  private final EncoderRegistry encoderRegistry;
  /**
   * 注册ResourceDecoder 解码模块，能够将各种类型数据，例如文件、byte数组等数据解码成bitmap或者drawable等资源
   */
  private final ResourceDecoderRegistry decoderRegistry;
  /**
   * 注册ResourceEncoder 编码存储模块，提供将bitmap或者drawable等资源文件进行持久化存储的功能
   */
  private final ResourceEncoderRegistry resourceEncoderRegistry;
  /**
   * 数据流重定向模块，例如重定向ByteBuffer中的position或者stream中的指针位置等
   */
  private final DataRewinderRegistry dataRewinderRegistry;
  /**
   * 类型转换模块，提供将不同资源类型进行转换的能力，例如将bitmap转成drawable等
   */
  private final TranscoderRegistry transcoderRegistry;
  /**
   * 图片头解析 注册表
   */
  private final ImageHeaderParserRegistry imageHeaderParserRegistry;

  private final ModelToResourceClassCache modelToResourceClassCache =
      new ModelToResourceClassCache();
  private final LoadPathCache loadPathCache = new LoadPathCache();
  private final Pool<List<Throwable>> throwableListPool = FactoryPools.threadSafeList();

  public Registry() {
    //数据加载模块 注册表，不同的数据类型使用不同的 ModelLoader（处理类）
    this.modelLoaderRegistry = new ModelLoaderRegistry(throwableListPool);
    //编码模块 用于将不同的数据源 缓存到磁盘上
    this.encoderRegistry = new EncoderRegistry();
    //解码模块 ，用于将不同的数据源 解码为 Bitmap ，Drawable 等
    this.decoderRegistry = new ResourceDecoderRegistry();
    //资源编码 ，用于将 Bitmap，Drawable 等 Resource 缓存到磁盘上
    this.resourceEncoderRegistry = new ResourceEncoderRegistry();
    //数据重定向模块
    this.dataRewinderRegistry = new DataRewinderRegistry();
    //类型转换模块，提供将不同资源类型进行转换的能力，例如将bitmap转成drawable等
    this.transcoderRegistry = new TranscoderRegistry();
    //图片头解析 注册表
    this.imageHeaderParserRegistry = new ImageHeaderParserRegistry();
    //设置资源编码器的优先级顺序
    setResourceDecoderBucketPriorityList(
        Arrays.asList(BUCKET_GIF, BUCKET_BITMAP, BUCKET_BITMAP_DRAWABLE));
  }

  /**
   * Registers the given {@link Encoder} for the given data class (InputStream, FileDescriptor etc).
   *
   * <p>The {@link Encoder} will be used both for the exact data class and any subtypes. For
   * example, registering an {@link Encoder} for {@link java.io.InputStream} will result in the
   * {@link Encoder} being used for
   * {@link android.content.res.AssetFileDescriptor.AutoCloseInputStream},
   * {@link java.io.FileInputStream} and any other subclass.
   *
   * <p>If multiple {@link Encoder}s are registered for the same type or super type, the
   * {@link Encoder} that is registered first will be used.
   *
   * @deprecated Use the equivalent {@link #append(Class, Class, ModelLoaderFactory)} method
   * instead.
   */
  @NonNull
  @Deprecated
  public <Data> Registry register(@NonNull Class<Data> dataClass, @NonNull Encoder<Data> encoder) {
    return append(dataClass, encoder);
  }

  /**
   * Appends the given {@link Encoder} onto the list of available {@link Encoder}s so that it is
   * attempted after all earlier and default {@link Encoder}s for the given data class.
   *
   * <p>The {@link Encoder} will be used both for the exact data class and any subtypes. For
   * example, registering an {@link Encoder} for {@link java.io.InputStream} will result in the
   * {@link Encoder} being used for
   * {@link android.content.res.AssetFileDescriptor.AutoCloseInputStream},
   * {@link java.io.FileInputStream} and any other subclass.
   *
   * <p>If multiple {@link Encoder}s are registered for the same type or super type, the
   * {@link Encoder} that is registered first will be used.
   *
   * @see #prepend(Class, Encoder)
   */
  @NonNull
  public <Data> Registry append(@NonNull Class<Data> dataClass, @NonNull Encoder<Data> encoder) {
    encoderRegistry.append(dataClass, encoder);
    return this;
  }

  /**
   * Prepends the given {@link Encoder} into the list of available {@link Encoder}s
   * so that it is attempted before all later and default {@link Encoder}s for the given
   * data class.
   *
   * <p>This method allows you to replace the default {@link Encoder} because it ensures
   * the registered {@link Encoder} will run first. If multiple {@link Encoder}s are registered for
   * the same type or super type, the {@link Encoder} that is registered first will be used.
   *
   * @see #append(Class, Encoder)
   */
  @NonNull
  public <Data> Registry prepend(@NonNull Class<Data> dataClass, @NonNull Encoder<Data> encoder) {
    encoderRegistry.prepend(dataClass, encoder);
    return this;
  }

  /**
   * Appends the given {@link ResourceDecoder} onto the list of all available
   * {@link ResourceDecoder}s allowing it to be used if all earlier and default
   * {@link ResourceDecoder}s for the given types fail (or there are none).
   *
   * <p>If you're attempting to replace an existing {@link ResourceDecoder} or would like to ensure
   * that your {@link ResourceDecoder} gets the chance to run before an existing
   * {@link ResourceDecoder}, use {@link #prepend(Class, Class, ResourceDecoder)}. This method is
   * best for new types of resources and data or as a way to add an additional fallback decoder
   * for an existing type of data.
   *
   * @param dataClass     The data that will be decoded from
   *                      ({@link java.io.InputStream}, {@link java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #append(String, Class, Class, ResourceDecoder)
   * @see #prepend(Class, Class, ResourceDecoder)
   */
  @NonNull
  public <Data, TResource> Registry append(
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    append(BUCKET_APPEND_ALL, dataClass, resourceClass, decoder);
    return this;
  }

  /**
   * Appends the given {@link ResourceDecoder} onto the list of available {@link ResourceDecoder}s
   * in this bucket, allowing it to be used if all earlier and default {@link ResourceDecoder}s for
   * the given types in this bucket fail (or there are none).
   *
   * <p>If you're attempting to replace an existing {@link ResourceDecoder} or would like to ensure
   * that your {@link ResourceDecoder} gets the chance to run before an existing
   * {@link ResourceDecoder}, use {@link #prepend(Class, Class, ResourceDecoder)}. This method is
   * best for new types of resources and data or as a way to add an additional fallback decoder
   * for an existing type of data.
   *
   * @param bucket        The bucket identifier to add this decoder to.
   * @param dataClass     The data that will be decoded from
   *                      ({@link java.io.InputStream}, {@link java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #prepend(String, Class, Class, ResourceDecoder)
   * @see #setResourceDecoderBucketPriorityList(List)
   *
   *   //添加一个 可以将 dataClass 转为 resourceClass 的解码器
   */
  @NonNull
  public <Data, TResource> Registry append(
      @NonNull String bucket,
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    decoderRegistry.append(bucket, decoder, dataClass, resourceClass);
    return this;
  }

  /**
   * Prepends the given {@link ResourceDecoder} into the list of all available
   * {@link ResourceDecoder}s so that it is attempted before all later and default
   * {@link ResourceDecoder}s for the given types.
   *
   * <p>This method allows you to replace the default {@link ResourceDecoder} because it ensures
   * the registered {@link ResourceDecoder} will run first. You can use the
   * {@link ResourceDecoder#handles(Object, Options)} to fall back to the default
   * {@link ResourceDecoder}s if you only want to change the default functionality for certain
   * types of data.
   *
   * @param dataClass     The data that will be decoded from
   *                      ({@link java.io.InputStream}, {@link java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #prepend(String, Class, Class, ResourceDecoder)
   * @see #append(Class, Class, ResourceDecoder)
   */
  @NonNull
  public <Data, TResource> Registry prepend(
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    prepend(BUCKET_PREPEND_ALL, dataClass, resourceClass, decoder);
    return this;
  }

  /**
   * Prepends the given {@link ResourceDecoder} into the list of available {@link ResourceDecoder}s
   * in the same bucket so that it is attempted before all later and default
   * {@link ResourceDecoder}s for the given types in that bucket.
   *
   * <p>This method allows you to replace the default {@link ResourceDecoder} for this bucket
   * because it ensures the registered {@link ResourceDecoder} will run first. You can use the
   * {@link ResourceDecoder#handles(Object, Options)} to fall back to the default
   * {@link ResourceDecoder}s if you only want to change the default functionality for certain
   * types of data.
   *
   * @param bucket        The bucket identifier to add this decoder to.
   * @param dataClass     The data that will be decoded from
   *                      ({@link java.io.InputStream}, {@link java.io.FileDescriptor} etc).
   * @param resourceClass The resource that will be decoded to ({@link android.graphics.Bitmap},
   *                      {@link com.bumptech.glide.load.resource.gif.GifDrawable} etc).
   * @param decoder       The {@link ResourceDecoder} to register.
   * @see #append(String, Class, Class, ResourceDecoder)
   * @see #setResourceDecoderBucketPriorityList(List)
   */
  @NonNull
  public <Data, TResource> Registry prepend(
      @NonNull String bucket,
      @NonNull Class<Data> dataClass,
      @NonNull Class<TResource> resourceClass,
      @NonNull ResourceDecoder<Data, TResource> decoder) {
    decoderRegistry.prepend(bucket, decoder, dataClass, resourceClass);
    return this;
  }

  /**
   * Overrides the default ordering of resource decoder buckets. You may also add custom buckets
   * which are identified as a unique string. Glide will attempt to decode using decoders in the
   * highest priority bucket before moving on to the next one.
   *
   * <p>The default order is [{@link #BUCKET_GIF}, {@link #BUCKET_BITMAP},
   * {@link #BUCKET_BITMAP_DRAWABLE}].
   *
   * <p>When registering decoders, you can use these buckets to specify the ordering relative only
   * to other decoders in that bucket.
   *
   * @param buckets The list of bucket identifiers in order from highest priority to least priority.
   *                <p>
   *                <p>
   *                这里是确定了 具体编码类的执行顺序吗？？？
   * @see #append(String, Class, Class, ResourceDecoder)
   * @see #prepend(String, Class, Class, ResourceDecoder)
   */
  // Final to avoid a PMD error.
  @NonNull
  public final Registry setResourceDecoderBucketPriorityList(@NonNull List<String> buckets) {
    List<String> modifiedBuckets = new ArrayList<>(buckets);
    modifiedBuckets.add(0, BUCKET_PREPEND_ALL);
    modifiedBuckets.add(BUCKET_APPEND_ALL);
    //添加到编码注册中心 中
    decoderRegistry.setBucketPriorityList(modifiedBuckets);
    return this;
  }

  /**
   * Appends the given {@link ResourceEncoder} into the list of available {@link ResourceEncoder}s
   * so that it is attempted after all earlier and default {@link ResourceEncoder}s for the given
   * data type.
   *
   * <p>The {@link ResourceEncoder} will be used both for the exact resource class and any subtypes.
   * For example, registering an {@link ResourceEncoder} for
   * {@link android.graphics.drawable.Drawable} (not recommended) will result in the
   * {@link ResourceEncoder} being used for {@link android.graphics.drawable.BitmapDrawable} and
   * {@link com.bumptech.glide.load.resource.gif.GifDrawable} and any other subclass.
   *
   * <p>If multiple {@link ResourceEncoder}s are registered for the same type or super type, the
   * {@link ResourceEncoder} that is registered first will be used.
   *
   * @deprecated Use the equivalent {@link #append(Class, ResourceEncoder)} method instead.
   */
  @NonNull
  @Deprecated
  public <TResource> Registry register(
      @NonNull Class<TResource> resourceClass, @NonNull ResourceEncoder<TResource> encoder) {
    return append(resourceClass, encoder);
  }

  /**
   * Appends the given {@link ResourceEncoder} into the list of available {@link ResourceEncoder}s
   * so that it is attempted after all earlier and default {@link ResourceEncoder}s for the given
   * data type.
   *
   * <p>The {@link ResourceEncoder} will be used both for the exact resource class and any subtypes.
   * For example, registering an {@link ResourceEncoder} for
   * {@link android.graphics.drawable.Drawable} (not recommended) will result in the
   * {@link ResourceEncoder} being used for {@link android.graphics.drawable.BitmapDrawable} and
   * {@link com.bumptech.glide.load.resource.gif.GifDrawable} and any other subclass.
   *
   * <p>If multiple {@link ResourceEncoder}s are registered for the same type or super type, the
   * {@link ResourceEncoder} that is registered first will be used.
   *
   * @see #prepend(Class, ResourceEncoder)
   */
  @NonNull
  public <TResource> Registry append(
      @NonNull Class<TResource> resourceClass, @NonNull ResourceEncoder<TResource> encoder) {
    resourceEncoderRegistry.append(resourceClass, encoder);
    return this;
  }

  /**
   * Prepends the given {@link ResourceEncoder} into the list of available {@link ResourceEncoder}s
   * so that it is attempted before all later and default {@link ResourceEncoder}s for the given
   * data type.
   *
   * <p>This method allows you to replace the default {@link ResourceEncoder} because it ensures
   * the registered {@link ResourceEncoder} will run first. If multiple {@link ResourceEncoder}s are
   * registered for the same type or super type, the {@link ResourceEncoder} that is registered
   * first will be used.
   *
   * @see #append(Class, ResourceEncoder)
   */
  @NonNull
  public <TResource> Registry prepend(
      @NonNull Class<TResource> resourceClass, @NonNull ResourceEncoder<TResource> encoder) {
    resourceEncoderRegistry.prepend(resourceClass, encoder);
    return this;
  }

  /**
   * Registers a new {@link com.bumptech.glide.load.data.DataRewinder.Factory} to handle a
   * non-default data type that can be rewind to allow for efficient reads of file headers.
   */
  @NonNull
  public Registry register(@NonNull DataRewinder.Factory<?> factory) {
    dataRewinderRegistry.register(factory);
    return this;
  }

  /**
   * Registers the given {@link ResourceTranscoder} to convert from the given resource {@link Class}
   * to the given transcode {@link Class}.
   *
   * @param resourceClass  The class that will be transcoded from (e.g.
   *                       {@link android.graphics.Bitmap}).
   * @param transcodeClass The class that will be transcoded to (e.g.
   *                       {@link android.graphics.drawable.BitmapDrawable}).
   * @param transcoder     The {@link ResourceTranscoder} to register.
   */
  @NonNull
  public <TResource, Transcode> Registry register(
      @NonNull Class<TResource> resourceClass, @NonNull Class<Transcode> transcodeClass,
      @NonNull ResourceTranscoder<TResource, Transcode> transcoder) {
    transcoderRegistry.register(resourceClass, transcodeClass, transcoder);
    return this;
  }

  /**
   * Registers a new {@link ImageHeaderParser} that can obtain some basic metadata from an image
   * header (orientation, type etc).
   */
  @NonNull
  public Registry register(@NonNull ImageHeaderParser parser) {
    imageHeaderParserRegistry.add(parser);
    return this;
  }

  /**
   * Appends a new {@link ModelLoaderFactory} onto the end of the existing set so that the
   * constructed {@link ModelLoader} will be tried after all default and previously registered
   * {@link ModelLoader}s for the given model and data classes.
   *
   * <p>If you're attempting to replace an existing {@link ModelLoader}, use
   * {@link #prepend(Class, Class, ModelLoaderFactory)}. This method is best for new types of models
   * and/or data or as a way to add an additional fallback loader for an existing type of
   * model/data.
   *
   * <p>If multiple {@link ModelLoaderFactory}s are registered for the same model and/or data
   * classes, the {@link ModelLoader}s they produce will be attempted in the order the
   * {@link ModelLoaderFactory}s were registered. Only if all {@link ModelLoader}s fail will the
   * entire request fail.
   *
   * @param modelClass The model class (e.g. URL, file path).
   * @param dataClass  the data class (e.g. {@link java.io.InputStream},
   *                   {@link java.io.FileDescriptor}).
   * @see #prepend(Class, Class, ModelLoaderFactory)
   * @see #replace(Class, Class, ModelLoaderFactory)
   */
  @NonNull
  public <Model, Data> Registry append(
      @NonNull Class<Model> modelClass, @NonNull Class<Data> dataClass,
      @NonNull ModelLoaderFactory<Model, Data> factory) {
    modelLoaderRegistry.append(modelClass, dataClass, factory);
    return this;
  }

  /**
   * Prepends a new {@link ModelLoaderFactory} onto the beginning of the existing set so that the
   * constructed {@link ModelLoader} will be tried before all default and previously registered
   * {@link ModelLoader}s for the given model and data classes.
   *
   * <p>If you're attempting to add additional functionality or add a backup that should run only
   * after the default {@link ModelLoader}s run, use
   * {@link #append(Class, Class, ModelLoaderFactory)}. This method is best for adding an additional
   * case to Glide's existing functionality that should run first. This method will still run
   * Glide's default {@link ModelLoader}s if the prepended {@link ModelLoader}s fail.
   *
   * <p>If multiple {@link ModelLoaderFactory}s are registered for the same model and/or data
   * classes, the {@link ModelLoader}s they produce will be attempted in the order the
   * {@link ModelLoaderFactory}s were registered. Only if all {@link ModelLoader}s fail will the
   * entire request fail.
   *
   * @param modelClass The model class (e.g. URL, file path).
   * @param dataClass  the data class (e.g. {@link java.io.InputStream},
   *                   {@link java.io.FileDescriptor}).
   * @see #append(Class, Class, ModelLoaderFactory)
   * @see #replace(Class, Class, ModelLoaderFactory)
   */
  @NonNull
  public <Model, Data> Registry prepend(
      @NonNull Class<Model> modelClass, @NonNull Class<Data> dataClass,
      @NonNull ModelLoaderFactory<Model, Data> factory) {
    modelLoaderRegistry.prepend(modelClass, dataClass, factory);
    return this;
  }

  /**
   * Removes all default and previously registered {@link ModelLoaderFactory}s for the given data
   * and model class and replaces all of them with the single {@link ModelLoader} provided.
   *
   * <p>If you're attempting to add additional functionality or add a backup that should run only
   * after the default {@link ModelLoader}s run, use
   * {@link #append(Class, Class, ModelLoaderFactory)}. This method should be used only when you
   * want to ensure that Glide's default {@link ModelLoader}s do not run.
   *
   * <p>One good use case for this method is when you want to replace Glide's default networking
   * library with your OkHttp, Volley, or your own implementation. Using
   * {@link #prepend(Class, Class, ModelLoaderFactory)} or
   * {@link #append(Class, Class, ModelLoaderFactory)} may still allow Glide's default networking
   * library to run in some cases. Using this method will ensure that only your networking library
   * will run and that the request will fail otherwise.
   *
   * @param modelClass The model class (e.g. URL, file path).
   * @param dataClass  the data class (e.g. {@link java.io.InputStream},
   *                   {@link java.io.FileDescriptor}).
   * @see #prepend(Class, Class, ModelLoaderFactory)
   * @see #append(Class, Class, ModelLoaderFactory)
   */
  @NonNull
  public <Model, Data> Registry replace(
      @NonNull Class<Model> modelClass,
      @NonNull Class<Data> dataClass,
      @NonNull ModelLoaderFactory<? extends Model, ? extends Data> factory) {
    modelLoaderRegistry.replace(modelClass, dataClass, factory);
    return this;
  }

  /**
   * getLoadPath()入参类型为<Data, TResource, Transcode>，
   * 其中<Data>是在getModelLoaders()返回的类型，例如InputStream或者ByteBuffer，
   * <TResource>是待定类型，调用者一般传?,<Transcode>为调用Glide.with().as(xxx)时as()传入的类型，
   * Glide提供有asBitmap(),asFile(),asGif()，默认是Drawable类型；在调用时<TResource>是待定类型
   * <p>
   * 我们以 加载网络图片来说 dataClass为 ByteBuffer ，resourceClass 为 Object ，transcodeClass 为  Class<Drawable>
   */
  @Nullable
  public <Data, TResource, Transcode> LoadPath<Data, TResource, Transcode> getLoadPath(
      @NonNull Class<Data> dataClass, //加载网络图片来说 dataClass为 ByteBuffer
      @NonNull Class<TResource> resourceClass,
      //加载网络图片来说 resourceClass 为  Class<Drawable> 前面都搞错了，后面再纠正
      @NonNull Class<Transcode> transcodeClass //加载网络图片来说 transcodeClass 为 Class<Drawable>
  ) {
    //在缓存中查找 第一次肯定没有的 ，不是主流程
    LoadPath<Data, TResource, Transcode> result =
        loadPathCache.get(dataClass, resourceClass, transcodeClass);
    if (loadPathCache.isEmptyLoadPath(result)) {
      return null;
    } else if (result == null) {
      //获取解码路径
      List<DecodePath<Data, TResource, Transcode>> decodePaths =
          getDecodePaths(dataClass, resourceClass, transcodeClass);
      // It's possible there is no way to decode or transcode to the desired types from a given
      // data class.
      if (decodePaths.isEmpty()) {
        result = null;
      } else {
        result =
            new LoadPath<>(
                dataClass, resourceClass, transcodeClass, decodePaths, throwableListPool);
      }
      loadPathCache.put(dataClass, resourceClass, transcodeClass, result);
    }
    return result;
  }

  /**
   * 获取到解码路径
   */
  @NonNull
  private <Data, TResource, Transcode> List<DecodePath<Data, TResource, Transcode>> getDecodePaths(
      @NonNull Class<Data> dataClass,  //加载网络图片来说 dataClass为 ByteBuffer
      @NonNull Class<TResource> resourceClass,
      //加载网络图片来说 resourceClass 为  Class<Drawable> 前面都搞错了，后面再纠正
      @NonNull Class<Transcode> transcodeClass//加载网络图片来说 transcodeClass 为 Class<Drawable>
  ) {
    List<DecodePath<Data, TResource, Transcode>> decodePaths = new ArrayList<>();
    //获取所有可以将 dataClass  转为 resourceClass 的对应的 decoder 的 resourceClass 集合 ，有可能有多个
    List<Class<TResource>> registeredResourceClasses = decoderRegistry
        .getResourceClasses(dataClass, resourceClass);
    //遍历 registeredResourceClass
    for (Class<TResource> registeredResourceClass : registeredResourceClasses) {
      //获取所有 可以将 registeredResourceClass 转为 transcodeClass 的对应的 transcoder 的 transcodeClass 集合
      List<Class<Transcode>> registeredTranscodeClasses =
          transcoderRegistry.getTranscodeClasses(registeredResourceClass, transcodeClass);
      //遍历registeredTranscodeClasses
      for (Class<Transcode> registeredTranscodeClass : registeredTranscodeClasses) {
        //获取所有可以将 dataClass  转为 registeredResourceClass 的对应的 decoder 的 resourceClass 集合 ，有可能有多个
        List<ResourceDecoder<Data, TResource>> decoders =
            decoderRegistry.getDecoders(dataClass, registeredResourceClass);
        //获取所有 可以将 registeredResourceClass 转为 registeredTranscodeClass 的对应的 transcoder 的 transcodeClass 集合
        ResourceTranscoder<TResource, Transcode> transcoder =
            transcoderRegistry.get(registeredResourceClass, registeredTranscodeClass);
        @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
        //创建DecodePath,把相关信息封装
            DecodePath<Data, TResource, Transcode> path =
            new DecodePath<>(dataClass, registeredResourceClass, registeredTranscodeClass,
                decoders, transcoder, throwableListPool);
        //添加进集合
        decodePaths.add(path);
      }
    }
    Log.e("Registry","decodePaths="+decodePaths);
    //返回集合
    return decodePaths;
  }

  /**
   * 获取到 可以将 modelClass 转为 resourceClass 的 ModelLoader
   */
  @NonNull
  public <Model, TResource, Transcode> List<Class<?>> getRegisteredResourceClasses(
      @NonNull Class<Model> modelClass, @NonNull Class<TResource> resourceClass,
      @NonNull Class<Transcode> transcodeClass) {
    //先从缓存中拿 ，第一次肯定没有
    List<Class<?>> result = modelToResourceClassCache.get(modelClass, resourceClass);

    if (result == null) {
      result = new ArrayList<>();
      //获取可以处理 modelClass 的所有 dataClasses
      List<Class<?>> dataClasses = modelLoaderRegistry.getDataClasses(modelClass);
      //遍历
      for (Class<?> dataClass : dataClasses) {
        //获取到可以将 dataClass 解码为 resourceClass 的 所有 decoder
        List<? extends Class<?>> registeredResourceClasses =
            decoderRegistry.getResourceClasses(dataClass, resourceClass);
        //遍历所有 decoder
        for (Class<?> registeredResourceClass : registeredResourceClasses) {
          //获取到所有可以将 registeredResourceClass 转为  transcodeClass 的 转换器
          List<Class<Transcode>> registeredTranscodeClasses = transcoderRegistry
              .getTranscodeClasses(registeredResourceClass, transcodeClass);
          if (!registeredTranscodeClasses.isEmpty() && !result.contains(registeredResourceClass)) {
            //添加到 结果集合中
            result.add(registeredResourceClass);
          }
        }
      }
      //添加到缓存中
      modelToResourceClassCache.put(modelClass, resourceClass,
          Collections.unmodifiableList(result));
    }

    return result;
  }

  public boolean isResourceEncoderAvailable(@NonNull Resource<?> resource) {
    //BitmapResource 的getResourceClass 方法返回为 Bitmap
    return resourceEncoderRegistry.get(resource.getResourceClass()) != null;
  }

  @NonNull
  public <X> ResourceEncoder<X> getResultEncoder(@NonNull Resource<X> resource)
      throws NoResultEncoderAvailableException {
    ResourceEncoder<X> resourceEncoder = resourceEncoderRegistry.get(resource.getResourceClass());
    if (resourceEncoder != null) {
      return resourceEncoder;
    }
    throw new NoResultEncoderAvailableException(resource.getResourceClass());
  }

  @NonNull
  @SuppressWarnings("unchecked")
  public <X> Encoder<X> getSourceEncoder(@NonNull X data) throws NoSourceEncoderAvailableException {
    Encoder<X> encoder = encoderRegistry.getEncoder((Class<X>) data.getClass());
    if (encoder != null) {
      return encoder;
    }
    throw new NoSourceEncoderAvailableException(data.getClass());
  }

  @NonNull
  public <X> DataRewinder<X> getRewinder(@NonNull X data) {
    return dataRewinderRegistry.build(data);
  }

  //获得数据加载模块 ModelLoader
  @NonNull
  public <Model> List<ModelLoader<Model, ?>> getModelLoaders(@NonNull Model model) {
    //返回可以处理此 model的所有 ModelLoader
    List<ModelLoader<Model, ?>> result = modelLoaderRegistry.getModelLoaders(model);
    if (result.isEmpty()) {
      throw new NoModelLoaderAvailableException(model);
    }
    return result;
  }

  @NonNull
  public List<ImageHeaderParser> getImageHeaderParsers() {
    List<ImageHeaderParser> result = imageHeaderParserRegistry.getParsers();
    if (result.isEmpty()) {
      throw new NoImageHeaderParserException();
    }
    return result;
  }

  /**
   * Thrown when no {@link com.bumptech.glide.load.model.ModelLoader} is registered for a given
   * model class.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class NoModelLoaderAvailableException extends MissingComponentException {
    public NoModelLoaderAvailableException(@NonNull Object model) {
      super("Failed to find any ModelLoaders for model: " + model);
    }

    public NoModelLoaderAvailableException(@NonNull Class<?> modelClass,
        @NonNull Class<?> dataClass) {
      super("Failed to find any ModelLoaders for model: " + modelClass + " and data: " + dataClass);
    }
  }

  /**
   * Thrown when no {@link ResourceEncoder} is registered for a given resource class.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class NoResultEncoderAvailableException extends MissingComponentException {
    public NoResultEncoderAvailableException(@NonNull Class<?> resourceClass) {
      super("Failed to find result encoder for resource class: " + resourceClass
          + ", you may need to consider registering a new Encoder for the requested type or"
          + " DiskCacheStrategy.DATA/DiskCacheStrategy.NONE if caching your transformed resource is"
          + " unnecessary.");
    }
  }

  /**
   * Thrown when no {@link Encoder} is registered for a given data class.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class NoSourceEncoderAvailableException extends MissingComponentException {
    public NoSourceEncoderAvailableException(@NonNull Class<?> dataClass) {
      super("Failed to find source encoder for data class: " + dataClass);
    }
  }

  /**
   * Thrown when some necessary component is missing for a load.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static class MissingComponentException extends RuntimeException {
    public MissingComponentException(@NonNull String message) {
      super(message);
    }
  }

  /**
   * Thrown when no {@link ImageHeaderParser} is registered.
   */
  // Never serialized by Glide.
  @SuppressWarnings("serial")
  public static final class NoImageHeaderParserException extends MissingComponentException {
    public NoImageHeaderParserException() {
      super("Failed to find image header parser.");
    }
  }
}
