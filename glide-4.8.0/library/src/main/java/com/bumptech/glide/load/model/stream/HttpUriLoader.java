package com.bumptech.glide.load.model.stream;

import android.net.Uri;
import android.support.annotation.NonNull;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads {@link InputStream}s from http or https {@link Uri}s.
 */
public class HttpUriLoader implements ModelLoader<Uri, InputStream> {
  private static final Set<String> SCHEMES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("http", "https")));

  //实际类型为 HttpGlideUrlLoader
  private final ModelLoader<GlideUrl, InputStream> urlLoader;

  // Public API.
  @SuppressWarnings("WeakerAccess")
  public HttpUriLoader(ModelLoader<GlideUrl, InputStream> urlLoader) {
    this.urlLoader = urlLoader;
  }

  @Override
  public LoadData<InputStream> buildLoadData(@NonNull Uri model, int width, int height,
      @NonNull Options options) {
    //将 model(就是图片的 url) 封装为 GlideUrl 然后 调用 urlLoader 的  buildLoadData
    //urlLoader类型为 HttpGlideUrlLoader ，其实就是调用 HttpGlideUrlLoader 的 buildLoadData 方法
    return urlLoader.buildLoadData(new GlideUrl(model.toString()), width, height, options);
  }

  @Override
  public boolean handles(@NonNull Uri model) {
    return SCHEMES.contains(model.getScheme());
  }

  /**
   * Factory for loading {@link InputStream}s from http/https {@link Uri}s.
   */
  public static class Factory implements ModelLoaderFactory<Uri, InputStream> {

    @NonNull
    @Override
    public ModelLoader<Uri, InputStream> build(MultiModelLoaderFactory multiFactory) {
      //multiFactory.build(GlideUrl.class, InputStream.class) 这句话会找你能处理 输入为 GlideUrl ，处理数据类型为 InputStream 的 ModelLoader ，
      // 其实就是 HttpGlideUrlLoader 然后记录在 HttpUriLoader的 urlLoader 变量中
      return new HttpUriLoader(multiFactory.build(GlideUrl.class, InputStream.class));
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }
}
