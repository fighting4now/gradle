/*
 * Copyright 2007 the original author or authors.
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

import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.cli.SystemPropertiesCommandLineConverter;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

import static org.gradle.wrapper.Download.UNKNOWN_VERSION;

/**
 *  gradle/wrapper/gradle-wrapper.jar的所有源代码都在该父目录下
 */

public class GradleWrapperMain {
    // 参数: -g 指定gradle的家目录
    public static final String GRADLE_USER_HOME_OPTION = "g";
    // gradle的家目录的默认值
    public static final String GRADLE_USER_HOME_DETAILED_OPTION = "gradle-user-home";
    public static final String GRADLE_QUIET_OPTION = "q";
    public static final String GRADLE_QUIET_DETAILED_OPTION = "quiet";

    public static void main(String[] args) throws Exception {
        File wrapperJar = wrapperJar();
        File propertiesFile = wrapperProperties(wrapperJar);
        File rootDir = rootDir(wrapperJar);

        CommandLineParser parser = new CommandLineParser();
        parser.allowUnknownOptions();
        parser.option(GRADLE_USER_HOME_OPTION, GRADLE_USER_HOME_DETAILED_OPTION).hasArgument();
        parser.option(GRADLE_QUIET_OPTION, GRADLE_QUIET_DETAILED_OPTION);

        SystemPropertiesCommandLineConverter converter = new SystemPropertiesCommandLineConverter();
        converter.configure(parser);

        ParsedCommandLine options = parser.parse(args);

        Properties systemProperties = System.getProperties();
        systemProperties.putAll(converter.convert(options, new HashMap<String, String>()));

        File gradleUserHome = gradleUserHome(options);

        addSystemProperties(gradleUserHome, rootDir);

        Logger logger = logger(options);
        // 从 gradle/wrapper/gradle-wrapper.properties中读取配置属性
        org.gradle.wrapper.WrapperExecutor wrapperExecutor = org.gradle.wrapper.WrapperExecutor.forWrapperPropertiesFile(propertiesFile);
        wrapperExecutor.execute(
                args,
                new org.gradle.wrapper.Install(logger, new org.gradle.wrapper.Download(logger, "gradlew", UNKNOWN_VERSION), new org.gradle.wrapper.PathAssembler(gradleUserHome)),
                new org.gradle.wrapper.BootstrapMainStarter());
    }

    /**
     * 添加JVM系统属性
     * @param gradleHome
     * @param rootDir
     */
    private static void addSystemProperties(File gradleHome, File rootDir) {
        // 先添加gradleHome中的gradle.properties中的所有JVM系统属性
        // 然后添加项目根目录下的gradle.properties中的所有JVM系统属性
        // 项目根目录下的gradle.properties中设置的JVM属性会覆盖gradleHome中的gradle.properties中设置JVM系统属性

        System.getProperties().putAll(org.gradle.wrapper.SystemPropertiesHandler.getSystemProperties(new File(gradleHome, "gradle.properties")));
        System.getProperties().putAll(org.gradle.wrapper.SystemPropertiesHandler.getSystemProperties(new File(rootDir, "gradle.properties")));
    }

    /**
     * 获取项目的根路径
     * @param wrapperJar   gradle/wrapper/gradle-wrapper.jar文件的绝对路径
     * @return
     */
    private static File rootDir(File wrapperJar) {
        // ${project.rootDir}/gradle/wrapper/gradle-wrapper.jar
        // ${project.rootDir}/gradle/wrapper/
        // ${project.rootDir}/gradle
        // ${project.rootDir}

        return wrapperJar.getParentFile().getParentFile().getParentFile();
    }

    /**
     *
     * @param wrapperJar  gradle/wrapper/gradle-wrapper.jar的路径
     * @return            gradle/wrapper/gradle-wrapper.properties的路径
     */
    private static File wrapperProperties(File wrapperJar) {
        return new File(wrapperJar.getParent(), wrapperJar.getName().replaceFirst("\\.jar$", ".properties"));
    }

    private static File wrapperJar() {
        URI location;
        try {
            // 运行jar包会得到当前jar包(gradle-wrapper.jar)的绝对路径
            location = GradleWrapperMain.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        // 是否是文件协议 file:///...
        if (!location.getScheme().equals("file")) {
            throw new RuntimeException(String.format("Cannot determine classpath for wrapper Jar from codebase '%s'.", location));
        }
        try {
            return Paths.get(location).toFile();
        } catch (NoClassDefFoundError e) {
            return new File(location.getPath());
        }
    }

    /**
     * 获取gradle家目录路径
     * @param options
     * @return
     */
    private static File gradleUserHome(ParsedCommandLine options) {
        // 如果命令行参数指定了gradle_user_home,就用命令行指定的
        if (options.hasOption(GRADLE_USER_HOME_OPTION)) {
            return new File(options.option(GRADLE_USER_HOME_OPTION).getValue());
        }
        // 命令行如果没有指定就用默认方式查找gradle_user_home
        return org.gradle.wrapper.GradleUserHomeLookup.gradleUserHome();
    }

    private static Logger logger(ParsedCommandLine options) {
        return new Logger(options.hasOption(GRADLE_QUIET_OPTION));
    }
}
