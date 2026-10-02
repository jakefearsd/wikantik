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
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code collectLinks} re-parses the whole body with a bare flexmark parser purely to feed the link hooks
 * ({@code addLocalLinkHook} and friends). Ordinary renders and metadata parses register no hooks, so that second
 * parse (and its attachment lookup per local link) must not run for them.
 */
class MarkdownParserLinkCollectionTest {

    private final TestEngine engine = TestEngine.build(
            TestEngine.with( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() ) );

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void theLinkCollectingPassRunsOnlyWhenAHookIsRegistered() throws Exception {
        engine.saveText( "LcHost", "host" );
        final AtomicInteger lookups = countAttachmentNameLookups();
        final String body = "see [a page](SomeLcPage)\n";

        parse( body, null );
        final int withoutHooks = lookups.getAndSet( 0 );
        final List< String > seen = new ArrayList<>();
        parse( body, seen );
        final int withHook = lookups.get();

        assertEquals( List.of( "SomeLcPage" ), seen, "a registered hook still sees every local link" );
        assertEquals( withoutHooks + 1, withHook, "only the hooked parse pays for the link-collecting pass" );
    }

    private void parse( final String body, final List< String > hookSink ) throws Exception {
        final Context ctx = Wiki.context().create( engine, engine.getManager( PageManager.class ).getPage( "LcHost" ) );
        final MarkdownParser parser = new MarkdownParser( ctx, new StringReader( body ) );
        if ( hookSink != null ) {
            parser.addLocalLinkHook( ( c, link ) -> {
                hookSink.add( link );
                return link;
            } );
        }
        parser.parse();
    }

    private AtomicInteger countAttachmentNameLookups() {
        final AttachmentManager real = engine.getManager( AttachmentManager.class );
        final AtomicInteger n = new AtomicInteger();
        engine.setManager( AttachmentManager.class, ( AttachmentManager ) Proxy.newProxyInstance(
                AttachmentManager.class.getClassLoader(), new Class< ? >[]{ AttachmentManager.class }, ( p, m, a ) -> {
                    if ( m.getName().startsWith( "getAttachmentInfo" ) ) {
                        n.incrementAndGet();
                    }
                    try {
                        return m.invoke( real, a );
                    } catch ( final InvocationTargetException e ) {
                        throw e.getCause();
                    }
                } ) );
        return n;
    }
}
