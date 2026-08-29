plugins {
	id("java-gradle-plugin")
	id("maven-publish")
}

gradlePlugin {
	plugins {
		create("artifact") {
			id = "com.nimbox.canexer.artifact"
			implementationClass = "com.nimbox.canexer.artifact.ArtifactPlugin"
		}
	}
}

publishing {
	repositories {
		maven {
			name = "GitHubPackages"
			url = uri("https://maven.pkg.github.com/nimbox/maven")
			credentials {
				username = providers.environmentVariable("GITHUB_ACTOR").orNull
				password = providers.environmentVariable("GITHUB_TOKEN").orNull
			}
		}
	}
}
