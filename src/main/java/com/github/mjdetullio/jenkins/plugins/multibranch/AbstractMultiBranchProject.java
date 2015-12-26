/*
 * The MIT License
 *
 * Copyright (c) 2014-2015, Matthew DeTullio, Stephen Connolly
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

import com.cloudbees.hudson.plugins.folder.computed.ChildObserver;
import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;
import com.cloudbees.hudson.plugins.folder.computed.OrphanedItemStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
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
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.scm.NullSCM;
import hudson.triggers.SCMTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.DescribableList;
import hudson.util.PersistedList;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.impl.SingleSCMSource;
import jenkins.util.TimeDuration;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
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
public abstract class AbstractMultiBranchProject<P extends AbstractProject<P, B> & TopLevelItem, B extends AbstractBuild<P, B>>
        extends ComputedFolder<P>
        implements TopLevelItem, SCMSourceOwner {

    private static final String CLASSNAME = AbstractMultiBranchProject.class.getName();
    private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

    private static final String UNUSED = "unused";
    private static final String TEMPLATE = "template";

    protected volatile boolean disabled;

    private PersistedList<String> disabledSubProjects;

    protected transient P templateProject;

    private boolean allowAnonymousSync;

    private boolean suppressTriggerNewBranchBuild;

    protected volatile SCMSource scmSource;

    /**
     * {@inheritDoc}
     */
    public AbstractMultiBranchProject(ItemGroup parent, String name) {
        super(parent, name);
        init2();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        super.onLoad(parent, name);
        init2();
        runSubProjectDisplayNameMigration();
        runDisabledSubProjectNameMigration();
    }

    /**
     * Common initialization that is invoked when either a new project is created with the constructor
     * {@link AbstractMultiBranchProject#AbstractMultiBranchProject(ItemGroup, String)} or when a project
     * is loaded from disk with {@link #onLoad(ItemGroup, String)}.
     */
    protected void init2() {
        if (disabledSubProjects == null) {
            disabledSubProjects = new PersistedList<String>(this);
        }

        // Owner doesn't seem to be set when loading from XML
        disabledSubProjects.setOwner(this);

        try {
            if (new File(getTemplateDir(), "config.xml").isFile()) {
                /*
                 * Do not use Items.load here, since it uses getRootDirFor(i)
                 * during onLoad, which returns the wrong location since
                 * templateProject would still be unset.  Instead, read the XML
                 * directly into templateProject and then invoke onLoad.
                 */
                //noinspection unchecked
                templateProject = (P) Items.getConfigFile(getTemplateDir()).read();
                templateProject.onLoad(this, TEMPLATE);
            } else {
                templateProject = createNewSubProject(this, TEMPLATE);
            }

            // Prevent tampering
            if (!(templateProject.getScm() instanceof NullSCM)) {
                templateProject.setScm(new NullSCM());
            }
            templateProject.disable();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load template project " + getTemplateDir(), e);
        }
    }

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
         * PersistedList/CopyOnWriteArray's iterator does not support
         * iter.remove() so can't modify the list while iterating.  Instead,
         * build a new list and replace the old one with it.
         */
        Set<String> newDisabledSubProjects = new HashSet<String>();

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

    /**
     * Defines how sub-projects should be created when provided the parent and the branch name.
     *
     * @param parent     this
     * @param branchName branch name
     * @return new sub-project of type {@link P}
     */
    protected abstract P createNewSubProject(AbstractMultiBranchProject parent, String branchName);

    /**
     * Stapler URL binding for ${rootUrl}/job/${project}/branch/${branchProject}
     *
     * @param name Branch project name
     * @return {@link #getItem(String)}
     */
    @SuppressWarnings(UNUSED)
    public P getBranch(String name) {
        return getItem(name);
    }

    /**
     * Retrieves the template sub-project.  Used by configure-entries.jelly.
     *
     * @return P - the template sub-project.
     */
    @SuppressWarnings(UNUSED)
    public P getTemplate() {
        return templateProject;
    }

    /**
     * Returns the "branches" directory inside the project directory.  This is where the sub-project
     * directories reside.
     *
     * @return File "branches" directory inside the project directory.
     */
    @Override
    @Nonnull
    public File getJobsDir() {
        return new File(getRootDir(), "branches");
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

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public String getUrlChildPrefix() {
        return "branch";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getRootDirFor(P child) {
        if (child.equals(templateProject)) {
            return getTemplateDir();
        }

        // All others are branches
        return super.getRootDirFor(child);
    }

    //region SCMSourceOwner implementation

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SCMSource> getSCMSources() {
        if (scmSource == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(scmSource);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @edu.umd.cs.findbugs.annotations.CheckForNull
    public SCMSource getSCMSource(@edu.umd.cs.findbugs.annotations.CheckForNull String sourceId) {
        if (scmSource != null && scmSource.getId().equals(sourceId)) {
            return scmSource;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSCMSourceUpdated(@NonNull SCMSource source) {
        scheduleBuild();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @edu.umd.cs.findbugs.annotations.CheckForNull
    public SCMSourceCriteria getSCMSourceCriteria(@NonNull SCMSource source) {
        return new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return true;
            }
        };
    }

    //endregion SCMSourceOwner implementation

    /**
     * Returns the project's only SCMSource.  Used by configure-entries.jelly.
     *
     * @return the project's only SCMSource (may be null)
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    public SCMSource getSCMSource() {
        return scmSource;
    }

    /**
     * Gets whether anonymous sync is allowed from <code>${JOB_URL}/syncBranches</code>
     *
     * @return boolean - true: no permission checked for URL,
     * false: permission checked
     */
    @SuppressWarnings(UNUSED)
    public boolean isAllowAnonymousSync() {
        return allowAnonymousSync;
    }

    /**
     * Sets whether anonymous sync is allowed from <code>${JOB_URL}/syncBranches</code>.
     *
     * @param b true/false
     * @throws IOException if problems saving
     */
    @SuppressWarnings(UNUSED)
    public void setAllowAnonymousSync(boolean b) throws IOException {
        allowAnonymousSync = b;
        save();
    }

    /**
     * Gets whether build on new branch is suppressed.
     *
     * @return boolean - true: no build triggered when sub-project created,
     * false: build is triggered when sub-project created
     */
    @SuppressWarnings(UNUSED)
    public boolean isSuppressTriggerNewBranchBuild() {
        return suppressTriggerNewBranchBuild;
    }

    /**
     * Sets whether build on new branch is suppressed.
     *
     * @param b true/false
     * @throws IOException if problems saving
     */
    @SuppressWarnings(UNUSED)
    public void setSuppressTriggerNewBranchBuild(boolean b) throws IOException {
        suppressTriggerNewBranchBuild = b;
        save();
    }

    /**
     * Exposes a URI that allows the trigger of a branch sync.
     *
     * @param req Stapler request
     * @param rsp Stapler response
     * @throws IOException          if problems
     * @throws InterruptedException if problems
     * @deprecated use {@link #doBuild(TimeDuration)} instead
     */
    @SuppressWarnings(UNUSED)
    @Deprecated
    @RequirePOST
    public void doSyncBranches(StaplerRequest req, StaplerResponse rsp) throws IOException, InterruptedException {
        if (!allowAnonymousSync) {
            checkPermission(CONFIGURE);
        }
        scheduleBuild();
    }

    /**
     * If copied, also copy the {@link #templateProject}.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public void onCopiedFrom(Item src) {
        super.onCopiedFrom(src);

        //noinspection unchecked
        AbstractMultiBranchProject<P, B> projectSrc =
                (AbstractMultiBranchProject<P, B>) src;

        /*
         * onLoad should have been invoked already, so there should be an
         * empty templateProject.  Just update by XML and that's it.
         */
        try {
            templateProject.updateByXml((Source) new StreamSource(projectSrc.getTemplate().getConfigFile().readRaw()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to copy templateProject from " + src.getName() + " into " + getName(), e);
        }
    }

    /**
     * Sets various implementation-specific fields and forwards wrapped req/rsp objects on to the
     * {@link #templateProject}'s {@link AbstractProject#doConfigSubmit(StaplerRequest, StaplerResponse)} method.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public void submit(StaplerRequest req, StaplerResponse rsp)
            throws ServletException, Descriptor.FormException, IOException {
        /*
         * Do NOT invoke super.submit(req, rsp), since it rebuilds triggers.
         * Because we're configuring triggers for both this project and the
         * template, there is a conflict.  Allow the template to rebuild
         * triggers from the root JSON form, and use a nested form for this
         * project's triggers.
         */

        JSONObject json = req.getSubmittedForm();

        // START stuff for ComputedFolder

        OrphanedItemStrategy orphanedItemStrategy =
                req.bindJSON(OrphanedItemStrategy.class, json.getJSONObject("orphanedItemStrategy"));

        // HACK!!!
        try {
            Field f = ComputedFolder.class.getDeclaredField("orphanedItemStrategy");
            f.setAccessible(true);
            f.set(this, orphanedItemStrategy);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Unable to submit orphanedItemStrategy", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to submit orphanedItemStrategy", e);
        }

        // HACK!!!
        try {
            Field f = ComputedFolder.class.getDeclaredField("triggers");
            f.setAccessible(true);

            //noinspection unchecked
            DescribableList<Trigger<?>, TriggerDescriptor> triggers =
                    (DescribableList<Trigger<?>, TriggerDescriptor>) f.get(this);

            for (Trigger t : triggers) {
                t.stop();
            }

            triggers.rebuild(req, json.getJSONObject("syncBranchesTriggers"),
                    Trigger.for_(this));

            for (Trigger t : triggers) {
                //noinspection unchecked
                t.start(this, true);
            }
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Unable to submit syncBranchesTriggers", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to submit syncBranchesTriggers", e);
        }

        // END stuff for ComputedFolder

        makeDisabled(req.getParameter("disable") != null);

        allowAnonymousSync = json.has("allowAnonymousSync");
        suppressTriggerNewBranchBuild = json.has("suppressTriggerNewBranchBuild");

        JSONObject scmSourceJson = json.optJSONObject("scmSource");
        if (scmSourceJson == null) {
            scmSource = null;
        } else {
            int value = Integer.parseInt(scmSourceJson.getString("value"));
            SCMSourceDescriptor descriptor = getSCMSourceDescriptors(true).get(value);
            scmSource = descriptor.newInstance(req, scmSourceJson);
            scmSource.setOwner(this);
        }

        templateProject.doConfigSubmit(
                new TemplateStaplerRequestWrapper(req),
                new TemplateStaplerResponseWrapper(req.getStapler(), rsp));

        ItemListener.fireOnUpdated(this);

        // notify the queue as the project might be now tied to different node
        Jenkins.getActiveInstance().getQueue().scheduleMaintenance();

        // this is to reflect the upstream build adjustments done above
        Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
    }

    /**
     * Tells this project type to use {@link SyncBranches} instead of {@link FolderComputation}.
     * <br>
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    protected FolderComputation<P> createComputation(FolderComputation<P> previous) {
        return new SyncBranches<P, B>(this, (SyncBranches<P, B>) previous);
    }

    /**
     * Defines how children are managed when Sync Branches is run.  It will retrieve the latest
     * {@link SCMHead}s and consult the {@link ChildObserver} to determine whether to update an
     * existing project or create a new project for the branch.  Current projects get updated
     * with the {@link #templateProject}'s config.
     * <br>
     * {@inheritDoc}
     */
    @Override
    protected void computeChildren(ChildObserver<P> observer, TaskListener listener)
            throws IOException, InterruptedException {
        // No SCM to source
        if (scmSource == null) {
            listener.getLogger().println("SCM not selected.");
            return;
        }

        Set<P> newProjects = new HashSet<P>();

        // Check SCM for branches
        Set<SCMHead> heads = scmSource.fetch(listener);

        for (SCMHead head : heads) {
            String branchName = head.getName();
            String branchNameEncoded = Util.rawEncode(branchName);

            listener.getLogger().println("Branch " + branchName + " encoded to " + branchNameEncoded);

            P project = observer.shouldUpdate(branchNameEncoded);

            if (!observer.mayCreate(branchNameEncoded)) {
                listener.getLogger().println("Ignoring duplicate " + branchNameEncoded);
                continue;
            }

            try {
                if (project == null) {
                    listener.getLogger().println("Creating project for branch " + branchNameEncoded);

                    project = createNewSubProject(this, branchNameEncoded);
                    newProjects.add(project);
                }

                listener.getLogger().println("Syncing config from template to branch " + branchNameEncoded);

                boolean wasDisabled = project.isDisabled();

                project.updateByXml((Source) new StreamSource(templateProject.getConfigFile().readRaw()));

                /*
                 * Build new SCM with the URL and branch already set.
                 *
                 * SCM must be set first since getRootDirFor(project) will give
                 * the wrong location during save, load, and elsewhere if SCM
                 * remains null (or NullSCM).
                 */
                project.setScm(scmSource.build(head));

                // Work-around for JENKINS-21017
                project.setCustomWorkspace(templateProject.getCustomWorkspace());

                if (branchName.equals(branchNameEncoded)) {
                    project.setDisplayName(null);
                } else {
                    project.setDisplayName(branchName);
                }

                if (!wasDisabled) {
                    project.enable();
                }

                observer.created(project);
            } catch (Throwable e) {
                e.printStackTrace(listener.fatalError(e.getMessage()));
            }
        }

        if (!suppressTriggerNewBranchBuild) {
            // Trigger build for new branches
            for (P project : newProjects) {
                listener.getLogger().println("Scheduling build for branch " + project.getName());
                try {
                    project.scheduleBuild(new SCMTrigger.SCMTriggerCause("New branch detected."));
                } catch (Throwable e) {
                    e.printStackTrace(listener.fatalError(e.getMessage()));
                }
            }
        }

        // notify the queue as the projects might be now tied to different node
        Jenkins.getActiveInstance().getQueue().scheduleMaintenance();
    }

    /**
     * Returns the last build.
     *
     * @return B - the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastBuild());
        }
        return retVal;
    }

    /**
     * Returns the oldest build in the record.
     *
     * @return B - the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getFirstBuild() {
        B retVal = null;

        for (P item : getItems()) {
            B b = item.getFirstBuild();

            if (b != null && (retVal == null || b.getTimestamp().before(retVal.getTimestamp()))) {
                retVal = b;
            }
        }

        return retVal;
    }

    /**
     * Returns the last successful build, if any. Otherwise null. A successful build would include
     * either {@link Result#SUCCESS} or {@link Result#UNSTABLE}.
     *
     * @return B - the build or null
     * @see #getLastStableBuild()
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastSuccessfulBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastSuccessfulBuild());
        }
        return retVal;
    }

    /**
     * Returns the last build that was anything but stable, if any. Otherwise null.
     *
     * @return B - the build or null
     * @see #getLastSuccessfulBuild
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastUnsuccessfulBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastUnsuccessfulBuild());
        }
        return retVal;
    }

    /**
     * Returns the last unstable build, if any. Otherwise null.
     *
     * @return B - the build or null
     * @see #getLastSuccessfulBuild
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastUnstableBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastUnstableBuild());
        }
        return retVal;
    }

    /**
     * Returns the last stable build, if any. Otherwise null.
     *
     * @return B - the build or null
     * @see #getLastSuccessfulBuild
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastStableBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastStableBuild());
        }
        return retVal;
    }

    /**
     * Returns the last failed build, if any. Otherwise null.
     *
     * @return B - the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastFailedBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastFailedBuild());
        }
        return retVal;
    }

    /**
     * Returns the last completed build, if any. Otherwise null.
     *
     * @return B - the build or null
     */
    @SuppressWarnings(UNUSED)
    @CheckForNull
    @Exported
    public B getLastCompletedBuild() {
        B retVal = null;
        for (P item : getItems()) {
            retVal = takeLast(retVal, item.getLastCompletedBuild());
        }
        return retVal;
    }

    @CheckForNull
    private B takeLast(B b1, B b2) {
        if (b2 != null && (b1 == null || b2.getTimestamp().after(b1.getTimestamp()))) {
            return b2;
        }
        return b1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBuildable() {
        return !isDisabled() && scmSource != null;
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
    public HttpResponse doDisable() throws IOException, ServletException {
        checkPermission(CONFIGURE);
        makeDisabled(true);
        return new HttpRedirect(".");
    }

    @SuppressWarnings(UNUSED)
    @CLIMethod(name = "enable-job")
    @RequirePOST
    public HttpResponse doEnable() throws IOException, ServletException {
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
     * Hack to remove @RequirePOST annotation, which causes problems with the "Run Now" button even
     * though <code>post="true"</code> is set in the jelly file.  Hmmmmmmmmmmmmmmmm...
     * <br>
     * {@inheritDoc}
     */
    @Override
    public HttpResponse doBuild(@QueryParameter TimeDuration delay) {
        return super.doBuild(delay);
    }

    /**
     * Returns a list of ViewDescriptors that we want to use for this project type.  Used by newView.jelly.
     */
    @SuppressWarnings(UNUSED)
    public static List<ViewDescriptor> getViewDescriptors() {
        return Collections.singletonList(
                (ViewDescriptor) Jenkins.getActiveInstance().getDescriptorByType(BranchListView.DescriptorImpl.class));
    }

    /**
     * Returns a list of SCMSourceDescriptors that we want to use for this project type.
     * Used by configure-entries.jelly.
     */
    public static List<SCMSourceDescriptor> getSCMSourceDescriptors(boolean onlyUserInstantiable) {
        List<SCMSourceDescriptor> descriptors =
                SCMSourceDescriptor.forOwner(AbstractMultiBranchProject.class, onlyUserInstantiable);

        /*
         * No point in having SingleSCMSource as an option, so axe it.
         * Might as well use the regular project if you really want this...
         */
        for (SCMSourceDescriptor descriptor : descriptors) {
            if (descriptor instanceof SingleSCMSource.DescriptorImpl) {
                descriptors.remove(descriptor);
                break;
            }
        }

        return descriptors;
    }

    /**
     * Inverse function of {@link hudson.Util#rawEncode(String)}.
     * <br>
     * Kanged from Branch API.
     *
     * @param s the encoded string.
     * @return the decoded string.
     */
    public static String rawDecode(String s) {
        final byte[] bytes; // should be US-ASCII but we can be tolerant
        try {
            bytes = s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("JLS specification mandates UTF-8 as a supported encoding", e);
        }

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i++) {
            final int b = bytes[i];
            if (b == '%' && i + 2 < bytes.length) {
                final int u = Character.digit((char) bytes[++i], 16);
                final int l = Character.digit((char) bytes[++i], 16);

                if (u != -1 && l != -1) {
                    buffer.write((char) ((u << 4) + l));
                    continue;
                }

                // should be a valid encoding but we can be tolerant
                i -= 2;
            }
            buffer.write(b);
        }

        try {
            return new String(buffer.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("JLS specification mandates UTF-8 as a supported encoding", e);
        }
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
        if (item.getParent() instanceof AbstractMultiBranchProject) {
            AbstractMultiBranchProject parent = (AbstractMultiBranchProject) item.getParent();
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
                    LOGGER.warning("Unable to correct template configuration.");
                }
            }

            // Don't allow sub-projects to be enabled if parent is disabled
            AbstractProject project = (AbstractProject) item;
            if (parent.isDisabled() && !project.isDisabled()) {
                try {
                    project.disable();
                } catch (IOException e) {
                    LOGGER.warning("Unable to keep sub-project disabled.");
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
     * Migrates <code>SyncBranchesTrigger</code> to {@link hudson.triggers.TimerTrigger} and copies the
     * template's {@link hudson.security.AuthorizationMatrixProperty} to the parent as a
     * {@link com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty}.
     */
    @SuppressWarnings(UNUSED)
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void migrate() throws IOException {
        final String projectAmpStartTag = "<hudson.security.AuthorizationMatrixProperty>";

        final File projectsDir = new File(Jenkins.getActiveInstance().getRootDir(), "jobs");

        if (!projectsDir.getCanonicalFile().isDirectory()) {
            return;
        }

        Collection<File> configFiles =
                FileUtils.listFiles(projectsDir, new NameFileFilter("config.xml"), TrueFileFilter.TRUE);

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
