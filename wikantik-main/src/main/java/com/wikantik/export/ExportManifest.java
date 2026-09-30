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
package com.wikantik.export;

import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

/**
 * The `.wikantik/manifest.json` companion written into every exported vault: the selection that
 * produced the export, and a per-file record (path + SHA-256) for every page and attachment
 * written, so a re-import or diff can verify integrity without re-deriving anything from the wiki.
 */
public record ExportManifest( int formatVersion, String serverBaseUrl, String exportedAt, Map< String, Object > selection,
                              List< PageEntry > pages, List< AttachmentEntry > attachments, List< String > warnings ) {
    public static final int FORMAT_VERSION = 1;

    public record PageEntry( String name, String canonicalId, int version, String path, String sha256 ) {}

    public record AttachmentEntry( String page, String name, String path, String sha256 ) {}

    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson( this );
    }
}
