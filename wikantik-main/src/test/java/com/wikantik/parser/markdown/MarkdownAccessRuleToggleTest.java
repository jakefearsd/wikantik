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
package com.wikantik.parser.markdown;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code textToHTML(..., parseAccessRules=false, ...)} must be honoured by the Markdown parser. */
class MarkdownAccessRuleToggleTest {

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ),
            TestEngine.with( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() ) );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private Page render( final boolean parseAccessRules ) throws Exception {
        final Page page = Wiki.contents().page( engine, "AclToggleScratch" );
        final Context ctx = Wiki.context().create( engine, page );
        ctx.setPage( page );
        ctx.setRealPage( page );
        engine.getManager( RenderingManager.class )
              .textToHTML( ctx, "[{ALLOW view Admin}]\n\nx", null, null, null, parseAccessRules, false );
        return page;
    }

    @Test
    void parseAccessRulesFalseLeavesAclUntouched() throws Exception {
        final Page page = render( false );
        assertTrue( page.getAcl() == null || page.getAcl().isEmpty(),
                "access rules were disabled; the ACL must not be applied" );
    }

    @Test
    void parseAccessRulesTrueAppliesAcl() throws Exception {
        final Page page = render( true );
        assertFalse( page.getAcl() == null || page.getAcl().isEmpty(), "ACL line is applied when enabled" );
    }
}
