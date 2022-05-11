/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.tasks.wrapper;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.plugins.StartScriptGenerator;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.api.tasks.options.OptionValues;
import org.gradle.internal.util.PropertiesUtils;
import org.gradle.util.DistributionLocator;
import org.gradle.util.GradleVersion;
import org.gradle.util.WrapUtil;
import org.gradle.wrapper.GradleWrapperMain;
import org.gradle.wrapper.Install;
import org.gradle.wrapper.WrapperExecutor;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * <p>Generates scripts (for *nix and windows) which allow you to build your project with Gradle, without having to
 * install Gradle.
 *
 * <p>When a user executes a wrapper script the first time, the script downloads and installs the appropriate Gradle
 * distribution and runs the build against this downloaded distribution. Any installed Gradle distribution is ignored
 * when using the wrapper scripts.
 *
 * <p>The scripts generated by this task are intended to be committed to your version control system. This task also
 * generates a small {@code gradle-wrapper.jar} bootstrap JAR file and properties file which should also be committed to
 * your VCS. The scripts delegates to this JAR.
 *
 * lxy: 不用安装Gradle  命令gradle wrapper  Gradle包装器生成
 */
public class Wrapper extends DefaultTask {
    public static final String DEFAULT_DISTRIBUTION_PARENT_NAME = Install.DEFAULT_DISTRIBUTION_PATH;

    /**
     * Specifies the Gradle distribution type.
     */
    public enum DistributionType {
        /**
         * binary-only Gradle distribution without sources and documentation
         */
        BIN,
        /**
         * complete Gradle distribution with binaries, sources and documentation
         */
        ALL
    }

    /**
     * Specifies how the wrapper path should be interpreted.
     */
    public enum PathBase {
        PROJECT, GRADLE_USER_HOME
    }

    private Object scriptFile;
    private Object jarFile;
    private String distributionPath;
    private PathBase distributionBase = PathBase.GRADLE_USER_HOME;
    private String distributionUrl;
    private String distributionSha256Sum;
    private GradleVersion gradleVersion;
    private DistributionType distributionType = DistributionType.BIN;
    private String archivePath;
    private PathBase archiveBase = PathBase.GRADLE_USER_HOME;
    private final DistributionLocator locator = new DistributionLocator();

    public Wrapper() {
        scriptFile = "gradlew";
        jarFile = "gradle/wrapper/gradle-wrapper.jar";
        // 默认为 wrapper/dists
        distributionPath = DEFAULT_DISTRIBUTION_PARENT_NAME;
        archivePath = DEFAULT_DISTRIBUTION_PARENT_NAME;
        gradleVersion = GradleVersion.current();
    }

    @Inject
    protected FileLookup getFileLookup() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    void generate() {
        File jarFileDestination = getJarFile();
        File unixScript = getScriptFile();
        FileResolver resolver = getFileLookup().getFileResolver(unixScript.getParentFile());
        String jarFileRelativePath = resolver.resolveAsRelativePath(jarFileDestination);
        // 生成 wrapper/gradle-wrapper.properties
        writeProperties(getPropertiesFile());
        // 生成 wrapper/gradle-wrapper.jar
        writeWrapperTo(jarFileDestination);

        StartScriptGenerator generator = new StartScriptGenerator();
        generator.setApplicationName("Gradle");
        generator.setMainClassName(GradleWrapperMain.class.getName());
        generator.setClasspath(WrapUtil.toList(jarFileRelativePath));
        generator.setOptsEnvironmentVar("GRADLE_OPTS");
        generator.setExitEnvironmentVar("GRADLE_EXIT_CONSOLE");
        generator.setAppNameSystemProperty("org.gradle.appname");
        generator.setScriptRelPath(unixScript.getName());
        generator.setDefaultJvmOpts(ImmutableList.of("-Xmx64m", "-Xms64m"));
        generator.generateUnixScript(unixScript);
        generator.generateWindowsScript(getBatchScript());
    }

    private void writeWrapperTo(File destination) {
        URL jarFileSource = Wrapper.class.getResource("/gradle-wrapper.jar");
        if (jarFileSource == null) {
            throw new GradleException("Cannot locate wrapper JAR resource.");
        }
        try (InputStream in = jarFileSource.openStream(); OutputStream out = new FileOutputStream(destination)) {
            ByteStreams.copy(in, out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write wrapper JAR to " + destination, e);
        }
    }

    /**
     * 将属性信息写入wrapper/gradle-wrapper.properties文件中
     * @param propertiesFileDestination
     */
    private void writeProperties(File propertiesFileDestination) {
        Properties wrapperProperties = new Properties();
        wrapperProperties.put(WrapperExecutor.DISTRIBUTION_URL_PROPERTY, getDistributionUrl());
        if (distributionSha256Sum != null) {
            wrapperProperties.put(WrapperExecutor.DISTRIBUTION_SHA_256_SUM, distributionSha256Sum);
        }
        wrapperProperties.put(WrapperExecutor.DISTRIBUTION_BASE_PROPERTY, distributionBase.toString());
        wrapperProperties.put(WrapperExecutor.DISTRIBUTION_PATH_PROPERTY, distributionPath);
        wrapperProperties.put(WrapperExecutor.ZIP_STORE_BASE_PROPERTY, archiveBase.toString());
        wrapperProperties.put(WrapperExecutor.ZIP_STORE_PATH_PROPERTY, archivePath);
        try {
            PropertiesUtils.store(wrapperProperties, propertiesFileDestination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the file to write the wrapper script to.
     */
    @OutputFile
    public File getScriptFile() {
        return getServices().get(FileOperations.class).file(scriptFile);
    }

    /**
     * The file to write the wrapper script to.
     *
     * @since 4.0
     */
    public void setScriptFile(File scriptFile) {
        this.scriptFile = scriptFile;
    }

    /**
     * The file to write the wrapper script to.
     */
    public void setScriptFile(Object scriptFile) {
        this.scriptFile = scriptFile;
    }

    /**
     * Returns the file to write the wrapper batch script to.
     */
    @OutputFile
    public File getBatchScript() {
        File scriptFile = getScriptFile();
        return new File(scriptFile.getParentFile(), scriptFile.getName().replaceFirst("(\\.[^\\.]+)?$", ".bat"));
    }

    /**
     * Returns the file to write the wrapper jar file to.
     */
    @OutputFile
    public File getJarFile() {
        return getServices().get(FileOperations.class).file(jarFile);
    }

    /**
     * The file to write the wrapper jar file to.
     *
     * @since 4.0
     */
    public void setJarFile(File jarFile) {
        this.jarFile = jarFile;
    }

    /**
     * The file to write the wrapper jar file to.
     */
    public void setJarFile(Object jarFile) {
        this.jarFile = jarFile;
    }

    /**
     * Returns the file to write the wrapper properties to.
     */
    @OutputFile
    public File getPropertiesFile() {
        File jarFileDestination = getJarFile();
        return new File(jarFileDestination.getParentFile(), jarFileDestination.getName().replaceAll("\\.jar$",
            ".properties"));
    }

    /**
     * Returns the path where the gradle distributions needed by the wrapper are unzipped. The path is relative to the
     * distribution base directory
     *
     * @see #setDistributionPath(String)
     */
    @Input
    public String getDistributionPath() {
        return distributionPath;
    }

    /**
     * Sets the path where the gradle distributions needed by the wrapper are unzipped. The path is relative to the
     * distribution base directory
     *
     * @see #setDistributionPath(String)
     */
    public void setDistributionPath(String distributionPath) {
        this.distributionPath = distributionPath;
    }

    /**
     * Returns the gradle version for the wrapper.
     *
     * @see #setGradleVersion(String)
     */
    @Input
    public String getGradleVersion() {
        return gradleVersion.getVersion();
    }

    /**
     * The version of the gradle distribution required by the wrapper. This is usually the same version of Gradle you
     * use for building your project.
     */
    @Option(option = "gradle-version", description = "The version of the Gradle distribution required by the wrapper.")
    public void setGradleVersion(String gradleVersion) {
        this.gradleVersion = GradleVersion.version(gradleVersion);
    }

    /**
     * Returns the type of the Gradle distribution to be used by the wrapper.
     *
     * @see #setDistributionType(DistributionType)
     */
    @Input
    public DistributionType getDistributionType() {
        return distributionType;
    }

    /**
     * The type of the Gradle distribution to be used by the wrapper. By default, this is {@link DistributionType#BIN},
     * which is the binary-only Gradle distribution without documentation.
     *
     * @see DistributionType
     */
    @Option(option = "distribution-type", description = "The type of the Gradle distribution to be used by the wrapper.")
    public void setDistributionType(DistributionType distributionType) {
        this.distributionType = distributionType;
    }

    /**
     * The list of available gradle distribution types.
     */
    @OptionValues("distribution-type")
    public List<DistributionType> getAvailableDistributionTypes() {
        return Arrays.asList(DistributionType.values());
    }

    /**
     * The URL to download the gradle distribution from.
     *
     * <p>If not set, the download URL is the default for the specified {@link #getGradleVersion()}.
     *
     * <p>If {@link #getGradleVersion()} is not set, will return null.
     *
     * <p>The wrapper downloads a certain distribution only once and caches it. If your distribution base is the
     * project, you might submit the distribution to your version control system. That way no download is necessary at
     * all. This might be in particular interesting, if you provide a custom gradle snapshot to the wrapper, because you
     * don't need to provide a download server then.
     */
    @Input
    public String getDistributionUrl() {
        if (distributionUrl != null) {
            return distributionUrl;
        } else if (gradleVersion != null) {
            return locator.getDistributionFor(gradleVersion, distributionType.name().toLowerCase(Locale.ENGLISH)).toString();
        } else {
            return null;
        }
    }

    /**
     * The URL to download the gradle distribution from.
     *
     * <p>If not set, the download URL is the default for the specified {@link #getGradleVersion()}.
     *
     * <p>If {@link #getGradleVersion()} is not set, will return null.
     *
     * <p>The wrapper downloads a certain distribution and caches it. If your distribution base is the
     * project, you might submit the distribution to your version control system. That way no download is necessary at
     * all. This might be in particular interesting, if you provide a custom gradle snapshot to the wrapper, because you
     * don't need to provide a download server then.
     */
    @Option(option = "gradle-distribution-url", description = "The URL to download the Gradle distribution from.")
    public void setDistributionUrl(String url) {
        this.distributionUrl = url;
    }

    /**
     * The SHA-256 hash sum of the gradle distribution.
     *
     * <p>If not set, the hash sum of the gradle distribution is not verified.
     *
     * <p>The wrapper allows for verification of the downloaded Gradle distribution via SHA-256 hash sum comparison.
     * This increases security against targeted attacks by preventing a man-in-the-middle attacker from tampering with
     * the downloaded Gradle distribution.
     *
     * @since 4.5
     */
    @Nullable
    @Optional
    @Input
    public String getDistributionSha256Sum() {
        return distributionSha256Sum;
    }

    /**
     * The SHA-256 hash sum of the gradle distribution.
     *
     * <p>If not set, the hash sum of the gradle distribution is not verified.
     *
     * <p>The wrapper allows for verification of the downloaded Gradle distribution via SHA-256 hash sum comparison.
     * This increases security against targeted attacks by preventing a man-in-the-middle attacker from tampering with
     * the downloaded Gradle distribution.
     *
     * @since 4.5
     */
    @Option(option = "gradle-distribution-sha256-sum", description = "The SHA-256 hash sum of the gradle distribution.")
    public void setDistributionSha256Sum(@Nullable String distributionSha256Sum) {
        this.distributionSha256Sum = distributionSha256Sum;
    }

    /**
     * The distribution base specifies whether the unpacked wrapper distribution should be stored in the project or in
     * the gradle user home dir.
     */
    @Input
    public PathBase getDistributionBase() {
        return distributionBase;
    }

    /**
     * The distribution base specifies whether the unpacked wrapper distribution should be stored in the project or in
     * the gradle user home dir.
     */
    public void setDistributionBase(PathBase distributionBase) {
        this.distributionBase = distributionBase;
    }

    /**
     * Returns the path where the gradle distributions archive should be saved (i.e. the parent dir). The path is
     * relative to the archive base directory.
     */
    @Input
    public String getArchivePath() {
        return archivePath;
    }

    /**
     * Set's the path where the gradle distributions archive should be saved (i.e. the parent dir). The path is relative
     * to the parent dir specified with {@link #getArchiveBase()}.
     */
    public void setArchivePath(String archivePath) {
        this.archivePath = archivePath;
    }

    /**
     * The archive base specifies whether the unpacked wrapper distribution should be stored in the project or in the
     * gradle user home dir.
     */
    @Input
    public PathBase getArchiveBase() {
        return archiveBase;
    }

    /**
     * The archive base specifies whether the unpacked wrapper distribution should be stored in the project or in the
     * gradle user home dir.
     */
    public void setArchiveBase(PathBase archiveBase) {
        this.archiveBase = archiveBase;
    }
}
