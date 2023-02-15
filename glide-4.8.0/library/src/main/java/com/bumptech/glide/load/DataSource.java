package com.bumptech.glide.load;

/**
 * Indicates the origin of some retrieved data.
 */
public enum DataSource {
  /**
   * Indicates data was probably retrieved locally from the device, although it may have been
   * obtained through a content provider that may have obtained the data from a remote source.
   * 数据源为 本机，应该指的是 资源文件或者sd卡中的文件
   */
  LOCAL,
  /**
   * Indicates data was retrieved from a remote source other than the device.
   * 数据源为 服务器，也就是网络
   */
  REMOTE,
  /**
   * Indicates data was retrieved unmodified from the on device cache.
   * 数据源为 原始的（未经过变换后的） 磁盘缓存
   */
  DATA_DISK_CACHE,
  /**
   * Indicates data was retrieved from modified content in the on device cache.
   * 数据源为 变换后的 磁盘缓存
   */
  RESOURCE_DISK_CACHE,
  /**
   * Indicates data was retrieved from the in memory cache.
   * 数据源为 内存缓存
   */
  MEMORY_CACHE,
}
