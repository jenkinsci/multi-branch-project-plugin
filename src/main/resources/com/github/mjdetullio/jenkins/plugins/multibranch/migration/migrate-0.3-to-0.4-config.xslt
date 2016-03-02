<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xalan="http://xml.apache.org/xslt">
    <xsl:strip-space elements="*"/>
    <xsl:output indent="yes" xalan:indent-amount="2"/>

    <xsl:param name="authProperty"/>

    <xsl:template match="/*">
        <xsl:copy>
            <xsl:attribute name="plugin">
                <xsl:text>multi-branch-project-plugin@0.4</xsl:text>
            </xsl:attribute>
            <!-- Convert sync-branches-trigger to TimerTrigger and move into triggers -->
            <xsl:element name="triggers">
                <xsl:element name="hudson.triggers.TimerTrigger">
                    <xsl:apply-templates select="/*/syncBranchesTrigger/spec"/>
                </xsl:element>
            </xsl:element>
            <!-- Add properties element with auth config -->
            <xsl:if test="not($authProperty = '')">
                <xsl:element name="properties">
                    <xsl:element name="com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty">
                        <xsl:attribute name="plugin">
                            <xsl:text>cloudbees-folder@5.1</xsl:text>
                        </xsl:attribute>
                        <xsl:apply-templates select="$authProperty/*"/>
                    </xsl:element>
                </xsl:element>
            </xsl:if>
            <!-- Whitelist remaining elements to copy, all others should be removed due to conversion of superclass from AbstractProject to AbstractItem -->
            <xsl:apply-templates select="child::*"/>
        </xsl:copy>
    </xsl:template>

    <!-- Do nothing with following elements, effectively deleting them -->
    <xsl:template match="/*/syncBranchesTrigger"/>

    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>