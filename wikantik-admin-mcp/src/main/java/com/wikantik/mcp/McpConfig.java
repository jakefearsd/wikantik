/*
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
 */
package com.wikantik.mcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loads MCP server configuration from classpath properties files.
 *
 * <p>Defaults are loaded from {@code /wikantik-mcp.properties} inside the JAR. An optional
 * override file with the same name on the thread-context classloader's classpath (e.g.
 * {@code tomcat/lib/wikantik-mcp.properties}) is overlaid on top, allowing configuration
 * without code changes.</p>
 */
public class McpConfig {

    private static final Logger LOG = LogManager.getLogger( McpConfig.class );
    private static final String RESOURCE_NAME = "wikantik-mcp.properties";

    private final Properties props;
    // Memoised result of instructions(): the file/classpath lookup runs at most
    // once per McpConfig instance, even when callers ask repeatedly (and even
    // when an override path is misconfigured). Prevents log spam at boot when
    // multiple components (McpEndpointBootstrapper, McpServerInitializer)
    // each call instructions() during startup.
    private volatile String cachedInstructions;

    /**
     * Creates a config by loading bundled defaults and overlaying any classpath override.
     *
     * <p>In a standard Tomcat deployment the webapp classloader finds the JAR-bundled
     * defaults first. The parent classloader (Tomcat's "common" loader, which includes
     * {@code tomcat/lib/}) is checked separately so that admin overrides placed in
     * {@code tomcat/lib/wikantik-mcp.properties} are overlaid on top of the defaults.</p>
     */
    @SuppressWarnings( "PMD.CompareObjectsWithEquals" ) // ClassLoader identity, not equality — we skip only the exact same loader instances.
    public McpConfig() {
        props = new Properties();
        final ClassLoader ownCl = McpConfig.class.getClassLoader();
        if ( ownCl != null ) {
            loadFromClasspath( ownCl, props );
        }

        // Overlay from the parent classloader (e.g. Tomcat's common classloader
        // which includes tomcat/lib/) so admin-placed overrides take effect.
        final ClassLoader parent = ownCl != null ? ownCl.getParent() : null;
        if ( parent != null ) {
            loadFromClasspath( parent, props );
        }

        // Also check TCCL for non-standard deployment models where
        // the thread context classloader differs from both of the above.
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if ( tccl != null && tccl != ownCl && tccl != parent ) {
            loadFromClasspath( tccl, props );
        }
    }

    /**
     * Package-private constructor for testing with pre-built properties.
     */
    McpConfig( final Properties properties ) {
        this.props = properties;
    }

    public String serverName() {
        return props.getProperty( "mcp.server.name", "wikantik-mcp" );
    }

    public String serverTitle() {
        return props.getProperty( "mcp.server.title", null );
    }

    public String serverVersion() {
        return props.getProperty( "mcp.server.version", "1.0.0" );
    }

    /**
     * Returns the MCP server instructions text, or empty string if none configured.
     *
     * <p>Resolution order is strictly two-stage:</p>
     * <ol>
     *   <li>If {@code mcp.instructions.file} is set, load that absolute filesystem
     *       path. On read failure, log an error and fall through to step 2.</li>
     *   <li>Load the bundled classpath resource {@code /wikantik-mcp-instructions.txt}
     *       via this class's own classloader (the webapp loader in production,
     *       the test classpath in tests). No TCCL or parent-classloader walk.</li>
     * </ol>
     *
     * <p>If both sources are unreadable, returns {@code ""} and logs at warn level.</p>
     */
    public String instructions() {
        String result = cachedInstructions;
        if ( result != null ) return result;
        synchronized ( this ) {
            if ( cachedInstructions != null ) return cachedInstructions;
            cachedInstructions = loadInstructionsOnce();
            return cachedInstructions;
        }
    }

    private String loadInstructionsOnce() {
        final String overridePath = props.getProperty( "mcp.instructions.file" );
        if ( overridePath != null && !overridePath.isBlank() ) {
            try ( final java.io.InputStream in =
                    java.nio.file.Files.newInputStream( java.nio.file.Path.of( overridePath ) ) ) {
                return new String( in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
            } catch ( final java.io.IOException e ) {
                LOG.error( "mcp.instructions.file={} is configured but unreadable; "
                        + "falling back to bundled resource: {}",
                        overridePath, e.getMessage() );
                // fall through to bundled resource
            }
        }
        try ( final java.io.InputStream in = McpConfig.class.getResourceAsStream(
                "/wikantik-mcp-instructions.txt" ) ) {
            if ( in != null ) {
                return new String( in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8 );
            }
            LOG.warn( "Bundled instructions resource /wikantik-mcp-instructions.txt not found; "
                    + "MCP server will serve an empty instructions field." );
            return "";
        } catch ( final java.io.IOException e ) {
            LOG.error( "Failed to read bundled instructions: {}", e.getMessage() );
            return "";
        }
    }

    /**
     * Returns the list of configured API keys for MCP access control.
     * Reads from {@code mcp.access.keys} (comma-separated).
     */
    public List< String > accessKeys() {
        final String raw = props.getProperty( "mcp.access.keys" );
        if ( raw == null || raw.isBlank() ) {
            return List.of();
        }
        return Arrays.stream( raw.split( "," ) )
                .map( String::strip )
                .filter( s -> !s.isEmpty() )
                .toList();
    }

    public int rateLimitGlobal() {
        return intProperty( "mcp.ratelimit.global", 0 );
    }

    public int rateLimitPerClient() {
        return intProperty( "mcp.ratelimit.perClient", 0 );
    }

    private int intProperty( final String key, final int defaultValue ) {
        final String val = props.getProperty( key );
        if ( val == null || val.isBlank() ) {
            return defaultValue;
        }
        try {
            return Integer.parseInt( val.strip() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Invalid integer for '{}': '{}'", key, val );
            return defaultValue;
        }
    }

    public String allowedCidrs() {
        final String cidrs = props.getProperty( "mcp.access.allowedCidrs" );
        return cidrs != null && !cidrs.isBlank() ? cidrs.strip() : null;
    }

    /**
     * Returns whether the MCP endpoint is allowed to run without any authentication
     * gate. Defaults to {@code false} — operators must explicitly set
     * {@code mcp.access.allowUnrestricted=true} to disable both API-key and CIDR
     * checks. When neither is configured and this flag is {@code false}, the filter
     * responds {@code 503 Service Unavailable} on every request.
     */
    public boolean allowUnrestricted() {
        final String raw = props.getProperty( "mcp.access.allowUnrestricted" );
        return raw != null && Boolean.parseBoolean( raw.strip() );
    }

    private static final int DEFAULT_RATE_LIMIT_MAX_CLIENTS = 10000;

    /**
     * Returns the maximum number of distinct clients tracked by the per-client rate limiter.
     * Reads from {@code wikantik.mcp.rate_limit.max_clients}; defaults to {@value DEFAULT_RATE_LIMIT_MAX_CLIENTS}.
     */
    public int rateLimiterMaxClients() {
        return positiveIntProperty( "wikantik.mcp.rate_limit.max_clients", DEFAULT_RATE_LIMIT_MAX_CLIENTS );
    }

    private static final int DEFAULT_KG_BULK_LIMIT = 50;

    /**
     * Returns the maximum number of proposals that may be acted on in a single
     * bulk KG curation call. Reads from {@code wikantik.mcp.kg_curation.bulk_limit};
     * defaults to {@value DEFAULT_KG_BULK_LIMIT}.
     */
    public int kgCurationBulkLimit() {
        return positiveIntProperty( "wikantik.mcp.kg_curation.bulk_limit", DEFAULT_KG_BULK_LIMIT );
    }

    /**
     * Reads a positive-int property. On missing/blank/non-numeric/non-positive input,
     * logs a single warn and returns {@code defaultValue}.
     */
    private int positiveIntProperty( final String key, final int defaultValue ) {
        final String raw = props.getProperty( key );
        if ( raw == null || raw.isBlank() ) return defaultValue;
        try {
            final int v = Integer.parseInt( raw.trim() );
            if ( v <= 0 ) {
                LOG.warn( "{}={} is not positive — falling back to default {}", key, raw, defaultValue );
                return defaultValue;
            }
            return v;
        } catch ( final NumberFormatException e ) {
            LOG.warn( "{}={} is not an integer — falling back to default {}", key, raw, defaultValue );
            return defaultValue;
        }
    }

    private static void loadFromClasspath( final ClassLoader cl, final Properties target ) {
        try ( InputStream is = cl.getResourceAsStream( RESOURCE_NAME ) ) {
            if ( is != null ) {
                target.load( is );
            }
        } catch ( final IOException e ) {
            LOG.warn( "Could not load {}: {}", RESOURCE_NAME, e.getMessage() );
        }
    }
}
