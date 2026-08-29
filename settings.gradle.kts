rootProject.name = "build-tools"

pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
	includeBuild("gradle-plugins/versioning")
	includeBuild("gradle-plugins/artifact")
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
