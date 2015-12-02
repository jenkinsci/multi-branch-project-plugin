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

### Why not use the Branch API Plugin?

This project was first started from scratch.  After discovering the Branch API,
the implementation was changed to utilize the API.  However, there were some
issues with API that caused the switch back to an independent plugin:

* The vision for this plugin is one consolidated configuration for all branches
  combined with support for the same build wrappers, builders, publishers, etc.
  that you would see in the "stock" Jenkins project types.  There does not seem
  to be a clear way to do this with the API.  This plugin uses an internal
  template project, whose XML is applied directly to the branch projects, making
  it possible to use all the same wrappers, builders, and publishers (including
  configuration from community plugins).
* The API is still experimental (it has its fair share of _TODOs_ in the
  source).  This plugin aims to be suitable for a production environment as soon
  as possible, without dependencies on the progress of the API's development.
* This plugin favors simplicity in configuration over the complexity seen in the
  API.  Admittedly, the API offers a lot of flexibility and nice-to-haves when
  it comes to configuring the project's SCM, but it seems like overkill.

If the Literate Plugin and the Branch API Plugin become stable, and the API can
be modified to support the functionality described in the first point, there is
a likelihood that this plugin will adopt the API.
