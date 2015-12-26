package com.github.mjdetullio.jenkins.plugins.multibranch;

import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Items;
import hudson.model.TopLevelItem;

import javax.annotation.Nonnull;

/**
 * Re-branding of {@link FolderComputation} in name only.
 *
 * @author Matthew DeTullio
 */
public final class SyncBranches<P extends AbstractProject<P, B> & TopLevelItem, B extends AbstractBuild<P, B>>
        extends FolderComputation<P> {
    public SyncBranches(TemplateDrivenMultiBranchProject<P, B> folder, SyncBranches<P, B> previous) {
        super(folder, previous);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public TemplateDrivenMultiBranchProject<P, B> getParent() {
        return (TemplateDrivenMultiBranchProject<P, B>) super.getParent();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        return "Sync Branches";
    }

    /**
     * Gives this class an alias for configuration XML.
     */
    @SuppressWarnings("unused")
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void registerXStream() {
        Items.XSTREAM.alias("sync-branches", SyncBranches.class);
    }
}
