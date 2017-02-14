/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
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

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import java.util.Collection;
import java.util.Collections;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMEvents;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMHeadEvent;
import jenkins.scm.impl.mock.MockSCMSource;
import jenkins.scm.impl.mock.MockSCMSourceEvent;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

public class FreeStyleMultiBranchProjectTest {
    /**
     * All tests in this class only create items and do not affect other global configuration, thus we trade test
     * execution time for the restriction on only touching items.
     */
    @ClassRule
    public static JenkinsRule r = new JenkinsRule();

    @Before
    public void cleanOutAllItems() throws Exception {
        for (TopLevelItem i : r.getInstance().getItems()) {
            i.delete();
        }
    }

    @Test
    public void given_multibranch_when_addingSourcesProgrammatically_then_noIndexingIsTriggered() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            r.waitUntilNoActivity();
            assertThat("Adding sources doesn't trigger indexing",
                    prj.getItems(), is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList()));
        }
    }

    @Test
    public void given_multibranchWithSources_when_indexing_then_branchesAreFoundAndBuilt() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();
            assertThat("We now have branches",
                    prj.getItems(), not(is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList())));
            FreeStyleProject master = prj.getItem("master");
            assertThat("We now have the master branch", master, notNullValue());
            r.waitUntilNoActivity();
            assertThat("The master branch was built", master.getLastBuild(), notNullValue());
            assertThat("The master branch was built", master.getLastBuild().getNumber(), is(1));
        }
    }

    @Test
    public void given_multibranchWithSources_when_matchingEvent_then_branchesAreFoundAndBuilt() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            fire(new MockSCMHeadEvent("given_multibranchWithSources_when_matchingEvent_then_branchesAreFoundAndBuilt", SCMEvent.Type.UPDATED, c, "foo", "master", "junkHash"));
            assertThat("We now have branches",
                    prj.getItems(), not(is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList())));
            FreeStyleProject master = prj.getItem("master");
            assertThat("We now have the master branch", master, notNullValue());
            r.waitUntilNoActivity();
            assertThat("The master branch was built", master.getLastBuild(), notNullValue());
            assertThat("The master branch was built", master.getLastBuild().getNumber(), is(1));
        }
    }

    @Test
    @Ignore("Currently failing to honour contract of multi-branch projects with respect to dead branches")
    public void given_multibranchWithSources_when_accessingBranch_then_branchCannotBeDeleted() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();
            assertThat("We now have branches",
                    prj.getItems(), not(is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList())));
            FreeStyleProject master = prj.getItem("master");
            assertThat("We now have the master branch", master, notNullValue());
            r.waitUntilNoActivity();
            assertThat("The master branch was built", master.getLastBuild(), notNullValue());
            assertThat(master.getACL().hasPermission(ACL.SYSTEM, Item.DELETE), is(false));
        }
    }

    @Test
    public void given_multibranchWithSources_when_accessingDeadBranch_then_branchCanBeDeleted() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();
            assertThat("We now have branches",
                    prj.getItems(), not(is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList())));
            FreeStyleProject master = prj.getItem("master");
            assertThat("We now have the master branch", master, notNullValue());
            r.waitUntilNoActivity();
            assertThat("The master branch was built", master.getLastBuild(), notNullValue());
            assumeThat(master.getACL().hasPermission(ACL.SYSTEM, Item.DELETE), is(false));
            c.deleteBranch("foo", "master");
            fire(new MockSCMHeadEvent(
                    "given_multibranchWithSources_when_branchRemoved_then_branchProjectIsNotBuildable",
                    SCMEvent.Type.REMOVED, c, "foo", "master", "junkHash"));
            r.waitUntilNoActivity();
            assertThat(master.getACL().hasPermission(ACL.SYSTEM, Item.DELETE), is(true));
        }
    }

    @Test
    public void given_multibranchWithSources_when_accessingBranch_then_branchCanBeBuilt() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();
            assertThat("We now have branches",
                    prj.getItems(), not(is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList())));
            FreeStyleProject master = prj.getItem("master");
            assertThat("We now have the master branch", master, notNullValue());
            r.waitUntilNoActivity();
            assertThat("The master branch was built", master.getLastBuild(), notNullValue());
            assertThat(master.isBuildable(), is(true));
        }
    }

    @Test
    @Ignore("Currently failing to honour contract of multi-branch projects with respect to dead branches")
    public void given_multibranchWithSources_when_accessingDeadBranch_then_branchCannotBeBuilt() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            FreeStyleMultiBranchProject prj = r.jenkins.createProject(FreeStyleMultiBranchProject.class, "foo");
            prj.getSourcesList().add(new BranchSource(new MockSCMSource(null, c, "foo", true, false, false)));
            prj.scheduleBuild2(0).getFuture().get();
            r.waitUntilNoActivity();
            assertThat("We now have branches",
                    prj.getItems(), not(is((Collection<FreeStyleProject>) Collections.<FreeStyleProject>emptyList())));
            FreeStyleProject master = prj.getItem("master");
            assertThat("We now have the master branch", master, notNullValue());
            r.waitUntilNoActivity();
            assertThat("The master branch was built", master.getLastBuild(), notNullValue());
            assumeThat(master.isBuildable(), is(true));
            c.deleteBranch("foo", "master");
            fire(new MockSCMHeadEvent(
                    "given_multibranchWithSources_when_branchRemoved_then_branchProjectIsNotBuildable",
                    SCMEvent.Type.REMOVED, c, "foo", "master", "junkHash"));
            r.waitUntilNoActivity();
            assertThat(master.isBuildable(), is(false));
        }
    }

    private void fire(MockSCMHeadEvent event) throws Exception {
        long watermark = SCMEvents.getWatermark();
        SCMHeadEvent.fireNow(event);
        SCMEvents.awaitAll(watermark);
        r.waitUntilNoActivity();
    }

    private void fire(MockSCMSourceEvent event) throws Exception {
        long watermark = SCMEvents.getWatermark();
        SCMSourceEvent.fireNow(event);
        SCMEvents.awaitAll(watermark);
        r.waitUntilNoActivity();
    }

}
