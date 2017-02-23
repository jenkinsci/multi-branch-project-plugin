/*
 * The MIT License
 *
 * Copyright (c) 2004-2016, Sun Microsystems, Inc., Kohsuke Kawaguchi,
 * Brian Westrich, Martin Eigenbrodt, Matthew DeTullio, Stephen Connolly
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

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.FolderIcon;
import com.cloudbees.hudson.plugins.folder.FolderIconDescriptor;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BallColor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.StringTokenizer;

/**
 * {@link FolderIcon} that actually shows a {@link BallColor} status icon, calculated from
 * the specified children.
 *
 * @author Matthew DeTullio
 */
@SuppressWarnings("unused")
public final class SelectJobsBallColorFolderIcon extends FolderIcon {
    private static final String UNUSED = "unused";

    private AbstractFolder<?> owner;

    private String jobs;

    /**
     * Constructor used only for data binding.
     *
     * @param jobs the jobs.
     */
    @SuppressWarnings(UNUSED)
    @DataBoundConstructor
    public SelectJobsBallColorFolderIcon(String jobs) {
        this.jobs = jobs;
    }

    /**
     * Gets the selected jobs.
     *
     * @return the selected jobs
     */
    public String getJobs() {
        return jobs;
    }

    @Override
    public void setOwner(AbstractFolder<?> folder) {
        this.owner = folder;
    }

    /**
     * Delegates the image to the {@link #owner}'s {@link BallColor}.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public String getImageOf(String size) {
        if (owner == null) {
            return BallColor.GREY.getImageOf(size);
        }

        return calculateBallColor().getImageOf(size);
    }

    /**
     * Delegates the description to the {@link #owner}'s {@link BallColor}.
     * <br>
     * {@inheritDoc}
     */
    @Override
    public String getDescription() {
        if (owner == null) {
            return BallColor.GREY.getDescription();
        }

        return calculateBallColor().getDescription();
    }

    /**
     * Calculates the color of the status ball for the owner based on selected descendants.
     * <br>
     * Logic kanged from Branch API (original author Stephen Connolly).
     *
     * @return the color of the status ball for the owner.
     */
    @Nonnull
    private BallColor calculateBallColor() {
        if (owner instanceof TemplateDrivenMultiBranchProject
                && ((TemplateDrivenMultiBranchProject) owner).isDisabled()) {
            return BallColor.DISABLED;
        }

        BallColor c = BallColor.DISABLED;
        boolean animated = false;

        StringTokenizer tokens = new StringTokenizer(Util.fixNull(jobs), ",");
        while (tokens.hasMoreTokens()) {
            String jobName = tokens.nextToken().trim();
            TopLevelItem item = owner.getItem(jobName);
            if (item != null && item instanceof Job) {
                BallColor d = ((Job) item).getIconColor();
                animated |= d.isAnimated();
                d = d.noAnime();
                if (d.compareTo(c) < 0) {
                    c = d;
                }
            }
        }

        if (animated) {
            c = c.anime();
        }

        return c;
    }

    /**
     * Registers a descriptor to appear in the "Icon" dropdown on the configuration page.
     */
    @SuppressWarnings(UNUSED)
    @Extension
    public static class DescriptorImpl extends FolderIconDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Aggregate Ball Color Status Icon (Select Jobs)";
        }

        /**
         * Form validation method.  Similar to
         * {@link hudson.tasks.BuildTrigger.DescriptorImpl#doCheck(AbstractProject, String)}.
         *
         * @param folder the folder being configured
         * @param value  the user-entered value
         * @return validation result
         */
        public FormValidation doCheck(@AncestorInPath AbstractFolder folder, @QueryParameter String value) {
            // Require CONFIGURE permission on this project
            if (!folder.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }

            boolean hasJobs = false;

            StringTokenizer tokens = new StringTokenizer(Util.fixNull(value), ",");
            while (tokens.hasMoreTokens()) {
                String jobName = tokens.nextToken().trim();

                if (StringUtils.isNotBlank(jobName)) {
                    Item item = Jenkins.getActiveInstance().getItem(jobName, (ItemGroup) folder, Item.class);

                    if (item == null) {
                        Job nearest = Items.findNearest(Job.class, jobName, folder);
                        String alternative = nearest != null ? nearest.getRelativeNameFrom((ItemGroup) folder) : "?";
                        return FormValidation.error(
                                hudson.tasks.Messages.BuildTrigger_NoSuchProject(jobName, alternative));
                    }

                    if (!(item instanceof Job)) {
                        return FormValidation.error(hudson.tasks.Messages.BuildTrigger_NotBuildable(jobName));
                    }

                    hasJobs = true;
                }
            }

            if (!hasJobs) {
                return FormValidation.error(hudson.tasks.Messages.BuildTrigger_NoProjectSpecified());
            }

            return FormValidation.ok();
        }

        /**
         * Auto completion for jobs.
         *
         * @param value     the user-entered value
         * @param container the folder being configured
         * @return candidates inside container based on value
         */
        public AutoCompletionCandidates doAutoCompleteJobs(@QueryParameter String value, @AncestorInPath ItemGroup container) {
            return AutoCompletionCandidates.ofJobNames(Job.class, value, container);
        }
    }
}
