plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("info.solidsoft.pitest") version "1.19.0"
}

group = "dev.ericsanchez"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

extra["springCloudVersion"] = "2025.1.3"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.wiremock:wiremock-standalone:3.13.1")
	testImplementation("org.awaitility:awaitility")
	testImplementation("com.tngtech.archunit:archunit:1.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Coverage of the whole test suite (unit and Testcontainers integration tests run in the same JVM).
jacoco {
	toolVersion = "0.8.15"
}

tasks.test {
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		csv.required = true
		html.required = true
	}
}

// Line coverage measured on 2026-10-14: 89.2% to 89.4% (1027 to 1029 of 1151 lines, depending on timing). The gate
// is that value rounded down to the ten.
tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	violationRules {
		rule {
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "0.80".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}

// Mutation testing of the pure logic only (run with ./gradlew pitest; not part of build).
pitest {
	pitestVersion = "1.30.0"
	junit5PluginVersion = "1.2.2"
	targetClasses = setOf(
		"dev.ericsanchez.ordersync.transform.*",
		"dev.ericsanchez.ordersync.retry.BackoffPolicy",
		"dev.ericsanchez.ordersync.webhook.HmacVerifier",
	)
	// Exact class names: a package glob would pull in the Testcontainers integration tests of the same packages.
	targetTests = setOf(
		"dev.ericsanchez.ordersync.transform.MoneyParserTest",
		"dev.ericsanchez.ordersync.transform.OrderTransformerTest",
		"dev.ericsanchez.ordersync.retry.BackoffPolicyTest",
		"dev.ericsanchez.ordersync.webhook.HmacVerifierTest",
	)
	outputFormats = setOf("XML", "HTML")
	timestampedReports = false
	// Measured on 2026-10-14: 100% (79 of 79 mutants killed). The gate is that score minus 5.
	mutationThreshold = 95
}
