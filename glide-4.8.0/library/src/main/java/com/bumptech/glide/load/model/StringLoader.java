package com.bumptech.glide.load.model;

import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import com.bumptech.glide.load.Options;
import java.io.File;
import java.io.InputStream;

/**
 * A model loader for handling certain string models. Handles paths, urls, and any uri string with a
 * scheme handled by {@link android.content.ContentResolver#openInputStream(Uri)}.
 *
 * @param <Data> The type of data that will be loaded from the given {@link java.lang.String}.
 */
public class StringLoader<Data> implements ModelLoader<String, Data> {
  private final ModelLoader<Uri, Data> uriLoader;

  // Public API.
  @SuppressWarnings("WeakerAccess")
  public StringLoader(ModelLoader<Uri, Data> uriLoader) {
    this.uriLoader = uriLoader;
  }

  //处理 tring类型的http url 的时候会调用
  @Override
  public LoadData<Data> buildLoadData(@NonNull String model, int width, int height,
      @NonNull Options options) {
    //将string类型的 url  转为 Uri 类型的 uri
    Uri uri = parseUri(model);
    if (uri == null || !uriLoader.handles(uri)) {
      return null;
    }

    Log.e("StringLoader","uriLoader="+uriLoader);

    //调用 uriLoader 的  buildLoadData 方法
    return uriLoader.buildLoadData(uri, width, height, options);
  }

  @Override
  public boolean handles(@NonNull String model) {
    // Avoid parsing the Uri twice and simply return null from buildLoadData if we don't handle this
    // particular Uri type.
    return true;
  }

  @Nullable
  private static Uri parseUri(String model) {
    Uri uri;
    if (TextUtils.isEmpty(model)) {
      return null;
    // See https://pmd.github.io/pmd-6.0.0/pmd_rules_java_performance.html#simplifystartswith
    } else if (model.charAt(0) == '/') {
      uri = toFileUri(model);
    } else {
      uri = Uri.parse(model);
      String scheme = uri.getScheme();
      if (scheme == null) {
        uri = toFileUri(model);
      }
    }
    return uri;
  }

  private static Uri toFileUri(String path) {
    return Uri.fromFile(new File(path));
  }

  /**
   * Factory for loading {@link InputStream}s from Strings.
   *
   * 用于处理流的
   */
  public static class StreamFactory implements ModelLoaderFactory<String, InputStream> {

    @NonNull
    @Override
    public ModelLoader<String, InputStream> build(
        @NonNull MultiModelLoaderFactory multiFactory) {
      return new StringLoader<>(multiFactory.build(Uri.class, InputStream.class));
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }

  /**
   * Factory for loading {@link ParcelFileDescriptor}s from Strings.
   *
   * 用于处理 文件的
   */
  public static class FileDescriptorFactory
      implements ModelLoaderFactory<String, ParcelFileDescriptor> {

    @NonNull
    @Override
    public ModelLoader<String, ParcelFileDescriptor> build(
        @NonNull MultiModelLoaderFactory multiFactory) {
      return new StringLoader<>(multiFactory.build(Uri.class, ParcelFileDescriptor.class));
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }

  /**
   * Loads {@link AssetFileDescriptor}s from Strings.
   *
   * 用于处理 asset file的
   */
  public static final class AssetFileDescriptorFactory
      implements ModelLoaderFactory<String, AssetFileDescriptor> {

    @Override
    public ModelLoader<String, AssetFileDescriptor> build(
        @NonNull MultiModelLoaderFactory multiFactory) {
      return new StringLoader<>(multiFactory.build(Uri.class, AssetFileDescriptor.class));
    }

    @Override
    public void teardown() {
      // Do nothing.
    }
  }
}
