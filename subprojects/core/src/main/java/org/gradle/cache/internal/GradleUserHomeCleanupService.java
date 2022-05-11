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

import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.util.GUtil;

import java.io.File;
import java.util.Properties;

/**
 * GradleUserHome清除服务类
 */
public class GradleUserHomeCleanupService implements Stoppable {

    private static final long MAX_UNUSED_DAYS_FOR_RELEASES = 30;
    private static final long MAX_UNUSED_DAYS_FOR_SNAPSHOTS = 7;
    private static final String CACHE_CLEANUP_PROPERTY = "org.gradle.cache.cleanup";

    private final Deleter deleter;
    private final GradleUserHomeDirProvider userHomeDirProvider;
    private final CacheScopeMapping cacheScopeMapping;
    private final UsedGradleVersions usedGradleVersions;
    private final ProgressLoggerFactory progressLoggerFactory;

    public GradleUserHomeCleanupService(
        Deleter deleter,
        GradleUserHomeDirProvider userHomeDirProvider,
        CacheScopeMapping cacheScopeMapping,
        UsedGradleVersions usedGradleVersions,
        ProgressLoggerFactory progressLoggerFactory
    ) {
        this.deleter = deleter;
        this.userHomeDirProvider = userHomeDirProvider;
        this.cacheScopeMapping = cacheScopeMapping;
        this.usedGradleVersions = usedGradleVersions;
        this.progressLoggerFactory = progressLoggerFactory;
    }

    @Override
    public void stop() {
        // 找到当前运行的gradle用户家目录(默认: ~/.gradle)下的gradle.properties,
        // 如该该文件存在并且存在属性org.gradle.cache.cleanup=false 则不清理缓存

        // TODO Will be implemented without hard-coded access to `$GRADLE_USER_HOME/gradle.properties` for 5.1 in #6084
        File gradleUserHomeDirectory = userHomeDirProvider.getGradleUserHomeDirectory();
        File gradleProperties = new File(gradleUserHomeDirectory, "gradle.properties");
        if (gradleProperties.isFile()) {
            Properties properties = GUtil.loadProperties(gradleProperties);
            String cleanup = properties.getProperty(CACHE_CLEANUP_PROPERTY);
            if (cleanup != null && cleanup.equals("false")) {
                return;
            }
        }
        File cacheBaseDir = cacheScopeMapping.getRootDirectory(null);
        boolean wasCleanedUp = execute(
            // 发行版本30天未使用删除,快照版本7天未使用输出
            new org.gradle.cache.internal.VersionSpecificCacheCleanupAction(cacheBaseDir, MAX_UNUSED_DAYS_FOR_RELEASES, MAX_UNUSED_DAYS_FOR_SNAPSHOTS, deleter));
        // 如果指定版本缓存目录(eg: .gradle/caches/5.6.4)被清除,
        // 那包装器中相应的gradle版本(eg: .gradle/wrapper/dists/gradle-5.6.4-bin)也会被清除
        if (wasCleanedUp) {
            execute(new org.gradle.cache.internal.WrapperDistributionCleanupAction(gradleUserHomeDirectory, usedGradleVersions));
        }
    }

    private boolean execute(org.gradle.cache.internal.DirectoryCleanupAction action) {
        ProgressLogger progressLogger = startNewOperation(action.getClass(), action.getDisplayName());
        try {
            return action.execute(new DefaultCleanupProgressMonitor(progressLogger));
        } finally {
            progressLogger.completed();
        }
    }

    private ProgressLogger startNewOperation(Class<?> loggerClass, String description) {
        return progressLoggerFactory.newOperation(loggerClass).start(description, description);
    }
}
