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
package com.wikantik.connectors.gdrive;

import java.io.IOException;
import java.util.List;

/** Thin seam over the Google Drive API so the connector logic is testable without the Google SDK. */
public interface DriveApi {
    /** Lists the direct children of a folder. */
    List<DriveFile> listFolder( String folderId ) throws IOException;
    /** Exports a Google-native document (e.g. Docs) to the given mime type. */
    byte[] export( String fileId, String mimeType ) throws IOException;
    /** Fetches the raw bytes of a native (non-Google-native) file. */
    byte[] getMedia( String fileId ) throws IOException;
}
