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
package com.wikantik.markdown.extensions.fences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlantUmlCheckCliTest {

    @TempDir
    Path dir;

    private record Result( int exit, String out, String err ) {
    }

    private static Result run( final String stdin, final String... args ) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int code = PlantUmlCheckCli.run( args, new ByteArrayInputStream( stdin.getBytes( StandardCharsets.UTF_8 ) ),
                new PrintStream( o, true, StandardCharsets.UTF_8 ), new PrintStream( e, true, StandardCharsets.UTF_8 ) );
        return new Result( code, o.toString( StandardCharsets.UTF_8 ), e.toString( StandardCharsets.UTF_8 ) );
    }

    private Path md( final String name, final String text ) throws IOException {
        final Path p = dir.resolve( name );
        Files.createDirectories( p.getParent() );
        Files.writeString( p, text );
        return p;
    }

    @Test
    void allGoodExit0() throws IOException {
        final Path f = md( "good.md", "# T\n\n```plantuml\nclass A\nclass B\nA --> B\n```\n" );
        final Result r = run( "", f.toString() );
        assertEquals( 0, r.exit(), r.err() );
        assertTrue( r.out().contains( "OK " + f + ":3\n" ), r.out() );
        assertTrue( r.out().contains( "1 diagrams, 1 ok, 0 failed" ), r.out() );
    }

    @Test
    void refusedExit1WithReason() throws IOException {
        final Path f = md( "bad.md", "```plantuml\n!include /etc/passwd\nclass A\n```\n" );
        final Result r = run( "", f.toString() );
        assertEquals( 1, r.exit() );
        assertTrue( r.out().contains( "FAIL " + f + ":1 include" ), r.out() );
    }

    @Test
    void syntaxErrorReportsLine() throws IOException {
        final Path f = md( "syn.md", "text\n\n```plantuml\n@startuml\nclass A\nthis is not valid plantuml\n@enduml\n```\n" );
        final Result r = run( "", f.toString() );
        assertEquals( 1, r.exit() );
        assertTrue( r.out().matches( "(?s).*FAIL " + java.util.regex.Pattern.quote( f.toString() )
                + ":3 syntax-error line \\d+.*" ), r.out() );
    }

    @Test
    void jsonOutput() throws IOException {
        final Path good = md( "a/good.md", "```plantuml\nclass A\n```\n" );
        final Path bad = md( "a/z/bad.md", "x\n```plantuml\n!include /etc/passwd\n```\n" );
        final Result r = run( "", "--json", dir.toString() );
        assertEquals( 1, r.exit() );
        final JsonArray a = JsonParser.parseString( r.out() ).getAsJsonArray();
        assertEquals( 2, a.size() );
        final JsonObject first = a.get( 0 ).getAsJsonObject();
        assertEquals( good.toString(), first.get( "file" ).getAsString() );
        assertEquals( 1, first.get( "line" ).getAsInt() );
        assertTrue( first.get( "ok" ).getAsBoolean() );
        assertTrue( first.get( "error" ).isJsonNull() );
        final JsonObject second = a.get( 1 ).getAsJsonObject();
        assertEquals( bad.toString(), second.get( "file" ).getAsString() );
        assertEquals( 2, second.get( "line" ).getAsInt() );
        assertTrue( second.get( "error" ).getAsString().startsWith( "include" ) );
    }

    @Test
    void missingPathExit2() {
        final Result r = run( "", dir.resolve( "nope.md" ).toString() );
        assertEquals( 2, r.exit() );
        assertTrue( r.err().contains( "usage:" ), r.err() );
        assertEquals( "", r.out() );
    }

    @Test
    void usageErrorsExit2() {
        assertEquals( 2, run( "" ).exit() );
        assertEquals( 2, run( "", "--bogus", "-" ).exit() );
    }

    @Test
    void exampleFencesIgnored() throws IOException {
        final Path f = md( "ex.md", "```plantuml example\n!include /etc/passwd\n```\n\n```mermaid\nflowchart TD\n```\n" );
        final Result r = run( "", f.toString() );
        assertEquals( 0, r.exit() );
        assertTrue( r.out().contains( "0 diagrams, 0 ok, 0 failed" ), r.out() );
    }

    @Test
    void stdinInput() {
        final Result r = run( "```plantuml\nclass A\n```\n\n```plantuml\n!include /etc/passwd\n```\n", "-" );
        assertEquals( 1, r.exit() );
        assertTrue( r.out().contains( "OK -:1\n" ), r.out() );
        assertTrue( r.out().contains( "FAIL -:5 include" ), r.out() );
    }

    @Test
    void syntaxErrorLineIsFileRelativeWithExplicitStartuml() throws IOException {
        // fence opens on line 3; content line 1 = @startuml (file line 4), "nope nope" is content line 3 = file line 6
        final Path f = md( "s1.md", "text\n\n```plantuml\n@startuml\nclass A\nnope nope\n@enduml\n```\n" );
        assertTrue( run( "", f.toString() ).out().contains( ":3 syntax-error line 6" ), "explicit @startuml" );
    }

    @Test
    void syntaxErrorLineIsFileRelativeWithoutStartuml() throws IOException {
        // fence opens on line 3; "class A" is file line 4, "nope nope" is file line 5
        final Path f = md( "s2.md", "text\n\n```plantuml\nclass A\nnope nope\n```\n" );
        assertTrue( run( "", f.toString() ).out().contains( ":3 syntax-error line 5" ), "implicit @startuml" );
    }

    @Test
    void logOutputNeverReachesStdout() throws Exception {
        // invalid UTF-8 makes the file read fail, which logs a WARN; with the default log4j console appender that
        // would land on stdout unless the CLI points System.out at stderr before log4j initialises.
        final Path bad = dir.resolve( "bad.md" );
        Files.write( bad, new byte[] { ( byte ) 0xff, ( byte ) 0xfe, ( byte ) 0xfd } );
        final List< String > cp = new ArrayList<>();
        for ( final String e : System.getProperty( "java.class.path" ).split( File.pathSeparator ) ) {
            if ( !e.endsWith( "test-classes" ) ) {   // keep the test log4j2 config out: exercise the default appender
                cp.add( e );
            }
        }
        final Process p = new ProcessBuilder( Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString(),
                "-Dlog4j2.level=WARN", "-cp", String.join( File.pathSeparator, cp ),
                "com.wikantik.markdown.extensions.fences.PlantUmlCheckCli", bad.toString() ).start();
        p.getOutputStream().close();
        final byte[] out = p.getInputStream().readAllBytes();
        final String err = new String( p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8 );
        assertEquals( 2, p.waitFor(), err );
        assertEquals( "", new String( out, StandardCharsets.UTF_8 ), "stdout must carry only the report" );
        assertTrue( err.contains( "check-plantuml" ), err );
    }
}
