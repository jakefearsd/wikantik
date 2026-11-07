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
 * Collaborative-editing settings. Dark by default ({@code wikantik.collab.enabled=false}). Out-of-range values fall back
 * to the default with a warning naming the key and value.
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

    private static final Logger LOG = LogManager.getLogger( CollabConfig.class );

    /** The configuration with every key at its declared default. */
    public static CollabConfig defaults() {
        return from( new Properties() );
    }

    /** Reads the {@code wikantik.collab.*} keys from {@code p}, applying the declared defaults. */
    public static CollabConfig from( final Properties p ) {
        return new CollabConfig(
                TextUtil.getBooleanProperty( p, "wikantik.collab.enabled", false ),
                positive( p, "wikantik.collab.maxParticipants", 20 ),
                positive( p, "wikantik.collab.maxUpdateBytes", 65536 ),
                positive( p, "wikantik.collab.poll.timeoutSeconds", 25 ),
                positive( p, "wikantik.collab.presence.timeoutSeconds", 45 ),
                positive( p, "wikantik.collab.permission.recheckSeconds", 30 ),
                positive( p, "wikantik.collab.history.inMemoryUpdates", 2000 ),
                positive( p, "wikantik.collab.snapshot.everyUpdates", 500 ),
                positive( p, "wikantik.collab.memory.idleEvictMinutes", 10 ),
                nonNegative( p, "wikantik.collab.draft.retentionDays", 0 ),
                positive( p, "wikantik.collab.pushesPerSecond", 20 ) );
    }

    private static int positive( final Properties p, final String key, final int dflt ) {
        final int v = TextUtil.getIntegerProperty( p, key, dflt );
        if( v <= 0 ) {
            LOG.warn( "{} = {} must be positive; using the default {}", key, v, dflt );
            return dflt;
        }
        return v;
    }

    private static int nonNegative( final Properties p, final String key, final int dflt ) {
        final int v = TextUtil.getIntegerProperty( p, key, dflt );
        if( v < 0 ) {
            LOG.warn( "{} = {} must not be negative; using the default {}", key, v, dflt );
            return dflt;
        }
        return v;
    }
}
