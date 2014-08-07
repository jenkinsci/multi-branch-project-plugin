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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildAuthorizationToken;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.JDK;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Label;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.model.ViewGroupMixIn;
import hudson.model.listeners.ItemListener;
import hudson.scm.SCMS;
import hudson.security.ACL;
import hudson.tasks.BuildTrigger;
import hudson.tasks.BuildWrappers;
import hudson.tasks.Builder;
import hudson.tasks.Publisher;
import hudson.triggers.Trigger;
import hudson.util.DescribableList;
import hudson.util.FormApply;
import hudson.util.FormValidation;
import hudson.views.DefaultViewsTabBar;
import hudson.views.ViewsTabBar;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import jenkins.model.BuildDiscarder;
import jenkins.model.Jenkins;
import jenkins.model.ProjectNamingStrategy;
import jenkins.scm.SCMCheckoutStrategy;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.impl.SingleSCMSource;
import net.sf.json.JSONException;
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

	private volatile SCMSource scmSource;

	private transient FreeStyleBranchProject templateProject;

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

		File templateDir = new File(getRootDir(), "template");
		try {
			if (!(new File(templateDir, "config.xml").isFile())) {
				templateProject = new FreeStyleBranchProject(this, "template");
			} else {
				templateProject = (FreeStyleBranchProject) Items.load(this,
						templateDir);
			}
			templateProject.setIsTemplate(true);
			templateProject.disable();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING,
					"Failed to load template project " + templateDir, e);
		}

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
	 * Retrieves the template sub-project.  Used by configure-entries.jelly.
	 */
	@SuppressWarnings(UNUSED)
	public FreeStyleBranchProject getTemplateProject() {
		return templateProject;
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
		if (child.isTemplate()) {
			return new File(getRootDir(), Util.rawEncode(child.getName()));
		}
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

	public SCMSource getSCMSource() {
		return scmSource;
	}

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
		//region Job mirror
		checkPermission(CONFIGURE);

		description = req.getParameter("description");

		boolean keepDependencies = req.getParameter("keepDependencies") != null;
		// Hack to set keepDependencies since it is not exposed
		try {
			Field f = Job.class.getDeclaredField("keepDependencies");
			f.setAccessible(true);
			f.set(templateProject, keepDependencies);
		} catch (Throwable e) {
			LOGGER.log(Level.WARNING, "Unable to set keepDependencies", e);
		}
		// End hack

		try {
			JSONObject json = req.getSubmittedForm();

			setDisplayName(json.optString("displayNameOrNull"));

			if (json.optBoolean("logrotate")) {
				templateProject.setBuildDiscarder(
						req.bindJSON(BuildDiscarder.class,
								json.optJSONObject("buildDiscarder")));
			} else {
				templateProject.setBuildDiscarder(null);
			}

			DescribableList<JobProperty<?>, JobPropertyDescriptor> t = new DescribableList<JobProperty<?>, JobPropertyDescriptor>(
					NOOP, getAllProperties());
			t.rebuild(req, json.optJSONObject("properties"),
					JobPropertyDescriptor.getPropertyDescriptors(
							this.getClass()));
			templateProject.getProperties().clear();
			for (JobProperty p : t) {
				// Hack to set property owner since it is not exposed
				// p.setOwner(this)
				try {
					Field f = JobProperty.class.getDeclaredField(
							"owner");
					f.setAccessible(true);
					f.set(p, templateProject);
				} catch (Throwable e) {
					LOGGER.log(Level.WARNING,
							"Unable to set job property owner", e);
				}
				// End hack

				//noinspection unchecked
				templateProject.addProperty(p);
			}

			submit(req, rsp);

			templateProject.save();
			save();
			ItemListener.fireOnUpdated(templateProject);
			// TODO could this be used to trigger syncBranches()?
			ItemListener.fireOnUpdated(this);

			String newName = req.getParameter("name");
			final ProjectNamingStrategy namingStrategy = Jenkins.getInstance().getProjectNamingStrategy();
			if (newName != null && !newName.equals(name)) {
				// check this error early to avoid HTTP response splitting.
				Jenkins.checkGoodName(newName);
				namingStrategy.checkName(newName);
				rsp.sendRedirect("rename?newName=" + URLEncoder.encode(newName,
						"UTF-8"));
			} else {
				if (namingStrategy.isForceExistingJobs()) {
					namingStrategy.checkName(name);
				}
				//noinspection ThrowableResultOfMethodCallIgnored
				FormApply.success(".").generateResponse(req, rsp, null);
			}
		} catch (JSONException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			pw.println(
					"Failed to parse form data. Please report this problem as a bug");
			pw.println("JSON=" + req.getSubmittedForm());
			pw.println();
			e.printStackTrace(pw);

			rsp.setStatus(SC_BAD_REQUEST);
			sendError(sw.toString(), req, rsp, true);
		}
		//endregion Job mirror

		//region AbstractProject mirror
		updateTransientActions();

		// TODO: does this even?
		Set<AbstractProject> upstream = Collections.emptySet();
		if (req.getParameter("pseudoUpstreamTrigger") != null) {
			upstream = new HashSet<AbstractProject>(
					Items.fromNameList(getParent(),
							req.getParameter("upstreamProjects"),
							AbstractProject.class));
		}

		// Hack to execute method convertUpstreamBuildTrigger(upstream);
		try {
			Method m = AbstractProject.class.getDeclaredMethod(
					"convertUpstreamBuildTrigger", Set.class);
			m.setAccessible(true);
			m.invoke(templateProject, upstream);
		} catch (Throwable e) {
			LOGGER.log(Level.WARNING,
					"Unable to execute convertUpstreamBuildTrigger(Set)", e);
		}
		// End hack

		// notify the queue as the project might be now tied to different node
		Jenkins.getInstance().getQueue().scheduleMaintenance();

		// this is to reflect the upstream build adjustments done above
		Jenkins.getInstance().rebuildDependencyGraphAsync();
		//endregion AbstractProject mirror

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
		templateProject.setJDK(jdk);
		if (req.getParameter("hasCustomQuietPeriod") != null) {
			templateProject.setQuietPeriod(
					Integer.parseInt(req.getParameter("quiet_period")));
		} else {
			templateProject.setQuietPeriod(null);
		}
		Integer scmCheckoutRetryCount = null;
		if (req.getParameter("hasCustomScmCheckoutRetryCount") != null) {
			scmCheckoutRetryCount = Integer.parseInt(
					req.getParameter("scmCheckoutRetryCount"));
		}
		// Hack to set retry count since it is not exposed
		try {
			Field f = AbstractProject.class.getDeclaredField(
					"scmCheckoutRetryCount");
			f.setAccessible(true);
			f.set(templateProject, scmCheckoutRetryCount);
		} catch (Throwable e) {
			LOGGER.log(Level.WARNING, "Unable to set scmCheckoutRetryCount", e);
		}
		// End hack
		templateProject.setBlockBuildWhenDownstreamBuilding(
				req.getParameter("blockBuildWhenDownstreamBuilding") != null);
		templateProject.setBlockBuildWhenUpstreamBuilding(
				req.getParameter("blockBuildWhenUpstreamBuilding") != null);

		if (req.hasParameter("customWorkspace")) {
			templateProject.setCustomWorkspace(Util.fixEmptyAndTrim(
					req.getParameter("customWorkspace.directory")));
		} else {
			templateProject.setCustomWorkspace(null);
		}

		if (json.has("scmCheckoutStrategy")) {
			templateProject.setScmCheckoutStrategy(
					req.bindJSON(SCMCheckoutStrategy.class,
							json.getJSONObject("scmCheckoutStrategy")));
		} else {
			templateProject.setScmCheckoutStrategy(null);
		}

		if (req.getParameter("hasSlaveAffinity") != null) {
			try {
				templateProject.setAssignedLabel(
						Label.parseExpression(Util.fixEmptyAndTrim(
								req.getParameter("_.assignedLabelString"))));
			} catch (ANTLRException e) {
				// must be old label or host name that includes whitespace or other unsafe chars
				templateProject.setAssignedLabel(null);
			}
		} else {
			templateProject.setAssignedLabel(null);
		}

		templateProject.setConcurrentBuild(
				req.getSubmittedForm().has("concurrentBuild"));

		//noinspection deprecation
		BuildAuthorizationToken authToken = BuildAuthorizationToken.create(req);
		// Hack to set auth token since it is not exposed
		try {
			Field f = AbstractProject.class.getDeclaredField(
					"authToken");
			f.setAccessible(true);
			f.set(templateProject, authToken);
		} catch (Throwable e) {
			LOGGER.log(Level.WARNING, "Unable to set scmCheckoutRetryCount", e);
		}
		// End hack

		templateProject.setScm(SCMS.parseSCM(req, this));

		// Keep triggers meant for sub-projects in a different list
		templateProject.getTriggersList().replaceBy(
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
		templateProject.getBuildWrappersList().rebuild(req, json,
				BuildWrappers.getFor(this));
		templateProject.getBuildersList().rebuildHetero(req, json,
				Builder.all(), "builder");
		templateProject.getPublishersList().rebuildHetero(req, json,
				Publisher.all(),
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

	/**
	 * Stops all triggers, then if a non-null spec is given clears all triggers
	 * and creates a new {@link SyncBranchesTrigger} from the spec, and finally
	 * starts all triggers.
	 */
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

	/**
	 * Checks the validity of the triggers associated with this project (there
	 * should always be one trigger of type {@link SyncBranchesTrigger}),
	 * corrects it if necessary, and returns the trigger.
	 *
	 * @return SyncBranchesTrigger that is non-null and valid
	 */
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
		} catch (Throwable e) {
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
		// No SCM to source from, so delete all the branch projects
		if (scmSource == null) {
			listener.getLogger().println("SCM not selected.");

			for (FreeStyleBranchProject project : getSubProjects().values()) {
				listener.getLogger().println(
						"Deleting project for branch " + project.getName());
				try {
					project.delete();
				} catch (Throwable e) {
					e.printStackTrace(listener.fatalError(e.getMessage()));
				}
			}

			getSubProjects().clear();

			return;
		}

		// Check SCM for branches
		Set<SCMHead> heads = scmSource.fetch(listener);

		/*
		 * TODO: look at name encode/decode, test branch names with odd characters
		 * -- needed for directory, might be entirely handled by getRootDirFor() and nothing more would be required
		 */
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
				listener.getLogger().println(
						"Creating project for branch " + branchName);
				try {
					/*
					 * TODO? create projects differently, copy like a normal job, but from the template (see ItemGroupMixIn#createTopLevelItem)
					 * -- probably not necessary since we unmarshal from config to each project below
					 */
					getSubProjects().put(branchName,
							new FreeStyleBranchProject(this, branchName));
				} catch (Throwable e) {
					e.printStackTrace(listener.fatalError(e.getMessage()));
				}
			}
		}

		// Delete all the sub-projects for branches that no longer exist
		Iterator<Map.Entry<String, FreeStyleBranchProject>> iter = getSubProjects().entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, FreeStyleBranchProject> entry = iter.next();

			if (!branches.containsKey(entry.getKey())) {
				listener.getLogger().println(
						"Deleting project for branch " + entry.getKey());
				try {
					iter.remove();
					entry.getValue().delete();
				} catch (Throwable e) {
					e.printStackTrace(listener.fatalError(e.getMessage()));
				}
			}
		}

		// Sync config for existing branch projects
		XmlFile configFile = templateProject.getConfigFile();
		for (FreeStyleBranchProject project : getSubProjects().values()) {
			listener.getLogger().println(
					"Syncing configuration to project for branch "
							+ project.getName());
			try {
				boolean wasDisabled = project.isDisabled();

				// TODO: does this even?
				configFile.unmarshal(project);
				project.setIsTemplate(false);
				if (!wasDisabled) {
					project.enable();
				}
				project.save();
				//noinspection unchecked
				project.onLoad(project.getParent(), project.getName());

				// Build new SCM with the URL and branch already set
				project.setScm(
						scmSource.build(branches.get(project.getName())));
				project.save();
			} catch (Throwable e) {
				e.printStackTrace(listener.fatalError(e.getMessage()));
			}
		}

		// notify the queue as the projects might be now tied to different node
		Jenkins.getInstance().getQueue().scheduleMaintenance();

		// this is to reflect the upstream build adjustments done above
		Jenkins.getInstance().rebuildDependencyGraphAsync();
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

	//
	// TODO: ball color and weather for parent
	//

	//
	// TODO: take action on child projects when enabling/disabling parent
	//

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
