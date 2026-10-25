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
package com.wikantik.plantuml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-off generator for the {@code src/test/resources/svg} fixtures. Disabled unless run with
 * {@code -Dwikantik.plantuml.generateFixtures=true}; the generated files are committed.
 */
class SvgFixtureGenerator {

    @Test
    @EnabledIfSystemProperty( named = "wikantik.plantuml.generateFixtures", matches = "true" )
    void generate() throws IOException {
        final Map< String, String > sources = new LinkedHashMap<>();
        sources.put( "class", "interface Shape\nabstract class Base<T> implements Shape {\n  +T value\n  -int count\n}\nclass Circle extends Base\nCircle --> Shape : draws\nnote right of Circle : a && b <ok>\nclass Linked [[https://example.com/x{tip}]]\n" );
        sources.put( "activity", "start\nfork\n:a;\nfork again\n:b;\nend fork\nif (x?) then (yes)\n:c;\nelse (no)\n:d;\nendif\nstop\n" );
        sources.put( "sequence", "actor Bob\nparticipant Alice\nBob -> Alice : hello\nactivate Alice\nAlice --> Bob : ok\ndeactivate Alice\nnote over Bob : hi\nBob -> Bob : [[https://example.com{tip} link]]\n" );
        sources.put( "usecase", "actor User\nUser --> (Login)\n(Login) .> (Auth) : include\nrectangle System {\n  usecase \"Pay\" as UC2\n}\nUser --> UC2\n" );
        sources.put( "component", "[Web] --> [API]\ncomponent DB\n[API] ..> DB : sql\npackage \"Core\" {\n  [Svc]\n}\nWeb -> Svc\n" );
        sources.put( "state", "[*] --> Idle\nIdle --> Running : start\nstate Running {\n  [*] --> A\n  A --> B\n}\nRunning --> [*]\n" );
        sources.put( "object", "object Foo {\n  id = 1\n  name = \"x\"\n}\nobject Bar\nFoo --> Bar : uses\n" );
        sources.put( "deployment", "node Server {\n  artifact app.war\n}\ndatabase PG\ncloud Net\nServer --> PG\nNet --> Server\n" );
        sources.put( "timing", "robust \"Web\" as W\nconcise \"User\" as U\n@0\nW is Idle\nU is Waiting\n@100\nW is Busy\nU is Active\n@300\nW is Idle\n" );
        sources.put( "c4", "skinparam rectangle<<person>> {\n  BackgroundColor #08427B\n  FontColor white\n}\nrectangle \"Customer\" <<person>> as C\nrectangle \"Shop\" as S\nrectangle \"Mail\" as M\nC --> S : Orders\nS --> M : Sends\n" );
        sources.put( "styled", "class A #red/blue\nclass B <<st>>\nskinparam shadowing true\nA ..> B\nA -[dashed]-> B\nnote left of A\n **bold** <color:red>c</color> <u>u</u> <s>s</s>\nend note\n" );
        final Path dir = Path.of( "src/test/resources/svg" );
        Files.createDirectories( dir );
        for( final Map.Entry< String, String > e : sources.entrySet() ) {
            final RenderOutcome out = PlantUmlEngine.render( e.getValue() );
            assertTrue( out.isDrawn(), () -> e.getKey() + ": " + out.reason() + " " + out.message() );
            Files.writeString( dir.resolve( e.getKey() + ".svg" ), out.drawing().lightSvg(), StandardCharsets.UTF_8 );
            if( "class".equals( e.getKey() ) ) {
                Files.writeString( dir.resolve( "class-dark.svg" ), out.drawing().darkSvg(), StandardCharsets.UTF_8 );
            }
        }
    }
}
