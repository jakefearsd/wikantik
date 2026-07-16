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
package com.wikantik.api.config;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


public class GenAiModeTest {

    @Test
    public void testAbsentProperty() {
        Properties props = new Properties();
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testFullValue() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "full" );
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testEmbeddingsOnlyValue() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "embeddings-only" );
        assertEquals( GenAiMode.EMBEDDINGS_ONLY, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testNoneValue() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "none" );
        assertEquals( GenAiMode.NONE, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testGarbageValue() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "garbage-value" );
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }


    @Test
    public void testBlankValue() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "" );
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testWhitespaceValue() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "   " );
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testCaseInsensitivity() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "FULL" );
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testCaseInsensitivityEmbeddingsOnly() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "EMBEDDINGS-ONLY" );
        assertEquals( GenAiMode.EMBEDDINGS_ONLY, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testCaseInsensitivityNone() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "NoNe" );
        assertEquals( GenAiMode.NONE, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testTrimWhitespace() {
        Properties props = new Properties();
        props.setProperty( GenAiMode.PROP, "  full  " );
        assertEquals( GenAiMode.FULL, GenAiMode.fromProperties( props ) );
    }

    @Test
    public void testAllowsChatInferenceFull() {
        assertTrue( GenAiMode.FULL.allowsChatInference() );
    }

    @Test
    public void testAllowsChatInferenceEmbeddingsOnly() {
        assertFalse( GenAiMode.EMBEDDINGS_ONLY.allowsChatInference() );
    }

    @Test
    public void testAllowsChatInferenceNone() {
        assertFalse( GenAiMode.NONE.allowsChatInference() );
    }

    @Test
    public void testAllowsEmbeddingsFull() {
        assertTrue( GenAiMode.FULL.allowsEmbeddings() );
    }

    @Test
    public void testAllowsEmbeddingsEmbeddingsOnly() {
        assertTrue( GenAiMode.EMBEDDINGS_ONLY.allowsEmbeddings() );
    }

    @Test
    public void testAllowsEmbeddingsNone() {
        assertFalse( GenAiMode.NONE.allowsEmbeddings() );
    }
}
