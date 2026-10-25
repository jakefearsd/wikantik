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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential guard for the SvgScrubber decomposition: the scrubbed output of every fixture and hostile input must stay
 * byte-identical to the goldens recorded from the pre-refactor implementation. Record with {@code -Dsvg.golden.record=true}.
 */
class SvgScrubberGoldenTest {

    private static final String NS = "xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"";
    private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";
    private static final String REJECTED = "REJECTED";
    private static final String[] FIXTURES = { "class", "class-dark", "styled", "activity", "sequence", "usecase", "component",
                                               "state", "object", "deployment", "timing", "c4" };

    private static String svg( final String body ) {
        return "<svg " + NS + ">" + body + "</svg>";
    }

    static Map< String, String > corpus() throws IOException {
        final Map< String, String > c = new LinkedHashMap<>();
        for( final String f : FIXTURES ) {
            try( InputStream in = SvgScrubberGoldenTest.class.getResourceAsStream( "/svg/" + f + ".svg" ) ) {
                c.put( "fixture-" + f, new String( in.readAllBytes(), StandardCharsets.UTF_8 ) );
            }
        }
        c.put( "script-foreign", svg( "<script>x</script><foreignObject><div/></foreignObject><g/>" ) );
        c.put( "other-dangerous", svg( "<style>@import 'x';</style><image href=\"http://e/x.png\"/>"
            + "<use xlink:href=\"http://e/x.svg#a\"/><iframe/><animate attributeName=\"href\" to=\"javascript:x\"/>"
            + "<set attributeName=\"href\" to=\"javascript:x\"/><rect id=\"r\"/>" ) );
        c.put( "on-attrs", "<svg " + NS + " onload=\"x()\"><rect OnClick=\"y()\" onmouseover=\"z\" x=\"1\"/></svg>" );
        int i = 0;
        for( final String href : new String[] { "javascript:alert(1)", " javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x",
                                                "//evil.example/x", "/other/path", "vbscript:x", "ftp://example.com/x", "HTTP://E.com/x",
                                                "/wiki/", "#", "#a b" } ) {
            c.put( "href-" + i++, svg( "<a href=\"" + href + "\"><g/></a><a xlink:href=\"" + href + "\"><g/></a>" ) );
        }
        c.put( "safe-hrefs", svg( "<a href=\"/wiki/Foo\" target=\"_top\"/><a xlink:href=\"https://e.com/x\" target=\"_blank\"/>"
            + "<a href=\"http://e.com\" target=\"evil\"/><a href=\"#n1\"/><g id=\"n1\"/>" ) );
        c.put( "style-urls", svg( "<rect x=\"1\" style=\"fill:url(http://evil/x)\"/><rect x=\"2\" fill=\"url(//evil/x)\"/>"
            + "<rect x=\"3\" style=\"fill:url('#a')\"/><rect x=\"4\" style=\"stroke:red;stroke-width:1\"/>"
            + "<rect x=\"5\" style=\"background:expression(x)\"/><rect x=\"6\" style=\"a:\\75rl(x)\"/>"
            + "<rect x=\"7\" style=\"@import 'x'\"/><rect x=\"8\" style=\"x:javascript:y\"/><rect x=\"9\" style=\"fill:url(#a)\"/>" ) );
        c.put( "ids", svg( "<defs><linearGradient id=\"g\"/></defs><rect fill=\"url(#g)\" filter=\"URL(#g) url(#h)\"/>"
            + "<rect fill=\"url(#g) url(http://e/x)\"/><rect fill=\"url(#bad id)\"/><rect fill=\"none\"/>" ) );
        c.put( "pi-comment", svg( "<?plantuml-src abc?><?plantuml 1.2?><!-- c --><g/>" ) );
        c.put( "doctype", "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg " + NS + ">&x;</svg>" );
        c.put( "unparsable", "<svg><g></svg>" );
        c.put( "empty", "" );
        c.put( "blank", "   " );
        c.put( "non-svg-root", "<html/>" );
        c.put( "label-text", svg( "<title>a\"b</title><text title='q\"&amp;&lt;x'>"
            + "&lt;script&gt;alert(1)&lt;/script&gt; a &amp;&amp; b ]]&gt; <![CDATA[<script>y</script>]]></text>" ) );
        c.put( "escapes", svg( "<text x=\"a&#10;b&#13;c&#9;d&quot;e\">t\r\n\t\"q\" &#13; &gt;</text>" ) );
        c.put( "xml-decl-ns", "<?xml version=\"1.0\"?><svg " + NS + " xmlns:evil=\"urn:x\"><g/></svg>" );
        c.put( "ns-bad-values", "<svg xmlns=\"http://evil/\" xmlns:xlink=\"http://evil/x\"><g xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"/></svg>" );
        c.put( "image-ok", svg( "<image x=\"1\" y=\"2\" width=\"3\" height=\"4\" onload=\"x\" filter=\"url(#a)\" preserveAspectRatio=\"none\" id=\"i\" xlink:href=\"" + PNG + "\"/>" ) );
        c.put( "image-href-and-xlink", svg( "<image href=\"" + PNG + "\" xlink:href=\"" + PNG + "\"/>"
            + "<image href=\"" + PNG + "\" xlink:href=\"http://e/x.png\"/><image href=\"data:image/jpeg;base64,AA AA\"/>"
            + "<image href=\"data:image/gif;base64,AAAA==\"/>" ) );
        i = 0;
        for( final String href : new String[] { "http://e.com/x.png", "https://e.com/x.png", "data:image/svg+xml;base64,PHN2Zz4=",
                                                "data:text/html;base64,PHNjcmlwdD4=", "data:image/png,rawbytes",
                                                "data:image/png;base64,AAAA\"onload=x", "javascript:x", "/wiki/x.png" } ) {
            final String h = href.replace( "\"", "&quot;" );
            c.put( "image-bad-" + i++, svg( "<image href=\"" + h + "\"/><image xlink:href=\"" + h + "\"/><image/><g/>" ) );
        }
        i = 0;
        for( final String style : new String[] { "background:image-set(url(x) 1x)", "cursor:url(x),auto", "position:fixed",
                                                 "fill:-webkit-image-set(url(x) 1x)", "fill:red/**/;x:url(y)", "fill:u\\72l(x)",
                                                 "fill:url(#a) , url(http://e/x)", "fill:@x", "fill:expression(x)",
                                                 "STROKE : red ; Fill:url(#a)", "stroke:;fill:red", ";;:x;noColon;fill:blue",
                                                 "fill:url(#a) url(#b)", "width:10px;height:5px;background:#fff" } ) {
            c.put( "style-" + i++, svg( "<rect style=\"" + style + "\"/>" ) );
        }
        c.put( "style-allowed-mix", svg( "<rect style=\"stroke:#181818;position:fixed;stroke-width:1;cursor:url(x)\"/>" ) );
        c.put( "namespace-tricks", "<svg " + NS + " xmlns:svg=\"http://www.w3.org/2000/svg\"><svg:script>x</svg:script>"
            + "<a XLINK:HREF=\"javascript:x\" HREF=\"javascript:y\"/><g xmlns=\"http://www.w3.org/1999/xhtml\"/></svg>" );
        c.put( "unknown-attrs-and-target", svg( "<rect foo=\"1\" data-source-line=\"3\" class=\"c\" target=\"_top\" TITLE=\"t\"/>"
            + "<a target=\"_blank\" href=\"/wiki/X\"/>" ) );
        c.put( "nested-dropped", svg( "<g><script><g id=\"x\"/></script><rect id=\"keep\"/></g><text>a<tspan>b</tspan>c</text>" ) );
        return c;
    }

    @Test
    void scrubOutputUnchangedByRefactor() throws Exception {
        final boolean record = Boolean.getBoolean( "svg.golden.record" );
        final Path dir = Path.of( "src/test/resources/svg-golden" );
        final Map< String, String > corpus = corpus();
        assertTrue( corpus.size() >= 60, "corpus shrank: " + corpus.size() );
        if( record ) {
            Files.createDirectories( dir );
        }
        for( final Map.Entry< String, String > e : corpus.entrySet() ) {
            String actual;
            try {
                actual = SvgScrubber.scrub( e.getValue(), "wkfTEST-" );
            } catch( final SvgScrubber.Rejected r ) {
                actual = REJECTED;
            }
            final Path file = dir.resolve( e.getKey() + ".golden" );
            if( record ) {
                Files.writeString( file, actual, StandardCharsets.UTF_8 );
            }
            assertEquals( Files.readString( file, StandardCharsets.UTF_8 ), actual, "golden mismatch: " + e.getKey() );
        }
    }
}
