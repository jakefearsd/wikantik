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
package com.wikantik.collab.adapter;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.collab.model.PublishRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A collaborative publish carries the editor's markup syntax into the save, as {@code PageResource.doPut} does
 * ({@code SaveOptions.markupSyntax}, stored as the page's {@link Page#MARKUP_SYNTAX} attribute). Kept apart from
 * {@code PageSaveHelperPageWriterTest} so the two can change independently.
 */
class PageSaveHelperPageWriterMarkupTest {

    private static final String PAGE = "CollabMarkup";

    private TestEngine engine;
    private PageManager pages;
    private PageSaveHelperPageWriter writer;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        pages = engine.getManager( PageManager.class );
        writer = new PageSaveHelperPageWriter( pages, new PageSaveHelper( engine, pages ) );
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( PAGE );
        engine.stop();
    }

    @Test
    void theMarkupSyntaxReachesTheSavedPage() throws Exception {
        writer.publish( new PublishRequest( PAGE, "Some *text*\n", Map.of(), 0, "alice", "note", "wiki" ) );
        assertEquals( "wiki", pages.getPage( PAGE ).getAttribute( Page.MARKUP_SYNTAX ),
            "a non-default syntax, so the default cannot satisfy the check" );
    }
}
