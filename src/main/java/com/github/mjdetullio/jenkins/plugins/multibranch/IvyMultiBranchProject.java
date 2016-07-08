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
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.ivy.IvyModuleSet;
import hudson.ivy.IvyModuleSetBuild;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import hudson.util.FormValidation;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.MultiBranchProjectDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;


/**
 * @author Florian Bühlmann
 */
public final class IvyMultiBranchProject extends TemplateDrivenMultiBranchProject<IvyModuleSet, IvyModuleSetBuild> {

    private static final String UNUSED = "unused";

    /**
     * Constructor that specifies the {@link hudson.model.ItemGroup} for this
     * project and the project name.
     *
     * @param parent the project's parent {@link hudson.model.ItemGroup}
     * @param name   the project's name
     */
    public IvyMultiBranchProject(ItemGroup parent, String name) {
        super(parent, name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected IvyModuleSet newTemplate() {
        return new IvyModuleSet(this, TemplateDrivenMultiBranchProject.TEMPLATE);
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    protected BranchProjectFactory<IvyModuleSet, IvyModuleSetBuild> newProjectFactory() {
        return new IvyBranchProjectFactory();
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public MultiBranchProjectDescriptor getDescriptor() {
        return (DescriptorImpl) Jenkins.getActiveInstance().getDescriptorOrDie(IvyMultiBranchProject.class);
    }

    /**
     * Stapler URL binding used by the configure page to check the location of
     * any file.
     *
     * @param value file to check
     * @return validation of file
     */
    @SuppressWarnings(UNUSED)
    public FormValidation doCheckFileInWorkspace(@QueryParameter String value) {
        // Probably not great
        return FormValidation.ok();
    }

    /**
     * Stapler URL binding used by the configure page to check the location of
     * alternate settings file.
     *
     * @param value file to check
     * @return validation of file
     */
    @SuppressWarnings(UNUSED)
    public FormValidation doCheckIvySettingsFile(@QueryParameter String value) {
        String v = Util.fixEmpty(value);
        if ((v == null) || (v.length() == 0)) {
            // Null values are allowed.
            return FormValidation.ok();
        }
        if (v.startsWith("/") || v.startsWith("\\") || v.matches("^\\w\\:\\\\.*")) {
            return FormValidation.error("Ivy settings file must be a relative path.");
        }
        return FormValidation.ok();
    }

    /**
     * {@link IvyMultiBranchProject}'s descriptor.
     */
    @Extension(optional = true)
    public static class DescriptorImpl extends MultiBranchProjectDescriptor {
        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return Messages.IvyMultiBranchProject_DisplayName();
        }

        /**
         * Sets the description of this item type.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         *
         * @return A string with the Item description.
         */
        public String getDescription() {
            return Messages.IvyMultiBranchProject_Description();
        }

        /**
         * Needed to define image for new item in Jenkins 2.x.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         *
         * @return A string that represents a URL pattern to get the Item icon in different sizes.
         */
        public String getIconFilePathPattern() {
            return "plugin/multi-branch-project-plugin/images/:size/ivymultibranchproject.png";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new IvyMultiBranchProject(parent, name);
        }
    }

    /**
     * Gives this class an alias for configuration XML.
     */
    @SuppressWarnings(UNUSED)
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void registerXStream() {
        Items.XSTREAM.alias("ivy-multi-branch-project", IvyMultiBranchProject.class);
    }
}
