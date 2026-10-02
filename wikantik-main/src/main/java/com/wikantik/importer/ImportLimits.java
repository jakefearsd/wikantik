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
package com.wikantik.importer;

import com.wikantik.util.TextUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Size and count limits for an Obsidian vault import (spec section 3). Exceeding any of them is
 * reported as an {@link ImportLimitException} naming the configuration key.
 *
 * @param maxUploadBytes       largest accepted upload zip, in bytes
 * @param maxUncompressedBytes largest total uncompressed size of all entries, in bytes
 * @param maxEntries           largest number of zip entries
 * @param maxPages             largest number of Markdown notes
 * @param maxConcurrent        largest number of import jobs running at once
 * @param maxPageBytes         largest single page body, in bytes
 */
public record ImportLimits( long maxUploadBytes, long maxUncompressedBytes, int maxEntries,
                            int maxPages, int maxConcurrent, int maxPageBytes ) {

    private static final Logger LOG = LogManager.getLogger( ImportLimits.class );

    public static final String PROP_MAX_UPLOAD_BYTES = "wikantik.import.maxUploadBytes";
    public static final int DEFAULT_MAX_UPLOAD_BYTES = 104857600;
    public static final String PROP_MAX_UNCOMPRESSED_BYTES = "wikantik.import.maxUncompressedBytes";
    public static final int DEFAULT_MAX_UNCOMPRESSED_BYTES = 524288000;
    public static final String PROP_MAX_ENTRIES = "wikantik.import.maxEntries";
    public static final int DEFAULT_MAX_ENTRIES = 20000;
    public static final String PROP_MAX_PAGES = "wikantik.import.maxPages";
    public static final int DEFAULT_MAX_PAGES = 2000;
    public static final String PROP_MAX_CONCURRENT = "wikantik.import.maxConcurrent";
    public static final int DEFAULT_MAX_CONCURRENT = 1;
    public static final String PROP_MAX_PAGE_BYTES = "wikantik.api.maxPageBytes";
    public static final int DEFAULT_MAX_PAGE_BYTES = 262144;

    /** Reads the limits from wiki properties; a missing or non-positive value falls back to the documented default. */
    public static ImportLimits fromProperties( final Properties props ) {
        return new ImportLimits(
            positive( props, PROP_MAX_UPLOAD_BYTES, DEFAULT_MAX_UPLOAD_BYTES ),
            positive( props, PROP_MAX_UNCOMPRESSED_BYTES, DEFAULT_MAX_UNCOMPRESSED_BYTES ),
            positive( props, PROP_MAX_ENTRIES, DEFAULT_MAX_ENTRIES ),
            positive( props, PROP_MAX_PAGES, DEFAULT_MAX_PAGES ),
            positive( props, PROP_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT ),
            positive( props, PROP_MAX_PAGE_BYTES, DEFAULT_MAX_PAGE_BYTES ) );
    }

    private static int positive( final Properties props, final String key, final int dflt ) {
        final int v = TextUtil.getIntegerProperty( props, key, dflt );
        if ( v < 1 ) {
            LOG.warn( "{} = {} is not a positive limit; using the default {}", key, v, dflt );
            return dflt;
        }
        return v;
    }

    /** The limits with every key at its default. */
    public static ImportLimits defaults() {
        return fromProperties( new Properties() );
    }
}
