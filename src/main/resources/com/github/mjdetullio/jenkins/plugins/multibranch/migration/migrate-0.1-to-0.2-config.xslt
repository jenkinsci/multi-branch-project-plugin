<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:xalan="http://xml.apache.org/xslt">
    <xsl:strip-space elements="*"/>
    <xsl:output indent="yes" xalan:indent-amount="2"/>

    <xsl:template match="/*">
        <xsl:copy>
            <xsl:attribute name="plugin">
                <xsl:text>multi-branch-project-plugin@0.2</xsl:text>
            </xsl:attribute>
            <xsl:apply-templates select="child::*"/>
        </xsl:copy>
    </xsl:template>

    <!-- Do nothing with following elements, effectively deleting them -->
    <xsl:template match="/*/builders|/*/publishers|/*/buildWrappers"/>

    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>