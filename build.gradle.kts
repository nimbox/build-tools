group = "com.nimbox.tools"

// The version is what git describes: the tag on a release commit, and the
// tag plus the distance and the hash otherwise. The same mapping the
// versioning plugin applies to its consumers; the plugin cannot version the
// build that produces it.
version = providers.exec {
	workingDir(rootDir)
	commandLine("git", "describe", "--tags", "--match", "v*", "--always", "--dirty", "--first-parent")
}.standardOutput.asText.map { raw ->
	val described = raw.trim().removePrefix("v")
	when {
		described.isEmpty() -> "0.0.0"
		described.substringBefore("-").matches(Regex("""\d+\.\d+\.\d+""")) -> described
		else -> "0.0.0-$described"
	}
}.get()

tasks.register("version") {
	group = "versioning"
	description = "Prints the project version"
	doLast { println(version) }
}

subprojects {
	group = rootProject.group
	version = rootProject.version
}
