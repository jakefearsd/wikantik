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
package com.wikantik.knowledge.judge;

import java.util.Properties;

/** Pure-data record holding the runtime config for the judge service + runner. */
public record KgJudgeConfig(
    boolean enabled,
    String endpoint,
    String model,
    boolean cronEnabled,
    int cronIntervalMinutes,
    int batchSize,
    int concurrency,
    int timeoutSeconds,
    int maxAttempts
) {
    public static KgJudgeConfig fromProperties( final Properties p ) {
        // Fall back to the extractor's canonical property names (matches what
        // EntityExtractorConfig reads via "ollama.base_url" / "ollama.model").
        // The legacy ".endpoint" key is honoured too in case any deployment
        // adopted that name from an early draft of the docs.
        final String extractorEndpoint = firstNonBlank(
            p.getProperty( "wikantik.knowledge.extractor.ollama.base_url" ),
            p.getProperty( "wikantik.knowledge.extractor.ollama.endpoint" ) );
        final String extractorModel    = p.getProperty( "wikantik.knowledge.extractor.ollama.model" );

        return new KgJudgeConfig(
            getBool( p,   "wikantik.kg.judge.enabled",          true ),
            getString( p, "wikantik.kg.judge.endpoint",         extractorEndpoint ),
            getString( p, "wikantik.kg.judge.model",            extractorModel ),
            getBool( p,   "wikantik.kg.judge.cron.enabled",     true ),
            getInt( p,    "wikantik.kg.judge.cron.interval_min", 5 ),
            getInt( p,    "wikantik.kg.judge.batch_size",        50 ),
            getInt( p,    "wikantik.kg.judge.concurrency",       2 ),
            getInt( p,    "wikantik.kg.judge.timeout_seconds",   30 ),
            getInt( p,    "wikantik.kg.judge.max_attempts",      3 )
        );
    }

    private static String firstNonBlank( final String... values ) {
        for ( final String v : values ) {
            if ( v != null && !v.isBlank() ) return v.trim();
        }
        return null;
    }

    private static String getString( final Properties p, final String k, final String def ) {
        final String v = p.getProperty( k );
        return ( v == null || v.isBlank() ) ? def : v.trim();
    }
    private static boolean getBool( final Properties p, final String k, final boolean def ) {
        final String v = p.getProperty( k );
        return ( v == null || v.isBlank() ) ? def : Boolean.parseBoolean( v.trim() );
    }
    private static int getInt( final Properties p, final String k, final int def ) {
        final String v = p.getProperty( k );
        try { return ( v == null || v.isBlank() ) ? def : Integer.parseInt( v.trim() ); }
        catch ( final NumberFormatException e ) { return def; }
    }
}
