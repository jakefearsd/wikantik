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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Engine;
import com.wikantik.api.observability.MeterRegistryHolder;
import com.wikantik.util.TextUtil;
import com.wikantik.work.WorkListener;
import com.wikantik.work.WorkSettings;
import com.wikantik.work.JdbcWorkStore;
import com.wikantik.work.WorkMetrics;
import com.wikantik.work.WorkService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;

/**
 * Builds the {@link WorkService} at startup, following the build-once-return-null-when-off pattern of
 * {@code InsightsWiring}; see {@code WikiEngine#initDataSourceSubsystems} for the call site.
 */
public final class WorkWiring {

    private static final Logger LOG = LogManager.getLogger( WorkWiring.class );

    /** The feature flag; default {@code false}. */
    public static final String PROP_ENABLED = "wikantik.work.enabled";

    /** Minutes a claim lasts before it lapses; 1 to 10080, default 240. */
    public static final String PROP_CLAIM_EXPIRY = "wikantik.work.claim.expiryMinutes";

    /** Largest single attachment in bytes; 1 to 104857600, default 10485760. */
    public static final String PROP_ATTACH_MAX_BYTES = "wikantik.work.attachments.maxBytes";

    /** Most live attachment bytes one item may hold; raised to the file limit when lower, default 52428800. */
    public static final String PROP_ATTACH_MAX_ITEM_BYTES = "wikantik.work.attachments.maxItemBytes";

    private static final int MULTIPART_CEILING = 104_857_600;

    private static final int DEFAULT_EXPIRY_MINUTES = 240;
    private static final int MAX_EXPIRY_MINUTES = 7 * 24 * 60;

    private WorkWiring() {
    }

    /**
     * @return the wired service, or {@code null} when {@code wikantik.work.enabled} is false or {@code ds} is
     *         {@code null}; callers no-op on {@code null}
     */
    public static WorkService build( final DataSource ds, final Properties props, final Engine engine ) {
        if ( !Boolean.parseBoolean( props.getProperty( PROP_ENABLED, "false" ).trim() ) ) {
            return null;
        }
        if ( ds == null ) {
            LOG.warn( "{} is true but no datasource is available -- work management is not started", PROP_ENABLED );
            return null;
        }
        final JdbcWorkStore store = new JdbcWorkStore( ds );
        final MeterRegistry registry = MeterRegistryHolder.get();
        final WorkListener listener = registry != null ? new WorkMetrics( registry, store ) : new WorkListener() { };
        final WorkService service = new WorkService( store, new WikiWorkRenderer( engine ),
            new WikiWorkDirectory( engine ), Clock.systemDefaultZone(),
            settings( props ), listener, new WikiWorkAudit( engine ),
            WorkNotifyWiring.ports( props, engine ) );
        LOG.info( "Work management started ({}=true, metrics {})", PROP_ENABLED, registry != null ? "on" : "off" );
        return service;
    }

    /** How long a claim lasts: {@link #PROP_CLAIM_EXPIRY} when within 1 to 10080 minutes, else 240 with a warning. */
    static Duration claimExpiry( final Properties props ) {
        final int minutes = TextUtil.getIntegerProperty( props, PROP_CLAIM_EXPIRY, DEFAULT_EXPIRY_MINUTES );
        if ( minutes < 1 || minutes > MAX_EXPIRY_MINUTES ) {
            LOG.warn( "{}={} is outside 1..{}; using {} minutes", PROP_CLAIM_EXPIRY, minutes, MAX_EXPIRY_MINUTES,
                DEFAULT_EXPIRY_MINUTES );
            return Duration.ofMinutes( DEFAULT_EXPIRY_MINUTES );
        }
        return Duration.ofMinutes( minutes );
    }

    /** The claim expiry plus both attachment limits, each validated as its key's description says. */
    static WorkSettings settings( final Properties props ) {
        final int file = TextUtil.getIntegerProperty( props, PROP_ATTACH_MAX_BYTES,
            (int) WorkSettings.DEFAULT_MAX_ATTACHMENT_BYTES );
        final int fileLimit;
        if ( file < 1 || file > MULTIPART_CEILING ) {
            LOG.warn( "{}={} is outside 1..{}; using {} bytes", PROP_ATTACH_MAX_BYTES, file, MULTIPART_CEILING,
                WorkSettings.DEFAULT_MAX_ATTACHMENT_BYTES );
            fileLimit = (int) WorkSettings.DEFAULT_MAX_ATTACHMENT_BYTES;
        } else {
            fileLimit = file;
        }
        final int item = TextUtil.getIntegerProperty( props, PROP_ATTACH_MAX_ITEM_BYTES,
            (int) WorkSettings.DEFAULT_MAX_ITEM_ATTACHMENT_BYTES );
        long itemLimit = item;
        if ( item < fileLimit ) {
            LOG.warn( "{}={} is below {}={}; using {}", PROP_ATTACH_MAX_ITEM_BYTES, item, PROP_ATTACH_MAX_BYTES,
                fileLimit, fileLimit );
            itemLimit = fileLimit;
        }
        return new WorkSettings( claimExpiry( props ), fileLimit, itemLimit );
    }
}
