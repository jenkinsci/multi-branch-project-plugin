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

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;

import hudson.FilePath;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.TopLevelItemDescriptor;
import hudson.util.FormValidation;

/**
 * @author Matthew DeTullio
 */
public class MavenMultiBranchProject extends
		AbstractMultiBranchProject<MavenModuleSet, MavenModuleSetBuild> {

	private static final String CLASSNAME = MavenMultiBranchProject.class.getName();
	private static final Logger LOGGER = Logger.getLogger(CLASSNAME);

	private static final String UNUSED = "unused";

	/**
	 * Constructor that specifies the {@link hudson.model.ItemGroup} for this
	 * project and the project name.
	 *
	 * @param parent - the project's parent {@link hudson.model.ItemGroup}
	 * @param name   - the project's name
	 */
	public MavenMultiBranchProject(ItemGroup parent, String name) {
		super(parent, name);
	}

	@Override
	protected MavenModuleSet createNewSubProject(
			AbstractMultiBranchProject parent, String branchName) {
		return new MavenModuleSet(parent, branchName);
	}

	protected Class<MavenModuleSetBuild> getBuildClass() {
		return MavenModuleSetBuild.class;
	}

	@Override
	public TopLevelItemDescriptor getDescriptor() {
		//		return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(
		//				MavenMultiBranchProject.class);
		return null;
	}

	/**
	 * Stapler URL binding used by the configure page to check the location of
	 * the POM, alternate settings file, etc - any file.
	 *
	 * @param value - file to check
	 * @return validation of file
	 */
	@SuppressWarnings(UNUSED)
	public FormValidation doCheckFileInWorkspace(@QueryParameter String value)
			throws IOException,
			ServletException {
		// TODO probably not great
		return FormValidation.ok();
	}

	// Commented out descriptor to disable this project type.
	//	/**
	//	 * Our project's descriptor.
	//	 */
	//	@Extension
	//	public static class DescriptorImpl extends AbstractProjectDescriptor {
	//		/**
	//		 * {@inheritDoc}
	//		 */
	//		@Override
	//		public String getDisplayName() {
	//			return Messages.MavenMultiBranchProject_DisplayName();
	//		}
	//
	//		/**
	//		 * {@inheritDoc}
	//		 */
	//		@Override
	//		public TopLevelItem newInstance(ItemGroup parent, String name) {
	//			return new MavenMultiBranchProject(parent, name);
	//		}
	//	}

	/**
	 * Gives this class an alias for configuration XML.
	 */
	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	@SuppressWarnings(UNUSED)
	public static void registerXStream() {
		Items.XSTREAM.alias("maven-multi-branch-project",
				MavenMultiBranchProject.class);
	}
}
