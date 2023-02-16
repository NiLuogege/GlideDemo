package com.bumptech.glide.request.target;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.widget.ImageView;
import com.bumptech.glide.request.transition.Transition;

/**
 * A base {@link com.bumptech.glide.request.target.Target} for displaying resources in {@link
 * android.widget.ImageView}s.
 *
 * @param <Z> The type of resource that this target will display in the wrapped {@link
 *            android.widget.ImageView}.
 */
// Public API.
@SuppressWarnings("WeakerAccess")
public abstract class ImageViewTarget<Z> extends ViewTarget<ImageView, Z>
    implements Transition.ViewAdapter {

  @Nullable
  private Animatable animatable;

  public ImageViewTarget(ImageView view) {
    super(view);
  }

  /**
   * @deprecated Use {@link #waitForLayout()} instead.
   */
  @SuppressWarnings({"deprecation"})
  @Deprecated
  public ImageViewTarget(ImageView view, boolean waitForLayout) {
    super(view, waitForLayout);
  }

  /**
   * Returns the current {@link android.graphics.drawable.Drawable} being displayed in the view
   * using {@link android.widget.ImageView#getDrawable()}.
   */
  @Override
  @Nullable
  public Drawable getCurrentDrawable() {
    return view.getDrawable();
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param drawable {@inheritDoc}
   */
  @Override
  public void setDrawable(Drawable drawable) {
    view.setImageDrawable(drawable);
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param placeholder {@inheritDoc}
   */
  @Override
  public void onLoadStarted(@Nullable Drawable placeholder) {
    super.onLoadStarted(placeholder);
    setResourceInternal(null);
    setDrawable(placeholder);
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param errorDrawable {@inheritDoc}
   */
  @Override
  public void onLoadFailed(@Nullable Drawable errorDrawable) {
    super.onLoadFailed(errorDrawable);
    setResourceInternal(null);
    setDrawable(errorDrawable);
  }

  /**
   * Sets the given {@link android.graphics.drawable.Drawable} on the view using {@link
   * android.widget.ImageView#setImageDrawable(android.graphics.drawable.Drawable)}.
   *
   * @param placeholder {@inheritDoc}
   */
  @Override
  public void onLoadCleared(@Nullable Drawable placeholder) {
    super.onLoadCleared(placeholder);
    if (animatable != null) {
      animatable.stop();
    }
    setResourceInternal(null);
    setDrawable(placeholder);
  }

  @Override
  public void onResourceReady(
      @NonNull Z resource,//对于加载网络图片来说 一般为 BitmapDrawable ，里面包含了具体的Bitmap对象
      @Nullable Transition<? super Z> transition//一般为 NoTransition
  ) {
    //NoTransition 的 transition 返回false ，所以会浸入这个分支
    if (transition == null || !transition.transition(resource, this)) {
      //设置资源啦
      setResourceInternal(resource);
    } else {
      maybeUpdateAnimatable(resource);
    }
  }

  @Override
  public void onStart() {
    if (animatable != null) {
      animatable.start();
    }
  }

  @Override
  public void onStop() {
    if (animatable != null) {
      animatable.stop();
    }
  }

  private void setResourceInternal(@Nullable Z resource//对于加载网络图片来说 一般为 BitmapDrawable ，里面包含了具体的Bitmap对象
  ) {
    // Order matters here. Set the resource first to make sure that the Drawable has a valid and
    // non-null Callback before starting it.
    //调用子类的 setResource 方法
    setResource(resource);
    //默认没有动画，不走这个
    maybeUpdateAnimatable(resource);
  }

  private void maybeUpdateAnimatable(@Nullable Z resource) {
    if (resource instanceof Animatable) {
      animatable = (Animatable) resource;
      animatable.start();
    } else {
      animatable = null;
    }
  }

  protected abstract void setResource(@Nullable Z resource);
}

