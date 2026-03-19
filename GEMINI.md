
# Gemini Development Guide for Wikantik

This document provides a guide for developing extensions for Apache Wikantik using this Gemini agent.

## Project Overview

Wikantik is a feature-rich WikiWiki engine built on Java and Java Servlets. Its modular architecture allows for extensive customization through plugins, themes, and providers.

### Key Directories

*   `jspwiki-api`: Defines the core interfaces for Wikantik's components, including plugins, providers, and managers.
*   `wikantik-main`: Contains the main implementation of the Wikantik engine, including the default managers and providers.
*   `wikantik-war`: The web application module, containing the JSP files and other web resources.
*   `jspwiki-plugins`: A directory for contributed plugins.

## Extension Development

### Core Concepts

Wikantik's functionality can be extended by implementing various interfaces. The most common extension points are:

*   **Plugins**: Implement the `com.wikantik.api.plugin.Plugin` interface to add new dynamic content to wiki pages.
*   **Providers**: Implement `com.wikantik.api.providers.PageProvider` or `com.wikantik.api.providers.AttachmentProvider` to change how wiki pages and attachments are stored and retrieved.
*   **Filters**: Implement `com.wikantik.api.filters.PageFilter` to intercept and modify page content before it is displayed or saved.

### Creating a "Hello World" Plugin

This example demonstrates how to create a simple plugin that greets the user.

1.  **Create the Plugin Class**:

    Create a new Java class that implements the `com.wikantik.api.plugin.Plugin` interface.

    ```java
    package com.example.wiki.plugins;

    import java.util.Map;
    import com.wikantik.api.plugin.Plugin;
    import com.wikantik.api.plugin.PluginException;
    import com.wikantik.api.core.Context;

    public class HelloWorldPlugin implements Plugin {
        public String execute(Context context, Map<String, String> params) throws PluginException {
            String name = params.get("name");
            if (name == null) {
                name = "World";
            }
            return "Hello " + name + "!";
        }
    }
    ```

2.  **Compile and Package**:

    Compile the Java class and package it into a JAR file.

3.  **Installation**:

    *   Copy the generated JAR file to the `WEB-INF/lib/` directory of your Wikantik installation.
    *   Update the `jspwiki.plugin.searchPath` property in your `wikantik-custom.properties` file to include the package of your new plugin (e.g., `com.example.wiki.plugins`).
    *   Restart your Wikantik instance.

4.  **Usage**:

    To use the plugin, add the following to a wiki page:

    ```
    [{HelloWorldPlugin name='Wikantik Developer'}]
    ```

    This will render the output: "Hello Wikantik Developer!"

## Important Commands

*   **Build the project**: `mvn clean install`
*   **Run the tests**: `mvn test`
*   **Run integration tests**: `mvn verify -Pintegration-tests`
*   **Start Wikantik in a container**: `mvn -P tomcat9x cargo:run -Dcargo.wait=true`
