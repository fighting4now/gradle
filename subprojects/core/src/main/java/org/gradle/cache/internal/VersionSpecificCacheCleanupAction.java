/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.cache.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;
import org.gradle.api.internal.changedetection.state.CrossBuildFileHashCache;
import org.gradle.api.specs.Spec;
import org.gradle.cache.CleanupProgressMonitor;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.gradle.util.GFileUtils;
import org.gradle.util.GradleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;

/**
 * 清除指定版本缓存
 */
public class VersionSpecificCacheCleanupAction implements org.gradle.cache.internal.DirectoryCleanupAction {
    private final static String FILE_HASHES_CACHE_KEY =  CrossBuildFileHashCache.Kind.FILE_HASHES.getCacheId();

    // eg: fileHashes/fileHashes.lock
    @VisibleForTesting static final String MARKER_FILE_PATH = FILE_HASHES_CACHE_KEY + "/" + FILE_HASHES_CACHE_KEY + ".lock";
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionSpecificCacheCleanupAction.class);
    // 每隔24小时检查指定版本缓存目录
    private static final long CLEANUP_INTERVAL_IN_HOURS = 24;

    private final org.gradle.cache.internal.VersionSpecificCacheDirectoryScanner versionSpecificCacheDirectoryScanner;
    private final long maxUnusedDaysForReleases;
    private final long maxUnusedDaysForSnapshots;
    private final Deleter deleter;

    public VersionSpecificCacheCleanupAction(File cacheBaseDir, long maxUnusedDaysForReleasesAndSnapshots, Deleter deleter) {
        this(cacheBaseDir, maxUnusedDaysForReleasesAndSnapshots, maxUnusedDaysForReleasesAndSnapshots, deleter);
    }

    public VersionSpecificCacheCleanupAction(File cacheBaseDir, long maxUnusedDaysForReleases, long maxUnusedDaysForSnapshots, Deleter deleter) {
        this.deleter = deleter;
        Preconditions.checkArgument(maxUnusedDaysForReleases >= maxUnusedDaysForSnapshots,
            "maxUnusedDaysForReleases (%s) must be greater than or equal to maxUnusedDaysForSnapshots (%s)", maxUnusedDaysForReleases, maxUnusedDaysForSnapshots);
        this.versionSpecificCacheDirectoryScanner = new org.gradle.cache.internal.VersionSpecificCacheDirectoryScanner(cacheBaseDir);
        this.maxUnusedDaysForReleases = maxUnusedDaysForReleases;
        this.maxUnusedDaysForSnapshots = maxUnusedDaysForSnapshots;
    }

    @Override
    @Nonnull
    public String getDisplayName() {
        return "Deleting unused version-specific caches in " + versionSpecificCacheDirectoryScanner.getBaseDir();
    }

    @Override
    public boolean execute(@Nonnull CleanupProgressMonitor progressMonitor) {
        if (requiresCleanup()) {
            Timer timer = Time.startTimer();
            performCleanup(progressMonitor);
            LOGGER.debug("Processed version-specific caches at {} for cleanup in {}", versionSpecificCacheDirectoryScanner.getBaseDir(), timer.getElapsed());
            return true;
        }
        return false;
    }

    /**
     * 根据指定版本目录(eg: .gradle/caches/5.6.4)下的gc.properties文件的上一次修改时间与当前时间作比较,
     *  两者的时间差大于24小时的就需要执行一次清除,相当于每24小时就需要执行一次清除检查
     * @return
     */
    private boolean requiresCleanup() {
        File gcFile = getGcFile();
        if (!gcFile.exists()) {
            return gcFile.getParentFile().exists();
        }
        long duration = System.currentTimeMillis() - gcFile.lastModified();
        long timeInHours = TimeUnit.MILLISECONDS.toHours(duration);
        return timeInHours >= CLEANUP_INTERVAL_IN_HOURS;
    }

    /**
     * 标记清除
     */
    private void markCleanedUp() {
        GFileUtils.touch(getGcFile());
    }

    private File getGcFile() {
        File currentVersionCacheDir = versionSpecificCacheDirectoryScanner.getDirectory(GradleVersion.current());
        return new File(currentVersionCacheDir, "gc.properties");
    }

    /**
     * 执行清除
     * @param progressMonitor
     */
    private void performCleanup(CleanupProgressMonitor progressMonitor) {
        MinimumTimestampProvider minimumTimestampProvider = new MinimumTimestampProvider();
        SortedSetMultimap<GradleVersion, org.gradle.cache.internal.VersionSpecificCacheDirectory> cacheDirsByBaseVersion = scanForVersionSpecificCacheDirs();
        for (GradleVersion baseVersion : cacheDirsByBaseVersion.keySet()) {
            performCleanup(cacheDirsByBaseVersion.get(baseVersion), minimumTimestampProvider, progressMonitor);
        }
        markCleanedUp();
    }

    private SortedSetMultimap<GradleVersion, org.gradle.cache.internal.VersionSpecificCacheDirectory> scanForVersionSpecificCacheDirs() {
        SortedSetMultimap<GradleVersion, org.gradle.cache.internal.VersionSpecificCacheDirectory> cacheDirsByBaseVersion = TreeMultimap.create();
        for (org.gradle.cache.internal.VersionSpecificCacheDirectory cacheDir : versionSpecificCacheDirectoryScanner.getExistingDirectories()) {
            cacheDirsByBaseVersion.put(cacheDir.getVersion().getBaseVersion(), cacheDir);
        }
        return cacheDirsByBaseVersion;
    }

    /**
     * 真正执行删除指定版本缓存目录
     * @param cacheDirsWithSameBaseVersion
     * @param minimumTimestampProvider
     * @param progressMonitor
     */
    private void performCleanup(SortedSet<org.gradle.cache.internal.VersionSpecificCacheDirectory> cacheDirsWithSameBaseVersion, MinimumTimestampProvider minimumTimestampProvider, CleanupProgressMonitor progressMonitor) {
        Spec<org.gradle.cache.internal.VersionSpecificCacheDirectory> cleanupCondition = new CleanupCondition(cacheDirsWithSameBaseVersion, minimumTimestampProvider);
        for (org.gradle.cache.internal.VersionSpecificCacheDirectory cacheDir : cacheDirsWithSameBaseVersion) {
            // 如果满足删除条件
            if (cleanupCondition.isSatisfiedBy(cacheDir)) {
                progressMonitor.incrementDeleted();
                try {
                    deleteCacheDir(cacheDir.getDir());
                } catch (Exception e) {
                    LOGGER.error("Failed to process/clean up version-specific cache directory: {}", cacheDir.getDir(), e);
                }
            } else {
                progressMonitor.incrementSkipped();
            }
        }
    }

    private void deleteCacheDir(File cacheDir) throws IOException {
        LOGGER.debug("Deleting version-specific cache directory at {}", cacheDir);
        // 递归删除指定版本缓存目录
        deleter.deleteRecursively(cacheDir);
    }

    /**
     * 清除条件
     */
    private static class CleanupCondition implements Spec<org.gradle.cache.internal.VersionSpecificCacheDirectory> {
        private final SortedSet<org.gradle.cache.internal.VersionSpecificCacheDirectory> cacheDirsWithSameBaseVersion;
        private final MinimumTimestampProvider minimumTimestampProvider;

        CleanupCondition(SortedSet<org.gradle.cache.internal.VersionSpecificCacheDirectory> cacheDirsWithSameBaseVersion, MinimumTimestampProvider minimumTimestampProvider) {
            this.cacheDirsWithSameBaseVersion = cacheDirsWithSameBaseVersion;
            this.minimumTimestampProvider = minimumTimestampProvider;
        }

        @Override
        public boolean isSatisfiedBy(org.gradle.cache.internal.VersionSpecificCacheDirectory cacheDir) {
            // 如果指定版本缓存目录的gradle版本大于当前运行的gradle版本,则不满足

            // 满足清除的条件
            // 如果指定版本缓存目录下的fileHashes/fileHashes.lock文件存在,
            // 并且该文件的最近修改时间与当前时间如果相差大于指定的天数(发行版本30天,快照版本7天)就满足删除的条件

            if (cacheDir.getVersion().compareTo(GradleVersion.current()) >= 0) {
                return false;
            }
            File markerFile = new File(cacheDir.getDir(), MARKER_FILE_PATH);
            return markerFile.exists() && markerFileHasNotBeenTouchedRecently(cacheDir, markerFile);
        }

        private boolean markerFileHasNotBeenTouchedRecently(org.gradle.cache.internal.VersionSpecificCacheDirectory cacheDir, File markerFile) {
            if (markerFile.lastModified() < minimumTimestampProvider.forReleases()) {
                return true;
            }
            if (cacheDir.getVersion().isSnapshot() && markerFile.lastModified() < minimumTimestampProvider.forSnapshots()) {
                return cacheDirsWithSameBaseVersion.tailSet(cacheDir).size() > 1;
            }
            return false;
        }
    }

    private class MinimumTimestampProvider {
        private final long minimumReleaseTimestamp;
        private final long minimumSnapshotTimestamp;

        MinimumTimestampProvider() {
            long startTime = System.currentTimeMillis();
            // 计算30天前的时间戳
            this.minimumReleaseTimestamp = compute(startTime, maxUnusedDaysForReleases);
            //// 计算7天前的时间戳
            this.minimumSnapshotTimestamp = compute(startTime, maxUnusedDaysForSnapshots);
        }

        private long compute(long startTime, long maxUnusedDays) {
            return Math.max(0, startTime - TimeUnit.DAYS.toMillis(maxUnusedDays));
        }

        long forReleases() {
            return minimumReleaseTimestamp;
        }

        long forSnapshots() {
            return minimumSnapshotTimestamp;
        }
    }
}
