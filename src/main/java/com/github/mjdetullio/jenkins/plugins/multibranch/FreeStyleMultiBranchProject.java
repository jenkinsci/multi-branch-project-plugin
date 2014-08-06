/*
 * The MIT License
 *
 * Copyright (c) 2014, Matthew DeTullio
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

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.bytecode.AdaptField;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.JDK;
import hudson.model.Label;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.model.ViewGroupMixIn;
import hudson.scm.SCMS;
import hudson.security.ACL;
import hudson.tasks.BuildTrigger;
import hudson.tasks.BuildWrappers;
import hudson.tasks.Builder;
import hudson.tasks.Publisher;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.views.DefaultViewsTabBar;
import hudson.views.ViewsTabBar;
import jenkins.model.Jenkins;
import jenkins.scm.SCMCheckoutStrategy;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.impl.SingleSCMSource;
import net.sf.json.JSONObject;

/**
 * @author Matthew DeTullio
 */
public class FreeStyleMultiBranchProject extends
		Project<FreeStyleBranchProject, FreeStyleBranchBuild> implements
		TopLevelItem, ItemGroup<FreeStyleBranchProject>, ViewGroup,
		SCMSourceOwner {

	private static final String CLASSNAME = FreeStyleMultiBranchProject.class.getName();
	private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

	private static final String UNUSED = "unused";
	private static final String DEFAULT_SYNC_SPEC = "H/5 * * * *";

	/**
	 * List of all {@link Trigger}s that will be synced to the sub-projects.
	 */
	@AdaptField(was = List.class)
	protected volatile DescribableList<Trigger<?>, TriggerDescriptor> subProjectTriggers = new DescribableList<Trigger<?>, TriggerDescriptor>(
			this);
	private static final AtomicReferenceFieldUpdater<FreeStyleMultiBranchProject, DescribableList> SUB_PROJECT_TRIGGERS_UPDATER =
			AtomicReferenceFieldUpdater.newUpdater(
					FreeStyleMultiBranchProject.class, DescribableList.class,
					"subProjectTriggers");

	private volatile SCMSource scmSource;

	private transient Map<String, FreeStyleBranchProject> subProjects;

	private transient ViewGroupMixIn viewGroupMixIn;

	private List<View> views;

	private volatile String primaryView;

	private transient ViewsTabBar viewsTabBar;

	/**
	 * Constructor that specifies the {@link ItemGroup} for this project and the
	 * project name.
	 *
	 * @param parent - the project's parent {@link ItemGroup}
	 * @param name   - the project's name
	 */
	public FreeStyleMultiBranchProject(ItemGroup parent, String name) {
		super(parent, name);
		init();
	}

	/**
	 * {@inheritDoc}
	 */
	public void onLoad(ItemGroup<? extends Item> parent, String name)
			throws IOException {
		super.onLoad(parent, name);
		init();
	}

	/**
	 * Common initialization that is invoked when either a new project is
	 * created with the constructor {@link FreeStyleMultiBranchProject(ItemGroup,
	 * String)} or when a project is loaded from disk with {@link
	 * #onLoad(ItemGroup, String)}.
	 */
	private synchronized void init() {
		if (views == null) {
			views = new CopyOnWriteArrayList<View>();
		}
		if (views.size() == 0) {
			BranchListView listView = new BranchListView("All", this);
			views.add(listView);
			listView.setIncludeRegex(".*");
			try {
				listView.save();
			} catch (IOException e) {
				LOGGER.log(Level.WARNING,
						"Failed to save initial multi-branch project view", e);
			}
		}
		if (primaryView == null) {
			primaryView = views.get(0).getViewName();
		}
		viewsTabBar = new DefaultViewsTabBar();
		viewGroupMixIn = new ViewGroupMixIn(this) {
			protected List<View> views() {
				return views;
			}

			protected String primaryView() {
				return primaryView;
			}

			protected void primaryView(String name) {
				primaryView = name;
			}
		};

		if (getBranchesDir().isDirectory()) {
			for (File branch : getBranchesDir().listFiles(new FileFilter() {
				public boolean accept(File pathname) {
					return pathname.isDirectory() && new File(pathname,
							"config.xml").isFile();
				}
			})) {
				try {
					Item item = Items.load(this, branch);
					getSubProjects().put(item.getName(),
							(FreeStyleBranchProject) item);
				} catch (IOException e) {
					LOGGER.log(Level.WARNING,
							"Failed to load branch project " + branch, e);
				}
			}
		}

		// Will check triggers(), add & start default sync cron if not there
		getSyncBranchesTrigger();
	}

	@WithBridgeMethods(List.class)
	protected DescribableList<Trigger<?>, TriggerDescriptor> subProjectTriggers() {
		if (subProjectTriggers == null) {
			SUB_PROJECT_TRIGGERS_UPDATER.compareAndSet(this, null,
					new DescribableList<Trigger<?>, TriggerDescriptor>(this));
		}
		return subProjectTriggers;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public TopLevelItemDescriptor getDescriptor() {
		return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(
				FreeStyleMultiBranchProject.class);
	}

	/**
	 * Stapler URL binding for ${rootUrl}/job/${project}/branch/${branchProject}
	 *
	 * @param name - Branch project name
	 * @return {@link #getItem(String)}
	 */
	@SuppressWarnings(UNUSED)
	public FreeStyleBranchProject getBranch(String name) {
		return getItem(name);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected Class<FreeStyleBranchBuild> getBuildClass() {
		return FreeStyleBranchBuild.class;
	}

	/**
	 * Retrieves the collection of sub-projects for this project.
	 */
	private synchronized Map<String, FreeStyleBranchProject> getSubProjects() {
		if (subProjects == null) {
			subProjects = new LinkedHashMap<String, FreeStyleBranchProject>();
		}
		return subProjects;
	}

	/**
	 * Returns the "branches" directory inside the project directory.  This is
	 * where the sub-project directories reside.
	 *
	 * @return File - "branches" directory inside the project directory.
	 */
	public File getBranchesDir() {
		File dir = new File(getRootDir(), "branches");
		if (!dir.isDirectory() && !dir.mkdirs()) {
			LOGGER.log(Level.WARNING,
					"Could not create branches directory {0}", dir);
		}
		return dir;
	}

	//region ItemGroup implementation

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<FreeStyleBranchProject> getItems() {
		return getSubProjects().values();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getUrlChildPrefix() {
		return "branch";
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public FreeStyleBranchProject getItem(String name) {
		return getSubProjects().get(name);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public File getRootDirFor(FreeStyleBranchProject child) {
		return new File(getBranchesDir(), Util.rawEncode(child.getName()));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onRenamed(FreeStyleBranchProject item, String oldName,
			String newName) {
		throw new UnsupportedOperationException(
				"Renaming sub-projects is not supported.  They should only be added or deleted.");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onDeleted(FreeStyleBranchProject item) {
		getSubProjects().remove(item.getName());
	}

	//endregion ItemGroup implementation

	//region ViewGroup implementation

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean canDelete(View view) {
		return viewGroupMixIn.canDelete(view);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void deleteView(View view) throws IOException {
		viewGroupMixIn.deleteView(view);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Exported
	public Collection<View> getViews() {
		return viewGroupMixIn.getViews();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public View getView(String name) {
		return viewGroupMixIn.getView(name);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Exported
	public View getPrimaryView() {
		return viewGroupMixIn.getPrimaryView();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onViewRenamed(View view, String oldName, String newName) {
		viewGroupMixIn.onViewRenamed(view, oldName, newName);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ViewsTabBar getViewsTabBar() {
		return viewsTabBar;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ItemGroup<? extends TopLevelItem> getItemGroup() {
		return this;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Action> getViewActions() {
		return Collections.emptyList();
	}

	//endregion ViewGroup implementation

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
		return Arrays.asList(scmSource);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@CheckForNull
	public SCMSource getSCMSource(@CheckForNull String sourceId) {
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
		// TODO: notification may be for a new branch that doesn't have a project yet, so trigger build for new projects?
		getSyncBranchesTrigger().run();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@CheckForNull
	public SCMSourceCriteria getSCMSourceCriteria(@NonNull SCMSource source) {
		return new SCMSourceCriteria() {
			@Override
			public boolean isHead(@NonNull Probe probe,
					@NonNull TaskListener listener)
					throws IOException {
				return true;
			}
		};
	}

	//endregion SCMSourceOwner implementation

	/**
	 * Stapler URL binding for creating views for our branch projects.  Unlike
	 * normal views, this only requires permission to configure the project, not
	 * create view permission.
	 *
	 * @param req - Stapler request
	 * @param rsp - Stapler response
	 * @throws IOException              - if problems
	 * @throws ServletException         - if problems
	 * @throws ParseException           - if problems
	 * @throws Descriptor.FormException - if problems
	 */
	@SuppressWarnings(UNUSED)
	public synchronized void doCreateView(StaplerRequest req,
			StaplerResponse rsp) throws IOException, ServletException,
			ParseException, Descriptor.FormException {
		checkPermission(CONFIGURE);
		viewGroupMixIn.addView(View.create(req, rsp, this));
	}

	/**
	 * Stapler URL binding used by the newView page to check for existing
	 * views.
	 *
	 * @param value - desired name of view
	 * @return {@link FormValidation#ok()} or {@link FormValidation#error(String)|
	 */
	@SuppressWarnings(UNUSED)
	public FormValidation doViewExistsCheck(@QueryParameter String value) {
		checkPermission(CONFIGURE);

		String view = Util.fixEmpty(value);
		if (view == null || getView(view) == null) {
			return FormValidation.ok();
		} else {
			return FormValidation.error(
					jenkins.model.Messages.Hudson_ViewAlreadyExists(view));
		}
	}

	/**
	 * Overrides the {@link hudson.model.AbstractProject} implementation because
	 * the user is not redirected to the parent properly for this project type.
	 * <p/> Inherited docs: <p/> {@inheritDoc}
	 *
	 * @param req - Stapler request
	 * @param rsp - Stapler response
	 * @throws IOException          - if problems
	 * @throws InterruptedException - if problems
	 */
	@Override
	@RequirePOST
	public void doDoDelete(StaplerRequest req, StaplerResponse rsp)
			throws IOException, InterruptedException {
		delete();
		if (req == null || rsp == null) {
			return;
		}
		rsp.sendRedirect2(req.getContextPath() + '/' + getParent().getUrl());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp)
			throws ServletException, Descriptor.FormException, IOException {
		super.doConfigSubmit(req, rsp);
		// TODO run this separately since it can block completion (user redirect) if unable to fetch from repository
		getSyncBranchesTrigger().run();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void submit(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, Descriptor.FormException {
		/*
		 * super.submit(req, rsp) is not called because it plays with the
		 * triggers, which we want this class to handle.  Specifically, we want
		 * to set triggers so they can be mirrored to the sub-projects, but we
		 * don't want to start them for this project.  We only want to start the
		 * SyncBranchesTrigger, whose settings we don't get from the list of
		 * other triggers.
		 */
		JSONObject json = req.getSubmittedForm();

		//region AbstractProject mirror
		makeDisabled(req.getParameter("disable") != null);

		JDK jdk = Jenkins.getInstance().getJDK(req.getParameter("jdk"));
		if (jdk == null) {
			jdk = new JDK(null, null);
		}
		setJDK(jdk);
		if (req.getParameter("hasCustomQuietPeriod") != null) {
			setQuietPeriod(Integer.parseInt(req.getParameter("quiet_period")));
		} else {
			setQuietPeriod(null);
		}
		Integer scmCheckoutRetryCount = null;
		if (req.getParameter("hasCustomScmCheckoutRetryCount") != null) {
			scmCheckoutRetryCount = Integer.parseInt(
					req.getParameter("scmCheckoutRetryCount"));
		}
		// Hack to set retry count since it is not exposed from AbstractProject
		try {
			Field f = AbstractProject.class.getDeclaredField(
					"scmCheckoutRetryCount");
			f.setAccessible(true);
			f.set(this, scmCheckoutRetryCount);
		} catch (NoSuchFieldException e) {
			// TODO
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO
			e.printStackTrace();
		}
		// End hack
		blockBuildWhenDownstreamBuilding =
				req.getParameter("blockBuildWhenDownstreamBuilding") != null;
		blockBuildWhenUpstreamBuilding =
				req.getParameter("blockBuildWhenUpstreamBuilding") != null;

		if (req.hasParameter("customWorkspace")) {
			setCustomWorkspace(Util.fixEmptyAndTrim(
					req.getParameter("customWorkspace.directory")));
		} else {
			setCustomWorkspace(null);
		}

		if (json.has("scmCheckoutStrategy")) {
			setScmCheckoutStrategy(req.bindJSON(SCMCheckoutStrategy.class,
					json.getJSONObject("scmCheckoutStrategy")));
		} else {
			setScmCheckoutStrategy(null);
		}

		if (req.getParameter("hasSlaveAffinity") != null) {
			try {
				setAssignedLabel(Label.parseExpression(Util.fixEmptyAndTrim(
						req.getParameter("_.assignedLabelString"))));
			} catch (ANTLRException e) {
				// must be old label or host name that includes whitespace or other unsafe chars
				setAssignedLabel(null);
			}
		} else {
			setAssignedLabel(null);
		}

		setConcurrentBuild(req.getSubmittedForm().has("concurrentBuild"));

		// BuildAuthorizationToken is deprecated, so authToken is probably ok as null
		// authToken = BuildAuthorizationToken.create(req);

		setScm(SCMS.parseSCM(req, this));

		// Keep triggers meant for sub-projects in a different list
		subProjectTriggers().replaceBy(
				buildDescribable(req, Trigger.for_(this)));

		for (Publisher _t : Descriptor.newInstancesFromHeteroList(req, json,
				"publisher", Jenkins.getInstance().getExtensionList(
						BuildTrigger.DescriptorImpl.class))) {
			BuildTrigger t = (BuildTrigger) _t;
			List<AbstractProject> childProjects;
			SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
			try {
				childProjects = t.getChildProjects((AbstractProject) this);
			} finally {
				SecurityContextHolder.setContext(orig);
			}
			for (AbstractProject downstream : childProjects) {
				downstream.checkPermission(BUILD);
			}
		}
		//endregion AbstractProject mirror

		//region Project mirror
		getBuildWrappersList().rebuild(req, json, BuildWrappers.getFor(this));
		getBuildersList().rebuildHetero(req, json, Builder.all(), "builder");
		getPublishersList().rebuildHetero(req, json, Publisher.all(),
				"publisher");
		//endregion Project mirror

		String syncBranchesCron = json.getString("syncBranchesCron");
		try {
			restartSyncBranchesTrigger(syncBranchesCron);
		} catch (ANTLRException e) {
			throw new IllegalArgumentException(
					"Failed to instantiate SyncBranchesTrigger", e);
		}

		primaryView = json.getString("primaryView");

		JSONObject scmSourceJson = json.optJSONObject("scmSource");
		if (scmSourceJson == null) {
			scmSource = null;
		} else {
			int value = Integer.parseInt(scmSourceJson.getString("value"));
			SCMSourceDescriptor descriptor = getSCMSourceDescriptors(true).get(
					value);
			scmSource = descriptor.newInstance(req, scmSourceJson);
			scmSource.setOwner(this);
		}
	}

	// TODO doc
	private synchronized void restartSyncBranchesTrigger(String cronTabSpec)
			throws IOException, ANTLRException {
		for (Trigger trigger : triggers()) {
			trigger.stop();
		}

		if (cronTabSpec != null) {
			// triggers() should only have our single SyncBranchesTrigger
			triggers().clear();

			addTrigger(new SyncBranchesTrigger(cronTabSpec));
		}

		for (Trigger trigger : triggers()) {
			//noinspection unchecked
			trigger.start(this, false);
		}
	}

	// TODO doc
	private synchronized SyncBranchesTrigger getSyncBranchesTrigger() {
		if (triggers().size() != 1
				|| !(triggers().get(0) instanceof SyncBranchesTrigger)
				|| triggers().get(0).getSpec() == null) {
			// triggers() isn't valid (for us), so let's fix it
			String spec = DEFAULT_SYNC_SPEC;
			if (triggers().size() > 1) {
				for (Trigger trigger : triggers()) {
					if (trigger instanceof SyncBranchesTrigger
							&& trigger.getSpec() != null) {
						spec = trigger.getSpec();
						break;
					}
				}
			}

			// Errors shouldn't occur here since spec should be valid
			try {
				restartSyncBranchesTrigger(spec);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING,
						"Failed to add trigger SyncBranchesTrigger", e);
			} catch (ANTLRException e) {
				LOGGER.log(Level.WARNING,
						"Failed to instantiate SyncBranchesTrigger", e);
			}
		}

		return (SyncBranchesTrigger) triggers().get(0);
	}

	/**
	 * Synchronizes the available sub-projects by checking if the project is
	 * disabled, then calling {@link #_syncBranches(TaskListener)} and logging
	 * its exceptions to the listener.
	 */
	public synchronized void syncBranches(TaskListener listener) {
		if (!isBuildable()) {
			listener.getLogger().println("Project disabled.");
			return;
		}

		try {
			_syncBranches(listener);
		} catch (InterruptedException e) {
			e.printStackTrace(listener.fatalError(e.getMessage()));
		} catch (IOException e) {
			e.printStackTrace(listener.fatalError(e.getMessage()));
		}
	}

	/**
	 * Synchronizes the available sub-projects with the available branches and
	 * updates all sub-project configurations with the configuration specified
	 * by this project.
	 */
	private synchronized void _syncBranches(TaskListener listener)
			throws IOException, InterruptedException {
		// TODO: This method is a mess
		// TODO: Log the fetch and all the other good stuff

		// No SCM to source from, so delete all the branch projects
		if (scmSource == null) {
			for (FreeStyleBranchProject project : getSubProjects().values()) {
				project.delete();
			}

			getSubProjects().clear();

			return;
		}

		// Check SCM for branches
		Set<SCMHead> heads = scmSource.fetch(listener);

		// TODO: look at name encode/decode, test branch names with odd characters
		/*
		 * Rather than creating a new Map for subProjects and swapping with
		 * the old one, always use getSubProjects() so synchronization is
		 * maintained.
		 */
		Map<String, SCMHead> branches = new HashMap<String, SCMHead>();
		for (SCMHead head : heads) {
			String branchName = head.getName();
			branches.put(branchName, head);

			if (!getSubProjects().containsKey(branchName)) {
				// Add new projects
				getSubProjects().put(branchName,
						new FreeStyleBranchProject(this, branchName));
			}
		}

		// Delete all the sub-projects for branches that no longer exist
		Iterator<Map.Entry<String, FreeStyleBranchProject>> iter = getSubProjects().entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, FreeStyleBranchProject> entry = iter.next();

			if (!branches.containsKey(entry.getKey())) {
				iter.remove();
				entry.getValue().delete();
			}
		}

		// Sync config for existing branch projects
		// TODO: Finish
		for (FreeStyleBranchProject project : getSubProjects().values()) {
			/*
			 * Copy the settings to the projects already in memory.  Everything
			 * is set by reference, so all the child projects will get the same
			 * objects.  We don't want this.
			 */
			project.setScm(scmSource.build(branches.get(project.getName())));
			// project.getTriggersList().replaceBy(subProjectTriggers());
			project.getBuildWrappersList().replaceBy(getBuildWrappersList());
			project.getBuildersList().replaceBy(getBuildersList());
			project.getPublishersList().replaceBy(getPublishersList());

			// Save configuration to file
			project.save();

			// Reload from file so new objects get created for each project
			// TODO: does not work, find solution
			project.getConfigFile().unmarshal(project);
		}
	}

	/**
	 * Used by Jelly to populate the Sync Branches Schedule field on the
	 * configuration page.
	 *
	 * @return String - cron
	 */
	@SuppressWarnings(UNUSED)
	public String getSyncBranchesCron() {
		return getSyncBranchesTrigger().getSpec();
	}

	/**
	 * Our project's descriptor.
	 */
	@Extension
	public static class DescriptorImpl extends AbstractProjectDescriptor {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public String getDisplayName() {
			return Messages.FreeStyleMultiBranchProject_DisplayName();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public TopLevelItem newInstance(ItemGroup parent, String name) {
			return new FreeStyleMultiBranchProject(parent, name);
		}
	}

	/**
	 * Gives this class an alias for configuration XML.
	 */
	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	@SuppressWarnings(UNUSED)
	public static void registerXStream() {
		Items.XSTREAM.alias("freestyle-multi-branch-project",
				FreeStyleMultiBranchProject.class);
	}

	/**
	 * Returns a list of SCMSourceDescriptors that we want to use for this
	 * project type.  Used by configure-entries.jelly.
	 */
	public static List<SCMSourceDescriptor> getSCMSourceDescriptors(
			boolean onlyUserInstantiable) {
		List<SCMSourceDescriptor> descriptors = SCMSourceDescriptor.forOwner(
				FreeStyleMultiBranchProject.class, onlyUserInstantiable);

		/*
		 * No point in having SingleSCMSource as an option, so axe it.
		 * Might as well use the regular FreeStyleProject if you really want
		 * this...
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
	 * Returns a list of ViewDescriptors that we want to use for this project
	 * type.  Used by newView.jelly.
	 */
	public static List<ViewDescriptor> getViewDescriptors() {
		return Arrays.asList(
				(ViewDescriptor) Jenkins.getInstance().getDescriptorByType(
						BranchListView.DescriptorImpl.class));
	}
}
