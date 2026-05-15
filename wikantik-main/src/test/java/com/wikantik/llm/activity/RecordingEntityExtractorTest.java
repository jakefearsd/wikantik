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
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingEntityExtractorTest {

    private static ExtractionChunk chunk() {
        return new ExtractionChunk( UUID.randomUUID(), "PageA", 0, List.of(), "the chunk text" );
    }

    @Test
    void recordsOkOnSuccessfulExtraction() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final ExtractionResult result = ExtractionResult.empty( "ollama", Duration.ofMillis( 5 ) );
        final EntityExtractor delegate = new EntityExtractor() {
            public String code() { return "ollama"; }
            public ExtractionResult extract( final ExtractionChunk c, final ExtractionContext ctx ) {
                return result;
            }
        };
        final RecordingEntityExtractor rec =
            new RecordingEntityExtractor( delegate, log, "ollama", "gemma4-assist" );

        assertSame( result, rec.extract( chunk(), null ) );

        final LlmActivityLog.Snapshot snap = log.snapshot( 10, null, null );
        assertEquals( 1, snap.calls().size() );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( "ENTITY_EXTRACTION", snap.calls().get( 0 ).subsystem() );
        assertEquals( "gemma4-assist", snap.calls().get( 0 ).model() );
    }

    @Test
    void recordsErrorAndRethrowsWhenDelegateThrows() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final EntityExtractor delegate = new EntityExtractor() {
            public String code() { return "ollama"; }
            public ExtractionResult extract( final ExtractionChunk c, final ExtractionContext ctx ) {
                throw new IllegalStateException( "contract violation" );
            }
        };
        final RecordingEntityExtractor rec =
            new RecordingEntityExtractor( delegate, log, "ollama", "gemma4-assist" );

        assertThrows( IllegalStateException.class, () -> rec.extract( chunk(), null ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void delegatesCode() {
        final EntityExtractor delegate = new EntityExtractor() {
            public String code() { return "claude"; }
            public ExtractionResult extract( final ExtractionChunk c, final ExtractionContext ctx ) {
                return ExtractionResult.empty( "claude", Duration.ZERO );
            }
        };
        final RecordingEntityExtractor rec = new RecordingEntityExtractor(
            delegate, new LlmActivityLog( true, 60, 100, 500 ), "claude", "claude-x" );
        assertEquals( "claude", rec.code() );
    }
}
