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

import java.io.IOException;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.hudson.plugins.folder.AbstractFolderDescriptor;

/**
 * @author Matthew DeTullio
 */
@SuppressWarnings("unchecked")
public final class IvyMultiBranchProject extends AbstractMultiBranchProject<IvyModuleSet, IvyModuleSetBuild> {

  /**
   * Constructor that specifies the {@link hudson.model.ItemGroup} for this project and the project name.
   *
   * @param parent the project's parent {@link hudson.model.ItemGroup}
   * @param name the project's name
   */
  public IvyMultiBranchProject(ItemGroup parent, String name) {
    super(parent, name);
  }

  @Override
  protected IvyModuleSet createNewSubProject(AbstractMultiBranchProject parent, String branchName) {
    return new IvyModuleSet(parent, branchName);
  }

  protected Class<IvyModuleSetBuild> getBuildClass() {
    return IvyModuleSetBuild.class;
  }

  @Override
  public AbstractFolderDescriptor getDescriptor() {
    return (DescriptorImpl)Jenkins.getActiveInstance().getDescriptorOrDie(IvyMultiBranchProject.class);
  }

  /**
   * Stapler URL binding used by the configure page to check the location of the POM, alternate settings file, etc - any file.
   *
   * @param value file to check
   * @return validation of file
   */
  public FormValidation doCheckFileInWorkspace(@QueryParameter String value) {
    // Probably not great
    return FormValidation.ok();
  }

  /**
   * Our project's descriptor.
   */
  @Extension(optional = true)
  public static class DescriptorImpl extends AbstractFolderDescriptor {
    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
      return Messages.IvyMultiBranchProject_DisplayName();
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
  @Initializer(before = InitMilestone.PLUGINS_STARTED)
  public static void registerXStream() {
    Items.XSTREAM.alias("ivy-multi-branch-project", IvyMultiBranchProject.class);
  }

  public FormValidation doCheckIvySettingsFile(@QueryParameter String value) throws IOException, ServletException {
    String v = Util.fixEmpty(value);
    if ((v == null) || (v.length() == 0)) {
      // Null values are allowed.
      return FormValidation.ok();
    }
    if ((v.startsWith("/")) || (v.startsWith("\\")) || (v.matches("^\\w\\:\\\\.*"))) {
      return FormValidation.error("Ivy settings file must be a relative path.");
    }
    return FormValidation.ok();
  }
}
