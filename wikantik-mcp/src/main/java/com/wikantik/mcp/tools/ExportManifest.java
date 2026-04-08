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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the {@code _export.json} manifest written alongside exported wiki pages.
 * Tracks source wiki identity, export timestamp, and per-page version numbers so that
 * {@link ImportContentTool} can detect conflicts on re-import.
 */
public class ExportManifest {

    static final String FILENAME = "_export.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private String wikiName;
    private String exportedAt;
    private Map< String, Integer > pageVersions;

    public ExportManifest() {
        this.pageVersions = new LinkedHashMap<>();
    }

    public ExportManifest( final String wikiName, final Instant exportedAt, final Map< String, Integer > pageVersions ) {
        this.wikiName = wikiName;
        this.exportedAt = exportedAt.toString();
        this.pageVersions = new LinkedHashMap<>( pageVersions );
    }

    public String getWikiName() { return wikiName; }
    public String getExportedAt() { return exportedAt; }
    public Map< String, Integer > getPageVersions() { return pageVersions; }

    /** Records the version of a page at export time. */
    public void putPageVersion( final String pageName, final int version ) {
        pageVersions.put( pageName, version );
    }

    /** Writes this manifest to {@code _export.json} in the given directory. */
    public void writeTo( final Path directory ) throws IOException {
        Files.writeString( directory.resolve( FILENAME ), GSON.toJson( this ), StandardCharsets.UTF_8 );
    }

    /** Reads a manifest from {@code _export.json} in the given directory. Returns null if not present. */
    public static ExportManifest readFrom( final Path directory ) throws IOException {
        final Path file = directory.resolve( FILENAME );
        if ( !Files.exists( file ) ) {
            return null;
        }
        final String json = Files.readString( file, StandardCharsets.UTF_8 );
        return GSON.fromJson( json, ExportManifest.class );
    }
}
