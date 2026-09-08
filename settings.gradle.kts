rootProject.name = "build-tools"

pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}

include("gradle-plugins:versioning")
include("gradle-plugins:artifact")
