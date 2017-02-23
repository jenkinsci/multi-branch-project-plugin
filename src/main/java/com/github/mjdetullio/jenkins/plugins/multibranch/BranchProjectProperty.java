/*
 * The MIT License
 *
 * Copyright (c) 2016, Matthew DeTullio
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
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.TopLevelItem;
import jenkins.branch.Branch;

import javax.annotation.Nonnull;

/**
 * A basic {@link JobProperty} that holds a {@link Branch} so that {@link TemplateDrivenMultiBranchProject}s can
 * manage the project holding this property.
 *
 * @author Matthew DeTullio
 */
public class BranchProjectProperty<P extends AbstractProject<P, B> & TopLevelItem, B extends AbstractBuild<P, B>>
        extends JobProperty<P> {

    private Branch branch;

    /**
     * Creates a new property with the Branch it will hold.
     *
     * @param branch the branch
     */
    public BranchProjectProperty(@Nonnull Branch branch) {
        this.branch = branch;
    }

    /**
     * Gets the Branch held by the property.
     *
     * @return the branch
     */
    @Nonnull
    public Branch getBranch() {
        return branch;
    }

    /**
     * Sets the Branch held by the property.
     *
     * @param branch the branch
     */
    public void setBranch(@Nonnull Branch branch) {
        this.branch = branch;
    }

    /**
     * {@link BranchProjectProperty}'s descriptor.
     */
    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Branch";
        }
    }
}
