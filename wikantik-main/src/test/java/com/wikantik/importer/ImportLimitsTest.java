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

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportLimitsTest {

    @Test
    void defaultsMatchSpec() {
        final ImportLimits l = ImportLimits.fromProperties( new Properties() );
        assertEquals( 104857600L, l.maxUploadBytes() );
        assertEquals( 524288000L, l.maxUncompressedBytes() );
        assertEquals( 20000, l.maxEntries() );
        assertEquals( 2000, l.maxPages() );
        assertEquals( 1, l.maxConcurrent() );
        assertEquals( 262144, l.maxPageBytes() );
        assertEquals( l, ImportLimits.defaults() );
    }

    @Test
    void propertiesOverride() {
        final Properties p = new Properties();
        p.setProperty( ImportLimits.PROP_MAX_PAGES, "5" );
        p.setProperty( ImportLimits.PROP_MAX_CONCURRENT, "3" );
        assertEquals( 5, ImportLimits.fromProperties( p ).maxPages() );
        assertEquals( 3, ImportLimits.fromProperties( p ).maxConcurrent() );
    }

    @Test
    void nonPositiveValuesClampToDefault() {
        for ( final String bad : new String[] { "0", "-5" } ) {
            final Properties p = new Properties();
            for ( final String k : new String[] { ImportLimits.PROP_MAX_UPLOAD_BYTES, ImportLimits.PROP_MAX_UNCOMPRESSED_BYTES,
                    ImportLimits.PROP_MAX_ENTRIES, ImportLimits.PROP_MAX_PAGES, ImportLimits.PROP_MAX_CONCURRENT,
                    ImportLimits.PROP_MAX_PAGE_BYTES } ) {
                p.setProperty( k, bad );
            }
            assertEquals( ImportLimits.defaults(), ImportLimits.fromProperties( p ), bad );
        }
    }
}
