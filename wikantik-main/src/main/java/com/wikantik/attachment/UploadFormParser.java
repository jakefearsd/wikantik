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
package com.wikantik.attachment;

import com.wikantik.util.TextUtil;
import org.apache.commons.fileupload2.core.FileItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts attachment-upload form fields from a parsed multipart request. Each known form
 * field is bound to a small handler (Strategy), replacing the prior switch on
 * {@code item.getFieldName()} inside {@link AttachmentServlet#upload}.
 */
final class UploadFormParser {

    @FunctionalInterface
    private interface FieldHandler {
        void apply( FileItem item, Builder out ) throws IOException;
    }

    private static final Map< String, FieldHandler > FIELD_HANDLERS = Map.of(
            "page",       UploadFormParser::handlePage,
            "changenote", UploadFormParser::handleChangeNote,
            "nextpage",   UploadFormParser::handleNextPage
    );

    private UploadFormParser() {}

    static UploadFormData parse( final List< FileItem > items ) throws IOException {
        final Builder out = new Builder();
        for ( final FileItem item : items ) {
            if ( item.isFormField() ) {
                final FieldHandler handler = FIELD_HANDLERS.get( item.getFieldName() );
                if ( handler != null ) {
                    handler.apply( item, out );
                }
            } else {
                out.addFile( item );
            }
        }
        return out.build();
    }

    private static void handlePage( final FileItem item, final Builder out ) throws IOException {
        // Kludge alert (preserved): if uploading a new revision of an existing attachment,
        // 'page' arrives as "Parent/attachment" — strip to just the parent.
        String wikipage = item.getString( StandardCharsets.UTF_8 );
        final int slashIndex = wikipage.indexOf( '/' );
        if ( slashIndex != -1 ) {
            wikipage = wikipage.substring( 0, slashIndex );
        }
        out.wikipage = wikipage;
    }

    private static void handleChangeNote( final FileItem item, final Builder out ) throws IOException {
        String note = item.getString( StandardCharsets.UTF_8 );
        if ( note != null ) {
            note = TextUtil.replaceEntities( note );
        }
        out.changeNote = note;
    }

    private static void handleNextPage( final FileItem item, final Builder out ) throws IOException {
        out.nextPage = item.getString( StandardCharsets.UTF_8 );
    }

    private static final class Builder {
        String wikipage;
        String changeNote;
        String nextPage;
        final List< FileItem > files = new ArrayList<>();

        void addFile( final FileItem item ) {
            files.add( item );
        }

        UploadFormData build() {
            return new UploadFormData( wikipage, changeNote, nextPage, List.copyOf( files ) );
        }
    }
}
