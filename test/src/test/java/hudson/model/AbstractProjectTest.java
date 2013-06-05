/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
package hudson.model;

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequestSettings;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.security.*;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Shell;
import hudson.scm.NullSCM;
import hudson.Launcher;
import hudson.FilePath;
import hudson.Functions;
import hudson.Util;
import hudson.tasks.ArtifactArchiver;
import hudson.util.StreamTaskListener;
import hudson.util.OneShotEvent;
import java.io.IOException;

import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.MemoryAssert;
import org.jvnet.hudson.test.recipes.PresetData;
import org.jvnet.hudson.test.recipes.PresetData.DataSet;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.Future;
import org.apache.commons.io.FileUtils;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import org.jvnet.hudson.test.MockFolder;

/**
 * @author Kohsuke Kawaguchi
 */
public class AbstractProjectTest extends HudsonTestCase {
    public void testConfigRoundtrip() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        Label l = jenkins.getLabel("foo && bar");
        project.setAssignedLabel(l);
        configRoundtrip((Item)project);

        assertEquals(l,project.getAssignedLabel());
    }

    /**
     * Tests the workspace deletion.
     */
    public void testWipeWorkspace() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new Shell("echo hello"));

        FreeStyleBuild b = project.scheduleBuild2(0).get();

        assertTrue("Workspace should exist by now",
                b.getWorkspace().exists());

        project.doDoWipeOutWorkspace();

        assertFalse("Workspace should be gone by now",
                b.getWorkspace().exists());
    }

    /**
     * Makes sure that the workspace deletion is protected.
     */
    @PresetData(DataSet.NO_ANONYMOUS_READACCESS)
    public void testWipeWorkspaceProtected() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        project.getBuildersList().add(new Shell("echo hello"));

        FreeStyleBuild b = project.scheduleBuild2(0).get();

        assertTrue("Workspace should exist by now",b.getWorkspace().exists());

        // make sure that the action link is protected
        new WebClient().assertFails(project.getUrl() + "doWipeOutWorkspace", HttpURLConnection.HTTP_FORBIDDEN);
    }

    /**
     * Makes sure that the workspace deletion link is not provided
     * when the user doesn't have an access.
     */
    @PresetData(DataSet.ANONYMOUS_READONLY)
    public void testWipeWorkspaceProtected2() throws Exception {
        ((GlobalMatrixAuthorizationStrategy) jenkins.getAuthorizationStrategy()).add(AbstractProject.WORKSPACE,"anonymous");

        // make sure that the deletion is protected in the same way
        testWipeWorkspaceProtected();

        // there shouldn't be any "wipe out workspace" link for anonymous user
        WebClient webClient = new WebClient();
        HtmlPage page = webClient.getPage(jenkins.getItem("test0"));

        page = (HtmlPage)page.getFirstAnchorByText("Workspace").click();
        try {
        	String wipeOutLabel = ResourceBundle.getBundle("hudson/model/AbstractProject/sidepanel").getString("Wipe Out Workspace");
           	page.getFirstAnchorByText(wipeOutLabel);
            fail("shouldn't find a link");
        } catch (ElementNotFoundException e) {
            // OK
        }
    }

    /**
     * Tests the &lt;optionalBlock @field> round trip behavior by using {@link AbstractProject#concurrentBuild}
     */
    public void testOptionalBlockDataBindingRoundtrip() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        for( boolean b : new boolean[]{true,false}) {
            p.setConcurrentBuild(b);
            submit(new WebClient().getPage(p,"configure").getFormByName("config"));
            assertEquals(b,p.isConcurrentBuild());
        }
    }

    /**
     * Tests round trip configuration of the blockBuildWhenUpstreamBuilding field
     */
    @Bug(4423)
    public void testConfiguringBlockBuildWhenUpstreamBuildingRoundtrip() throws Exception {
        FreeStyleProject p = createFreeStyleProject();        
        p.blockBuildWhenUpstreamBuilding = false;
        
        HtmlForm form = new WebClient().getPage(p, "configure").getFormByName("config");
        HtmlInput input = form.getInputByName("blockBuildWhenUpstreamBuilding");
        assertFalse("blockBuildWhenUpstreamBuilding check box is checked.", input.isChecked());
        
        input.setChecked(true);
        submit(form);        
        assertTrue("blockBuildWhenUpstreamBuilding was not updated from configuration form", p.blockBuildWhenUpstreamBuilding);
        
        form = new WebClient().getPage(p, "configure").getFormByName("config");
        input = form.getInputByName("blockBuildWhenUpstreamBuilding");
        assertTrue("blockBuildWhenUpstreamBuilding check box is not checked.", input.isChecked());
    }

    /**
     * Unless the concurrent build option is enabled, polling and build should be mutually exclusive
     * to avoid allocating unnecessary workspaces.
     */
    @Bug(4202)
    public void testPollingAndBuildExclusion() throws Exception {
        final OneShotEvent sync = new OneShotEvent();

        final FreeStyleProject p = createFreeStyleProject();
        FreeStyleBuild b1 = buildAndAssertSuccess(p);

        p.setScm(new NullSCM() {
            @Override
            public boolean pollChanges(AbstractProject project, Launcher launcher, FilePath workspace, TaskListener listener) {
                try {
                    sync.block();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }

            /**
             * Don't write 'this', so that subtypes can be implemented as anonymous class.
             */
            private Object writeReplace() { return new Object(); }
            
            @Override public boolean requiresWorkspaceForPolling() {
                return true;
            }
        });
        Thread t = new Thread() {
            @Override public void run() {
                p.pollSCMChanges(StreamTaskListener.fromStdout());
            }
        };
        try {
            t.start();
            Future<FreeStyleBuild> f = p.scheduleBuild2(0);

            // add a bit of delay to make sure that the blockage is happening
            Thread.sleep(3000);

            // release the polling
            sync.signal();

            FreeStyleBuild b2 = assertBuildStatusSuccess(f);

            // they should have used the same workspace.
            assertEquals(b1.getWorkspace(), b2.getWorkspace());
        } finally {
            t.interrupt();
        }
    }

    @Bug(1986)
    public void testBuildSymlinks() throws Exception {
        // If we're on Windows, don't bother doing this.
        if (Functions.isWindows())
            return;

        FreeStyleProject job = createFreeStyleProject();
        job.getBuildersList().add(new Shell("echo \"Build #$BUILD_NUMBER\"\n"));
        FreeStyleBuild build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        File lastSuccessful = new File(job.getRootDir(), "lastSuccessful"),
             lastStable = new File(job.getRootDir(), "lastStable");
        // First build creates links
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        FreeStyleBuild build2 = job.scheduleBuild2(0, new Cause.UserCause()).get();
        // Another build updates links
        assertSymlinkForBuild(lastSuccessful, 2);
        assertSymlinkForBuild(lastStable, 2);
        // Delete latest build should update links
        build2.delete();
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        // Delete all builds should remove links
        build.delete();
        assertFalse("lastSuccessful link should be removed", lastSuccessful.exists());
        assertFalse("lastStable link should be removed", lastStable.exists());
    }

    private static void assertSymlinkForBuild(File file, int buildNumber)
            throws IOException, InterruptedException {
        assertTrue("should exist and point to something that exists", file.exists());
        assertTrue("should be symlink", Util.isSymlink(file));
        String s = FileUtils.readFileToString(new File(file, "log"));
        assertTrue("link should point to build #" + buildNumber + ", but link was: "
                   + Util.resolveSymlink(file, TaskListener.NULL) + "\nand log was:\n" + s,
                   s.contains("Build #" + buildNumber + "\n"));
    }

    @Bug(2543)
    public void testSymlinkForPostBuildFailure() throws Exception {
        // If we're on Windows, don't bother doing this.
        if (Functions.isWindows())
            return;

        // Links should be updated after post-build actions when final build result is known
        FreeStyleProject job = createFreeStyleProject();
        job.getBuildersList().add(new Shell("echo \"Build #$BUILD_NUMBER\"\n"));
        FreeStyleBuild build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        assertEquals(Result.SUCCESS, build.getResult());
        File lastSuccessful = new File(job.getRootDir(), "lastSuccessful"),
             lastStable = new File(job.getRootDir(), "lastStable");
        // First build creates links
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
        // Archive artifacts that don't exist to create failure in post-build action
        job.getPublishersList().add(new ArtifactArchiver("*.foo", "", false, false));
        build = job.scheduleBuild2(0, new Cause.UserCause()).get();
        assertEquals(Result.FAILURE, build.getResult());
        // Links should not be updated since build failed
        assertSymlinkForBuild(lastSuccessful, 1);
        assertSymlinkForBuild(lastStable, 1);
    }

    @Bug(15156)
    public void testGetBuildAfterGC() throws Exception {
        FreeStyleProject job = createFreeStyleProject();
        job.scheduleBuild2(0, new Cause.UserIdCause()).get();
        MemoryAssert.assertGC(new WeakReference(job.getLastBuild()));
        assertTrue(job.getLastBuild() != null);
    }

    @Bug(13502)
    public void testHandleBuildTrigger() throws Exception {
        Project u = createFreeStyleProject("u"),
                d = createFreeStyleProject("d"),
                e = createFreeStyleProject("e");

        u.addPublisher(new BuildTrigger("d", Result.SUCCESS));

        jenkins.setSecurityRealm(createDummySecurityRealm());
        ProjectMatrixAuthorizationStrategy authorizations = new ProjectMatrixAuthorizationStrategy();
        jenkins.setAuthorizationStrategy(authorizations);

        authorizations.add(Jenkins.ADMINISTER, "admin");
        authorizations.add(Jenkins.READ, "user");

        // user can READ u and CONFIGURE e
        Map<Permission, Set<String>> permissions = new HashMap<Permission, Set<String>>();
        permissions.put(Job.READ, Collections.singleton("user"));
        u.addProperty(new AuthorizationMatrixProperty(permissions));

        permissions = new HashMap<Permission, Set<String>>();
        permissions.put(Job.CONFIGURE, Collections.singleton("user"));
        e.addProperty(new AuthorizationMatrixProperty(permissions));

        User user = User.get("user");
        SecurityContext sc = ACL.impersonate(user.impersonate());
        try {
            e.convertUpstreamBuildTrigger(Collections.<AbstractProject>emptySet());
        } finally {
            SecurityContextHolder.setContext(sc);
        }

        assertEquals(1, u.getPublishersList().size());
    }

    @Bug(17137)
    public void testExternalBuildDirectorySymlinks() throws Exception {
        // XXX when using JUnit 4 add: Assume.assumeFalse(Functions.isWindows()); // symlinks may not be available
        HtmlForm form = new WebClient().goTo("configure").getFormByName("config");
        File builds = createTmpDir();
        form.getInputByName("_.rawBuildsDir").setValueAttribute(builds + "/${ITEM_FULL_NAME}");
        submit(form);
        assertEquals(builds + "/${ITEM_FULL_NAME}", jenkins.getRawBuildsDir());
        FreeStyleProject p = jenkins.createProject(MockFolder.class, "d").createProject(FreeStyleProject.class, "p");
        FreeStyleBuild b1 = p.scheduleBuild2(0).get();
        File link = new File(p.getRootDir(), "lastStable");
        assertTrue(link.exists());
        assertEquals(b1.getRootDir().getAbsolutePath(), resolveAll(link).getAbsolutePath());
        FreeStyleBuild b2 = p.scheduleBuild2(0).get();
        assertTrue(link.exists());
        assertEquals(b2.getRootDir().getAbsolutePath(), resolveAll(link).getAbsolutePath());
        b2.delete();
        assertTrue(link.exists());
        assertEquals(b1.getRootDir().getAbsolutePath(), resolveAll(link).getAbsolutePath());
        b1.delete();
        assertFalse(link.exists());
    }

    private File resolveAll(File link) throws InterruptedException, IOException {
        while (true) {
            File f = Util.resolveSymlinkToFile(link);
            if (f==null)    return link;
            link = f;
        }
    }

    @Bug(17138)
    public void testExternalBuildDirectoryRenameDelete() throws Exception {
        HtmlForm form = new WebClient().goTo("configure").getFormByName("config");
        File builds = createTmpDir();
        form.getInputByName("_.rawBuildsDir").setValueAttribute(builds + "/${ITEM_FULL_NAME}");
        submit(form);
        assertEquals(builds + "/${ITEM_FULL_NAME}", jenkins.getRawBuildsDir());
        FreeStyleProject p = jenkins.createProject(MockFolder.class, "d").createProject(FreeStyleProject.class, "prj");
        FreeStyleBuild b = p.scheduleBuild2(0).get();
        File oldBuildDir = new File(builds, "d/prj");
        assertEquals(new File(oldBuildDir, b.getId()), b.getRootDir());
        assertTrue(b.getRootDir().isDirectory());
        p.renameTo("proj");
        File newBuildDir = new File(builds, "d/proj");
        assertEquals(new File(newBuildDir, b.getId()), b.getRootDir());
        assertTrue(b.getRootDir().isDirectory());
        p.delete();
        assertFalse(b.getRootDir().isDirectory());
    }

    @Bug(17575)
    public void testDeleteRedirect() throws Exception {
        createFreeStyleProject("j1");
        assertEquals("", deleteRedirectTarget("job/j1"));
        createFreeStyleProject("j2");
        Jenkins.getInstance().addView(new AllView("v1"));
        assertEquals("view/v1/", deleteRedirectTarget("view/v1/job/j2"));
        MockFolder d = Jenkins.getInstance().createProject(MockFolder.class, "d");
        d.addView(new AllView("v2"));
        d.createProject(FreeStyleProject.class, "j3");
        d.createProject(FreeStyleProject.class, "j4");
        d.createProject(FreeStyleProject.class, "j5");
        assertEquals("job/d/", deleteRedirectTarget("job/d/job/j3"));
        assertEquals("job/d/view/v2/", deleteRedirectTarget("job/d/view/v2/job/j4"));
        assertEquals("view/v1/job/d/", deleteRedirectTarget("view/v1/job/d/job/j5"));
    }
    private String deleteRedirectTarget(String job) throws Exception {
        WebClient wc = new WebClient();
        String base = wc.getContextPath();
        String loc = wc.getPage(wc.addCrumb(new WebRequestSettings(new URL(base + job + "/doDelete"), HttpMethod.POST))).getWebResponse().getUrl().toString();
        assertTrue(loc, loc.startsWith(base));
        return loc.substring(base.length());
    }

}
