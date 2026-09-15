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
package com.wikantik.extractcli;

/**
 * Flags shared by every wikantik-extract-cli tool's {@code Args}: help,
 * the database connection, and the Ollama URL. {@code BootstrapExtractionCli.Args}
 * and {@code JudgeExperimentCli.Args} both extend this so the flag set (and its
 * "requires a value" / "unset env var" error text) can't drift between the two CLIs.
 *
 * <p>Fields stay {@code public} — subclasses are themselves public so their tests
 * can drive {@code parse(String[])} directly without reflection, and public fields
 * declared here are still accessible through them.
 */
@SuppressWarnings( "PMD.AbstractClassWithoutAbstractMethod" ) // stateful base (the shared flag fields), only ever extended
abstract class CommonCliArgs {

    public boolean showHelp = false;
    public String  jdbcUrl      = "jdbc:postgresql://localhost:5432/jspwiki";
    public String  jdbcUser     = "jspwiki";
    public String  jdbcPassword = "";
    public String  ollamaUrl    = "http://inference.jakefear.com:11434";

    protected CommonCliArgs() {
        // defaults are the field initializers above
    }

    /**
     * Consumes {@code argv[i]} (and, for value-taking flags, {@code argv[i + 1]})
     * if {@code key} is one of the common flags. Returns the argv index to resume
     * scanning from on a match, or {@code -1} if {@code key} isn't a common flag —
     * in which case the caller's own switch should handle it.
     */
    final int applyCommonFlag( final String key, final String[] argv, final int i ) {
        switch ( key ) {
            case "-h", "--help"        -> { showHelp = true; return i; }
            case "--jdbc-url"          -> { jdbcUrl = req( argv, i + 1, key ); return i + 1; }
            case "--jdbc-user"         -> { jdbcUser = req( argv, i + 1, key ); return i + 1; }
            case "--jdbc-password"     -> { jdbcPassword = req( argv, i + 1, key ); return i + 1; }
            case "--jdbc-password-env" -> { jdbcPassword = env( req( argv, i + 1, key ) ); return i + 1; }
            case "--ollama-url"        -> { ollamaUrl = req( argv, i + 1, key ); return i + 1; }
            default -> { return -1; }
        }
    }

    /** Fails fast when the JDBC coordinates are blank (skipped when only help was requested). */
    final void requireJdbcCoordinates() {
        if ( showHelp ) return;
        if ( jdbcUrl.isBlank() ) throw new IllegalArgumentException( "--jdbc-url is required" );
        if ( jdbcUser.isBlank() ) throw new IllegalArgumentException( "--jdbc-user is required" );
    }

    static String req( final String[] argv, final int i, final String flag ) {
        if ( i >= argv.length ) throw new IllegalArgumentException( flag + " requires a value" );
        return argv[ i ];
    }

    static String env( final String name ) {
        final String v = System.getenv( name );
        if ( v == null || v.isEmpty() ) {
            throw new IllegalArgumentException( "environment variable '" + name + "' is unset or empty" );
        }
        return v;
    }
}
