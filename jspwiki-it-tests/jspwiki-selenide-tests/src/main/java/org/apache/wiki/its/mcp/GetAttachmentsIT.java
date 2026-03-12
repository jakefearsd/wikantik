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
package org.apache.wiki.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Integration tests for the {@code get_attachments} MCP tool.
 */
public class GetAttachmentsIT extends WithMcpTestSetup {

    @Test
    public void getAttachmentsForPageWithNoAttachments() {
        final Map< String, Object > result = mcp.getAttachments( "Main" );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > attachments = ( List< Map< String, Object > > ) result.get( "attachments" );

        Assertions.assertNotNull( attachments, "Attachments list should not be null" );
        Assertions.assertTrue( attachments.isEmpty(), "Main page should have no attachments" );
    }

    @Test
    public void getAttachmentsForNonExistentPageReturnsError() {
        final Map< String, Object > result = mcp.getAttachmentsExpectingError(
                "NonExistentAttachmentPage_" + System.currentTimeMillis() );
        Assertions.assertNotNull( result.get( "error" ), "Error message should be present" );
    }
}
