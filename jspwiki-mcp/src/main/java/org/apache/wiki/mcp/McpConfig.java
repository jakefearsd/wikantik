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
package org.apache.wiki.mcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loads MCP server configuration from classpath properties files.
 *
 * <p>Defaults are loaded from {@code /jspwiki-mcp.properties} inside the JAR. An optional
 * override file with the same name on the thread-context classloader's classpath (e.g.
 * {@code tomcat/lib/jspwiki-mcp.properties}) is overlaid on top, allowing configuration
 * without code changes.</p>
 */
public class McpConfig {

    private static final Logger LOG = LogManager.getLogger( McpConfig.class );
    private static final String RESOURCE_NAME = "jspwiki-mcp.properties";

    private final Properties props;

    /**
     * Creates a config by loading bundled defaults and overlaying any classpath override.
     *
     * <p>In a standard Tomcat deployment the webapp classloader finds the JAR-bundled
     * defaults first. The parent classloader (Tomcat's "common" loader, which includes
     * {@code tomcat/lib/}) is checked separately so that admin overrides placed in
     * {@code tomcat/lib/jspwiki-mcp.properties} are overlaid on top of the defaults.</p>
     */
    public McpConfig() {
        props = new Properties();
        final ClassLoader ownCl = McpConfig.class.getClassLoader();
        loadFromClasspath( ownCl, props );

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
        return props.getProperty( "mcp.server.name", "jspwiki-mcp" );
    }

    public String serverTitle() {
        return props.getProperty( "mcp.server.title", null );
    }

    public String serverVersion() {
        return props.getProperty( "mcp.server.version", "1.0.0" );
    }

    /**
     * Returns the MCP server instructions text, or {@code null} if none configured.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Load the file named by {@code mcp.instructions.file} from the classpath</li>
     *   <li>Fall back to the inline {@code mcp.instructions} property value</li>
     *   <li>Return {@code null} if neither is set</li>
     * </ol>
     */
    public String instructions() {
        final String file = props.getProperty( "mcp.instructions.file" );
        if ( file != null && !file.isBlank() ) {
            final String text = loadTextResource( file );
            if ( text != null ) {
                return text;
            }
            LOG.debug( "Instructions file '{}' not found on classpath, checking inline property", file );
        }
        final String inline = props.getProperty( "mcp.instructions" );
        return inline != null && !inline.isBlank() ? inline : null;
    }

    /**
     * Returns the list of configured API keys for MCP access control.
     * Reads from {@code mcp.access.keys} (comma-separated) first,
     * falling back to the legacy {@code mcp.access.key} single-key property.
     */
    public List< String > accessKeys() {
        String raw = props.getProperty( "mcp.access.keys" );
        if ( raw == null || raw.isBlank() ) {
            raw = props.getProperty( "mcp.access.key" );
        }
        if ( raw == null || raw.isBlank() ) {
            return List.of();
        }
        return Arrays.stream( raw.split( "," ) )
                .map( String::strip )
                .filter( s -> !s.isEmpty() )
                .toList();
    }

    /**
     * @deprecated Use {@link #accessKeys()} instead. Returns the first configured key, or {@code null}.
     */
    @Deprecated( forRemoval = true, since = "2.0" )
    public String accessKey() {
        final List< String > keys = accessKeys();
        return keys.isEmpty() ? null : keys.get( 0 );
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

    private String loadTextResource( final String resourceName ) {
        // Try thread-context classloader first (picks up external overrides), then our own
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if ( tccl != null ) {
            try ( final InputStream is = tccl.getResourceAsStream( resourceName ) ) {
                if ( is != null ) {
                    return new String( is.readAllBytes(), StandardCharsets.UTF_8 ).strip();
                }
            } catch ( final IOException e ) {
                LOG.warn( "Error reading instructions file '{}': {}", resourceName, e.getMessage() );
            }
        }
        try ( final InputStream is = McpConfig.class.getClassLoader().getResourceAsStream( resourceName ) ) {
            if ( is != null ) {
                return new String( is.readAllBytes(), StandardCharsets.UTF_8 ).strip();
            }
        } catch ( final IOException e ) {
            LOG.warn( "Error reading instructions file '{}': {}", resourceName, e.getMessage() );
        }
        return null;
    }

    private static void loadFromClasspath( final ClassLoader cl, final Properties target ) {
        try ( final InputStream is = cl.getResourceAsStream( RESOURCE_NAME ) ) {
            if ( is != null ) {
                target.load( is );
            }
        } catch ( final IOException e ) {
            LOG.warn( "Could not load {}: {}", RESOURCE_NAME, e.getMessage() );
        }
    }
}
