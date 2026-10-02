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

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentViolation;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.spi.Wiki;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link ImportPageSink} backed by the live engine; saves go through the same pipeline as {@code write_pages}. */
public final class EngineImportPageSink implements ImportPageSink {

    private final Engine engine;
    private final PageManager pages;
    private final AttachmentManager attachments;
    private final PageSaveHelper saveHelper;
    private final AttachmentGate gate;

    public EngineImportPageSink( final Engine engine, final PageManager pages, final AttachmentManager attachments,
                                 final PageSaveHelper saveHelper ) {
        this( engine, pages, attachments, saveHelper, null );
    }

    /** As above; {@code gate} (nullable) is re-applied to every attachment at apply time. */
    public EngineImportPageSink( final Engine engine, final PageManager pages, final AttachmentManager attachments,
                                 final PageSaveHelper saveHelper, final AttachmentGate gate ) {
        this.gate = gate;
        this.engine = engine;
        this.pages = pages;
        this.attachments = attachments;
        this.saveHelper = saveHelper;
    }

    @Override
    public java.util.Optional< String > attachmentRejection( final String fileName, final long size ) {
        return gate == null ? java.util.Optional.empty() : gate.rejection( fileName, size );
    }

    @Override
    public boolean pageExists( final String name ) {
        return pages.wikiPageExists( name );
    }

    /**
     * Saves a new page. Note: {@code PageSaveHelper} has no create-only guard (its {@code expectedVersion}
     * check only fires for an existing page), so a page created by someone else between the job's
     * {@code pageExists} check and this save would be overwritten. The window is the few milliseconds
     * between those two calls; the job re-checks existence immediately before each save.
     */
    @Override
    public List< String > savePage( final String name, final String body, final Map< String, Object > metadata,
                                    final String author, final String changeNote ) throws ImportSaveException {
        FrontmatterWarningSink.clear();
        ContentWarningSink.clear();
        try {
            saveHelper.saveText( name, body, SaveOptions.builder().author( author ).changeNote( changeNote )
                .markupSyntax( "markdown" ).metadata( metadata.isEmpty() ? null : metadata )
                .replaceMetadata( true ).build() );
            final List< String > warnings = new ArrayList<>();
            FrontmatterWarningSink.drain( name ).forEach( v -> warnings.add( "frontmatter: " + v.message() ) );
            ContentWarningSink.drain().forEach( v -> warnings.add( "content: " + v.message() ) );
            return warnings;
        } catch ( final FrontmatterValidationException e ) {
            throw new ImportSaveException( "frontmatter validation failed: "
                + e.violations().stream().map( v -> v.message() ).toList(), e );
        } catch ( final ContentValidationException e ) {
            throw new ImportSaveException( "math validation failed: "
                + e.violations().stream().map( ContentViolation::message ).toList(), e );
        } catch ( final WikiException | RuntimeException e ) {
            throw new ImportSaveException( String.valueOf( e.getMessage() ), e );
        } finally {
            FrontmatterWarningSink.clear();
            ContentWarningSink.clear();
        }
    }

    @Override
    public void storeAttachment( final String page, final String fileName, final InputStream in,
                                 final String author ) throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, page, fileName );
        att.setAuthor( author );
        attachments.storeAttachment( att, in );
    }
}
