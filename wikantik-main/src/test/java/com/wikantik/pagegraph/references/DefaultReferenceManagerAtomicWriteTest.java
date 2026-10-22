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
package com.wikantik.pagegraph.references;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code refmgr.ser} and the per-page attribute caches must be replaced atomically: a failed write
 * (provoked here by an unwritable directory, where an in-place rewrite of the existing file would
 * still succeed) leaves the previous file untouched instead of truncating it.
 */
class DefaultReferenceManagerAtomicWriteTest {

    private static final byte[] SENTINEL = "previous-good-content".getBytes( StandardCharsets.UTF_8 );

    private DefaultReferenceManager manager( final File workDir ) throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        return new DefaultReferenceManager( engine, pageManager, attachmentManager );
    }

    @Test
    void failedSerializeKeepsPreviousReferenceDatabase( @TempDir final File workDir ) throws Exception {
        final DefaultReferenceManager mgr = manager( workDir );
        final File ser = new File( workDir, "refmgr.ser" );
        Files.write( ser.toPath(), SENTINEL );
        Assumptions.assumeTrue( workDir.setWritable( false ) );
        Assumptions.assumeFalse( workDir.canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            final Method m = DefaultReferenceManager.class.getDeclaredMethod( "serializeToDisk" );
            m.setAccessible( true );
            m.invoke( mgr );
        } finally {
            workDir.setWritable( true );
        }
        assertArrayEquals( SENTINEL, Files.readAllBytes( ser.toPath() ), "a failed write must not truncate refmgr.ser" );
    }

    @Test
    void failedAttrSerializeKeepsPreviousAttributeCache( @TempDir final File workDir ) throws Exception {
        final DefaultReferenceManager mgr = manager( workDir );
        final Method hash = DefaultReferenceManager.class.getDeclaredMethod( "getHashFileName", String.class );
        hash.setAccessible( true );
        final File attrDir = new File( workDir, "refmgr-attr" );
        assertTrue( attrDir.mkdirs() );
        final File cache = new File( attrDir, ( String ) hash.invoke( mgr, "HasAttrs" ) );
        Files.write( cache.toPath(), SENTINEL );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "HasAttrs" );
        when( page.getAttributes() ).thenReturn( Map.of( "k", "v" ) );
        final Context ctx = mock( Context.class );
        when( ctx.getPage() ).thenReturn( page );

        Assumptions.assumeTrue( attrDir.setWritable( false ) );
        Assumptions.assumeFalse( attrDir.canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            mgr.postSave( ctx, "no links" );
        } finally {
            attrDir.setWritable( true );
        }
        assertArrayEquals( SENTINEL, Files.readAllBytes( cache.toPath() ), "a failed write must not truncate the attribute cache" );
    }
}
