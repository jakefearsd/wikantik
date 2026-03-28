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

package com.wikantik.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class FileUtilTest
{

    /**
     *  This test actually checks if your JDK is misbehaving.  On my own Debian
     *  machine, changing the system to use UTF-8 suddenly broke Java, and I put
     *  in this test to check for its brokenness.  If your tests suddenly stop
     *  running, check if this one is Assertions.failing too.  If it is, your platform is
     *  broken.  If it's not, seek for the bug in your code.
     */
    @Test
    public void testJDKString() {
        final String src = "abc\u00e4\u00e5\u00a6";

        final String res = new String( src.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1 );

        Assertions.assertEquals( src, res );
    }

    @Test
    public void testReadContentsLatin1()
        throws Exception
    {
        final String src = "abc\u00e4\u00e5\u00a6";

        final String res = FileUtil.readContents( new ByteArrayInputStream( src.getBytes(StandardCharsets.ISO_8859_1) ),
                                            StandardCharsets.ISO_8859_1.name() );

        Assertions.assertEquals( src, res );
    }

    /**
     *  Check that fallbacks to ISO-Latin1 still work.
     */
    @Test
    public void testReadContentsLatin1_2()
        throws Exception
    {
        final String src = "abc\u00e4\u00e5\u00a6def";

        final String res = FileUtil.readContents( new ByteArrayInputStream( src.getBytes(StandardCharsets.ISO_8859_1) ),
                                            StandardCharsets.UTF_8.name() );

        Assertions.assertEquals( src, res );
    }

    /**
     * ISO Latin 1 from a pipe.
     */
    @Test
    @DisabledOnOs( OS.WINDOWS )
    public void testReadContentsFromPipeOnLinux() throws Exception {
        String src = "abc\n123456\n\nfoobar.\n";

        // Make a very long string.
        for( int i = 0; i < 10; i++ ) {
            src += src;
        }

        src += "\u00e4\u00e5\u00a6";

        final File f = FileUtil.newTmpFile( src, StandardCharsets.ISO_8859_1 );

        // Use ProcessBuilder instead of deprecated Runtime.exec()
        final ProcessBuilder pb = new ProcessBuilder( "cat", f.getAbsolutePath() );
        pb.directory( f.getParentFile() );
        final Process process = pb.start();
        final String result = FileUtil.readContents( process.getInputStream(), StandardCharsets.UTF_8.name() );
        f.delete();
        Assertions.assertEquals( src, result );
    }

    @Test
    public void testReadContentsReader()
        throws IOException
    {
        final String data = "ABCDEF";

        final String result = FileUtil.readContents( new StringReader( data ) );

        Assertions.assertEquals( data, result );
    }

    /**
     * Verifies that non-ASCII characters (umlauts, CJK) survive a round-trip through
     * UTF-8 stream readers/writers, which requires explicit charset specification.
     */
    @Test
    public void testNonAsciiRoundTripThroughUtf8Streams() throws IOException {
        final String nonAscii = "Gr\u00fc\u00dfe \u00e4\u00f6\u00fc \u4e16\u754c \ud55c\uad6d\uc5b4";

        // Write through OutputStreamWriter with explicit UTF-8
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try ( final OutputStreamWriter writer = new OutputStreamWriter( baos, StandardCharsets.UTF_8 ) ) {
            writer.write( nonAscii );
        }

        // Read back through InputStreamReader with explicit UTF-8
        final ByteArrayInputStream bais = new ByteArrayInputStream( baos.toByteArray() );
        final String result;
        try ( final InputStreamReader reader = new InputStreamReader( bais, StandardCharsets.UTF_8 ) ) {
            result = FileUtil.readContents( reader );
        }

        Assertions.assertEquals( nonAscii, result );
    }

    /**
     * Verifies that newTmpFile correctly handles non-ASCII content with UTF-8 encoding.
     */
    @Test
    public void testNewTmpFileWithNonAsciiUtf8() throws IOException {
        final String nonAscii = "\u00e4\u00f6\u00fc\u00df \u4e16\u754c";
        final File tmpFile = FileUtil.newTmpFile( nonAscii, StandardCharsets.UTF_8 );
        try {
            final String result = FileUtil.readContents(
                java.nio.file.Files.newInputStream( tmpFile.toPath() ), StandardCharsets.UTF_8.name() );
            Assertions.assertEquals( nonAscii, result );
        } finally {
            tmpFile.delete();
        }
    }

    // --- copyContents (Reader/Writer) tests ---

    @Test
    public void testCopyContentsReaderWriter() throws IOException {
        final String content = "Hello, World! This is a test with special chars: \u00e4\u00f6\u00fc";
        final StringReader reader = new StringReader( content );
        final StringWriter writer = new StringWriter();
        FileUtil.copyContents( reader, writer );
        assertEquals( content, writer.toString() );
    }

    @Test
    public void testCopyContentsReaderWriterEmpty() throws IOException {
        final StringReader reader = new StringReader( "" );
        final StringWriter writer = new StringWriter();
        FileUtil.copyContents( reader, writer );
        assertEquals( "", writer.toString() );
    }

    // --- copyContents (InputStream/OutputStream) tests ---

    @Test
    public void testCopyContentsStreamRoundTrip() throws IOException {
        final byte[] data = "Stream copy test data 12345".getBytes( StandardCharsets.UTF_8 );
        final ByteArrayInputStream in = new ByteArrayInputStream( data );
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileUtil.copyContents( in, out );
        assertArrayEquals( data, out.toByteArray() );
    }

    @Test
    public void testCopyContentsStreamEmpty() throws IOException {
        final ByteArrayInputStream in = new ByteArrayInputStream( new byte[0] );
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileUtil.copyContents( in, out );
        assertEquals( 0, out.size() );
    }

    // --- readContents (InputStream with encoding) tests ---

    @Test
    public void testReadContentsUTF8() throws IOException {
        final String expected = "Hello UTF-8 \u00e4\u00f6\u00fc";
        final ByteArrayInputStream in = new ByteArrayInputStream(
            expected.getBytes( StandardCharsets.UTF_8 ) );
        final String result = FileUtil.readContents( in, StandardCharsets.UTF_8.name() );
        assertEquals( expected, result );
    }

    @Test
    public void testReadContentsEmpty() throws IOException {
        final ByteArrayInputStream in = new ByteArrayInputStream( new byte[0] );
        final String result = FileUtil.readContents( in, StandardCharsets.UTF_8.name() );
        assertEquals( "", result );
    }

    // --- runSimpleCommand tests ---

    @Test
    @DisabledOnOs( OS.WINDOWS )
    public void testRunSimpleCommand() throws Exception {
        final String result = FileUtil.runSimpleCommand( "echo hello", "/tmp" );
        assertEquals( "hello\n", result );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    public void testRunSimpleCommandMultipleWords() throws Exception {
        final String result = FileUtil.runSimpleCommand( "echo foo bar", "/tmp" );
        assertEquals( "foo bar\n", result );
    }

    // --- newTmpFile tests ---

    @Test
    public void testNewTmpFileDefaultEncoding() throws IOException {
        final String content = "test content";
        final File f = FileUtil.newTmpFile( content );
        try {
            assertTrue( f.exists() );
            assertTrue( f.getName().startsWith( "wikantik" ) );
            final String result = FileUtil.readContents(
                java.nio.file.Files.newInputStream( f.toPath() ), StandardCharsets.ISO_8859_1.name() );
            assertEquals( content, result );
        } finally {
            f.delete();
        }
    }

    // --- getThrowingMethod tests ---

    @Test
    public void testGetThrowingMethod() {
        try {
            throw new RuntimeException( "test" );
        } catch ( RuntimeException e ) {
            String result = FileUtil.getThrowingMethod( e );
            assertNotNull( result );
            assertTrue( result.contains( "FileUtilTest" ) );
            assertTrue( result.contains( "testGetThrowingMethod" ) );
        }
    }

    @Test
    public void testGetThrowingMethodEmptyStackTrace() {
        final Throwable t = new Throwable() {
            @Override
            public StackTraceElement[] getStackTrace() {
                return new StackTraceElement[0];
            }
        };
        final String result = FileUtil.getThrowingMethod( t );
        assertEquals( "[Stack trace not available]", result );
    }

}
