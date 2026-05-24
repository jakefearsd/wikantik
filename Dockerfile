# Stage 1: Build WAR with Maven
#
# We deliberately use a single `COPY . .` rather than the older per-module
# pom-copy + dependency:go-offline trick. The reactor module list churns
# (modules added/renamed several times in the last few months) — keeping a
# hard-coded list in this Dockerfile bit-rots silently. Tradeoff: every
# source change invalidates the dependency cache. Still under a minute on
# warm builds.
FROM maven:3.9-eclipse-temurin-21 AS build

# wikantik-war's reactor build invokes npm + vite to bundle the React
# frontend into the WAR. The Maven base image has no Node — pull in
# Node 20 LTS (matches CLAUDE.md's "Node.js + npm | 18+" requirement).
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .
RUN mvn -B clean package -pl wikantik-war -am -DskipTests

# Stage 2: Tomcat 11 / JDK 21 runtime
# Pinned to the same version as bin/deploy-local.sh's TOMCAT_VERSION so the
# bare-metal and container install paths run on the identical Tomcat patch.
# Bumping this means bumping deploy-local.sh in lockstep.
FROM tomcat:11.0.22-jdk21-temurin

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Remove default webapps
RUN rm -rf ${CATALINA_HOME}/webapps/* ${CATALINA_HOME}/bin/*.bat

# Create Wikantik directories
RUN mkdir -p /var/wikantik/pages /var/wikantik/work /var/wikantik/logs

# Download PostgreSQL JDBC driver
RUN curl -fsSL -o ${CATALINA_HOME}/lib/postgresql-42.7.4.jar \
    https://jdbc.postgresql.org/download/postgresql-42.7.4.jar

# Copy Tomcat configuration.
#
# Three groups of files, with intentionally different relationships to the
# bare-metal install path (bin/deploy-local.sh):
#
# 1. server.xml + log4j2 — legitimately divergent between environments.
#    The container's server.xml is production-hardened (port=-1, xpoweredBy=
#    false, autoDeploy=false, no ErrorReportValve content). The container's
#    log4j2 emits ECS JSON for log aggregators (Loki/Promtail). The
#    bare-metal template prefers dev-friendly defaults: human-readable
#    console + autoDeploy + DEBUG on providers/auth. Both share the
#    Cloudflare RemoteIpValve + custom AccessLogValve customizations —
#    if you change those valves, edit BOTH files.
#
# 2. context.xml — single source of truth at
#    wikantik-war/src/main/config/tomcat/Tomcat-context.xml.template,
#    consumed by both paths. Carries the CookieProcessor sameSiteCookies=
#    lax cookie-hardening (Lax, so the cross-site SSO return to /sso/callback
#    keeps the session) that applies regardless of environment.
#
# 3. catalina.properties — container-specific (jar-scan filter tuning); the
#    bare-metal install uses Tomcat's stock catalina.properties unchanged.
COPY docker/config/server.xml ${CATALINA_HOME}/conf/server.xml
COPY docker/config/catalina.properties ${CATALINA_HOME}/conf/catalina.properties
COPY docker/config/log4j2-docker.xml ${CATALINA_HOME}/lib/log4j2.xml
COPY wikantik-war/src/main/config/tomcat/Tomcat-context.xml.template \
     ${CATALINA_HOME}/conf/context.xml

# Copy entrypoint
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Database migrations bundled with the image so the entrypoint can run
# them before Tomcat starts. Same migrate.sh + migrations/ used by the
# bare-metal install and CI — single source of truth.
COPY bin/db/ /opt/wikantik/db/
RUN chmod +x /opt/wikantik/db/migrate.sh

# Deploy WAR (unzipped for faster startup)
COPY --from=build /src/wikantik-war/target/Wikantik.war /tmp/Wikantik.war
RUN mkdir -p ${CATALINA_HOME}/webapps/ROOT \
    && unzip -q -d ${CATALINA_HOME}/webapps/ROOT /tmp/Wikantik.war \
    && rm /tmp/Wikantik.war

# Copy default page content
COPY docs/wikantik-pages/ /var/wikantik/pages/

ENV CATALINA_OPTS="-Djava.security.egd=file:/dev/./urandom -XX:MaxRAMPercentage=70.0 --add-modules=jdk.incubator.vector"
ENV LANG=en_US.UTF-8

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
CMD ["catalina.sh", "run"]
