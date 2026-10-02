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

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Port through which an import job creates pages and attachments in the wiki. */
public interface ImportPageSink {

    /** True when a page of exactly this name exists now. */
    boolean pageExists( String name );

    /**
     * Creates a page; returns validation warnings.
     *
     * @throws ImportSaveException if the page could not be saved (validation or storage failure)
     */
    List< String > savePage( String name, String body, Map< String, Object > metadata, String author,
                             String changeNote ) throws ImportSaveException;

    /** The reason an attachment is refused by the current upload policy, or empty when it may be stored. */
    default java.util.Optional< String > attachmentRejection( final String fileName, final long size ) {
        return java.util.Optional.empty();
    }

    /** Stores one attachment on an existing page. */
    void storeAttachment( String page, String fileName, InputStream in, String author ) throws Exception;
}
