/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.launcher.configuration;

import java.util.Map;

/**
 * An immutable view of the properties that are available prior to calculating the build layout. That is, the properties that are
 * defined on the command-line and in the system properties of the current JVM.
 */
public interface InitialProperties {
    /**
     * Returns the system properties defined as command-line options.
     * 返回定义在命令行选项的JVM系统参数
     */
    Map<String, String> getRequestedSystemProperties();
}
