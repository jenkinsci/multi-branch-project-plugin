# Jenkins Multi-Branch Project Plugin

This plugin adds additional project types that create sub-projects for each
branch using a shared configuration.

## Usage

Install the plugin using one of these methods:

* Use the Jenkins plugin update center to download and install the latest
version.
* Clone this repo, manually compile the HPI file, and upload it through the
Jenkins interface on the "advanced" tab in the plugin update center to get
the newest/unreleased code.

The project types will appear in the list on the "New Job" page.  When
configuring the project, the SCM portion will be different.  This section tells
the project how to find branches to create sub-projects for.  Just about
everything else should look like a normal free-style project and will be
applied to each sub-project.

## Development Instructions

To build the plugin locally:

    mvn clean verify

To release the plugin:

    mvn release:prepare release:perform

To test in a local Jenkins instance:

    mvn hpi:run

## Credits

Thanks to Stephen Connolly for his work on the Jenkins
[Literate Plugin](https://github.com/jenkinsci/literate-plugin),
[Branch API Plugin](https://github.com/jenkinsci/branch-api-plugin), and
[SCM API Plugin](https://github.com/jenkinsci/scm-api-plugin).  These plugins
were great reference for creating this plugin's implementation.  The SCM API
is also what allows this plugin to use different SCM types for obtaining the
branch list.

Thanks to Jesse Glick for stablizing a good chunk of boilerplate code from the
Branch API Plugin and then abstracting it into the
[Folders Plugin](https://github.com/jenkinsci/cloudbees-folder-plugin).

