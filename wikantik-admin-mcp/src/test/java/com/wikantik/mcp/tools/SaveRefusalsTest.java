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
package com.wikantik.mcp.tools;

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentViolation;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pins the refusal result maps of update_page and write_pages (keys, order, values). */
class SaveRefusalsTest {

    private static final ContentViolation CV =
        new ContentViolation( "body", Severity.ERROR, "MATH", "bad math", null );
    private static final FieldViolation FV =
        FieldViolation.of( "status", Severity.ERROR, "status.bad", "bad status" );
    private static final AccessRuleShapeException ARE = new AccessRuleShapeException( List.of(
        new AccessRuleShapeException.Violation( AccessRuleShapeException.CODE, "DENY", 3, null, "[{DENY}]", "no deny" ) ) );

    private static String text( final McpSchema.CallToolResult r ) {
        return ( (McpSchema.TextContent) r.content().get( 0 ) ).text();
    }

    private static String update( final Throwable toThrow, final String content ) throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final Page existing = mock( Page.class );
        when( existing.getVersion() ).thenReturn( 2 );
        when( pm.getPage( "P" ) ).thenReturn( existing );
        when( pm.getPureText( eq( "P" ), anyInt() ) ).thenReturn( "cur" );
        if ( toThrow != null ) {
            doThrow( toThrow ).when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );
        }
        return text( new UpdatePageTool( helper, pm, null ).execute( Map.of(
            "slug", "P", "content", content, "expectedContentHash", McpToolUtils.computeContentHash( "cur" ) ) ) );
    }

    private static String write( final Throwable toThrow, final String content ) throws Exception {
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        if ( toThrow != null ) {
            doThrow( toThrow ).when( helper ).saveText( eq( "P" ), any(), any( SaveOptions.class ) );
        }
        return text( new WritePagesTool( helper, pm, null ).execute( Map.of(
            "pages", List.of( Map.of( "slug", "P", "content", content ) ) ) ) );
    }

    private static final String BAD_FM = "---\ntitle: a: b\n---\nbody";

    private static final String MATH_TAIL = "\"error\":\"math validation failed\",\"violations\":"
        + "[{\"locus\":\"body\",\"severity\":\"ERROR\",\"code\":\"MATH\",\"message\":\"bad math\",\"location\":null}]";
    private static final String ACL_TAIL = "\"error\":\"access rule shape refused\",\"violations\":"
        + "[{\"code\":\"ACCESS_RULE_SHAPE\",\"shape\":\"DENY\",\"line\":3,\"field\":null,\"text\":\"[{DENY}]\",\"message\":\"no deny\"}],\"total\":1";
    private static final String FM_TAIL = "\"error\":\"frontmatter validation failed\",\"violations\":"
        + "[{\"field\":\"status\",\"severity\":\"ERROR\",\"code\":\"status.bad\",\"message\":\"bad status\",\"suggestion\":null}]";
    private static final String PARSE_TAIL = "\"error\":\"frontmatter parse error: mapping values are not allowed here\\n"
        + " in \\u0027string\\u0027, line 1, column 9:\\n    title: a: b\\n            ^\\n\","
        + "\"frontmatterLine\":1,\"frontmatterColumn\":9,\"hint\":\"Wrap values containing \\u0027:\\u0027 or other YAML special "
        + "characters in double quotes, e.g. title: \\\"Foo: Bar\\\". Or pass the fields as the structured metadata argument "
        + "and omit the YAML block from content.\"";

    private static String updEntry( final String tail ) { return "{\"pageName\":\"P\",\"updated\":false," + tail + "}"; }

    private static String wrEntry( final String tail ) {
        return "{\"results\":[{\"pageName\":\"P\",\"created\":false," + tail + "}],"
            + "\"summary\":{\"total\":1,\"createdCount\":0,\"failedCount\":1}}";
    }

    @Test void updateMath() throws Exception {
        assertEquals( updEntry( MATH_TAIL ), update( new ContentValidationException( List.of( CV ) ), "b" ) );
    }
    @Test void updateAcl() throws Exception { assertEquals( updEntry( ACL_TAIL ), update( ARE, "b" ) ); }
    @Test void updateFm() throws Exception {
        assertEquals( updEntry( FM_TAIL ), update( new FrontmatterValidationException( List.of( FV ) ), "b" ) );
    }
    @Test void updateParse() throws Exception { assertEquals( updEntry( PARSE_TAIL ), update( null, BAD_FM ) ); }
    @Test void writeMath() throws Exception {
        assertEquals( wrEntry( MATH_TAIL ), write( new ContentValidationException( List.of( CV ) ), "b" ) );
    }
    @Test void writeAcl() throws Exception { assertEquals( wrEntry( ACL_TAIL ), write( ARE, "b" ) ); }
    @Test void writeFm() throws Exception {
        assertEquals( wrEntry( FM_TAIL ), write( new FrontmatterValidationException( List.of( FV ) ), "b" ) );
    }
    @Test void writeParse() throws Exception { assertEquals( wrEntry( PARSE_TAIL ), write( null, BAD_FM ) ); }

    private static String json( final Map< String, Object > m ) { return McpToolUtils.SHARED_GSON.toJson( m ); }

    @Test void directMath() {
        assertEquals( "{\"updated\":false," + MATH_TAIL + "}",
            json( SaveRefusals.refusalFor( new ContentValidationException( List.of( CV ) ), "updated" ) ) );
    }
    @Test void directAcl() { assertEquals( "{\"created\":false," + ACL_TAIL + "}", json( SaveRefusals.refusalFor( ARE, "created" ) ) ); }
    @Test void directFm() {
        assertEquals( "{\"created\":false," + FM_TAIL + "}",
            json( SaveRefusals.refusalFor( new FrontmatterValidationException( List.of( FV ) ), "created" ) ) );
    }
    @Test void directOtherIsNull() { assertNull( SaveRefusals.refusalFor( new IllegalStateException( "x" ), "created" ) ); }
    @Test void directParse() throws Exception {
        final com.wikantik.api.frontmatter.FrontmatterParseException e;
        try {
            FrontmatterNormalizer.normalize( BAD_FM, null );
            throw new AssertionError( "expected parse failure" );
        } catch ( final com.wikantik.api.frontmatter.FrontmatterParseException fpe ) {
            e = fpe;
        }
        assertEquals( "{\"updated\":false," + PARSE_TAIL + "}", json( SaveRefusals.parseError( e, "updated" ) ) );
        assertEquals( List.of( "created", "error", "hint" ), List.copyOf( SaveRefusals.parseError(
            new com.wikantik.api.frontmatter.FrontmatterParseException( "m", 0, 0 ), "created" ).keySet() ) );
    }
}
