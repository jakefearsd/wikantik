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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wikantik.api.core.Context;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CitationLinkRenderingFilterTest {

    @Test
    void rewritesCiteHrefToWikiSlug() throws Exception {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "abc123" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationLinkRenderingFilter f = new CitationLinkRenderingFilter( idx );
        final String html = "<p><a href=\"cite://abc123/Rollback%20Steps\">claim</a></p>";
        final String out = f.postTranslate( mock( Context.class ), html );
        assertTrue( out.contains( "href=\"/wiki/Deploy" ) );
        assertTrue( out.contains( "wiki-citation" ) );
        assertFalse( out.contains( "cite://" ) );
    }

    @Test
    void unknownTargetGetsMissingClassAndDoesNotThrow() throws Exception {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "gone" ) ).thenReturn( Optional.empty() );
        final CitationLinkRenderingFilter f = new CitationLinkRenderingFilter( idx );
        final String out = f.postTranslate( mock( Context.class ), "<a href=\"cite://gone\">c</a>" );
        assertTrue( out.contains( "wiki-citation-missing" ) );
    }
}
