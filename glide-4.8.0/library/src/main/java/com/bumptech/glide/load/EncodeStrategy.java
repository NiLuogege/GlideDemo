package com.bumptech.glide.load;

/**
 * Details how an {@link com.bumptech.glide.load.ResourceEncoder} will encode a resource to cache.
 */
public enum EncodeStrategy {
  /**
   * Writes the original unmodified data for the resource to disk, not include downsampling or
   * transformations.
   *
   * 将原始图片 谢如磁盘
   */
  SOURCE,

  /**
   * Writes the decoded, downsampled and transformed data for the resource to disk.
   * //将转换后的 图片 写入磁盘
   */
  TRANSFORMED,

  /**
   * Will write no data.
   */
  NONE,
}
