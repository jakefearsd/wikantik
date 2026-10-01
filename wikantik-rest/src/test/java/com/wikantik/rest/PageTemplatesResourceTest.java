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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PageTemplatesResourceTest {
    @Test
    @SuppressWarnings( "unchecked" )
    void payloadListsEveryTemplateWithItsFields() {
        final Map< String, Object > payload = PageTemplatesResource.templatesPayload();
        final List< Map< String, Object > > templates = ( List< Map< String, Object > > ) payload.get( "templates" );
        assertEquals( 5, templates.size() );
        final Map< String, Object > runbook = templates.stream()
                .filter( t -> "runbook".equals( t.get( "type" ) ) ).findFirst().orElseThrow();
        assertEquals( "Runbook", runbook.get( "label" ) );
        assertTrue( ( ( Map< String, Object > ) runbook.get( "metadata" ) ).containsKey( "runbook" ) );
        assertTrue( ( ( String ) runbook.get( "body" ) ).startsWith( "# {{title}}" ) );
    }
}
