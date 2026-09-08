plugins {
	id("java-gradle-plugin")
	id("maven-publish")
}

dependencies {
	implementation("com.squareup.moshi:moshi:1.15.1")
	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
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
