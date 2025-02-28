/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.jar;

import java.io.File;
import java.nio.file.FileSystems;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.util.DefaultFileSet;

/**
 * Base class for creating a jar from project classes.
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @version $Id$
 */
public abstract class AbstractJarMojo extends AbstractMojo {

    private static final String[] DEFAULT_EXCLUDES = new String[] {"**/package.html"};

    private static final String[] DEFAULT_INCLUDES = new String[] {"**/**"};

    private static final String MODULE_DESCRIPTOR_FILE_NAME = "module-info.class";

    private static final String SEPARATOR = FileSystems.getDefault().getSeparator();

    @Component
    private ToolchainsJdkSpecification toolchainsJdkSpecification;

    @Component
    private ToolchainManager toolchainManager;

    /**
     * List of files to include. Specified as fileset patterns which are relative to the input directory whose contents
     * is being packaged into the JAR.
     */
    @Parameter
    private String[] includes;

    /**
     * List of files to exclude. Specified as fileset patterns which are relative to the input directory whose contents
     * is being packaged into the JAR.
     */
    @Parameter
    private String[] excludes;

    /**
     * Directory containing the generated JAR.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    /**
     * Name of the generated JAR.
     */
    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String finalName;

    /**
     * The Jar archiver.
     */
    @Component
    private Map<String, Archiver> archivers;

    /**
     * The {@link MavenProject}.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The {@link MavenSession}.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The archive configuration to use. See <a href="http://maven.apache.org/shared/maven-archiver/index.html">Maven
     * Archiver Reference</a>.
     */
    @Parameter
    private MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

    /**
     * Using this property will fail your build cause it has been removed from the plugin configuration. See the
     * <a href="https://maven.apache.org/plugins/maven-jar-plugin/">Major Version Upgrade to version 3.0.0</a> for the
     * plugin.
     *
     * @deprecated For version 3.0.0 this parameter is only defined here to break the build if you use it!
     */
    @Parameter(property = "jar.useDefaultManifestFile", defaultValue = "false")
    @Deprecated
    private boolean useDefaultManifestFile;

    /**
     *
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Require the jar plugin to build a new JAR even if none of the contents appear to have changed. By default, this
     * plugin looks to see if the output jar exists and inputs have not changed. If these conditions are true, the
     * plugin skips creation of the jar. This does not work when other plugins, like the maven-shade-plugin, are
     * configured to post-process the jar. This plugin can not detect the post-processing, and so leaves the
     * post-processed jar in place. This can lead to failures when those plugins do not expect to find their own output
     * as an input. Set this parameter to <tt>true</tt> to avoid these problems by forcing this plugin to recreate the
     * jar every time.<br/>
     * Starting with <b>3.0.0</b> the property has been renamed from <code>jar.forceCreation</code> to
     * <code>maven.jar.forceCreation</code>.
     */
    @Parameter(property = "maven.jar.forceCreation", defaultValue = "false")
    private boolean forceCreation;

    /**
     * Skip creating empty archives.
     */
    @Parameter(defaultValue = "false")
    private boolean skipIfEmpty;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601 extended offset date-time
     * (e.g. in UTC such as '2011-12-03T10:15:30Z' or with an offset '2019-10-05T20:37:42+06:00'),
     * or as an int representing seconds since the epoch
     * (like <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * If the JAR contains the {@code META-INF/versions} directory it will be detected as a multi-release JAR file
     * ("MRJAR"), adding the {@code Multi-Release: true} attribute to the main section of the JAR MANIFEST.MF.
     *
     * @since 3.4.0
     */
    @Parameter(property = "maven.jar.detectMultiReleaseJar", defaultValue = "true")
    private boolean detectMultiReleaseJar;

    /**
     * If set to {@code false}, the files and directories that by default are excluded from the resulting archive,
     * like {@code .gitignore}, {@code .cvsignore} etc. will be included.
     * This means all files like the following will be included.
     * <ul>
     * <li>Misc: &#42;&#42;/&#42;~, &#42;&#42;/#&#42;#, &#42;&#42;/.#&#42;, &#42;&#42;/%&#42;%, &#42;&#42;/._&#42;</li>
     * <li>CVS: &#42;&#42;/CVS, &#42;&#42;/CVS/&#42;&#42;, &#42;&#42;/.cvsignore</li>
     * <li>RCS: &#42;&#42;/RCS, &#42;&#42;/RCS/&#42;&#42;</li>
     * <li>SCCS: &#42;&#42;/SCCS, &#42;&#42;/SCCS/&#42;&#42;</li>
     * <li>VSSercer: &#42;&#42;/vssver.scc</li>
     * <li>MKS: &#42;&#42;/project.pj</li>
     * <li>SVN: &#42;&#42;/.svn, &#42;&#42;/.svn/&#42;&#42;</li>
     * <li>GNU: &#42;&#42;/.arch-ids, &#42;&#42;/.arch-ids/&#42;&#42;</li>
     * <li>Bazaar: &#42;&#42;/.bzr, &#42;&#42;/.bzr/&#42;&#42;</li>
     * <li>SurroundSCM: &#42;&#42;/.MySCMServerInfo</li>
     * <li>Mac: &#42;&#42;/.DS_Store</li>
     * <li>Serena Dimension: &#42;&#42;/.metadata, &#42;&#42;/.metadata/&#42;&#42;</li>
     * <li>Mercurial: &#42;&#42;/.hg, &#42;&#42;/.hg/&#42;&#42;</li>
     * <li>Git: &#42;&#42;/.git, &#42;&#42;/.git/&#42;&#42;</li>
     * <li>Bitkeeper: &#42;&#42;/BitKeeper, &#42;&#42;/BitKeeper/&#42;&#42;, &#42;&#42;/ChangeSet,
     * &#42;&#42;/ChangeSet/&#42;&#42;</li>
     * <li>Darcs: &#42;&#42;/_darcs, &#42;&#42;/_darcs/&#42;&#42;, &#42;&#42;/.darcsrepo,
     * &#42;&#42;/.darcsrepo/&#42;&#42;&#42;&#42;/-darcs-backup&#42;, &#42;&#42;/.darcs-temp-mail
     * </ul>
     *
     * @see <a href="https://codehaus-plexus.github.io/plexus-utils/apidocs/org/codehaus/plexus/util/AbstractScanner.html#DEFAULTEXCLUDES">DEFAULTEXCLUDES</a>
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = "true")
    private boolean addDefaultExcludes;

    /**
     * Return the specific output directory to serve as the root for the archive.
     * @return get classes directory.
     */
    protected abstract File getClassesDirectory();

    /**
     * Return the {@link #project MavenProject}
     *
     * @return the MavenProject.
     */
    protected final MavenProject getProject() {
        return project;
    }

    /**
     * Overload this to produce a jar with another classifier, for example a test-jar.
     * @return get the classifier.
     */
    protected abstract String getClassifier();

    /**
     * Overload this to produce a test-jar, for example.
     * @return return the type.
     */
    protected abstract String getType();

    /**
     * Returns the Jar file to generate, based on an optional classifier.
     *
     * @param basedir the output directory
     * @param resultFinalName the name of the ear file
     * @param classifier an optional classifier
     * @return the file to generate
     */
    protected File getJarFile(File basedir, String resultFinalName, String classifier) {
        if (basedir == null) {
            throw new IllegalArgumentException("basedir is not allowed to be null");
        }
        if (resultFinalName == null) {
            throw new IllegalArgumentException("finalName is not allowed to be null");
        }

        String fileName = resultFinalName + (hasClassifier() ? "-" + classifier : "") + ".jar";

        return new File(basedir, fileName);
    }

    /**
     * Generates the JAR.
     * @return The instance of File for the created archive file.
     * @throws MojoExecutionException in case of an error.
     */
    public File createArchive() throws MojoExecutionException {
        File jarFile = getJarFile(outputDirectory, finalName, getClassifier());

        FileSetManager fileSetManager = new FileSetManager();
        FileSet jarContentFileSet = new FileSet();
        jarContentFileSet.setDirectory(getClassesDirectory().getAbsolutePath());
        jarContentFileSet.setIncludes(Arrays.asList(getIncludes()));
        jarContentFileSet.setExcludes(Arrays.asList(getExcludes()));

        String[] includedFiles = fileSetManager.getIncludedFiles(jarContentFileSet);

        if (detectMultiReleaseJar
                && Arrays.stream(includedFiles)
                        .anyMatch(p -> p.startsWith("META-INF" + SEPARATOR + "versions" + SEPARATOR))) {
            getLog().debug("Adding 'Multi-Release: true' manifest entry.");
            archive.addManifestEntry("Multi-Release", "true");
        }

        // May give false positives if the files is named as module descriptor
        // but is not in the root of the archive or in the versioned area
        // (and hence not actually a module descriptor).
        // That is fine since the modular Jar archiver will gracefully
        // handle such case.
        // And also such case is unlikely to happen as file ending
        // with "module-info.class" is unlikely to be included in Jar file
        // unless it is a module descriptor.
        boolean containsModuleDescriptor =
                Arrays.stream(includedFiles).anyMatch(p -> p.endsWith(MODULE_DESCRIPTOR_FILE_NAME));

        String archiverName = containsModuleDescriptor ? "mjar" : "jar";

        MavenArchiver archiver = new MavenArchiver();
        archiver.setCreatedBy("Maven JAR Plugin", "org.apache.maven.plugins", "maven-jar-plugin");
        archiver.setArchiver((JarArchiver) archivers.get(archiverName));
        archiver.setOutputFile(jarFile);

        Optional.ofNullable(toolchainManager.getToolchainFromBuildContext("jdk", session))
                .ifPresent(toolchain -> toolchainsJdkSpecification
                        .getJDKSpecification(toolchain)
                        .ifPresent(jdkSpec -> {
                            archive.addManifestEntry("Build-Jdk-Spec", jdkSpec);
                            archive.addManifestEntry(
                                    "Build-Tool-Jdk-Spec", System.getProperty("java.specification.version"));
                            archiver.setBuildJdkSpecDefaultEntry(false);
                            getLog().info("Set Build-Jdk-Spec based on toolchain in maven-jar-plugin " + toolchain);
                        }));

        // configure for Reproducible Builds based on outputTimestamp value
        archiver.configureReproducibleBuild(outputTimestamp);

        archive.setForced(forceCreation);

        try {
            File contentDirectory = getClassesDirectory();
            if (!contentDirectory.exists()) {
                if (!forceCreation) {
                    getLog().warn("JAR will be empty - no content was marked for inclusion!");
                }
            } else {
                archiver.getArchiver().addFileSet(getFileSet(contentDirectory));
            }

            archiver.createArchive(session, project, archive);

            return jarFile;
        } catch (Exception e) {
            // TODO: improve error handling
            throw new MojoExecutionException("Error assembling JAR", e);
        }
    }

    /**
     * Generates the JAR.
     * @throws MojoExecutionException in case of an error.
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (useDefaultManifestFile) {
            throw new MojoExecutionException("You are using 'useDefaultManifestFile' which has been removed"
                    + " from the maven-jar-plugin. "
                    + "Please see the >>Major Version Upgrade to version 3.0.0<< on the plugin site.");
        }

        if (skipIfEmpty
                && (!getClassesDirectory().exists() || getClassesDirectory().list().length < 1)) {
            getLog().info("Skipping packaging of the " + getType());
        } else {
            File jarFile = createArchive();

            if (hasClassifier()) {
                projectHelper.attachArtifact(getProject(), getType(), getClassifier(), jarFile);
            } else {
                if (projectHasAlreadySetAnArtifact()) {
                    throw new MojoExecutionException("You have to use a classifier "
                            + "to attach supplemental artifacts to the project instead of replacing them.");
                }
                getProject().getArtifact().setFile(jarFile);
            }
        }
    }

    private boolean projectHasAlreadySetAnArtifact() {
        if (getProject().getArtifact().getFile() == null) {
            return false;
        }

        return getProject().getArtifact().getFile().isFile();
    }

    /**
     * Return {@code true} in case where the classifier is not {@code null} and contains something else than white spaces.
     *
     * @return {@code true} if the classifier is set.
     */
    protected boolean hasClassifier() {
        return getClassifier() != null && !getClassifier().trim().isEmpty();
    }

    private String[] getIncludes() {
        if (includes != null && includes.length > 0) {
            return includes;
        }
        return DEFAULT_INCLUDES;
    }

    private String[] getExcludes() {
        if (excludes != null && excludes.length > 0) {
            return excludes;
        }
        return DEFAULT_EXCLUDES;
    }

    private org.codehaus.plexus.archiver.FileSet getFileSet(File contentDirectory) {
        DefaultFileSet fileSet = DefaultFileSet.fileSet(contentDirectory)
                .prefixed("")
                .includeExclude(getIncludes(), getExcludes())
                .includeEmptyDirs(true);

        fileSet.setUsingDefaultExcludes(addDefaultExcludes);
        return fileSet;
    }
}
