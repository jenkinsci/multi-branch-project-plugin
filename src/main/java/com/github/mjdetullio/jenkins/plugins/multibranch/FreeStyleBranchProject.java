/*
 * The MIT License
 *
 * Copyright (c) 2014, Matthew DeTullio, Stephen Connolly
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

import java.io.IOException;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Project;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import jenkins.model.Jenkins;

/**
 * Wrapper for the {@link Project} class that imitates normal {@link
 * hudson.model.FreeStyleProject}s, but for use as a sub-project of the {@link
 * FreeStyleMultiBranchProject} type.
 *
 * @author Matthew DeTullio
 */
public class FreeStyleBranchProject
		extends Project<FreeStyleBranchProject, FreeStyleBranchBuild>
		implements TopLevelItem {

	private static final String UNUSED = "unused";

	/**
	 * Constructor that specifies the {@link ItemGroup} for this project and the
	 * project name.
	 *
	 * @param parent - the project's parent {@link ItemGroup}
	 * @param name   - the project's name
	 */
	public FreeStyleBranchProject(ItemGroup parent, String name) {
		super(parent, name);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected Class<FreeStyleBranchBuild> getBuildClass() {
		return FreeStyleBranchBuild.class;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(
				getClass());
	}

	/**
	 * Disables renaming of this project type. <p/> Inherited docs: <p/>
	 * {@inheritDoc}
	 */
	@Override
	@RequirePOST
	public void doDoRename(StaplerRequest req, StaplerResponse rsp) {
		throw new UnsupportedOperationException(
				"Renaming sub-projects is not supported.  They should only be added or deleted.");
	}

	/**
	 * Disables configuring of this project type via Stapler. <p/> Inherited
	 * docs: <p/> {@inheritDoc}
	 */
	@Override
	@RequirePOST
	public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) {
		throw new UnsupportedOperationException(
				"This sub-project configuration cannot be edited directly.");
	}

	/**
	 * Disables configuring of this project type via Stapler. <p/> Inherited
	 * docs: <p/> {@inheritDoc}
	 */
	@WebMethod(name = "config.xml")
	public void doConfigDotXml(StaplerRequest req, StaplerResponse rsp)
			throws IOException {
		if (req.getMethod().equals("POST")) {
			// submission
			throw new UnsupportedOperationException(
					"This sub-project configuration cannot be edited directly.");
		}
		super.doConfigDotXml(req, rsp);
	}

	/**
	 * This project type has to be a {@link TopLevelItem} in order to be shown
	 * in the multi-branch project's branch list, but {@link TopLevelItem}s are
	 * also shown in the new job list.  This hack will remove this project type
	 * from the new job list.
	 *
	 * @author Stephen Connolly
	 */
	@Extension
	public static class DescriptorImpl extends AbstractProjectDescriptor {

		/**
		 * {@inheritDoc}
		 */
		@Override
		public String getDisplayName() {
			return null;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public TopLevelItem newInstance(ItemGroup parent, String name) {
			throw new UnsupportedOperationException();
		}

		/**
		 * Method that removes this descriptor from the list of {@link
		 * hudson.model.TopLevelItemDescriptor}s because we don't want to appear
		 * as one.
		 *
		 * @throws Exception if the hack fails.
		 */
		@Initializer(after = InitMilestone.JOB_LOADED,
				before = InitMilestone.COMPLETED)
		@SuppressWarnings(UNUSED)
		public static void postInitialize() throws Exception {
			DescriptorExtensionList<TopLevelItem, TopLevelItemDescriptor> all = Items.all();
			all.remove(all.get(DescriptorImpl.class));
		}
	}

	/**
	 * Gives this class an alias for configuration XML.
	 */
	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	@SuppressWarnings(UNUSED)
	public static void registerXStream() {
		Items.XSTREAM.alias("freestyle-branch-project",
				FreeStyleBranchProject.class);
	}
}
