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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantUmlPolicyTest {

    private static String reasonOf( final String source ) {
        return PlantUmlPolicy.analyse( source ).reason();
    }

    private static String wrap( final String body ) {
        return "@startuml\n" + body + "\n@enduml";
    }

    @ParameterizedTest( name = "{0} -> {1}" )
    @CsvSource( delimiter = '|', textBlock = """
        '@startgantt\\n[a] lasts 2 days\\n@endgantt' | unsupported-diagram
        '@startumlfoo\\nA -> B\\n@enduml'            | unsupported-diagram
        '@startuml\\nditaa\\nA\\n@enduml'             | unsupported-diagram
        '@startuml\\n  DITAA -E\\n@enduml'            | unsupported-diagram
        '@startuml\\nhelp colors\\n@enduml'           | unsupported-diagram
        '@startuml\\n help skinparams\\n@enduml'      | unsupported-diagram
        '@startuml\\ncrash\\n@enduml'                 | unsupported-diagram
        '@startuml\\nA -> B\\n@enduml\\n@startuml\\nC -> D\\n@enduml' | multiple-diagrams
        '@startuml(id=a)\\nA -> B\\n@startuml name\\nC -> D' | multiple-diagrams
        '@startuml\\n!include foo.puml\\n@enduml'     | include
        '@startuml\\n!includeurl http://x\\n@enduml'  | include
        '@startuml\\n!includesub a!B\\n@enduml'       | include
        '@startuml\\n!include_many x\\n@enduml'       | include
        '@startuml\\n!include_once x\\n@enduml'       | include
        '@startuml\\n!includedef x\\n@enduml'         | include
        '@startuml\\n!import x\\n@enduml'             | include
        '@startuml\\n  !INCLUDE foo\\n@enduml'        | include
        '@startuml\\n!include <aws/Compute>\\n@enduml' | include
        '@startuml\\n!theme cerulean from https://x\\n@enduml' | include
        '@startuml\\n!pragma layout elk\\n@enduml'    | layout-pragma
        '@startuml\\n!pragma graphviz_dot jdot\\n@enduml' | layout-pragma
        '@startuml\\n!dump_memory out.txt\\n@enduml'  | file-access
        '@startuml\\nclass A <<img:x.png>>\\n@enduml' | image
        '@startuml\\nnote: <img src="x">\\n@enduml'   | image
        '@startuml\\nA : <IMG:x>\\n@enduml'           | image
        '@startuml\\nclass A [[javascript:alert(1)]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [[file:///etc]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [[//evil.example/x]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [[data:x]]\\n@enduml'    | unsafe-link
        '@startuml\\nclass A [[ JAVASCRIPT:x]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [[java&#115;cript:x]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [[%6Aavascript:x]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [[[javascript:x]]]\\n@enduml' | unsafe-link
        '@startuml\\nclass A [["javascript:x"]]\\n@enduml' | unsafe-link
        '@STARTUML\\nA -> B\\n@startuml\\nC -> D' | multiple-diagrams
        '\\t@startuml\\nA -> B\\n\\t@startuml\\nC -> D' | multiple-diagrams
        '@startuml\\n!theme x from y\\n@enduml' | include
        """ )
    void refusals( final String escaped, final String reason ) {
        assertEquals( reason, reasonOf( escaped.replace( "\\n", "\n" ).replace( "\\t", "\t" ) ) );
    }

    @Test
    void tooLarge() {
        final String big = "A -> B : " + "x".repeat( PlantUmlPolicy.MAX_SOURCE_BYTES );
        assertEquals( "too-large", reasonOf( big ) );
    }

    @Test
    void sizeIsMeasuredInUtf8Bytes() {
        // 3-byte chars: under the cap in chars, over it in bytes.
        final String wide = "€".repeat( PlantUmlPolicy.MAX_SOURCE_BYTES / 2 );
        assertEquals( "too-large", reasonOf( wide ) );
        assertNull( reasonOf( "A -> B : " + "x".repeat( 1000 ) ) );
    }

    @Test
    void orderingFirstMatchWins() {
        // Not @startuml beats an include in the same source; include beats file-access.
        assertEquals( "unsupported-diagram", reasonOf( "@startgantt\n!include x\n@endgantt" ) );
        assertEquals( "include", reasonOf( wrap( "!include x\n%getenv(\"X\")" ) ) );
    }

    /** Every builtin in PlantUML 1.2026.8 net.sourceforge.plantuml.tim.builtin that touches files, env, classpath. */
    @ParameterizedTest
    @ValueSource( strings = { "getenv", "load_json", "file_exists", "dirpath", "filename", "filename_no_extension",
        "filedate", "get_stdlib", "get_all_stdlib", "get_all_theme", "call_user_func", "invoke_procedure", "eval" } )
    void deniedBuiltins( final String name ) {
        assertEquals( "file-access", reasonOf( wrap( "!$v = %" + name + "(\"x\")" ) ), name );
        assertEquals( "file-access", reasonOf( wrap( "title %" + name.toUpperCase() + "()" ) ), name );
    }

    @Test
    void deniedBuiltinDoesNotMatchHarmlessPrefix() {
        assertNull( reasonOf( wrap( "title %filenamefoo" ) ) );
    }

    @ParameterizedTest
    @ValueSource( strings = {
        "@startuml\n!include <C4/C4_Container>\n@enduml",
        "class A\nclass B\nA --> B",
        "@startuml\nstart\nfork\n:a;\nfork again\n:b;\nend fork\nstop\n@enduml",
        "@startuml\nskinparam monochrome true\nA -> B\n@enduml",
        "@startuml\n!pragma useVerticalIf on\nstart\nstop\n@enduml",
        "@startuml\n!define X 1\nA -> B\n@enduml",
        "@startuml\ntitle X\nA -> B\n@enduml",
        "@startuml\ntitle %strlen(\"a\")\n@enduml",
        "preamble line\n@startuml(id=x)\nA -> B\n@enduml",
        "@startuml\n!theme cerulean\nA -> B\n@enduml" } )
    void allowed( final String source ) {
        final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( source );
        assertNull( a.reason(), source );
        assertTrue( a.allowed() );
    }

    @Test
    void includeStdlibIsRefusedButAllowedRowIsStdlibC4() {
        // Brief lists '!include <C4/C4_Container>' as allowed but '!include <aws/Compute>' as include:
        // only the C4 stdlib is allow-listed.
        assertNull( reasonOf( wrap( "!include <C4/C4_Container>" ) ) );
        assertEquals( "include", reasonOf( wrap( "!include <aws/Compute>" ) ) );
    }

    @Test
    void linkTargetsInOrder() {
        final String src = wrap( "class A [[/wiki/PageOne]]\nB [[/wiki/Page%20Two{tip} label]]\n"
            + "C [[#anchor]]\nD [[https://example.com/x]]" );
        final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( src );
        assertNull( a.reason() );
        assertEquals( List.of( "/wiki/PageOne", "/wiki/Page%20Two", "#anchor", "https://example.com/x" ), a.linkTargets() );
        assertEquals( List.of(), PlantUmlPolicy.analyse( wrap( "A -> B" ) ).linkTargets() );
    }

    @Test
    void pathologicalInputsAreFast() {
        final String[] unit = { " !theme", " !include", " !pragma", " %getenv", " <img", " [[", "@startuml ", "\t" };
        for( final String u : unit ) {
            final String big = u.repeat( PlantUmlPolicy.MAX_SOURCE_BYTES / u.length() );
            final long t0 = System.nanoTime();
            PlantUmlPolicy.analyse( big );
            PlantUmlPolicy.analyse( big.replace( " ", "\n" ) );
            final long ms = ( System.nanoTime() - t0 ) / 1_000_000;
            assertTrue( ms < 200, "'" + u.strip() + "' took " + ms + " ms" );
        }
    }

    @Test
    void neverThrows() {
        assertEquals( List.of(), PlantUmlPolicy.analyse( null ).linkTargets() );
        assertNull( PlantUmlPolicy.analyse( "" ).reason() );
    }
}
