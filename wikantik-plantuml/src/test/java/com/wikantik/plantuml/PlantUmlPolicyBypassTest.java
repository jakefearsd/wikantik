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
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Table-driven bypass attempts against {@link PlantUmlPolicy}; each row is a source and the reason code it must get. */
class PlantUmlPolicyBypassTest {

    private static String wrap( final String body ) {
        return "@startuml\n" + body + "\n@enduml";
    }

    private static Stream< String[] > refusedConstructs() {
        return Stream.of(
            // include family: spacing, case, line endings, trailing text, mid-line after whitespace
            new String[] { wrap( "!include\tfoo.puml" ), "include" },
            new String[] { wrap( "   \t!InClUdE   foo.puml" ), "include" },
            new String[] { "@startuml\r\n!include foo\r\n@enduml", "include" },
            new String[] { "@startuml\r!include foo\r@enduml", "include" },
            new String[] { wrap( "!include <C4/C4_Container> extra" ), "include" },
            new String[] { wrap( "!include <C4/../../etc/passwd>" ), "include" },
            new String[] { wrap( "!include <C4/C4_Container>.puml" ), "include" },
            new String[] { wrap( "!include https://example.org/x.puml" ), "include" },
            new String[] { wrap( "!includeurl https://example.org/x.puml" ), "include" },
            new String[] { wrap( "!include_many a.puml" ), "include" },
            new String[] { wrap( "!includesub a.puml!SUB" ), "include" },
            new String[] { wrap( "!import lib.zip" ), "include" },
            new String[] { wrap( "A -> B : x !include evil" ), "include" },
            new String[] { wrap( "!theme plain from /etc" ), "include" },
            new String[] { wrap( "!THEME plain FROM https://example.org/themes" ), "include" },
            // file system / environment / indirect callers
            new String[] { wrap( "title %getenv(\"HOME\")" ), "file-access" },
            new String[] { wrap( "!$j = %load_json(\"/etc/passwd\")" ), "file-access" },
            new String[] { wrap( "!if %file_exists(\"/etc/passwd\") == 1\nA\n!endif" ), "file-access" },
            new String[] { wrap( "!$f = %call_user_func(\"%getenv\", \"HOME\")" ), "file-access" },
            new String[] { wrap( "!procedure $p()\n!endprocedure\n%invoke_procedure(\"$p\")" ), "file-access" },
            new String[] { wrap( "title %eval(\"1+1\")" ), "file-access" },
            new String[] { wrap( "title %dirpath()" ), "file-access" },
            new String[] { wrap( "title %filename()" ), "file-access" },
            new String[] { wrap( "title %filedate(\"x\")" ), "file-access" },
            new String[] { wrap( "   !dump_memory" ), "file-access" },
            // pragmas
            new String[] { wrap( "!pragma layout graphviz" ), "layout-pragma" },
            new String[] { wrap( "   !PRAGMA   LAYOUT elk" ), "layout-pragma" },
            new String[] { wrap( "!pragma graphviz_dot /usr/bin/dot" ), "layout-pragma" },
            // images
            new String[] { wrap( "note left: <img:https://example.org/x.png>" ), "image" },
            new String[] { wrap( "class A <<  <IMG:/etc/passwd> >>" ), "image" },
            new String[] { wrap( "A -> B : <img\tsrc=x>" ), "image" },
            // links
            new String[] { wrap( "class A [[javascript:alert(1)]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[\tjavascript:alert(1)]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[JAVASCRIPT:alert(1) label]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[data:text/html,<script>alert(1)</script>]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[ftp://example.org/x]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[http:evil.example]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[/other/path]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[​javascript:alert(1)]]" ), "unsafe-link" },
            new String[] { wrap( "class A [[https://ok.example/x]] [[javascript:alert(1)]]" ), "unsafe-link" },
            // embedded sub-diagram literal
            new String[] { wrap( "note right: {{\nclass A\n}}" ), "embedded-diagram" },
            new String[] { wrap( "A -> B : {{json {\"a\":1} }}" ), "embedded-diagram" },
            // non-uml and structure
            new String[] { "@startjson\n{}\n@endjson", "unsupported-diagram" },
            new String[] { "@startuml-x\nA\n@enduml", "unsupported-diagram" },
            new String[] { "@startuml;x\nA\n@enduml", "unsupported-diagram" },
            new String[] { "@startuml\n@enduml\n@startuml\n@enduml", "multiple-diagrams" },
            new String[] { "@startuml\nA\n@enduml\n@startuml(id=2)\nB\n@enduml", "multiple-diagrams" },
            new String[] { "@startuml\nA\n@enduml\n@startjson\n{}\n@endjson", null } );
    }

    @ParameterizedTest( name = "[{index}] {1}" )
    @MethodSource( "refusedConstructs" )
    void refusesEveryBypassAttempt( final String source, final String reason ) {
        final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( source );
        assertEquals( reason, a.reason(), source );
        assertEquals( reason == null, a.allowed() );
        if( reason != null ) {
            assertEquals( List.of(), a.linkTargets(), "a refused source must expose no link targets" );
        }
    }

    @Test
    void everyAllowedLinkSchemeIsAcceptedAndRecordedCaseInsensitively() {
        final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( wrap( "class A [[/wiki/X]]\nclass B [[#a]]\nclass C [[http://a.example]]\n"
            + "class D [[https://a.example]]\nclass E [[HTTPS://A.EXAMPLE]]\nclass F [[/WIKI/X]]" ) );
        assertNull( a.reason() );
        assertEquals( List.of( "/wiki/X", "#a", "http://a.example", "https://a.example", "HTTPS://A.EXAMPLE", "/WIKI/X" ), a.linkTargets() );
    }

    @Test
    void emptyAndOddLinkTargetsAreIgnoredNotRefused() {
        final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( wrap( "class A [[]]\nclass B [[ ]]\nclass C [[{only a tooltip}]]\n"
            + "class D [[#frag]]" ) );
        assertNull( a.reason() );
        assertEquals( List.of( "#frag" ), a.linkTargets() );
    }

    @Test
    void secondStartOfAnyKindAfterAUmlStartIsNotTheFirst() {
        // only the first @start token decides the diagram kind; later non-uml starts are not "unsupported"
        assertNull( PlantUmlPolicy.analyse( "@startuml\nA\n@enduml\n@startjson\n{}\n@endjson" ).reason() );
    }

    /**
     * Documents current behaviour: the preprocessor can BUILD the refused literal, so the policy alone does not catch
     * it. Containment does not rely on the policy; {@link PlantUmlEngineContainmentTest} pins what happens next.
     */
    @Test
    void preprocessorBuiltOpenBraceIsNotRefusedButContained() {
        assertNull( PlantUmlPolicy.analyse( wrap( "note right: %chr(123)%chr(123)\nclass A" ) ).reason() );
        assertNull( PlantUmlPolicy.analyse( wrap( "!$o = \"{\"\nnote right: $o$o\nclass A" ) ).reason() );
        assertNull( PlantUmlPolicy.analyse( wrap( "!define OPEN {\nnote right: OPENOPEN\nclass A" ) ).reason() );
    }

    @Test
    void spriteLoadersAreNotRefusedByPolicyAndSandboxDeniesTheirSources() {
        // sprites load via the sandboxed PlantUML runtime (file/URL access denied there); the policy deliberately has no rule
        assertFalse( PlantUmlPolicy.analyse( wrap( "sprite $s jar:archimate/app-service\nclass A <<$s>>" ) ).reason() != null );
        assertTrue( PlantUmlPolicy.analyse( wrap( "sprite $s <svg viewBox=\"0 0 1 1\"></svg>\nclass A" ) ).allowed() );
    }
}
