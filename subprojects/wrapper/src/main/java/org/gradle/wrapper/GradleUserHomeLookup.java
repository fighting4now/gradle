/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.wrapper;

import java.io.File;

public class GradleUserHomeLookup {
    // 默认gradle的家目录为 用户目录/.gradle   ~/.gradle
    public static final String DEFAULT_GRADLE_USER_HOME = System.getProperty("user.home") + "/.gradle";
    public static final String GRADLE_USER_HOME_PROPERTY_KEY = "gradle.user.home";
    public static final String GRADLE_USER_HOME_ENV_KEY = "GRADLE_USER_HOME";

    /**
     * 获取gradle的家目录
     * @return
     */
    public static File gradleUserHome() {
        // 先从JVM环境变量中找gradle.user.home,找到就返回    启动JVM时,可以通过 -D 设置JVM 环境变量值
        // 没有找到再从系统环境变量中找GRADLE_USER_HOME(区分大小写),同样找到就返回
        // 如果还没有找到就用默认值 ~/.gradle

        String gradleUserHome;
        if ((gradleUserHome = System.getProperty(GRADLE_USER_HOME_PROPERTY_KEY)) != null) {
            return new File(gradleUserHome);
        }
        if ((gradleUserHome = System.getenv(GRADLE_USER_HOME_ENV_KEY)) != null) {
            return new File(gradleUserHome);
        }
        return new File(DEFAULT_GRADLE_USER_HOME);
    }
}
