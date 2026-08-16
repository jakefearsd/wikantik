```
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
```

# 1. IDE Specific

| Maven Command       | Description                                                        |
|---------------------|--------------------------------------------------------------------|
| mvn eclipse:eclipse | generates Eclipse project files (alternatively, you could use m2e) |
| mvn idea:idea       | generates IDEA IntelliJ project files                              |


# 2. Build Specific

| Maven Command (1)                                               | Description                                                                                                                         |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| mvn                                                             | performs a default build (root pom `defaultGoal`: `verify apache-rat:check` — note: no implicit `clean`)                            |
| mvn clean install                                               | performs a build                                                                                                                    |
| mvn clean install -DskipTests                                   | performs a build, skipping test execution but still compiling tests and building test-jars (preferred — use this instead of `-Dmaven.test.skip`) |
| mvn clean install -Dmaven.test.skip                             | performs a build, skipping the tests (discouraged — also skips building test-jars, breaking the reactor when other modules depend on them) |
| mvn clean test                                                  | compiles the source and executes the tests                                                                                          |
| mvn test -Dtest=WikantikMarkupParserTest                         | run just a single test class                                                                                                        |
| mvn test -Dtest=WikantikMarkupParserTest#testHeadingHyperlinks3  | run just a single test within a test class                                                                                          |
| mvn test -Dtest=TestClassName#methodName -Dmaven.surefire.debug | debug a test in Eclipse or IDEA to see why it's failing (see http://www.jroller.com/gmazza/entry/jpa_and_junit#debugging)           |
| mvn org.codehaus.cargo:cargo-maven3-plugin:run                  | (from main war module) starts Wikantik on a Cargo-launched Tomcat 11 instance at http://localhost:8080/Wikantik with an attached debugger on port 5005 |
| mvn clean deploy -Papache-release -Dgpg.passphrase=<passphrase> | deploys generated artifact to a repository. If -Dgpg.passphrase is not given, expects a gpg-agent running                           |
| mvn clean install -Pintegration-tests                           | runs the full integration-test reactor (single-threaded). This is a slow fallback — the canonical, parallel-safe IT gate is `bin/run-tests.sh --parallel 4` (see CLAUDE.md "Testing Commands"); do not bolt `-T` onto this command directly (2) |
| mvn test -Dtest=MemoryProfiling                                 | (from wikantik-main module) runs a memory profiling test                                                                             |

(1) `-T 1C` can be added to most of these commands in order to run a parallel build, thus decreasing build time, i.e., `mvn clean install -T 1C`.

(2) Exception: never add `-T` to a raw `mvn ... -Pintegration-tests` invocation. Only `bin/run-tests.sh --parallel N` gives each IT module a reserved port set and a uniquely-named pgvector container; a bare parallel Maven build corrupts shared IT state.

# 3. Reports Specific

| Maven Command                                           | Description                                                                                         |
|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| mvn apache-rat:check                                    | creates an Apache RAT report. See: http://creadur.apache.org/rat/apache-rat-plugin/plugin-info.html |
| mvn clean install -Pcoverage                             | generates the live JaCoCo coverage report (aggregated cross-module in `wikantik-coverage-report`); the `cobertura-maven-plugin` is declared in `pluginManagement` but is not the tool actually used for coverage here |
| mvn javadoc:javadoc                                     | creates javadocs adding some UML class/package level diagrams                                       |
| mvn sonar:sonar                                         | generates a Sonar report. Expects a Sonar server running at http://localhost:9000/                  |
