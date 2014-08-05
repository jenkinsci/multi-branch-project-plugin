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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerFallback;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Action;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.ListView;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.View;
import hudson.model.ViewGroup;
import hudson.model.ViewGroupMixIn;
import hudson.util.FormValidation;
import hudson.views.DefaultViewsTabBar;
import hudson.views.ViewsTabBar;
import jenkins.model.Jenkins;
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
		StaplerFallback, SCMSourceOwner {

	private static final String CLASSNAME = FreeStyleMultiBranchProject.class.getName();
	private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

	private static final String UNUSED = "unused";

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
			ListView listView = new ListView("All", this);
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

		// TODO: for testing only -- populate 2 dummy projects if none were loaded
		//		if (subProjects == null) {
		//			getSubProjects().put("branch1",
		//					new FreeStyleBranchProject(this, "branch1"));
		//			getSubProjects().put("branch2",
		//					new FreeStyleBranchProject(this, "branch2"));
		//			for (FreeStyleBranchProject project : getSubProjects().values()) {
		//				try {
		//					project.save();
		//				} catch (IOException e) {
		//					e.printStackTrace();
		//				}
		//			}
		//		}
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

	// Start ItemGroup implementation

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
		// Branch projects should only be created and deleted, never renamed
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onDeleted(FreeStyleBranchProject item) {
		// TODO: More work here required?
		getSubProjects().remove(item);
	}

	// End ItemGroup implementation

	// Start ViewGroup implementation

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
		// TODO: add a way to configure the primary view
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

	// End ViewGroup implementation

	/**
	 * Default to the primary view when navigating to the project index page.
	 *
	 * @return {@link #getPrimaryView()}
	 */
	@Override
	public View getStaplerFallback() {
		return getPrimaryView();
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
	public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp)
			throws ServletException, Descriptor.FormException, IOException {
		super.doConfigSubmit(req, rsp);
		syncBranches();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void submit(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, Descriptor.FormException {
		super.submit(req, rsp);

		JSONObject json = req.getSubmittedForm();
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

	// Start SCMSourceOwner implementation

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
	public SCMSource getSCMSource(String sourceId) {
		return scmSource;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onSCMSourceUpdated(@NonNull SCMSource source) {
		try {
			syncBranches();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
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

	// End SCMSourceOwner implementation

	/**
	 * Synchronizes the available sub-projects with the available branches and
	 * updates all sub-project configurations with the configuration specified
	 * by this project.
	 */
	public synchronized void syncBranches() throws IOException {
		// TODO: Log the fetch and all the other good stuff
		try {
			// Check SCM for branches
			Set<SCMHead> heads = scmSource.fetch(null);

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
				project.setScm(
						scmSource.build(branches.get(project.getName())));
				project.getBuildWrappersList().replaceBy(
						getBuildWrappersList());
				project.getBuildersList().replaceBy(getBuildersList());
				project.getPublishersList().replaceBy(getPublishersList());
				project.save();
			}
		} catch (InterruptedException e) {
			// TODO: Log properly
			e.printStackTrace();
		}
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
	 * A periodic thread that runs {@link #syncBranches()} on each project of
	 * this type. <p/> Automatically registered on start-up by Jenkins.
	 * Recurrence cannot be adjusted after start-up.
	 */
	@Extension
	@SuppressWarnings(UNUSED)
	public static class SyncBranchesThread extends AsyncPeriodicWork {
		/**
		 * Constructor for this thread that specifies the thread name.  Logs are
		 * output to a file of the same name.
		 */
		public SyncBranchesThread() {
			super("SyncBranchesThread");
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public long getRecurrencePeriod() {
			// TODO: Make recurrence configurable, possibly with a cron setting.
			// TODO: WARNING - runtime may eventually exceed recurrence of 5 minutes
			return 5 * MIN;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void execute(TaskListener listener)
				throws IOException, InterruptedException {
			for (FreeStyleMultiBranchProject project : Jenkins.getInstance().getAllItems(
					FreeStyleMultiBranchProject.class)) {
				project.syncBranches();
			}
		}
	}

	/**
	 * Gives this class an alias for configuration XML.
	 */
	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	@SuppressWarnings(UNUSED)
	public static void registerXStream() {
		Items.XSTREAM.alias("freestyle-multibranch-project",
				FreeStyleMultiBranchProject.class);
	}

	/**
	 * Returns a list of SCMSourceDescriptors that we want to use for this
	 * project type.
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
}
