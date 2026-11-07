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
package com.wikantik.collab.testing;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Replays every committed shrunk trace in {@code collab/regressions/} against a fresh stub and a fresh session, forever (F1). */
class CollabRegressionTracesTest {

    private static Path dir() throws URISyntaxException {
        final URL url = CollabRegressionTracesTest.class.getResource( "/collab/regressions" );
        assertNotNull( url, "src/test/resources/collab/regressions is missing" );
        return Path.of( url.toURI() );
    }

    @Test
    void theRegressionDirectoryIsOnTheClasspath() throws URISyntaxException {
        assertNotNull( dir() );
    }

    @TestFactory
    Stream< DynamicTest > everyCommittedTraceReplaysCleanly() throws IOException, URISyntaxException {
        final List< Path > traces;
        try( Stream< Path > s = Files.list( dir() ) ) {
            traces = s.filter( p -> p.getFileName().toString().endsWith( ".json" ) ).sorted().toList();
        }
        return SimTarget.all().stream().flatMap( target -> traces.stream().map( p -> DynamicTest.dynamicTest(
            target + " " + p.getFileName(), () -> {
                final SimTrace recorded = SimTrace.fromJson( Files.readString( p, StandardCharsets.UTF_8 ) );
                final SimTrace replayed = target.replay( recorded );
                assertEquals( SimTrace.Status.PASS, replayed.outcome().status(), target + " " + p.getFileName() + " (seed "
                    + recorded.seed() + ", " + recorded.profile() + "): " + replayed.outcome().message() );
            } ) ) );
    }
}
