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
package com.wikantik.collab;

import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Collaborative-editing settings. Dark by default ({@code wikantik.collab.enabled=false}). Out-of-range or unparseable
 * values fall back to the default with a warning naming the key and value. Bounds: {@code poll.timeoutSeconds} at most
 * {@value #MAX_POLL_TIMEOUT_SECONDS} (Cloudflare closes idle requests at about 100 s) and below
 * {@code presence.timeoutSeconds} (a parked pull must not outlive its participant's presence; otherwise both fall back
 * to their defaults), and {@code maxUpdateBytes} at most {@value #MAX_UPDATE_BYTES_CAP}.
 */
public record CollabConfig(
        boolean enabled,
        int maxParticipants,
        int maxUpdateBytes,
        int pollTimeoutSeconds,
        int presenceTimeoutSeconds,
        int permissionRecheckSeconds,
        int historyInMemoryUpdates,
        int snapshotEveryUpdates,
        int memoryIdleEvictMinutes,
        int draftRetentionDays,
        int pushesPerSecond ) {

    /** The largest accepted {@code poll.timeoutSeconds}. */
    public static final int MAX_POLL_TIMEOUT_SECONDS = 60;
    /** The largest accepted {@code maxUpdateBytes} (16 MiB). */
    public static final int MAX_UPDATE_BYTES_CAP = 16 * 1024 * 1024;

    private static final Logger LOG = LogManager.getLogger( CollabConfig.class );
    private static final String POLL = "wikantik.collab.poll.timeoutSeconds";
    private static final String PRESENCE = "wikantik.collab.presence.timeoutSeconds";
    private static final int POLL_DEFAULT = 25;
    private static final int PRESENCE_DEFAULT = 45;

    /** The configuration with every key at its declared default. */
    public static CollabConfig defaults() {
        return from( new Properties() );
    }

    /** Reads the {@code wikantik.collab.*} keys from {@code p}, applying the declared defaults. */
    public static CollabConfig from( final Properties p ) {
        int poll = bounded( p, POLL, POLL_DEFAULT, MAX_POLL_TIMEOUT_SECONDS );
        int presence = positive( p, PRESENCE, PRESENCE_DEFAULT );
        if( poll >= presence ) {
            LOG.warn( "{} = {} must be below {} = {}; using the defaults {} and {}", POLL, poll, PRESENCE, presence,
                POLL_DEFAULT, PRESENCE_DEFAULT );
            poll = POLL_DEFAULT;
            presence = PRESENCE_DEFAULT;
        }
        return new CollabConfig(
                TextUtil.getBooleanProperty( p, "wikantik.collab.enabled", false ),
                positive( p, "wikantik.collab.maxParticipants", 20 ),
                bounded( p, "wikantik.collab.maxUpdateBytes", 65536, MAX_UPDATE_BYTES_CAP ),
                poll,
                presence,
                positive( p, "wikantik.collab.permission.recheckSeconds", 30 ),
                positive( p, "wikantik.collab.history.inMemoryUpdates", 2000 ),
                positive( p, "wikantik.collab.snapshot.everyUpdates", 500 ),
                positive( p, "wikantik.collab.memory.idleEvictMinutes", 10 ),
                nonNegative( p, "wikantik.collab.draft.retentionDays", 0 ),
                positive( p, "wikantik.collab.pushesPerSecond", 20 ) );
    }

    private static int bounded( final Properties p, final String key, final int dflt, final int max ) {
        final int v = positive( p, key, dflt );
        if( v > max ) {
            LOG.warn( "{} = {} must be at most {}; using the default {}", key, v, max, dflt );
            return dflt;
        }
        return v;
    }

    private static int positive( final Properties p, final String key, final int dflt ) {
        final int v = read( p, key, dflt );
        if( v <= 0 ) {
            LOG.warn( "{} = {} must be positive; using the default {}", key, v, dflt );
            return dflt;
        }
        return v;
    }

    private static int nonNegative( final Properties p, final String key, final int dflt ) {
        final int v = read( p, key, dflt );
        if( v < 0 ) {
            LOG.warn( "{} = {} must not be negative; using the default {}", key, v, dflt );
            return dflt;
        }
        return v;
    }

    /**
     * The value as {@link TextUtil#getIntegerProperty} finds it (a system property, then an environment variable with
     * dots as underscores, then {@code p}), but an unparseable value is warned about instead of silently defaulted.
     */
    private static int read( final Properties p, final String key, final int dflt ) {
        final String override = System.getProperties().getProperty( key, System.getenv( key.replace( ".", "_" ) ) );
        final String raw = override != null ? override : p.getProperty( key );
        if( raw == null || raw.isBlank() ) {
            return dflt;
        }
        try {
            return Integer.parseInt( raw.trim() );
        } catch( final NumberFormatException e ) {
            LOG.warn( "{} = '{}' is not an integer; using the default {}: {}", key, raw, dflt, e.getMessage() );
            return dflt;
        }
    }
}
