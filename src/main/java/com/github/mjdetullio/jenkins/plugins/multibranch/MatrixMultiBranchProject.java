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
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.MultiBranchProjectDescriptor;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;

/**
 * @author Alastair D'Silva
 */
public final class MatrixMultiBranchProject extends TemplateDrivenMultiBranchProject<MatrixProject, MatrixBuild> {

    private static final String UNUSED = "unused";

    /**
     * Constructor that specifies the {@link ItemGroup} for this project and the
     * project name.
     *
     * @param parent the project's parent {@link ItemGroup}
     * @param name   the project's name
     */
    public MatrixMultiBranchProject(ItemGroup parent, String name) {
        super(parent, name);
    }

    @Override
    protected MatrixProject newTemplate() {
        return new MatrixProject(this, TemplateDrivenMultiBranchProject.TEMPLATE);
    }

    @Nonnull
    @Override
    protected BranchProjectFactory<MatrixProject, MatrixBuild> newProjectFactory() {
        return new MatrixBranchProjectFactory();
    }

    @Nonnull
    @Override
    public MultiBranchProjectDescriptor getDescriptor() {
        return (DescriptorImpl) Jenkins.getActiveInstance().getDescriptorOrDie(MatrixMultiBranchProject.class);
    }

    /**
     * {@link MatrixMultiBranchProject}'s descriptor.
     */
    @Extension(optional = true)
    public static class DescriptorImpl extends MultiBranchProjectDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.MatrixMultiBranchProject_DisplayName();
        }

        /**
         * Sets the description of this item type.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         *
         * @return A string with the Item description.
         */
        public String getDescription() {
            return Messages.MatrixMultiBranchProject_Description();
        }

        /**
         * Needed to define image for new item in Jenkins 2.x.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         *
         * @return A string that represents a URL pattern to get the Item icon in different sizes.
         */
        public String getIconFilePathPattern() {
            return "plugin/multi-branch-project-plugin/images/:size/matrixmultibranchproject.png";
        }

        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new MatrixMultiBranchProject(parent, name);
        }
    }

    /**
     * Gives this class an alias for configuration XML.
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    @SuppressWarnings(UNUSED)
    public static void registerXStream() {
        Items.XSTREAM.alias("matrix-multi-branch-project", MatrixMultiBranchProject.class);
    }
}
