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
package com.wikantik.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the planned attachments of a spooled vault zip in ONE sequential {@link ZipInputStream} pass (UTF-8),
 * in stream order. The central directory is never consulted: it can disagree with the local headers the plan
 * validated and point a planned name at a hidden deflate bomb. Each entry is handed out as a
 * {@link PlannedEntryStream} bounded by its planned size and the zip-bomb ratio guard.
 */
final class PlannedEntryPass {

    /** Receives one planned attachment and its bounded entry stream. */
    @FunctionalInterface
    interface Handler {
        void accept( PlannedAttachment attachment, PlannedEntryStream in );
    }

    private PlannedEntryPass() {
    }

    /**
     * Streams {@code zip}, calling {@code handler} for each planned attachment as its entry is reached.
     *
     * @return the planned attachments whose entry never appeared
     */
    static List< PlannedAttachment > run( final Path zip, final List< PlannedAttachment > planned, final Handler handler )
            throws IOException {
        final Map< String, Deque< PlannedAttachment > > pending = new LinkedHashMap<>();
        for ( final PlannedAttachment a : planned ) {
            pending.computeIfAbsent( a.entryName(), k -> new ArrayDeque<>() ).add( a );
        }
        try ( CountingInputStream raw = new CountingInputStream( Files.newInputStream( zip ) );
              ZipInputStream zin = new ZipInputStream( raw, StandardCharsets.UTF_8 ) ) {
            ZipEntry entry = zin.getNextEntry();
            while ( entry != null && !pending.isEmpty() ) {
                final Deque< PlannedAttachment > queue = pending.get( entry.getName() );
                if ( queue != null ) {
                    final PlannedAttachment a = queue.poll();
                    if ( queue.isEmpty() ) {
                        pending.remove( entry.getName() );
                    }
                    handler.accept( a, new PlannedEntryStream( zin, raw, a.size() ) );
                }
                entry = zin.getNextEntry();
            }
        }
        final List< PlannedAttachment > missing = new ArrayList<>();
        pending.values().forEach( missing::addAll );
        return missing;
    }
}
