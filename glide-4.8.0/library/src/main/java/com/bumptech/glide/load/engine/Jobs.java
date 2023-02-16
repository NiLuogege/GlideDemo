package com.bumptech.glide.load.engine;

import android.support.annotation.VisibleForTesting;
import com.bumptech.glide.load.Key;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class Jobs {
  private final Map<Key, EngineJob<?>> jobs = new HashMap<>();
  private final Map<Key, EngineJob<?>> onlyCacheJobs = new HashMap<>();

  @VisibleForTesting
  Map<Key, EngineJob<?>> getAll() {
    return Collections.unmodifiableMap(jobs);
  }

  EngineJob<?> get(Key key, boolean onlyRetrieveFromCache) {
    return getJobMap(onlyRetrieveFromCache).get(key);
  }

  void put(Key key, EngineJob<?> job) {
    //进行缓存 ，expected.onlyRetrieveFromCache() 默认为 false ,所以一般缓存到 jobs 中
    getJobMap(job.onlyRetrieveFromCache()).put(key, job);
  }

  void removeIfCurrent(Key key, EngineJob<?> expected) {
    //expected.onlyRetrieveFromCache() 默认为 false ,所以 getJobMap 返回为 jobs
    Map<Key, EngineJob<?>> jobMap = getJobMap(expected.onlyRetrieveFromCache());
    //如果有相同的key 进行 remove
    if (expected.equals(jobMap.get(key))) {
      jobMap.remove(key);
    }
  }

  private Map<Key, EngineJob<?>> getJobMap(boolean onlyRetrieveFromCache) {
    return onlyRetrieveFromCache ? onlyCacheJobs : jobs;
  }
}
