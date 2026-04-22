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
package com.wikantik.search.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Smoke-test entry point for the embedding client. Loads the default wiki
 * properties, overlays any {@code -D} overrides, forces the master flag on
 * (so the CLI is not blocked by the production default), and embeds a single
 * text against the selected model. Prints the model metadata, request latency,
 * L2 norm, and the head of the vector — enough to confirm that a model tag
 * is pullable, the network path is open, and prefixes are reaching the server.
 */
@SuppressWarnings("PMD.SystemPrintln")
public final class EmbeddingCli {

    private EmbeddingCli() {}
    public static void main( final String[] args ) throws IOException {
        if( args.length < 3 ) {
            usage();
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final EmbeddingKind kind;
        try {
            kind = EmbeddingKind.valueOf( args[ 1 ].toUpperCase( Locale.ROOT ) );
        } catch( final IllegalArgumentException e ) {
            System.err.println( "invalid kind: " + args[ 1 ] + " (expected 'query' or 'document')" );
            System.exit( 2 );
            return;
        }
        final String text = String.join( " ", Arrays.copyOfRange( args, 2, args.length ) );

        final Properties props = loadProperties();
        // CLI is explicit — the master flag is only a production safety net.
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( EmbeddingConfig.PROP_MODEL, modelCode );

        final EmbeddingConfig cfg = EmbeddingConfig.fromProperties( props );
        final TextEmbeddingClient client = EmbeddingClientFactory.create( cfg ).orElseThrow(
            () -> new IllegalStateException( "factory refused to build a client — check config" ) );

        System.out.printf( "model=%s  dim=%d  backend=%s%n  url=%s  tag=%s  kind=%s%n",
            client.modelName(), client.dimension(), cfg.backend(),
            cfg.baseUrl(), cfg.resolvedOllamaTag(), kind.name().toLowerCase( Locale.ROOT ) );
        System.out.printf( "input (%d chars): %s%n", text.length(), abbreviate( text, 80 ) );

        final long t0 = System.nanoTime();
        final List< float[] > out = client.embed( List.of( text ), kind );
        final long elapsedMs = ( System.nanoTime() - t0 ) / 1_000_000L;

        final float[] vec = out.get( 0 );
        double squared = 0;
        for( final float f : vec ) squared += (double) f * f;
        final double norm = Math.sqrt( squared );

        final StringBuilder head = new StringBuilder();
        final int show = Math.min( 8, vec.length );
        for( int i = 0; i < show; i++ ) {
            if( i > 0 ) head.append( ", " );
            head.append( String.format( Locale.ROOT, "%+.4f", vec[ i ] ) );
        }

        System.out.printf( "latency=%dms  l2_norm=%.4f  head=[%s, …]%n", elapsedMs, norm, head );
    }

    private static Properties loadProperties() throws IOException {
        final Properties p = new Properties();
        try( final InputStream in = EmbeddingCli.class.getResourceAsStream( "/ini/wikantik.properties" ) ) {
            if( in != null ) p.load( in );
        }
        // Overlay -D system properties so the CLI can be retargeted without a build.
        for( final String key : PROPERTY_KEYS ) {
            final String v = System.getProperty( key );
            if( v != null && !v.isBlank() ) p.setProperty( key, v );
        }
        return p;
    }

    private static final List< String > PROPERTY_KEYS = List.of(
        EmbeddingConfig.PROP_ENABLED,
        EmbeddingConfig.PROP_BACKEND,
        EmbeddingConfig.PROP_BASE_URL,
        EmbeddingConfig.PROP_API_KEY,
        EmbeddingConfig.PROP_MODEL,
        EmbeddingConfig.PROP_OLLAMA_TAG,
        EmbeddingConfig.PROP_TIMEOUT_MS,
        EmbeddingConfig.PROP_BATCH_SIZE
    );

    private static String abbreviate( final String s, final int max ) {
        if( s.length() <= max ) return s;
        return s.substring( 0, max - 1 ) + "…";
    }

    private static void usage() {
        System.err.println( """
            Usage: EmbeddingCli <model-code> <query|document> <text…>

              model-code:  nomic-embed-v1.5 | bge-m3 | qwen3-embedding-0.6b
              kind:        query | document
              text:        one or more arg tokens; joined with spaces

            Overrides (via -D on the java / mvn exec command line):
              -Dwikantik.search.embedding.base-url=http://host:port
              -Dwikantik.search.embedding.ollama-tag=<custom-tag>
              -Dwikantik.search.embedding.api-key=<bearer-token>
              -Dwikantik.search.embedding.timeout-ms=30000
              -Dwikantik.search.embedding.batch-size=32

            Example:
              mvn -pl wikantik-main -am exec:java \\
                  -Dexec.mainClass=com.wikantik.search.embedding.EmbeddingCli \\
                  -Dexec.args="bge-m3 query 'who owns the chunker'"
            """ );
    }
}
