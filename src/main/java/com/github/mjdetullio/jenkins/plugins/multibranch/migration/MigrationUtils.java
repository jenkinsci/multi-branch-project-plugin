package com.github.mjdetullio.jenkins.plugins.multibranch.migration;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.ItemGroup;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Matthew DeTullio
 */
public class MigrationUtils {
    private static final Logger LOGGER = Logger.getLogger(MigrationUtils.class.getName());

    private static final String CONFIG_XML = "config.xml";

    private MigrationUtils() {
        // prevent outside instantiation
    }

    @SuppressWarnings("unused")
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void migrate() {
        Map<File, String> configXmlFiles = findProjectConfigXmlFiles(Jenkins.getActiveInstance().getRootDir());
        for (Map.Entry<File, String> entry : configXmlFiles.entrySet()) {
            File configFile = entry.getKey();
            String version = entry.getValue();
            try {
                if (version.startsWith("0.1")) {
                    migrate01To02(configFile);
                    migrate02To03(configFile);
                    migrate03To04(configFile);
                } else if (version.startsWith("0.2")) {
                    migrate02To03(configFile);
                    migrate03To04(configFile);
                } else if (version.startsWith("0.3")) {
                    migrate03To04(configFile);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate project: " + configFile.getParent(), e);
            } catch (TransformerException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate project: " + configFile.getParent(), e);
            }
        }

    }

    private static void migrate01To02(File configFile) throws IOException, TransformerException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.1-to-0.2-config.xslt";
        doTransform(xsltPath, configFile, configFile, null);
    }

    public static void migrate01To02SubProjects(ItemGroup parent, String name) {
        File jobDir = new File(new File(parent.getRootDir(), "jobs"), name);
        String jobFullName = getFullName(parent, name);

        File branchesDir = new File(jobDir, "branches");
        if (!branchesDir.isDirectory()) {
            return;
        }

        File[] branchDirs = branchesDir.listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
        if (branchDirs == null) {
            return;
        }

        for (File branchDir : branchDirs) {
            File configFile = new File(branchDir, CONFIG_XML);
            if (!configFile.isFile()) {
                continue;
            }

            migrate01To02SubProject(configFile);
            migrate01To02SubProjectBuilds(branchDir, jobFullName + "/" + branchDir.getName());
        }
    }

    private static void migrate01To02SubProject(@Nonnull File configFile) {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.1-to-0.2-branch-config.xslt";
        try {
            doTransform(xsltPath, configFile, configFile, null);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to migrate sub-project from 0.1 to 0.2+: " + configFile, e);
        } catch (TransformerException e) {
            LOGGER.log(Level.WARNING, "Failed to migrate sub-project from 0.1 to 0.2+: " + configFile, e);
        }
    }

    private static void migrate01To02SubProjectBuilds(@Nonnull File branchDir, @Nonnull String fullName) {
        String buildsDir = Util.replaceMacro(
                Jenkins.getActiveInstance().getRawBuildsDir(),
                ImmutableMap.of(
                        "JENKINS_HOME",
                        Jenkins.getActiveInstance().getRootDir().getPath(),
                        "ITEM_ROOTDIR", branchDir.getPath(),
                        "ITEM_FULLNAME", fullName,
                        "ITEM_FULL_NAME", fullName.replace(':', '$')
                ));
        if (buildsDir == null) {
            return;
        }

        File[] buildDirs = new File(buildsDir).listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
        if (buildDirs == null) {
            return;
        }


        for (File buildDir : buildDirs) {
            File buildFile = new File(buildDir, "build.xml");
            if (!buildFile.isFile()) {
                continue;
            }

            String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.1-to-0.2-branch-build.xslt";
            try {
                doTransform(xsltPath, buildFile, buildFile, null);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate sub-project build from 0.1 to 0.2+: " + buildFile, e);
            } catch (TransformerException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate sub-project build from 0.1 to 0.2+: " + buildFile, e);
            }
        }
    }

    private static void migrate02To03(File configFile) throws IOException, TransformerException {
        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.2-to-0.3-config.xslt";
        doTransform(xsltPath, configFile, configFile, null);
    }

    private static void migrate03To04(File configFile) throws IOException, TransformerException {
        File templateConfigFile = new File(new File(configFile.getParentFile(), "template"), CONFIG_XML);
        if (!templateConfigFile.isFile()) {
            return;
        }

        Map<String, Object> transformerParams = new HashMap<String, Object>();
        transformerParams.put("authProperty", MigrationUtils.getAuthPropertyFromTemplate(templateConfigFile));

        String xsltPath = "com/github/mjdetullio/jenkins/plugins/multibranch/migration/migrate-0.3-to-0.4-config.xslt";
        doTransform(xsltPath, configFile, configFile, transformerParams);
    }


    @Nonnull
    private static Map<File, String> findProjectConfigXmlFiles(@Nonnull File searchDir) {
        Map<File, String> configFiles = new HashMap<File, String>();

        File jobsDir = new File(searchDir, "jobs");
        if (!jobsDir.isDirectory()) {
            return configFiles;
        }

        File[] jobs = jobsDir.listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
        if (jobs == null) {
            return configFiles;
        }

        for (File jobDir : jobs) {
            File configFile = new File(jobDir, CONFIG_XML);
            if (configFile.isFile()) {
                String version = null;
                try {
                    version = peekAtConfigVersion(configFile);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to peek at config file: " + configFile, e);
                } catch (XMLStreamException e) {
                    LOGGER.log(Level.WARNING, "Failed to peek at config file: " + configFile, e);
                }

                if (version == null) {
                    // Recurse (for folders support)
                    configFiles.putAll(findProjectConfigXmlFiles(jobDir));
                } else {
                    configFiles.put(configFile, version);
                }
            }
        }

        return configFiles;
    }

    @VisibleForTesting
    @Nullable
    static String peekAtConfigVersion(@Nonnull File configFile) throws XMLStreamException, IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader reader = factory.createXMLStreamReader(FileUtils.openInputStream(configFile));

        while (reader.hasNext()) {
            if (reader.getEventType() == XMLStreamConstants.START_ELEMENT) {
                return getPluginAttributeVersion(reader);
            }
            reader.next();
        }

        return null;
    }

    @Nullable
    private static String getPluginAttributeVersion(@Nonnull XMLStreamReader reader) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attrLocalName = reader.getAttributeLocalName(i);
            if ("plugin".equals(attrLocalName)) {
                String attrValue = reader.getAttributeValue(i);
                if (attrValue.startsWith("multi-branch-project-plugin@")) {
                    return attrValue.substring("multi-branch-project-plugin@".length());
                }
            }
        }
        return null;
    }

    @VisibleForTesting
    static void doTransform(@Nonnull String xsltPath, @Nonnull File inputConfigFile, @Nonnull File outputConfigFile,
            @Nullable Map<String, Object> transformerParams) throws IOException, TransformerException {
        URL xsltResource = MigrationUtils.class.getClassLoader().getResource(xsltPath);
        if (xsltResource == null) {
            throw new IllegalStateException("XSLT file does not exist on classpath: " + xsltPath);
        }

        Source xsltSource = new StreamSource(xsltResource.openStream());
        xsltSource.setSystemId(xsltResource.toExternalForm());

        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer(xsltSource);
        if (transformerParams != null) {
            for (Entry<String, Object> entry : transformerParams.entrySet()) {
                transformer.setParameter(entry.getKey(), entry.getValue());
            }
        }

        Source xmlSource = new StreamSource(new FileInputStream(inputConfigFile));
        transformer.transform(xmlSource, new StreamResult(new FileOutputStream(outputConfigFile)));
    }

    @VisibleForTesting
    @Nullable
    static Node getAuthPropertyFromTemplate(@Nonnull File templateConfigFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(templateConfigFile);
            XPathFactory xPathfactory = XPathFactory.newInstance();
            XPath xpath = xPathfactory.newXPath();
            XPathExpression expr = xpath.compile("/*/properties/hudson.security.AuthorizationMatrixProperty");
            return (Node) expr.evaluate(doc, XPathConstants.NODE);
        } catch (ParserConfigurationException e) {
            LOGGER.log(Level.FINE, "Unable to setup auth property reader", e);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Unable to parse template: " + templateConfigFile, e);
        } catch (XPathExpressionException e) {
            LOGGER.log(Level.FINE, "Unable to read auth property from template: " + templateConfigFile, e);
        } catch (SAXException e) {
            LOGGER.log(Level.FINE, "Unable to parse template: " + templateConfigFile, e);
        }
        return null;
    }

    @Nonnull
    private static String getFullName(@Nonnull ItemGroup parent, @Nonnull String name) {
        String n = parent.getFullName();
        if (n.length() == 0) {
            return name;
        } else {
            return n + '/' + name;
        }
    }
}
