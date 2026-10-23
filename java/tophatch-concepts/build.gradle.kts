plugins {
	`java-library`
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(libs.json)
	implementation(libs.msgpack.core)
	testImplementation(libs.junit.jupiter)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

// Private fixtures live in git-annex and may be absent (or dangling
// symlinks), so they are read from the filesystem rather than as
// resources; tests skip whatever they cannot find.
val fixtureRootProperty = "org.rapidcow.archive.bridge.fixtureRoot"

tasks.named<Test>("test") {
	useJUnitPlatform()
	val fixtureRoot = file(providers.gradleProperty(fixtureRootProperty)
		.getOrElse("src/test/fixtures"))
	systemProperty(fixtureRootProperty, fixtureRoot.absolutePath)
	inputs.files(provider {
		fixtureRoot.walkTopDown().filter { it.isFile }.toList()
	}).withPropertyName("optionalFixtures")
}
