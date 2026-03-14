# Apache JSPWiki

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

The license file can be found in LICENSE.


## What is JSPWiki?

JSPWiki is a simple (well, not anymore) WikiWiki clone, written in Java
and JSP.  A WikiWiki is a website which allows anyone to participate
in its development.  JSPWiki supports all the traditional wiki features,
as well as very detailed access control and security integration using JAAS.

* For more information see https://jspwiki-wiki.apache.org/


## Documentation

### Development Setup

- [PostgreSQLLocalDeployment.md](docs/PostgreSQLLocalDeployment.md) — Local dev environment with PostgreSQL and Tomcat
- [DevelopingWithPostgresql.md](docs/DevelopingWithPostgresql.md) — Full PostgreSQL schema, JDBC, and JNDI configuration guide
- [MvnCheatSheet.md](docs/MvnCheatSheet.md) — Maven build, test, and debug commands
- [LoggingConfig.md](docs/LoggingConfig.md) — Log4j2 external configuration

### Deployment & Operations

- [DockerDeployment.md](docs/DockerDeployment.md) — Docker Compose setup, backups, and restoration
- [JspwikiDeployment.md](docs/JspwikiDeployment.md) — Docker vs. direct Tomcat deployment comparison
- [SendingEmailFromTheWiki.md](docs/SendingEmailFromTheWiki.md) — SMTP relay setup (Brevo, SendGrid, Mailjet, SES, Resend)
- [ObservabilityDesign.md](docs/ObservabilityDesign.md) — Grafana, Prometheus, and Loki observability stack

### Features

- [MarkdownLinks.md](docs/MarkdownLinks.md) — Markdown internal and external link syntax
- [OAuthImplementation.md](docs/OAuthImplementation.md) — OAuth SSO implementation plan (Google, GitHub)
- [FullOAuth.md](docs/FullOAuth.md) — OAuth/OpenID Connect detailed design
- [RelationalUserDatabase.md](docs/RelationalUserDatabase.md) — MySQL/PostgreSQL user database configuration
- [Sitemap.md](docs/Sitemap.md) — Sitemap.xml servlet implementation
- [SitemapOptimization.md](docs/SitemapOptimization.md) — SEO best practices for sitemaps

### Architecture & Design

- [RefactorToPatterns.md](docs/RefactorToPatterns.md) — GoF design pattern opportunities in the codebase
- [PerformanceEvaluation.md](docs/PerformanceEvaluation.md) — I/O, indexing, and rendering bottleneck analysis
- [NewUI.md](docs/NewUI.md) — React SPA reader UI design
- [semantic_wiki_thoughts.md](docs/semantic_wiki_thoughts.md) — AI-augmented semantic wiki vision

### MCP Integration

The `jspwiki-mcp` module provides a Model Context Protocol server for AI-assisted wiki operations. See the module's own documentation for setup and usage.

- [wiki-article-cluster skill](docs/superpowers/skills/wiki-article-cluster/SKILL.md) — Skill for researching and publishing multi-page wiki article clusters

### Research

- [research_history.md](docs/research_history.md) — Log of research sessions and article clusters published to the wiki

### Legal Templates

- [PrivacyPolicy.md](docs/PrivacyPolicy.md) — Privacy policy template
- [TermsOfService.md](docs/TermsOfService.md) — Terms of service template


## Pre-requirements

Okay, so you wanna Wiki?  You'll need the following things:

REQUIRED:

* A JSP engine that supports Servlet API 6.0.  We recommend [Apache Tomcat](https://tomcat.apache.org/)
  for a really easy installation. Tomcat 11 or later is required.

* Some previous administration experience...  If you've ever installed
  Apache or any other web server, you should be pretty well off.

* And of course, a server to run the JSP engine on.

* JDK 21+


OPTIONAL:

* JavaMail package from java.sun.com, if you want to use log4j mailing
  capabilities.  You'll also need the Java Activation Framework.

## Really simple installation

Since JSPWiki 2.1.153, JSPWiki comes with a really simple installation
engine.  Just do the following:

1) Install Tomcat from https://tomcat.apache.org/ (or any other servlet
   container)

2) Rename the JSPWiki.war file from the download and rename it based on
   your desired URL (if you want it different from /JSPWiki).  For example,
   if you want your URL to be http://.../wiki, rename it to wiki.war.
   This name will be referred to as <appname> below.
   Place this WAR in your `$TOMCAT_HOME/webapps` folder and then start Tomcat.

3) Point your browser at http://&lt;myhost>/&lt;appname>/Install.jsp

4) Answer a couple of simple questions

5) Restart your container

6) Point your browser to http://&lt;myhost>/&lt;appname>/

That's it!

## Advanced Installation

In the `$TOMCAT_HOME/lib` folder (or equivalent based on your servlet container),
place a `jspwiki-custom.properties` file, which can contain any overrides to the
default `ini/jspwiki.properties` file in the JSPWiki JAR.  For any values not
placed in `jspwiki-custom.properties` file JSPWiki will rely on the default file.
Review the default file to look for values you may wish to override in the custom
file.  Some common values to override in your custom file include
`jspwiki.xmlUserDatabaseFile`, `jspwiki.xmlGroupDatabaseFile`,
`jspwiki.fileSystemProvider.pageDir`, `jspwiki.basicAttachmentProvider.storageDir`,
and `appender.rolling.fileName`.  The comments in the default file will suggest
appropriate values to override them with.

The custom file can also be placed in the `WEB-INF/` folder of the WAR, but storing
this file in `$TOMCAT_HOME/lib` allows you to upgrade the JSPWiki WAR without needing
to re-insert your customizations.

Unzip the contents of `jspwiki-corepages.zip` into your newly created
directory.  You can find the rest of the documentation in the
`JSPWiki-doc.zip` file.

(Re)start tomcat.

Point your browser at http://&lt;where your Tomcat is installed>/MyWiki/.
You should see the Main Wiki page.  See the next section if you want
to edit the pages =).

The `WEB-INF/jspwiki.policy` file is used to change access permissions for
the Wiki.

Check the Apache JSPWiki website and project documentation for additional
setup and configuration suggestions.

## Using Docker

A `Dockerfile` and `docker-compose.yml` are included in this repository for building
and running JSPWiki from source. See [DockerDeployment.md](docs/DockerDeployment.md)
for the full guide including backups and data persistence.

### Build and Run from Source
```
$ docker compose up -d
```

Then point your browser at http://localhost:8080/.

> **Note:** The upstream Docker Hub image at
> [apache/jspwiki](https://registry.hub.docker.com/r/apache/jspwiki/) tracks the
> official Apache release and may not reflect the current state of this repository.
> Building from source (as shown above) is recommended.

## Upgrading from previous versions

Please read [ReleaseNotes](./ReleaseNotes) and the [UPGRADING](./UPGRADING) documents available with this
distribution.

## Contact

Questions can be asked to JSPWiki team members and fellow users via the jspwiki-users
mailing list: See https://jspwiki.apache.org/community/mailing_lists.html.
Please use the user mailing list instead of contacting team members directly,
and as this is a public list stored in public archives, be sure to avoid including
any sensitive information (passwords, data, etc.) in your questions.
