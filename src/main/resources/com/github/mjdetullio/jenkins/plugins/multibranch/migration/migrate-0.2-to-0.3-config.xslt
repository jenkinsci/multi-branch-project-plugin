<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xalan="http://xml.apache.org/xslt">
    <xsl:strip-space elements="*"/>
    <xsl:output indent="yes" xalan:indent-amount="2"/>

    <xsl:template match="/*">
        <xsl:copy>
            <xsl:attribute name="plugin">
                <xsl:text>multi-branch-project-plugin@0.3</xsl:text>
            </xsl:attribute>
            <!-- Whitelist remaining elements to copy, all others should be removed due to conversion of superclass from AbstractProject to AbstractItem -->
            <xsl:apply-templates select="/*/actions|/*/description|/*/disabled|/*/views|/*/primaryView|/*/disabledSubProjects|/*/allowAnonymousSync|/*/scmSource"/>
            <!-- Move sync-branches-trigger out of triggers, adding as a direct child -->
            <xsl:element name="syncBranchesTrigger">
                <xsl:apply-templates select="/*/triggers/sync-branches-trigger/spec|/*/triggers/com.github.mjdetullio.jenkins.plugins.multibranch.SyncBranchesTrigger/spec"/>
            </xsl:element>
        </xsl:copy>
    </xsl:template>

    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>