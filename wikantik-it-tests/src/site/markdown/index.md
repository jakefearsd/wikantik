# Wikantik Integration Tests

Integration test suite for Wikantik. Uses Maven Cargo to start an embedded
Tomcat instance, then runs Selenide browser automation tests and MCP
protocol tests against the live application. Always run sequentially
(never with `-T`), using `mvn clean install -Pintegration-tests -fae`.
