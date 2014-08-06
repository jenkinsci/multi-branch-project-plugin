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

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Items;
import hudson.model.ListView;
import hudson.model.TopLevelItem;
import hudson.model.ViewGroup;

/**
 * Wrapper for {@link ListView} that provides additional support for listing
 * branches in multi-branch projects.
 *
 * @author Matthew DeTullio
 */
public class BranchListView extends ListView {

	private static final String UNUSED = "unused";

	/**
	 * Constructor used for loading objects of this type.
	 *
	 * @param name - Name of view
	 */
	@DataBoundConstructor
	@SuppressWarnings(UNUSED)
	public BranchListView(String name) {
		super(name);
	}

	/**
	 * Constructor used to instantiate new views with an owner.
	 *
	 * @param name  - Name of view
	 * @param owner - Owner of view
	 */
	@SuppressWarnings(UNUSED)
	public BranchListView(String name, ViewGroup owner) {
		super(name, owner);
	}

	/**
	 * Alias for {@link #getItem(String)}. This is the one used in the URL
	 * binding.
	 */
	@SuppressWarnings(UNUSED)
	public final TopLevelItem getBranch(String name) {
		return getItem(name);
	}

	/**
	 * Used by Jelly to get the correct configure URL when linking from views
	 * with no jobs.
	 *
	 * @return String - cron
	 */
	@SuppressWarnings(UNUSED)
	public String getConfigureUrl() {
		return getOwner().getUrl() + "configure";
	}

	/**
	 * Stapler URL binding
	 */
	@SuppressWarnings(UNUSED)
	public final void doNewJob() {
		throw new UnsupportedOperationException(
				"New jobs cannot be created for this project directly.");
	}

	/**
	 * Stapler URL binding
	 */
	@SuppressWarnings(UNUSED)
	public final void doCreateItem() {
		throw new UnsupportedOperationException(
				"New jobs cannot be created for this project directly.");
	}

	/**
	 * Our descriptor that is simply a duplicate of the normal {@link ListView}
	 * descriptor.
	 */
	@Extension
	public static class DescriptorImpl extends ListView.DescriptorImpl {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean isInstantiable() {
			return false;
		}
	}

	/**
	 * Gives this class an alias for configuration XML.
	 */
	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	@SuppressWarnings(UNUSED)
	public static void registerXStream() {
		Items.XSTREAM.alias("branch-list-view", BranchListView.class);
	}
}
