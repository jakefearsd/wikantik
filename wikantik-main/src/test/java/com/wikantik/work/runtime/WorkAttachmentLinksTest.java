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
package com.wikantik.work.runtime;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkAttachmentLinksTest {

    @Test
    void rewritesAnExactTokenToTheItemsDownloadRoute() {
        assertEquals( "/ctx/api/work/items/PLAT-7/attachments/5",
            WorkAttachmentLinks.rewriter( "/ctx", "PLAT-7" ).apply( "work-attachment:5" ) );
        assertEquals( "/api/work/items/PLAT-7/attachments/5",
            WorkAttachmentLinks.rewriter( "", "PLAT-7" ).apply( "work-attachment:5" ) );
        assertEquals( "/api/work/items/PLAT-7/attachments/5",
            WorkAttachmentLinks.rewriter( null, "PLAT-7" ).apply( "work-attachment:5" ) );
    }

    @Test
    void leavesEverythingElse() {
        final UnaryOperator< String > r = WorkAttachmentLinks.rewriter( "/ctx", "PLAT-7" );
        for ( final String url : new String[] { "work-attachment:../../x", "work-attachment:5/../6",
            "work-attachment:", "work-attachment:5x", "work-attachment: 5", "WORK-ATTACHMENT:5",
            "work-attachment:%35", "https://x/work-attachment:5", "work-attachment:1234567890123456789",
            "work-attachment:5\n", "work-attachment:-5", "Alpha", "" } ) {
            assertNull( r.apply( url ), url );
        }
        assertNull( r.apply( null ) );
    }

    @Test
    void noUsableKeyMeansNoRewrite() {
        for ( final String key : new String[] { null, "plat-7", "PLAT-7/../X", "PLAT-7 ", "PLAT", "" } ) {
            assertNull( WorkAttachmentLinks.rewriter( "/ctx", key ).apply( "work-attachment:5" ), String.valueOf( key ) );
        }
    }
}
