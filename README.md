# Jenkins Multi-Branch Project Plugin (DEPRECATED)

This plugin is deprecated.  Please move to the
[Multibranch Pipeline](https://plugins.jenkins.io/workflow-multibranch) job type.

## Development Instructions

To build the plugin locally:

    mvn clean verify

To release the plugin:

    mvn release:prepare release:perform

To test in a local Jenkins instance:

    mvn hpi:run
