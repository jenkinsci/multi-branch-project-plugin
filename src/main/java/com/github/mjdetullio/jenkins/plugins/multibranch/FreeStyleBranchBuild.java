/*
 * The MIT License
 *
 * Copyright (c) 2013, Matthew DeTullio
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
import java.util.Calendar;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Build;
import hudson.model.Items;

/**
 * Wrapper for the {@link Build} class for use with {@link
 * FreeStyleBranchProject}s, which are sub-projects of {@link
 * FreeStyleMultiBranchProject}s.
 *
 * @author Matthew DeTullio
 */
public class FreeStyleBranchBuild extends
		Build<FreeStyleBranchProject, FreeStyleBranchBuild> {

	private static final String UNUSED = "unused";

	/**
	 * Constructor that specifies the project the build is assigned to.
	 *
	 * @param project - the project this build is assigned to
	 */
	@SuppressWarnings(UNUSED)
	public FreeStyleBranchBuild(FreeStyleBranchProject project)
			throws IOException {
		super(project);
	}

	/**
	 * Constructor that specifies the project the build is assigned to and the
	 * timestamp.
	 *
	 * @param job       - the project this build is assigned to
	 * @param timestamp - timestamp of the build
	 */
	@SuppressWarnings(UNUSED)
	public FreeStyleBranchBuild(FreeStyleBranchProject job,
			Calendar timestamp) {
		super(job, timestamp);
	}

	/**
	 * Constructor that specifies the project the build is assigned to and the
	 * build directory.  Used for loading logs of completed builds.
	 *
	 * @param project  - the project this build is assigned to
	 * @param buildDir - the directory this build resides in
	 */
	@SuppressWarnings(UNUSED)
	public FreeStyleBranchBuild(FreeStyleBranchProject project, File buildDir)
			throws IOException {
		super(project, buildDir);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void run() {
		execute(new BranchBuildExecution());
	}

	/**
	 * Defines a standard {@link BuildExecution} with no additional function.
	 * This is required due to BuildExecution being <code>protected</code>.
	 */
	public class BranchBuildExecution extends BuildExecution {
		// Intentionally blank
	}

	/**
	 * Gives this class an alias for configuration XML.
	 */
	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	@SuppressWarnings(UNUSED)
	public static void registerXStream() {
		Items.XSTREAM.alias("freestyle-branch-build",
				FreeStyleBranchBuild.class);
	}
}
