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
package com.wikantik.connectors.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.connectors.confluence.ConfluenceConfig;
import com.wikantik.connectors.gdrive.DriveConfig;
import com.wikantik.connectors.github.GithubConfig;
import com.wikantik.connectors.web.FeedConfig;
import com.wikantik.connectors.web.SitemapConfig;
import com.wikantik.connectors.web.WebCrawlerConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation + JSON-to-typed-config translation for the admin-UI-creatable connector types.
 * Bridges the admin UI's per-connector JSON blob (persisted as {@code connector_configs.config})
 * and the six typed config records the connectors themselves consume.
 *
 * <p>{@code filesystem} is deliberately excluded from {@link #UI_TYPES} and therefore always
 * fails {@link #validate} with an "unknown type" error: filesystem connectors read arbitrary
 * server paths, so they stay operator-only (properties-only), never creatable from the admin UI
 * (design decision D9, {@code docs/superpowers/specs/2026-07-15-connector-admin-ui-design.md}).
 */
public final class ConnectorConfigCodec {

    private static final Logger LOG = LogManager.getLogger( ConnectorConfigCodec.class );

    /** Connector types the admin UI may create. {@code filesystem} is not one of them (D9). */
    public static final Set< String > UI_TYPES = Set.of( "webcrawler", "sitemap", "feed", "gdrive", "github", "confluence" );

    private static final String DEFAULT_USER_AGENT = "WikantikCrawler/1.0 (+https://wiki.wikantik.com)";
    private static final Pattern ID_PATTERN = Pattern.compile( "[a-z0-9-]{1,64}" );
    private static final Pattern REPO_PATTERN = Pattern.compile( "[^/\\s]+/[^/\\s]+" );

    private ConnectorConfigCodec() {}

    /** Field-keyed validation errors; empty ⇒ {@link #ok()}. The map is defensively immutable. */
    public record Validation( Map< String, String > errors ) {
        public Validation {
            errors = Map.copyOf( errors );
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** Validates a connector id: {@code [a-z0-9-]{1,64}}, else an error under key {@code "connector_id"}. */
    public static Validation validateId( final String id ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        if ( id == null || !ID_PATTERN.matcher( id ).matches() ) {
            errors.put( "connector_id", "must match [a-z0-9-]{1,64}" );
        }
        return new Validation( errors );
    }

    /** Field-keyed validation of {@code config} against {@code type}'s rules. An unrecognized
     *  {@code type} (including {@code filesystem}, D9) yields a single {@code "connector_type"} error. */
    public static Validation validate( final String type, final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        switch ( type ) {
            case "webcrawler" -> validateWebCrawler( config, errors );
            case "sitemap" -> validateSitemap( config, errors );
            case "feed" -> validateFeed( config, errors );
            case "gdrive" -> validateDrive( config, errors );
            case "github" -> validateGithub( config, errors );
            case "confluence" -> validateConfluence( config, errors );
            default -> errors.put( "connector_type", "unknown type: " + type );
        }
        return new Validation( errors );
    }

    /** Builds the typed config record for {@code type} with defaults applied. Callers must call
     *  {@link #validate} first: throws {@link IllegalArgumentException} if validation would fail. */
    public static Object toConfig( final String type, final JsonObject config ) {
        return build( type, config, false );
    }

    /** Same as {@link #toConfig}, but with dry-run-safe caps clamped: {@code max_pages}/{@code max_files}/
     *  {@code max_items} → {@code min(value, 3)}, {@code max_depth} → {@code 1}, {@code delay_ms} → {@code 0}. */
    public static Object toConfigForTest( final String type, final JsonObject config ) {
        return build( type, config, true );
    }

    private static Object build( final String type, final JsonObject config, final boolean forTest ) {
        final Validation v = validate( type, config );
        if ( !v.ok() ) {
            throw new IllegalArgumentException( "invalid " + type + " config: " + v.errors() );
        }
        return switch ( type ) {
            case "webcrawler" -> buildWebCrawler( config, forTest );
            case "sitemap" -> buildSitemap( config, forTest );
            case "feed" -> buildFeed( config, forTest );
            case "gdrive" -> buildDrive( config, forTest );
            case "github" -> buildGithub( config, forTest );
            case "confluence" -> buildConfluence( config, forTest );
            default -> throw new IllegalArgumentException( "unknown type: " + type );
        };
    }

    // ---- per-type validation --------------------------------------------------------------

    private static void validateWebCrawler( final JsonObject config, final Map< String, String > errors ) {
        requireHttpUrlList( errors, config, "seeds" );
        requireMinInt( errors, config, "max_pages", 100, 0 );
        requireMinInt( errors, config, "max_depth", 3, 1 );
        requireMinLong( errors, config, "delay_ms", 1000L, 0L );
    }

    private static void validateSitemap( final JsonObject config, final Map< String, String > errors ) {
        requireHttpUrlList( errors, config, "sitemap_urls" );
        requireMinInt( errors, config, "max_pages", 500, 0 );
        requireMinLong( errors, config, "delay_ms", 1000L, 0L );
    }

    private static void validateFeed( final JsonObject config, final Map< String, String > errors ) {
        requireHttpUrlList( errors, config, "feed_urls" );
        requireMinInt( errors, config, "max_items", 100, 0 );
        requireMinLong( errors, config, "delay_ms", 1000L, 0L );
    }

    private static void validateDrive( final JsonObject config, final Map< String, String > errors ) {
        if ( strList( config, "folder_ids" ).isEmpty() ) {
            errors.put( "folder_ids", "at least one folder id is required" );
        }
        requireNonBlank( errors, config, "client_id" );
        requireHttpUrl( errors, config, "redirect_uri" );
        requireMinInt( errors, config, "max_files", 500, 0 );
        // client_secret is intentionally never validated here — it is not part of the admin-UI
        // config JSON; it lives in the CredentialStore and is resolved at assembly (Task 5).
    }

    private static void validateGithub( final JsonObject config, final Map< String, String > errors ) {
        final String repo = str( config, "repo" );
        if ( repo == null ) {
            errors.put( "repo", "repo is required" );
        } else if ( !REPO_PATTERN.matcher( repo ).matches() ) {
            errors.put( "repo", "must be \"owner/name\"" );
        }
        requireMinInt( errors, config, "max_files", 500, 0 );
    }

    private static void validateConfluence( final JsonObject config, final Map< String, String > errors ) {
        requireHttpUrl( errors, config, "base_url" );
        requireNonBlank( errors, config, "space_key" );
        requireNonBlank( errors, config, "email" );
        requireMinInt( errors, config, "max_pages", 500, 0 );
    }

    // ---- per-type building ------------------------------------------------------------------

    private static WebCrawlerConfig buildWebCrawler( final JsonObject config, final boolean forTest ) {
        return new WebCrawlerConfig(
            strList( config, "seeds" ),
            boolVal( config, "same_host_only", true ),
            str( config, "path_prefix" ),
            clampMax( intVal( config, "max_pages", 100 ), forTest ),
            forTest ? 1 : intVal( config, "max_depth", 3 ),
            forTest ? 0L : longVal( config, "delay_ms", 1000L ),
            strOr( config, "user_agent", DEFAULT_USER_AGENT ),
            boolVal( config, "respect_robots", true ) );
    }

    private static SitemapConfig buildSitemap( final JsonObject config, final boolean forTest ) {
        return new SitemapConfig(
            strList( config, "sitemap_urls" ),
            clampMax( intVal( config, "max_pages", 500 ), forTest ),
            forTest ? 0L : longVal( config, "delay_ms", 1000L ),
            strOr( config, "user_agent", DEFAULT_USER_AGENT ),
            boolVal( config, "respect_robots", true ),
            boolVal( config, "same_host_only", true ) );
    }

    private static FeedConfig buildFeed( final JsonObject config, final boolean forTest ) {
        return new FeedConfig(
            strList( config, "feed_urls" ),
            clampMax( intVal( config, "max_items", 100 ), forTest ),
            boolVal( config, "fetch_full_articles", true ),
            forTest ? 0L : longVal( config, "delay_ms", 1000L ),
            strOr( config, "user_agent", DEFAULT_USER_AGENT ),
            boolVal( config, "respect_robots", true ),
            boolVal( config, "same_host_only", true ) );
    }

    private static DriveConfig buildDrive( final JsonObject config, final boolean forTest ) {
        return new DriveConfig(
            strList( config, "folder_ids" ),
            clampMax( intVal( config, "max_files", 500 ), forTest ),
            str( config, "client_id" ),
            null,   // client_secret lives in the CredentialStore, resolved at assembly (Task 5)
            str( config, "redirect_uri" ),
            strOr( config, "export_mime", "text/markdown" ) );
    }

    private static GithubConfig buildGithub( final JsonObject config, final boolean forTest ) {
        return new GithubConfig(
            str( config, "repo" ),
            str( config, "branch" ),
            str( config, "path_prefix" ),
            clampMax( intVal( config, "max_files", 500 ), forTest ) );
    }

    private static ConfluenceConfig buildConfluence( final JsonObject config, final boolean forTest ) {
        return new ConfluenceConfig(
            str( config, "base_url" ),
            str( config, "space_key" ),
            str( config, "email" ),
            clampMax( intVal( config, "max_pages", 500 ), forTest ) );
    }

    private static int clampMax( final int value, final boolean forTest ) {
        return forTest ? Math.min( value, 3 ) : value;
    }

    // ---- typed config → JSON (Task 11 — the admin-UI "import" route reconstructs the config JSON
    // for a properties-defined connector from its typed record, one round trip through toConfig) ---

    /** Serializes a typed config record for {@code type} back to the codec's JSON field names —
     *  the inverse of {@link #toConfig}. {@code gdrive}'s {@code client_secret} is intentionally
     *  never emitted (it is not part of the admin-UI config JSON; it lives in the
     *  {@code CredentialStore}, mirroring {@link #validateDrive}/{@link #buildDrive}). Throws
     *  {@link IllegalArgumentException} for an unrecognized {@code type} or a {@code config} that
     *  is not the record {@code type} builds (a caller bug, not a data-shape issue). */
    public static JsonObject toJson( final String type, final Object config ) {
        return switch ( type ) {
            case "webcrawler" -> webCrawlerToJson( ( WebCrawlerConfig ) config );
            case "sitemap" -> sitemapToJson( ( SitemapConfig ) config );
            case "feed" -> feedToJson( ( FeedConfig ) config );
            case "gdrive" -> driveToJson( ( DriveConfig ) config );
            case "github" -> githubToJson( ( GithubConfig ) config );
            case "confluence" -> confluenceToJson( ( ConfluenceConfig ) config );
            default -> throw new IllegalArgumentException( "unknown type: " + type );
        };
    }

    private static JsonObject webCrawlerToJson( final WebCrawlerConfig c ) {
        final JsonObject json = new JsonObject();
        putStrList( json, "seeds", c.seeds() );
        json.addProperty( "same_host_only", c.sameHostOnly() );
        json.addProperty( "path_prefix", c.pathPrefix() );
        json.addProperty( "max_pages", c.maxPages() );
        json.addProperty( "max_depth", c.maxDepth() );
        json.addProperty( "delay_ms", c.delayMs() );
        json.addProperty( "user_agent", c.userAgent() );
        json.addProperty( "respect_robots", c.respectRobots() );
        return json;
    }

    private static JsonObject sitemapToJson( final SitemapConfig c ) {
        final JsonObject json = new JsonObject();
        putStrList( json, "sitemap_urls", c.sitemapUrls() );
        json.addProperty( "max_pages", c.maxPages() );
        json.addProperty( "delay_ms", c.delayMs() );
        json.addProperty( "user_agent", c.userAgent() );
        json.addProperty( "respect_robots", c.respectRobots() );
        json.addProperty( "same_host_only", c.sameHostOnly() );
        return json;
    }

    private static JsonObject feedToJson( final FeedConfig c ) {
        final JsonObject json = new JsonObject();
        putStrList( json, "feed_urls", c.feedUrls() );
        json.addProperty( "max_items", c.maxItems() );
        json.addProperty( "fetch_full_articles", c.fetchFullArticles() );
        json.addProperty( "delay_ms", c.delayMs() );
        json.addProperty( "user_agent", c.userAgent() );
        json.addProperty( "respect_robots", c.respectRobots() );
        json.addProperty( "same_host_only", c.sameHostOnly() );
        return json;
    }

    private static JsonObject driveToJson( final DriveConfig c ) {
        final JsonObject json = new JsonObject();
        putStrList( json, "folder_ids", c.folderIds() );
        json.addProperty( "max_files", c.maxFiles() );
        json.addProperty( "client_id", c.clientId() );
        json.addProperty( "redirect_uri", c.redirectUri() );
        json.addProperty( "export_mime", c.exportMimeType() );
        // client_secret intentionally omitted — lives in the CredentialStore, never round-tripped here.
        return json;
    }

    private static JsonObject githubToJson( final GithubConfig c ) {
        final JsonObject json = new JsonObject();
        json.addProperty( "repo", c.repo() );
        json.addProperty( "branch", c.branch() );
        json.addProperty( "path_prefix", c.pathPrefix() );
        json.addProperty( "max_files", c.maxFiles() );
        return json;
    }

    private static JsonObject confluenceToJson( final ConfluenceConfig c ) {
        final JsonObject json = new JsonObject();
        json.addProperty( "base_url", c.baseUrl() );
        json.addProperty( "space_key", c.spaceKey() );
        json.addProperty( "email", c.email() );
        json.addProperty( "max_pages", c.maxPages() );
        return json;
    }

    private static void putStrList( final JsonObject json, final String key, final List< String > values ) {
        final JsonArray arr = new JsonArray();
        for ( final String v : values ) arr.add( v );
        json.add( key, arr );
    }

    // ---- validation helpers ------------------------------------------------------------------

    private static void requireHttpUrlList( final Map< String, String > errors, final JsonObject config, final String key ) {
        final List< String > urls = strList( config, key );
        if ( urls.isEmpty() ) {
            errors.put( key, "at least one http(s) URL is required" );
            return;
        }
        for ( final String url : urls ) {
            if ( !isHttpUrl( url ) ) {
                errors.put( key, "all entries must be valid http(s) URLs" );
                return;
            }
        }
    }

    private static void requireHttpUrl( final Map< String, String > errors, final JsonObject config, final String key ) {
        final String value = str( config, key );
        if ( value == null ) {
            errors.put( key, key + " is required" );
        } else if ( !isHttpUrl( value ) ) {
            errors.put( key, "must be a valid http(s) URL" );
        }
    }

    private static void requireNonBlank( final Map< String, String > errors, final JsonObject config, final String key ) {
        if ( str( config, key ) == null ) {
            errors.put( key, key + " is required" );
        }
    }

    private static void requireMinInt( final Map< String, String > errors, final JsonObject config, final String key, final int def, final int min ) {
        if ( intVal( config, key, def ) < min ) {
            errors.put( key, "must be >= " + min );
        }
    }

    private static void requireMinLong( final Map< String, String > errors, final JsonObject config, final String key, final long def, final long min ) {
        if ( longVal( config, key, def ) < min ) {
            errors.put( key, "must be >= " + min );
        }
    }

    private static boolean isHttpUrl( final String value ) {
        try {
            final URI uri = new URI( value.trim() );
            final String scheme = uri.getScheme();
            return ( "http".equalsIgnoreCase( scheme ) || "https".equalsIgnoreCase( scheme ) ) && uri.getHost() != null;
        } catch ( final URISyntaxException e ) {
            return false;
        }
    }

    // ---- JSON field helpers ------------------------------------------------------------------
    // All helpers are shape-proof: a value of the wrong JSON shape (object where a string is
    // expected, multi-element array where a scalar is expected, …) degrades to null / the default
    // instead of throwing, so validate() turns it into a field-keyed error — validate() must never
    // throw on any JsonObject input.

    private static String str( final JsonObject config, final String key ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return null;
        try {
            final String value = config.get( key ).getAsString().trim();
            return value.isBlank() ? null : value;
        } catch ( final UnsupportedOperationException | IllegalStateException | NumberFormatException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as a string ({}) — treating as absent", key, e.getMessage() );
            return null;
        }
    }

    private static String strOr( final JsonObject config, final String key, final String def ) {
        final String value = str( config, key );
        return value == null ? def : value;
    }

    private static int intVal( final JsonObject config, final String key, final int def ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return def;
        try {
            return config.get( key ).getAsInt();
        } catch ( final NumberFormatException | UnsupportedOperationException | IllegalStateException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as an int ({}) — using default {}", key, e.getMessage(), def );
            return def;
        }
    }

    private static long longVal( final JsonObject config, final String key, final long def ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return def;
        try {
            return config.get( key ).getAsLong();
        } catch ( final NumberFormatException | UnsupportedOperationException | IllegalStateException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as a long ({}) — using default {}", key, e.getMessage(), def );
            return def;
        }
    }

    private static boolean boolVal( final JsonObject config, final String key, final boolean def ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return def;
        try {
            return config.get( key ).getAsBoolean();
        } catch ( final UnsupportedOperationException | IllegalStateException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as a boolean ({}) — using default {}", key, e.getMessage(), def );
            return def;
        }
    }

    private static List< String > strList( final JsonObject config, final String key ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return List.of();
        final JsonElement el = config.get( key );
        if ( !el.isJsonArray() ) return List.of();
        final List< String > out = new ArrayList<>();
        for ( final JsonElement item : el.getAsJsonArray() ) {
            if ( item.isJsonNull() ) continue;
            try {
                final String value = item.getAsString().trim();
                if ( !value.isEmpty() ) out.add( value );
            } catch ( final UnsupportedOperationException | IllegalStateException | NumberFormatException | ClassCastException e ) {
                LOG.warn( "connector config field '{}': list entry not readable as a string ({}) — skipping entry", key, e.getMessage() );
            }
        }
        return List.copyOf( out );
    }
}
