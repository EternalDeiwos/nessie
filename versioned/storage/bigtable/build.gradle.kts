/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.tools.ant.taskdefs.condition.Os

plugins {
  id("nessie-conventions-server")
  id("nessie-jacoco")
}

extra["maven.name"] = "Nessie - Storage - BigTable"

description = "Storage implementation for BigTable."

dependencies {
  implementation(project(":nessie-versioned-storage-common"))
  implementation(project(":nessie-versioned-storage-common-serialize"))

  implementation(platform(libs.google.cloud.bigtable.bom))
  implementation("com.google.cloud:google-cloud-bigtable")

  // javax/jakarta
  compileOnly(libs.jakarta.validation.api)
  compileOnly(libs.javax.validation.api)
  compileOnly(libs.jakarta.annotation.api)
  compileOnly(libs.findbugs.jsr305)

  compileOnly(libs.errorprone.annotations)
  implementation(libs.guava)

  compileOnly(libs.testcontainers.testcontainers)
  compileOnly(libs.docker.java.api)

  compileOnly(libs.immutables.builder)
  compileOnly(libs.immutables.value.annotations)
  annotationProcessor(libs.immutables.value.processor)

  compileOnly(project(":nessie-versioned-storage-testextension"))

  intTestImplementation(project(":nessie-versioned-storage-common-tests"))
  intTestImplementation(project(":nessie-versioned-storage-testextension"))
  intTestImplementation(project(":nessie-versioned-tests"))
  intTestRuntimeOnly(libs.testcontainers.testcontainers)
  intTestRuntimeOnly(libs.docker.java.api)
  intTestImplementation(platform(libs.junit.bom))
  intTestImplementation(libs.bundles.junit.testing)
  intTestRuntimeOnly(libs.logback.classic)
}

// Testcontainers is not supported on Windows :(
if (Os.isFamily(Os.FAMILY_WINDOWS)) {
  tasks.withType<Test>().configureEach { this.enabled = false }
}

// Issue w/ testcontainers/podman in GH workflows :(
if (Os.isFamily(Os.FAMILY_MAC) && System.getenv("CI") != null) {
  tasks.named<Test>("intTest") { this.enabled = false }
}