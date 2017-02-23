/*
 * The MIT License
 *
 * Copyright (c) 2014-2015, Matthew DeTullio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.mjdetullio.jenkins.plugins.multibranch;

import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.cli.declarative.CLIMethod;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.scm.NullSCM;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.PersistedList;
import jenkins.branch.Branch;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchPropertyStrategy;
import jenkins.branch.BranchSource;
import jenkins.branch.BuildRetentionBranchProperty;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.branch.MultiBranchProject;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Matthew DeTullio
 */
public abstract class TemplateDrivenMultiBranchProject<P extends AbstractProject<P, B> & TopLevelItem, B extends AbstractBuild<P, B>> // NOSONAR
        extends MultiBranchProject<P, B>
        implements TopLevelItem, SCMSourceOwner {

    private static final String CLASSNAME = TemplateDrivenMultiBranchProject.class.getName();
    private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

    private static final String UNUSED = "unused";

    public static final String TEMPLATE = "template";

    protected volatile boolean disabled;

    private PersistedList<String> disabledSubProjects;

    protected transient P template; // NOSONAR

    // Used for migration only
    private transient SCMSource scmSource; // NOSONAR

    /**
     * Constructor, mandated by {@link TopLevelItem}.
     *
     * @param parent the parent of this multi-branch job.
     * @param name   the name of the multi-branch job.
     */
    public TemplateDrivenMultiBranchProject(ItemGroup parent, String name) {
        super(parent, name);
        init3();
    }

    @Override
    public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        super.onLoad(parent, name);
        init3();
        runSubProjectDisplayNameMigration();
        runDisabledSubProjectNameMigration();
        migrateToBranchApi();
    }

    /**
     * Common initialization that is invoked when either a new project is created with the constructor
     * {@link TemplateDrivenMultiBranchProject#TemplateDrivenMultiBranchProject(ItemGroup, String)} or when a project
     * is loaded from disk with {@link #onLoad(ItemGroup, String)}.
     */
    protected void init3() {
        if (disabledSubProjects == null) {
            disabledSubProjects = new PersistedList<>(this);
        }

        // Owner doesn't seem to be set when loading from XML
        disabledSubProjects.setOwner(this);

        try {
            XmlFile templateXmlFile = Items.getConfigFile(getTemplateDir());
            if (templateXmlFile.getFile().isFile()) {
                /*
                 * Do not use Items.load here, since it uses getRootDirFor(i) during onLoad,
                 * which returns the wrong location since template would still be unset.
                 * Instead, read the XML directly into template and then invoke onLoad.
                 */
                //noinspection unchecked
                template = (P) templateXmlFile.read();
                template.onLoad(this, TEMPLATE);
            } else {
                /*
                 * Don't use the factory here because newInstance calls setBranch, attempting
                 * to save the project before template is set.  That would invoke
                 * getRootDirFor(i) and get the wrong directory to save into.
                 */
                template = newTemplate();
            }

            // Prevent tampering
            if (!(template.getScm() instanceof NullSCM)) {
                template.setScm(new NullSCM());
            }
            template.disable();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load template project " + getTemplateDir(), e);
        }
    }

    /**
     * Should create a new project using this {@link TemplateDrivenMultiBranchProject} as the parent and
     * {@link TemplateDrivenMultiBranchProject#TEMPLATE} as the name.
     *
     * @return a new project to serve as the {@link #template}.
     */
    protected abstract P newTemplate();

    /**
     * Overrides view initialization to use BranchListView instead of AllView.
     * <br>
     * {@inheritDoc}
     */
    @Override
    protected void initViews(List<View> views) throws IOException {
        BranchListView v = new BranchListView("All", this);
        v.setIncludeRegex(".*");
        views.add(v);
        v.save();
    }

    private void runSubProjectDisplayNameMigration() {
        for (P project : getItems()) {
            String projectName = project.getName();
            String projectNameDecoded = rawDecode(projectName);

            if (!projectName.equals(projectNameDecoded)
                    && project.getDisplayNameOrNull() == null) {
                try {
                    project.setDisplayName(projectNameDecoded);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to update display name for project " + projectName, e);
                }
            }
        }
    }

    private void runDisabledSubProjectNameMigration() throws IOException {
        /*
         * PersistedList/CopyOnWriteArray's iterator does not support iter.remove() so we can't modify
         * the list while iterating.  Instead, build a new list and replace the old one with it.
         */
        Set<String> newDisabledSubProjects = new HashSet<>();

        for (String disabledSubProject : disabledSubProjects) {
            if (getItem(disabledSubProject) == null) {
                // Didn't find item, so don't add it to new list
                // Do we have the encoded item though?
                String encoded = Util.rawEncode(disabledSubProject);

                if (getItem(encoded) != null) {
                    // Found encoded item, add encoded name to new list
                    newDisabledSubProjects.add(encoded);
                }
            } else {
                // Found item, add to new list
                newDisabledSubProjects.add(disabledSubProject);
            }
        }

        disabledSubProjects.clear();
        disabledSubProjects.addAll(newDisabledSubProjects);
    }

    private void migrateToBranchApi() {
        if (scmSource == null) {
            // Already migrated
            return;
        }

        BranchProjectFactory<P, B> factory = getProjectFactory();

        for (P project : getItems()) {
            BranchProjectProperty property = project.getProperty(BranchProjectProperty.class);

            if (property == null) {
                Branch branch = new Branch(scmSource.getId(), new SCMHead(project.getDisplayName()), project.getScm(),
                        Collections.<BranchProperty>emptyList());

                factory.setBranch(project, branch);
            }
        }

        BranchSource branchSource = new BranchSource(scmSource);

        if (template.getBuildDiscarder() != null) {
            BuildRetentionBranchProperty property = new BuildRetentionBranchProperty(template.getBuildDiscarder());
            BranchPropertyStrategy strategy = new DefaultBranchPropertyStrategy(new BranchProperty[]{property});
            branchSource.setStrategy(strategy);
            try {
                template.setBuildDiscarder(null);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate build discarder for project " + getFullDisplayName(), e);
            }
        }

        getSourcesList().add(branchSource);
        scmSource = null;
    }

    /**
     * Retrieves the template sub-project.  Used by jelly views.
     *
     * @return P - the template sub-project.
     */
    @SuppressWarnings(UNUSED)
    public P getTemplate() {
        return template;
    }

    /**
     * Returns the "template" directory inside the project directory.  This is the template project's directory.
     *
     * @return File - "template" directory inside the project directory.
     */
    @Nonnull
    public File getTemplateDir() {
        return new File(getRootDir(), TEMPLATE);
    }

    @Nonnull
    @Override
    public File getRootDirFor(P child) {
        if (child.equals(template)) {
            return getTemplateDir();
        }

        // All others are branches
        return super.getRootDirFor(child);
    }

    /**
     * If copied, also copy the {@link #template}.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public void onCopiedFrom(Item src) {
        super.onCopiedFrom(src);

        //noinspection unchecked
        TemplateDrivenMultiBranchProject<P, B> projectSrc = (TemplateDrivenMultiBranchProject<P, B>) src;

        /*
         * onLoad should have been invoked already, so there should be an
         * empty template.  Just update by XML and that's it.
         */
        try {
            template.updateByXml((Source) new StreamSource(projectSrc.getTemplate().getConfigFile().readRaw()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to copy template from " + src.getName() + " into " + getName(), e);
        }
    }

    /**
     * Sets various implementation-specific fields and forwards wrapped req/rsp objects on to the
     * {@link #template}'s {@link AbstractProject#doConfigSubmit(StaplerRequest, StaplerResponse)} method.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public void submit(StaplerRequest req, StaplerResponse rsp)
            throws ServletException, Descriptor.FormException, IOException {
        super.submit(req, rsp);

        makeDisabled(req.getParameter("disable") != null);

        template.doConfigSubmit(
                new TemplateStaplerRequestWrapper(req),
                new TemplateStaplerResponseWrapper(req.getStapler(), rsp));

        ItemListener.fireOnUpdated(this);

        // notify the queue as the project might be now tied to different node
        Jenkins.getActiveInstance().getQueue().scheduleMaintenance();

        // this is to reflect the upstream build adjustments done above
        Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
    }

    /**
     * Returns the last build.
     *
     * @return the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastBuild());
        }
        return retVal;
    }

    /**
     * Returns the oldest build in the record.
     *
     * @return the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getFirstBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            Run run = job.getFirstBuild();
            if (run != null && (retVal == null || run.getTimestamp().before(retVal.getTimestamp()))) {
                retVal = run;
            }
        }
        return retVal;
    }

    /**
     * Returns the last successful build, if any. Otherwise null. A successful build would include
     * either {@link Result#SUCCESS} or {@link Result#UNSTABLE}.
     *
     * @return the build or null
     * @see #getLastStableBuild()
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastSuccessfulBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastSuccessfulBuild());
        }
        return retVal;
    }

    /**
     * Returns the last build that was anything but stable, if any. Otherwise null.
     *
     * @return the build or null
     * @see #getLastSuccessfulBuild
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastUnsuccessfulBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastUnsuccessfulBuild());
        }
        return retVal;
    }

    /**
     * Returns the last unstable build, if any. Otherwise null.
     *
     * @return the build or null
     * @see #getLastSuccessfulBuild
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastUnstableBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastUnstableBuild());
        }
        return retVal;
    }

    /**
     * Returns the last stable build, if any. Otherwise null.
     *
     * @return the build or null
     * @see #getLastSuccessfulBuild
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastStableBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastStableBuild());
        }
        return retVal;
    }

    /**
     * Returns the last failed build, if any. Otherwise null.
     *
     * @return the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastFailedBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastFailedBuild());
        }
        return retVal;
    }

    /**
     * Returns the last completed build, if any. Otherwise null.
     *
     * @return the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public Run getLastCompletedBuild() {
        Run retVal = null;
        for (Job job : getAllJobs()) {
            retVal = takeLast(retVal, job.getLastCompletedBuild());
        }
        return retVal;
    }

    @CheckForNull
    private Run takeLast(Run run1, Run run2) {
        if (run2 != null && (run1 == null || run2.getTimestamp().after(run1.getTimestamp()))) {
            return run2;
        }
        return run1;
    }

    @Override
    public boolean isBuildable() {
        return !isDisabled() && super.isBuildable();
    }

    /**
     * Gets whether this project is disabled.
     *
     * @return boolean - true: disabled, false: enabled
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Marks the build as disabled.
     *
     * @param b true - disable, false - enable
     * @throws IOException if problem saving
     */
    public void makeDisabled(boolean b) throws IOException {
        if (disabled == b) {
            return;
        }
        this.disabled = b;

        Collection<P> projects = getItems();

        // Manage the sub-projects
        if (b) {
            /*
             * Populate list only if it is empty.  Running this loop when the
             * parent (and therefore, all sub-projects) are already disabled will
             * add all branches.  Obviously not desirable.
             */
            if (disabledSubProjects.isEmpty()) {
                for (P project : projects) {
                    if (project.isDisabled()) {
                        disabledSubProjects.add(project.getName());
                    }
                }
            }

            // Always forcefully disable all sub-projects
            for (P project : projects) {
                project.disable();
            }
        } else {
            // Re-enable only the projects that weren't manually marked disabled
            for (P project : projects) {
                if (!disabledSubProjects.contains(project.getName())) {
                    project.enable();
                }
            }

            // Clear the list so it can be rebuilt when parent is disabled
            disabledSubProjects.clear();
        }

        save();
        ItemListener.fireOnUpdated(this);
    }

    /**
     * Specifies whether this project may be disabled by the user. By default, it can be only if this
     * is a {@link TopLevelItem}; would be false for matrix configurations, etc.
     *
     * @return true if the GUI should allow {@link #doDisable} and the like
     */
    @SuppressWarnings(UNUSED)
    public boolean supportsMakeDisabled() {
        return true;
    }

    @SuppressWarnings(UNUSED)
    public void disable() throws IOException {
        makeDisabled(true);
    }

    @SuppressWarnings(UNUSED)
    public void enable() throws IOException {
        makeDisabled(false);
    }

    @SuppressWarnings(UNUSED)
    @CLIMethod(name = "disable-job")
    @RequirePOST
    public HttpResponse doDisable() throws IOException, ServletException { // NOSONAR
        checkPermission(CONFIGURE);
        makeDisabled(true);
        return new HttpRedirect(".");
    }

    @SuppressWarnings(UNUSED)
    @CLIMethod(name = "enable-job")
    @RequirePOST
    public HttpResponse doEnable() throws IOException, ServletException { // NOSONAR
        checkPermission(CONFIGURE);
        makeDisabled(false);
        return new HttpRedirect(".");
    }

    /**
     * Gets whether or not this item is configurable (always true).  Used in Jelly.
     *
     * @return boolean - true
     */
    @SuppressWarnings(UNUSED)
    public boolean isConfigurable() {
        return true;
    }

    /**
     * Get the term used in the UI to represent this kind of {@link AbstractProject}. Must start with a capital letter.
     */
    @Override
    public String getPronoun() {
        return AlternativeUiTextProvider.get(PRONOUN, this, hudson.model.Messages.AbstractProject_Pronoun());
    }

    /**
     * Returns a list of {@link ViewDescriptor}s that we want to use for this project type.  Used by newView.jelly.
     *
     * @return list of {@link ViewDescriptor}s that we want to use for this project type
     */
    @SuppressWarnings(UNUSED)
    public static List<ViewDescriptor> getViewDescriptors() {
        return Collections.singletonList(
                (ViewDescriptor) Jenkins.getActiveInstance().getDescriptorByType(BranchListView.DescriptorImpl.class));
    }

    /**
     * Triggered by different listeners to enforce state for multi-branch projects and their sub-projects.
     * <ul>
     * <li>Watches for changes to the template project and corrects the SCM and enabled/disabled state if modified.</li>
     * <li>Looks for rogue template project in the branches directory and removes it if no such sub-project exists.</li>
     * <li>Re-disables sub-projects if they were enabled when the parent project was disabled.</li>
     * </ul>
     *
     * @param item the item that was just updated
     */
    public static void enforceProjectStateOnUpdated(Item item) {
        if (item.getParent() instanceof TemplateDrivenMultiBranchProject) {
            TemplateDrivenMultiBranchProject parent = (TemplateDrivenMultiBranchProject) item.getParent();
            AbstractProject template = parent.getTemplate();

            if (item.equals(template)) {
                try {
                    if (!(template.getScm() instanceof NullSCM)) {
                        template.setScm(new NullSCM());
                    }

                    if (!template.isDisabled()) {
                        template.disable();
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to correct template configuration.", e);
                }
            }

            // Don't allow sub-projects to be enabled if parent is disabled
            AbstractProject project = (AbstractProject) item;
            if (parent.isDisabled() && !project.isDisabled()) {
                try {
                    project.disable();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to keep sub-project disabled.", e);
                }
            }
        }
    }

    /**
     * Additional listener for normal changes to Items in the UI, used to enforce state for
     * multi-branch projects and their sub-projects.
     */
    @SuppressWarnings(UNUSED)
    @Extension
    public static final class BranchProjectItemListener extends ItemListener {
        @Override
        public void onUpdated(Item item) {
            enforceProjectStateOnUpdated(item);
        }
    }

    /**
     * Additional listener for changes to Items via config.xml POST, used to enforce state for
     * multi-branch projects and their sub-projects.
     */
    @SuppressWarnings(UNUSED)
    @Extension
    public static final class BranchProjectSaveListener extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Item) {
                enforceProjectStateOnUpdated((Item) o);
            }
        }
    }

    /**
     * Populate a list of config files.  Avoid traversing down into directories that may be expensive to stat.
     *
     * @param dir the directory to traverse
     * @throws IOException if no directory listing was retrieved at a symlink expected to be a directory
     */
    private static List<File> getConfigFiles(File dir) throws IOException {
        List<File> files = new ArrayList<>();

        File[] contents = dir.getCanonicalFile().listFiles();
        /*
         * Directory could be a symlink and this is a problem. getCanonicalFile() does not properly handle this for
         * Windows (see https://bugs.openjdk.java.net/browse/JDK-8022671). The workaround is to use toRealPath().
         */
        if (contents == null && (contents = dir.toPath().toRealPath().toFile().listFiles()) == null) {
            throw new IOException("Tried to treat '" + dir + "' as a directory, but could not get a listing");
        }

        for (final File file : contents) {
            if (file.getName().equals("config.xml")) {
                files.add(file);
            }

            // Don't descend into dirs that we know will be expensive
            if (file.getName().equals("archive") || file.getName().equals("builds")
                    || file.getName().startsWith("workspace")) {
                continue;
            }

            if (file.isDirectory()) {
                files.addAll(getConfigFiles(file));
            }
        }

        return files;
    }


    /**
     * Migrates {@code SyncBranchesTrigger} to {@link hudson.triggers.TimerTrigger} and copies the
     * template's {@code hudson.security.AuthorizationMatrixProperty} to the parent as a
     * {@code com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty}.
     *
     * @throws IOException if errors reading/modifying files during migration
     */
    @SuppressWarnings(UNUSED)
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void migrate() throws IOException {
        final String projectAmpStartTag = "<hudson.security.AuthorizationMatrixProperty>";

        final File projectsDir = new File(Jenkins.getActiveInstance().getRootDir(), "jobs");

        if (!projectsDir.getCanonicalFile().isDirectory()) {
            return;
        }

        List<File> configFiles = getConfigFiles(projectsDir);

        for (final File configFile : configFiles) {
            String xml = FileUtils.readFileToString(configFile);

            // Rename and wrap trigger open tag
            xml = xml.replaceFirst(
                    "(?m)^  <syncBranchesTrigger>(\r?\n)    <spec>",
                    "  <triggers>\n    <hudson.triggers.TimerTrigger>\n      <spec>");

            // Rename and wrap trigger close tag
            xml = xml.replaceFirst(
                    "(?m)^  </syncBranchesTrigger>",
                    "    </hudson.triggers.TimerTrigger>\n  </triggers>");

            // Copy AMP from template if parent does not have a properties tag
            if (!xml.matches("(?ms).+(\r?\n)  <properties.+")
                    // Long line is long
                    && xml.matches("(?ms).*<((freestyle|maven)-multi-branch-project|com\\.github\\.mjdetullio\\.jenkins\\.plugins\\.multibranch\\.(FreeStyle|Maven)MultiBranchProject)( plugin=\".*?\")?.+")) {

                File templateConfigFile = new File(new File(configFile.getParentFile(), TEMPLATE), "config.xml");

                if (templateConfigFile.isFile()) {
                    String templateXml = FileUtils.readFileToString(templateConfigFile);

                    int start = templateXml.indexOf(projectAmpStartTag);
                    int end = templateXml.indexOf("</hudson.security.AuthorizationMatrixProperty>");

                    if (start != -1 && end != -1) {
                        String ampSettings = templateXml.substring(
                                start + projectAmpStartTag.length(),
                                end);

                        xml = xml.replaceFirst("(?m)^  ",
                                "  <properties>\n    " +
                                        "<com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty>" +
                                        ampSettings +
                                        "</com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty>" +
                                        "\n  </properties>\n  ");
                    }
                }
            }

            FileUtils.writeStringToFile(configFile, xml);
        }
    }
}
