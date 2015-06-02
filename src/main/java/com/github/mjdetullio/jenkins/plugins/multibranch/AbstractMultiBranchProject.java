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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.text.ParseException;
import java.util.ArrayList;
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

import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.google.common.collect.ImmutableMap;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BallColor;
import hudson.model.DependencyGraph;
import hudson.model.Descriptor;
import hudson.model.HealthReport;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.model.ViewGroupMixIn;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.scm.NullSCM;
import hudson.tasks.Publisher;
import hudson.triggers.SCMTrigger;
import hudson.triggers.Trigger;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.PersistedList;
import hudson.util.TimeUnit2;
import hudson.views.DefaultViewsTabBar;
import hudson.views.ViewsTabBar;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import jenkins.model.Jenkins;
import jenkins.model.ProjectNamingStrategy;
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
public abstract class AbstractMultiBranchProject<P extends AbstractProject<P, B> & TopLevelItem, B extends AbstractBuild<P, B>>
		extends AbstractProject<P, B>
		implements TopLevelItem, ItemGroup<P>, ViewGroup, SCMSourceOwner {

	private static final String CLASSNAME = AbstractMultiBranchProject.class.getName();
	private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

	private static final String UNUSED = "unused";
	private static final String TEMPLATE = "template";
	private static final String DEFAULT_SYNC_SPEC = "H/5 * * * *";

	private boolean allowAnonymousSync;

	protected volatile SCMSource scmSource;

	protected transient P templateProject;

	// Map of branch name -> sub-project
	private transient Map<String, P> subProjects;

	private List<String> disabledSubProjects;

	private transient ViewGroupMixIn viewGroupMixIn;

	private List<View> views;

	protected volatile String primaryView;

	private transient ViewsTabBar viewsTabBar;

	/**
	 * {@inheritDoc}
	 */
	public AbstractMultiBranchProject(ItemGroup parent, String name) {
		super(parent, name);
		init();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onLoad(ItemGroup<? extends Item> parent, String name)
			throws IOException {
		super.onLoad(parent, name);
		runBranchProjectMigration();
		init();
	}

	private synchronized void runBranchProjectMigration() {
		if (disabledSubProjects == null) {
			disabledSubProjects = new PersistedList<String>(this);
		}

		List<File> subProjects = new ArrayList<File>();
		subProjects.add(getTemplateDir());

		File[] files = getBranchesDir().listFiles();
		if (files != null) {
			subProjects.addAll(Arrays.asList(files));
		}

		for (File subProjectDir : subProjects) {
			File configFile = new File(subProjectDir, "config.xml");

			if (!subProjectDir.isDirectory() || !configFile.exists()
					|| !configFile.isFile()) {
				continue;
			}

			try {
				String xml = FileUtils.readFileToString(configFile);

				xml = xml.replaceFirst(
						"(?m)^<(freestyle-branch-project|com\\.github\\.mjdetullio\\.jenkins\\.plugins\\.multibranch\\.FreeStyleBranchProject)( plugin=\".*?\")?>$",
						"<project>");
				xml = xml.replaceFirst(
						"(?m)^</(freestyle-branch-project|com\\.github\\.mjdetullio\\.jenkins\\.plugins\\.multibranch\\.FreeStyleBranchProject)>$",
						"</project>");
				xml = xml.replaceFirst(
						"(?m)^  <template>(true|false)</template>(\r?\n)", "");

				/*
				 * Previously, sub-projects would reference the parent to see if
				 * they were disabled.  Now the parent must track each
				 * sub-project to see if it should stay disabled when the parent
				 * is re-enabled.
				 *
				 * If disabled, this needs to be propagated down to the
				 * sub-projects.
				 */
				if (isDisabled()) {
					if (!subProjectDir.equals(getTemplateDir()) && xml.matches(
							"(?ms).+(\r?\n)  <disabled>true</disabled>(\r?\n).+")) {
						disabledSubProjects.add(
								rawDecode(subProjectDir.getName()));
					}

					xml = xml.replaceFirst(
							"(?m)^  <disabled>false</disabled>$",
							"  <disabled>true</disabled>");
				}

				FileUtils.writeStringToFile(configFile, xml);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Unable to migrate " + configFile, e);
			}

			String branchFullName =
					getFullName() + '/' + subProjectDir.getName();

			// Replacement mirrors jenkins.model.Jenkins#expandVariablesForDirectory
			File[] builds = new File(Util.replaceMacro(
					Jenkins.getInstance().getRawBuildsDir(),
					ImmutableMap.of(
							"JENKINS_HOME",
							Jenkins.getInstance().getRootDir().getPath(),
							"ITEM_ROOTDIR", subProjectDir.getPath(),
							"ITEM_FULLNAME", branchFullName,
							"ITEM_FULL_NAME", branchFullName.replace(':', '$')
					))).listFiles();

			if (builds == null) {
				continue;
			}

			for (File buildDir : builds) {
				File buildFile = new File(buildDir, "build.xml");

				if (!buildDir.isDirectory() || !buildFile.exists()
						|| !buildFile.isFile()) {
					continue;
				}

				try {
					String xml = FileUtils.readFileToString(buildFile);

					xml = xml.replaceFirst(
							"(?m)^<(freestyle-branch-build|com\\.github\\.mjdetullio\\.jenkins\\.plugins\\.multibranch\\.FreeStyleBranchBuild)( plugin=\".*?\")?>$",
							"<build>");
					xml = xml.replaceFirst(
							"(?m)^</(freestyle-branch-build|com\\.github\\.mjdetullio\\.jenkins\\.plugins\\.multibranch\\.FreeStyleBranchBuild)>$",
							"</build>");
					xml = xml.replaceAll(
							" class=\"(freestyle-branch-build|com\\.github\\.mjdetullio\\.jenkins\\.plugins\\.multibranch\\.FreeStyleBranchBuild)\"",
							" class=\"build\"");

					FileUtils.writeStringToFile(buildFile, xml);
				} catch (IOException e) {
					LOGGER.log(Level.WARNING, "Unable to migrate " + buildFile,
							e);
				}
			}
		}
	}

	/**
	 * Common initialization that is invoked when either a new project is
	 * created with the constructor {@link AbstractMultiBranchProject
	 * (ItemGroup, String)} or when a project is loaded from disk with {@link
	 * #onLoad(ItemGroup, String)}.
	 */
	protected synchronized void init() {
		if (subProjects == null) {
			subProjects = Collections.synchronizedMap(new LinkedHashMap<String, P>());
		}

		if (disabledSubProjects == null) {
			disabledSubProjects = new PersistedList<String>(this);
		}

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
			@Override
			protected List<View> views() {
				return views;
			}

			@Override
			protected String primaryView() {
				return primaryView;
			}

			@Override
			protected void primaryView(String name) {
				primaryView = name;
			}
		};

		try {
			if (!(new File(getTemplateDir(), "config.xml").isFile())) {
				templateProject = createNewSubProject(this, TEMPLATE);
			} else {
				//noinspection unchecked
				templateProject = (P) Items.load(this, getTemplateDir());
			}

			// Prevent tampering
			if (!(templateProject.getScm() instanceof NullSCM)) {
				templateProject.setScm(new NullSCM());
			}
			templateProject.disable();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING,
					"Failed to load template project " + getTemplateDir(), e);
		}

		if (getBranchesDir().isDirectory()) {
			for (File branch : getBranchesDir().listFiles(new FileFilter() {
				@Override
				public boolean accept(File pathname) {
					return pathname.isDirectory() && new File(pathname,
							"config.xml").isFile();
				}
			})) {
				try {
					Item item = (Item) Items.getConfigFile(branch).read();
					item.onLoad(this, rawDecode(branch.getName()));

					//noinspection unchecked
					P project = (P) item;

					subProjects.put(item.getName(), project);

					// Handle offline tampering of disabled setting
					if (isDisabled() && !project.isDisabled()) {
						project.disable();
					}
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
	 * Defines how sub-projects should be created when provided the parent and
	 * the branch name.
	 *
	 * @param parent     - this
	 * @param branchName - branch name
	 * @return new sub-project of type {@link P}
	 */
	protected abstract P createNewSubProject(AbstractMultiBranchProject parent,
			String branchName);

	/**
	 * Stapler URL binding for ${rootUrl}/job/${project}/branch/${branchProject}
	 *
	 * @param name - Branch project name
	 * @return {@link #getItem(String)}
	 */
	@SuppressWarnings(UNUSED)
	public P getBranch(String name) {
		return getItem(name);
	}

	/**
	 * Retrieves the template sub-project.  Used by configure-entries.jelly.
	 */
	@SuppressWarnings(UNUSED)
	public P getTemplate() {
		return templateProject;
	}

	/**
	 * Retrieves the collection of sub-projects for this project.
	 */
	private List<P> getSubProjects() {
		synchronized (subProjects) {
			return new ArrayList<P>(subProjects.values());
		}
	}

	private Set<String> getCurrentBranchNames() {
		synchronized (subProjects) {
			return new HashSet<String>(subProjects.keySet());
		}
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

	/**
	 * Returns the "template" directory inside the project directory.  This is
	 * the template project's directory.
	 *
	 * @return File - "template" directory inside the project directory.
	 */
	public File getTemplateDir() {
		return new File(getRootDir(), TEMPLATE);
	}

	//region ItemGroup implementation

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<P> getItems() {
		return getSubProjects();
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
	public P getItem(String name) {
		return subProjects.get(name);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public File getRootDirFor(P child) {
		// Null SCM should be the template
		if (child.getScm() == null || child.getScm() instanceof NullSCM) {
			return getTemplateDir();
		}

		// All others are branches
		return new File(getBranchesDir(), Util.rawEncode(child.getName()));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onRenamed(P item, String oldName, String newName) {
		throw new UnsupportedOperationException(
				"Renaming sub-projects is not supported.  They should only be added or deleted.");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onDeleted(P item) {
		subProjects.remove(item.getName());
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
	 * Returns the project's only SCMSource.  Used by configure-entries.jelly.
	 *
	 * @return the project's only SCMSource (may be null)
	 */
	public SCMSource getSCMSource() {
		return scmSource;
	}

	/**
	 * Gets whether anonymous sync is allowed from <code>${JOB_URL}/syncBranches</code>
	 */
	@SuppressWarnings(UNUSED)
	public boolean isAllowAnonymousSync() {
		return allowAnonymousSync;
	}

	/**
	 * Sets whether anonymous sync is allowed from <code>${JOB_URL}/syncBranches</code>.
	 *
	 * @param b - true/false
	 * @throws IOException - if problems saving
	 */
	@SuppressWarnings(UNUSED)
	public void setAllowAnonymousSync(boolean b) throws IOException {
		allowAnonymousSync = b;
		save();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isBuildable() {
		return false;
	}

	/**
	 * Stapler URL binding for creating views for our branch projects.  Unlike
	 * normal views, this only requires permission to configure the project,
	 * not
	 * create view permission.
	 *
	 * @param req - Stapler request
	 * @param rsp - Stapler response
	 * @throws IOException              - if problems
	 * @throws ServletException         - if problems
	 * @throws java.text.ParseException - if problems
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
	 * @return {@link hudson.util.FormValidation#ok()} or {@link
	 * hudson.util.FormValidation#error(String)|
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
	 * Overrides the {@link hudson.model.AbstractProject} implementation
	 * because the user is not redirected to the parent properly for this
	 * project type.
	 * <p/>
	 * Inherited docs:
	 * <p/>
	 * {@inheritDoc}
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
	 * Exposes a URI that allows the trigger of a branch sync.
	 *
	 * @param req - Stapler request
	 * @param rsp - Stapler response
	 * @throws IOException          - if problems
	 * @throws InterruptedException - if problems
	 */
	@RequirePOST
	@SuppressWarnings(UNUSED)
	public void doSyncBranches(StaplerRequest req, StaplerResponse rsp)
			throws IOException, InterruptedException {
		if (!allowAnonymousSync) {
			checkPermission(CONFIGURE);
		}
		getSyncBranchesTrigger().run();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp)
			throws ServletException, Descriptor.FormException, IOException {
		checkPermission(CONFIGURE);

		description = req.getParameter("description");

		makeDisabled(req.getParameter("disable") != null);

		allowAnonymousSync = req.getSubmittedForm().has("allowAnonymousSync");

		try {
			JSONObject json = req.getSubmittedForm();

			setDisplayName(json.optString("displayNameOrNull"));

			/*
			 * Save job properties to the parent project.
			 * Needed for things like project-based matrix authorization so the
			 * parent project's ACL works as desired.
			 */
			DescribableList<JobProperty<?>, JobPropertyDescriptor> t = new DescribableList<JobProperty<?>, JobPropertyDescriptor>(
					NOOP, getAllProperties());
			t.rebuild(req, json.optJSONObject("properties"),
					JobPropertyDescriptor.getPropertyDescriptors(
							this.getClass()));
			properties.clear();
			for (JobProperty p : t) {
				// Hack to set property owner since it is not exposed
				// p.setOwner(this)
				try {
					Field f = JobProperty.class.getDeclaredField("owner");
					f.setAccessible(true);
					f.set(p, this);
				} catch (Throwable e) {
					LOGGER.log(Level.WARNING,
							"Unable to set job property owner", e);
				}
				// End hack
				//noinspection unchecked
				properties.add(p);
			}

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
				SCMSourceDescriptor descriptor = getSCMSourceDescriptors(
						true).get(
						value);
				scmSource = descriptor.newInstance(req, scmSourceJson);
				scmSource.setOwner(this);
			}

			templateProject.doConfigSubmit(
					new TemplateStaplerRequestWrapper(req), rsp);

			save();
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
				// templateProject.doConfigSubmit(req, rsp) already does this
				//noinspection ThrowableResultOfMethodCallIgnored
				//FormApply.success(".").generateResponse(req, rsp, null);
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

		// notify the queue as the project might be now tied to different node
		Jenkins.getInstance().getQueue().scheduleMaintenance();

		// this is to reflect the upstream build adjustments done above
		Jenkins.getInstance().rebuildDependencyGraphAsync();
		//endregion AbstractProject mirror

		// TODO run this separately since it can block completion (user redirect) if unable to fetch from repository
		getSyncBranchesTrigger().run();
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
		if (isDisabled()) {
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

			for (P project : getSubProjects()) {
				listener.getLogger().println(
						"Deleting project for branch " + project.getName());
				try {
					project.delete();
				} catch (Throwable e) {
					e.printStackTrace(listener.fatalError(e.getMessage()));
				}
			}

			subProjects.clear();

			return;
		}

		// Check SCM for branches
		Set<SCMHead> heads = scmSource.fetch(listener);

		Map<String, SCMHead> branches = new HashMap<String, SCMHead>();
		Set<String> newBranches = new HashSet<String>();
		for (SCMHead head : heads) {
			String branchName = head.getName();
			branches.put(branchName, head);

			if (!subProjects.containsKey(branchName)) {
				// Add new projects
				listener.getLogger().println(
						"Creating project for branch " + branchName);
				try {
					subProjects.put(branchName,
							createNewSubProject(this, branchName));
					newBranches.add(branchName);
				} catch (Throwable e) {
					e.printStackTrace(listener.fatalError(e.getMessage()));
				}
			}
		}

		// Delete all the sub-projects for branches that no longer exist

		Set<String> projectBranchesToRemove = getCurrentBranchNames();
		projectBranchesToRemove.removeAll(branches.keySet());
		for (String branchName : projectBranchesToRemove) {
			listener.getLogger().println("Deleting project for branch " + branchName);

			try {
				P project = subProjects.remove(branchName);
				project.delete();
			} catch (Throwable e) {
				e.printStackTrace(listener.fatalError(e.getMessage()));
			}
		}


		// Sync config for existing branch projects
		XmlFile configFile = templateProject.getConfigFile();
		for (P project : getSubProjects()) {
			listener.getLogger().println(
					"Syncing configuration to project for branch "
							+ project.getName());
			try {
				boolean wasDisabled = project.isDisabled();

				configFile.unmarshal(project);

				/*
				 * Build new SCM with the URL and branch already set.
				 *
				 * SCM must be set first since getRootDirFor(project) will give
				 * the wrong location during save, load, and elsewhere if SCM
				 * remains null (or NullSCM).
				 */
				project.setScm(
						scmSource.build(branches.get(project.getName())));

				if (!wasDisabled) {
					project.enable();
				}

				// Work-around for JENKINS-21017
				project.setCustomWorkspace(
						templateProject.getCustomWorkspace());

				//noinspection unchecked
				project.onLoad(project.getParent(), project.getName());
			} catch (Throwable e) {
				e.printStackTrace(listener.fatalError(e.getMessage()));
			}
		}

		// notify the queue as the projects might be now tied to different node
		Jenkins.getInstance().getQueue().scheduleMaintenance();

		// this is to reflect the upstream build adjustments done above
		Jenkins.getInstance().rebuildDependencyGraphAsync();

		// Trigger build for new branches
		// TODO make this optional
		for (String branch : newBranches) {
			listener.getLogger().println(
					"Scheduling build for branch " + branch);
			try {
				P project = subProjects.get(branch);
				project.scheduleBuild(
						new SCMTrigger.SCMTriggerCause("New branch detected."));
			} catch (Throwable e) {
				e.printStackTrace(listener.fatalError(e.getMessage()));
			}
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
	 * Used as the color of the status ball for the project.
	 * <p/>
	 * Kanged from Branch API.
	 *
	 * @return the color of the status ball for the project.
	 */
	@Override
	@Exported(visibility = 2, name = "color")
	public BallColor getIconColor() {
		if (isDisabled()) {
			return BallColor.DISABLED;
		}

		BallColor c = BallColor.DISABLED;
		boolean animated = false;

		for (P item : getItems()) {
			BallColor d = item.getIconColor();
			animated |= d.isAnimated();
			d = d.noAnime();
			if (d.compareTo(c) < 0) {
				c = d;
			}
		}

		if (animated) {
			c = c.anime();
		}

		return c;
	}

	/**
	 * Get the current health reports for a job.
	 * <p/>
	 * Kanged from Branch API.
	 *
	 * @return the health reports. Never returns null
	 */
	@Exported(name = "healthReport")
	public List<HealthReport> getBuildHealthReports() {
		// TODO: cache reports?
		int branchCount = 0;
		int branchBuild = 0;
		int branchSuccess = 0;
		long branchAge = 0;

		for (P item : getItems()) {
			branchCount++;
			B lastBuild = item.getLastBuild();
			if (lastBuild != null) {
				branchBuild++;
				Result r = lastBuild.getResult();
				if (r != null && r.isBetterOrEqualTo(Result.SUCCESS)) {
					branchSuccess++;
				}
				branchAge += TimeUnit2.MILLISECONDS.toDays(
						lastBuild.getTimeInMillis()
								- System.currentTimeMillis());
			}
		}

		List<HealthReport> reports = new ArrayList<HealthReport>();
		if (branchCount > 0) {
			reports.add(new HealthReport(branchSuccess * 100 / branchCount,
					Messages._Health_BranchSuccess()));
			reports.add(new HealthReport(branchBuild * 100 / branchCount,
					Messages._Health_BranchBuilds()));
			reports.add(new HealthReport(Math.min(100,
					Math.max(0, (int) (100 - (branchAge / branchCount)))),
					Messages._Health_BranchAge()));
			Collections.sort(reports);
		}

		return reports;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void makeDisabled(boolean b) throws IOException {
		super.makeDisabled(b);

		Iterable<P> subProjectsView = getSubProjects();

		// Manage the sub-projects
		if (b) {
			/*
			 * Populate list only if it is empty.  Running this loop when the
			 * parent (and therefore, all sub-projects) are already disabled will
			 * add all branches.  Obviously not desirable.
			 */
			if (disabledSubProjects.isEmpty()) {
				for (P project : subProjectsView) {
					if (project.isDisabled()) {
						disabledSubProjects.add(project.getName());
					}
				}
			}

			// Always forcefully disable all sub-projects
			for (P project : subProjectsView) {
				project.disable();
			}
		} else {
			// Re-enable only the projects that weren't manually marked disabled
			for (P project : subProjectsView) {
				if (!disabledSubProjects.contains(project.getName())) {
					project.enable();
				}
			}

			// Clear the list so it can be rebuilt when parent is disabled
			disabledSubProjects.clear();

			/*
			 * Apparently the great authors of PersistedList decided you don't
			 * need to "persist" when the list is cleared...
			 */
			save();
		}
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

	//
	//
	// selective Project mirror below
	// TODO: cleanup more
	//
	//

	/**
	 * Used by SyncBranchesAction to load the sidepanel when the user is on the
	 * Sync Branches Log page.
	 *
	 * @return this
	 */
	public AbstractProject<?, ?> asProject() {
		return this;
	}

	public DescribableList<Publisher, Descriptor<Publisher>> getPublishersList() {
		// TODO: is this ok?
		return templateProject.getPublishersList();
	}

	protected void buildDependencyGraph(DependencyGraph graph) {
		// no-op
		// TODO: build for each sub-project?
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isFingerprintConfigured() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void submit(StaplerRequest req, StaplerResponse rsp)
			throws IOException,
			ServletException, Descriptor.FormException {
		// No-op
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected List<Action> createTransientActions() {
		List<Action> r = super.createTransientActions();

		for (Trigger trigger : triggers()) {
			r.addAll(trigger.getProjectActions());
		}

		return r;
	}

	//
	//
	// END: selective Project mirror
	//
	//

	/**
	 * Returns a list of SCMSourceDescriptors that we want to use for this
	 * project type.  Used by configure-entries.jelly.
	 */
	public static List<SCMSourceDescriptor> getSCMSourceDescriptors(
			boolean onlyUserInstantiable) {
		List<SCMSourceDescriptor> descriptors = SCMSourceDescriptor.forOwner(
				AbstractMultiBranchProject.class, onlyUserInstantiable);

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
	 * <p/>
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
			throw new IllegalStateException(
					"JLS specification mandates UTF-8 as a supported encoding",
					e);
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
			throw new IllegalStateException(
					"JLS specification mandates UTF-8 as a supported encoding",
					e);
		}
	}

	/**
	 * Triggered by different listeners to enforce state for multi-branch
	 * projects and their sub-projects.
	 * <ul>
	 * <li>Ensures multi-branch project's SCM is a {@link NullSCM}.</li>
	 * <li>Watches for changes to the template project and corrects the SCM and
	 * enabled/disabled state if modified.</li>
	 * <li>Looks for rogue template project in the branches directory and
	 * removes it if no such sub-project exists.</li>
	 * <li>Re-disables sub-projects if they were enabled when the parent
	 * project was disabled.</li>
	 * </ul>
	 *
	 * @param item - the item that was just updated
	 */
	public static void enforceProjectStateOnUpdated(Item item) {
		if (item instanceof AbstractMultiBranchProject) {
			AbstractMultiBranchProject project = (AbstractMultiBranchProject) item;
			if (!(project.getScm() instanceof NullSCM)) {
				try {
					project.setScm(new NullSCM());
				} catch (IOException e) {
					LOGGER.warning("Unable to correct project configuration.");
				}
			}
		}

		if (item.getParent() instanceof AbstractMultiBranchProject) {
			AbstractMultiBranchProject parent = (AbstractMultiBranchProject) item.getParent();
			AbstractProject template = parent.getTemplate();

			// Direct memory reference comparison
			if (item == template) {
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

				/*
				 * Remove template from branches directory if there isn't a
				 * sub-project with the same name.
				 */

				if (!parent.subProjects.containsKey(TEMPLATE)) {
					try {
						FileUtils.deleteDirectory(
								new File(parent.getBranchesDir(), TEMPLATE));
					} catch (IOException e) {
						LOGGER.warning("Unable to delete rogue template dir.");
					}
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
	 * Additional listener for normal changes to Items in the UI, used to
	 * enforce state for multi-branch projects and their sub-projects.
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
	 * Additional listener for changes to Items via config.xml POST, used to
	 * enforce state for multi-branch projects and their sub-projects.
	 */
	@SuppressWarnings(UNUSED)
	@Extension
	public static final class BranchProjectSaveListener extends
			SaveableListener {
		@Override
		public void onChange(Saveable o, XmlFile file) {
			if (o instanceof Item) {
				enforceProjectStateOnUpdated((Item) o);
			}
		}
	}
}
