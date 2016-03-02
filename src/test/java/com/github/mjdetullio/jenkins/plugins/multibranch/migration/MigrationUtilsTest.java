package com.github.mjdetullio.jenkins.plugins.multibranch.migration;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.xml.stream.XMLStreamException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Matthew DeTullio
 */
public class MigrationUtilsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testProjectConfigVersionNull() throws IOException, XMLStreamException {
        File file = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-core-config.xml");
        String version = MigrationUtils.peekAtConfigVersion(file);
        Assert.assertNull(version);
    }

    @Test
    public void testProjectConfigVersion01() throws IOException, XMLStreamException {
        File file = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.1-config.xml");
        String version = MigrationUtils.peekAtConfigVersion(file);
        Assert.assertEquals("0.1.3", version);
    }

    @Test
    public void testTransform01to02Config() throws TransformerException, IOException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.1-to-0.2-config.xslt";
        File xmlFile = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.1-config.xml");
        File tmpFile = temporaryFolder.newFile();

        MigrationUtils.doTransform(xsltPath, xmlFile, tmpFile, null);

        System.out.println("01 to 02 config");
        System.out.println(FileUtils.readFileToString(tmpFile));
    }

    @Test
    public void testTransform01to02BranchConfig() throws TransformerException, IOException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.1-to-0.2-branch-config.xslt";
        File xmlFile = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.1-branch-config.xml");
        File tmpFile = temporaryFolder.newFile();

        MigrationUtils.doTransform(xsltPath, xmlFile, tmpFile, null);

        System.out.println("01 to 02 branch config");
        System.out.println(FileUtils.readFileToString(tmpFile));
    }

    @Test
    public void testTransform01to02BranchBuild() throws TransformerException, IOException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.1-to-0.2-branch-build.xslt";
        File xmlFile = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.1-branch-build.xml");
        File tmpFile = temporaryFolder.newFile();

        MigrationUtils.doTransform(xsltPath, xmlFile, tmpFile, null);

        System.out.println("01 to 02 branch build");
        System.out.println(FileUtils.readFileToString(tmpFile));
    }

    @Test
    public void testTransform02to03Config() throws TransformerException, IOException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.2-to-0.3-config.xslt";
        File xmlFile = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.2-config.xml");
        File tmpFile = temporaryFolder.newFile();

        MigrationUtils.doTransform(xsltPath, xmlFile, tmpFile, null);

        System.out.println("02 to 03 config");
        System.out.println(FileUtils.readFileToString(tmpFile));
    }

    @Test
    public void testTransform03to04Config() throws TransformerException, IOException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.3-to-0.4-config.xslt";
        File xmlFile = new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.3-config.xml");
        File tmpFile = temporaryFolder.newFile();

        Map<String, Object> transformerParams = new HashMap<String, Object>();
        transformerParams.put("authProperty", MigrationUtils.getAuthPropertyFromTemplate(new File("src/test/resources/com/github/mjdetullio/jenkins/plugins/multibranch/migration/sample-0.3-branch-config2.xml")));

        MigrationUtils.doTransform(xsltPath, xmlFile, tmpFile, transformerParams);

        System.out.println("03 to 04 config");
        System.out.println(FileUtils.readFileToString(tmpFile));
    }
}
