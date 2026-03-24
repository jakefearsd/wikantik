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
package com.wikantik.cache.memcached;

import com.wikantik.api.core.Engine;
import com.wikantik.api.engine.Initializable;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.cache.CacheInfo;
import com.wikantik.cache.CachingManager;
import com.wikantik.util.CheckedSupplier;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.utils.AddrUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Memcached-based {@link CachingManager} using the xmemcached client.
 *
 * <p><b>Shadow key set:</b> Memcached has no key-enumeration API, so this class maintains
 * a local {@code ConcurrentHashMap<cacheName, Set<key>>} that mirrors what has been stored.
 * Entries are added on {@link #put} / {@link #get} (supplier path) and removed on
 * {@link #remove}. When an entry expires in memcached via TTL, its shadow entry becomes
 * stale; a subsequent {@link #get} that returns {@code null} for a shadowed key cleans
 * up the shadow set lazily. This makes {@link #keys} safe for all callers —
 * null results are simply skipped.</p>
 *
 * <p><b>Eviction listeners:</b> Memcached has no client-side eviction callbacks.
 * {@link #registerListener} is a deliberate no-op, consistent with the current
 * EhCache implementation which also does not wire real eviction events.</p>
 *
 * <p><b>Switching to this adapter:</b> In
 * {@code wikantik-main/src/main/resources/ini/classmappings.xml}, change the
 * {@code mappedClass} for {@code CachingManager} from
 * {@code com.wikantik.cache.EhcacheCachingManager} to
 * {@code com.wikantik.cache.memcached.MemcachedCachingManager} and add this JAR to the
 * classpath. Set {@code wikantik.cache.memcached.servers} in your properties file.</p>
 */
public class MemcachedCachingManager implements CachingManager, Initializable {

    private static final Logger LOG = LogManager.getLogger( MemcachedCachingManager.class );
    private static final int DEFAULT_TTL_SECONDS = 24 * 60 * 60;
    private static final int DEFAULT_MAX_ENTRIES = 1_000;

    /** Comma-separated {@code host:port} list of memcached servers. Default: {@code localhost:11211}. */
    static final String PROP_SERVERS = "wikantik.cache.memcached.servers";

    /** TTL for cached entries in seconds. Default: 86400 (24 h). */
    static final String PROP_TTL = "wikantik.cache.memcached.ttl";

    /** Reported max-entries for {@link CacheInfo} (does not enforce a cap). Default: 1000. */
    static final String PROP_MAX_ENTRIES = "wikantik.cache.memcached.max-entries";

    private MemcachedClient client;
    private int ttlSeconds = DEFAULT_TTL_SECONDS;
    private int maxEntries = DEFAULT_MAX_ENTRIES;

    // Shadow key sets: per-cache tracking of which keys have been stored,
    // because memcached has no enumeration API.
    private final Map< String, Set< String > > shadowKeys = new ConcurrentHashMap<>();
    private final Map< String, CacheInfo > cacheStats = new ConcurrentHashMap<>();
    private final Set< String > enabledCaches = ConcurrentHashMap.newKeySet();

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws WikiException {
        final String cacheEnabled = props.getProperty( PROP_CACHE_ENABLE,
                props.getProperty( "wikantik.usePageCache", "true" ) );
        if( !"true".equalsIgnoreCase( cacheEnabled ) ) {
            return;
        }
        final String servers = props.getProperty( PROP_SERVERS, "localhost:11211" );
        ttlSeconds = Integer.parseInt( props.getProperty( PROP_TTL, String.valueOf( DEFAULT_TTL_SECONDS ) ) );
        maxEntries = Integer.parseInt( props.getProperty( PROP_MAX_ENTRIES, String.valueOf( DEFAULT_MAX_ENTRIES ) ) );
        try {
            client = new XMemcachedClientBuilder( AddrUtil.getAddresses( servers ) ).build();
            LOG.info( "Memcached client connected to {}", servers );
        } catch( final IOException e ) {
            throw new WikiException( "Failed to connect to memcached at " + servers, e );
        }
        for( final String name : List.of( CACHE_ATTACHMENTS, CACHE_ATTACHMENTS_COLLECTION,
                CACHE_ATTACHMENTS_DYNAMIC, CACHE_DOCUMENTS, CACHE_PAGES,
                CACHE_PAGES_HISTORY, CACHE_PAGES_TEXT, CACHE_HTML ) ) {
            registerCache( name );
        }
    }

    void registerCache( final String cacheName ) {
        enabledCaches.add( cacheName );
        shadowKeys.put( cacheName, ConcurrentHashMap.newKeySet() );
        cacheStats.put( cacheName, new CacheInfo( cacheName, maxEntries ) );
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        if( client != null ) {
            try {
                client.shutdown();
            } catch( final IOException e ) {
                LOG.warn( "Error shutting down memcached client", e );
            }
            client = null;
        }
        enabledCaches.clear();
        shadowKeys.clear();
        cacheStats.clear();
    }

    /** {@inheritDoc} */
    @Override
    public boolean enabled( final String cacheName ) {
        return enabledCaches.contains( cacheName );
    }

    /** {@inheritDoc} */
    @Override
    public CacheInfo info( final String cacheName ) {
        return enabled( cacheName ) ? cacheStats.get( cacheName ) : null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a snapshot of the shadow key set. Stale entries (TTL-expired in memcached
     * but not yet cleaned from the shadow) will be present; callers that do a subsequent
     * {@link #get} for each key will receive {@code null} for expired entries, which is
     * the contract all callers already handle.</p>
     */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T extends Serializable > List< T > keys( final String cacheName ) {
        if( !enabled( cacheName ) ) {
            return Collections.emptyList();
        }
        return new ArrayList<>( ( Set< T > ) shadowKeys.get( cacheName ) );
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T, E extends Exception > T get( final String cacheName, final Serializable key,
            final CheckedSupplier< T, E > supplier ) throws E {
        if( !keyAndCacheAreNotNull( cacheName, key ) ) {
            return null;
        }
        final String mcKey = toMemcachedKey( cacheName, key );
        final String keyStr = key.toString();
        try {
            final T cached = ( T ) client.get( mcKey );
            if( cached != null ) {
                cacheStats.get( cacheName ).hit();
                return cached;
            }
            // null returned — entry was never stored or has expired via TTL.
            // Evict from shadow set to keep it tidy.
            shadowKeys.get( cacheName ).remove( keyStr );
        } catch( final Exception e ) {
            LOG.warn( "Memcached get failed for cache {} key {} — falling back to supplier", cacheName, key, e );
        }
        // Cache miss: try supplier, then populate cache.
        final T fresh = supplier.get();
        if( fresh != null ) {
            cacheStats.get( cacheName ).miss();
            try {
                client.set( mcKey, ttlSeconds, fresh );
                shadowKeys.get( cacheName ).add( keyStr );
            } catch( final Exception e ) {
                LOG.warn( "Memcached set failed for cache {} key {}", cacheName, key, e );
            }
        }
        return fresh;
    }

    /** {@inheritDoc} */
    @Override
    public void put( final String cacheName, final Serializable key, final Object val ) {
        if( !keyAndCacheAreNotNull( cacheName, key ) ) {
            return;
        }
        if( val == null ) {
            // Memcached does not accept null values; treat as removal.
            remove( cacheName, key );
            return;
        }
        final String mcKey = toMemcachedKey( cacheName, key );
        try {
            client.set( mcKey, ttlSeconds, val );
            shadowKeys.get( cacheName ).add( key.toString() );
        } catch( final Exception e ) {
            LOG.warn( "Memcached put failed for cache {} key {}", cacheName, key, e );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void remove( final String cacheName, final Serializable key ) {
        if( !keyAndCacheAreNotNull( cacheName, key ) ) {
            return;
        }
        final String mcKey = toMemcachedKey( cacheName, key );
        try {
            client.delete( mcKey );
        } catch( final Exception e ) {
            LOG.warn( "Memcached delete failed for cache {} key {}", cacheName, key, e );
        }
        shadowKeys.get( cacheName ).remove( key.toString() );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Memcached has no client-side eviction callbacks, so this method is a no-op and
     * returns {@code false}. This is intentionally consistent with the current
     * {@code EhcacheCachingManager} which also does not wire real eviction events.</p>
     */
    @Override
    public boolean registerListener( final String cacheName, final String listener, final Object... args ) {
        LOG.debug( "registerListener for cache {} listener {} — no-op in memcached adapter", cacheName, listener );
        return false;
    }

    /**
     * Maps a logical {@code (cacheName, key)} pair to a memcached-safe key string.
     * Uses SHA-256 to produce a fixed-length, character-safe key regardless of the input.
     * The result is always 65 characters ("w" + 64 hex digits), well within memcached's 250-byte limit.
     */
    String toMemcachedKey( final String cacheName, final Serializable key ) {
        final String logical = cacheName + ":" + key.toString();
        try {
            final MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            final byte[] hash = digest.digest( logical.getBytes( StandardCharsets.UTF_8 ) );
            return "w" + HexFormat.of().formatHex( hash );
        } catch( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 not available", e );
        }
    }

    boolean keyAndCacheAreNotNull( final String cacheName, final Serializable key ) {
        return enabled( cacheName ) && key != null;
    }

}
