package com.bumptech.glide.module;

import android.content.Context;
import android.support.annotation.NonNull;
import com.bumptech.glide.GlideBuilder;

/**
 * Defines a set of dependencies and options to use when initializing Glide within an application.
 *
 * <p>There can be at most one {@link AppGlideModule} in an application. Only Applications
 * can include a {@link AppGlideModule}. Libraries must use {@link LibraryGlideModule}.
 *
 * <p>Classes that extend {@link AppGlideModule} must be annotated with
 * {@link com.bumptech.glide.annotation.GlideModule} to be processed correctly.
 *
 * <p>Classes that extend {@link AppGlideModule} can optionally be annotated with
 * {@link com.bumptech.glide.annotation.Excludes} to optionally exclude one or more
 * {@link LibraryGlideModule} and/or {@link GlideModule} classes.
 *
 * <p>Once an application has migrated itself and all libraries it depends on to use Glide's
 * annotation processor, {@link AppGlideModule} implementations should override
 * {@link #isManifestParsingEnabled()} and return {@code false}.
 */
// Used only in javadoc.
@SuppressWarnings("deprecation")
public abstract class AppGlideModule extends LibraryGlideModule implements AppliesOptions {
  /**
   * Returns {@code true} if Glide should check the AndroidManifest for {@link GlideModule}s.
   *
   * <p>Implementations should return {@code false} after they and their dependencies have migrated
   * to Glide's annotation processor.
   *
   * <p>Returns {@code true} by default.
   *
   * 表示是否需要解析 manifest中配置的  GlideModules ，是为了兼容 3.0的 Glide'
   *
   * 可以在配置的时候关闭这个功能
   */
  public boolean isManifestParsingEnabled() {
    return true;
  }

  /**
   * 更改配置用
   */
  @Override
  public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
    // Default empty impl.
  }
}
