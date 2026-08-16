# Wikantik Aggregate Coverage Report

Build-time only — this module produces no runtime artifact. It combines
unit coverage (`target/jacoco.exec` from each module) and integration
coverage (`target/jacoco-it.exec`, written by the JaCoCo agent on the
Cargo-launched Tomcat JVM during the IT phase) into a single cross-module
report against the production classes.

Activated by the `coverage` profile; run after a coverage build plus the IT
runs, e.g. `mvn clean install -Pcoverage -DskipITs -T 1C`.
