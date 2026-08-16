# Wikantik Integration Tests

Integration test suite for Wikantik. Uses Maven Cargo to start an embedded
Tomcat instance, then runs Selenide browser automation tests and MCP
protocol tests against the live application. Run through
`bin/run-tests.sh --parallel 4`, the sanctioned path for parallel IT
execution — it reserves per-module ports and pgvector containers so modules
cannot collide. Do not bolt `-T` onto a raw
`mvn clean install -Pintegration-tests` invocation.
