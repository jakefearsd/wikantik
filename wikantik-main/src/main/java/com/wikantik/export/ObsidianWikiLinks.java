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

import com.wikantik.api.parser.WikiLinkSyntax;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Native {@code [[ ]]} links and embeds for the Obsidian export: page targets are re-mapped to the vault
 * file basename (exact page name only), attachment targets to the vault attachment target. Matches inside
 * code are skipped by {@link WikiLinkSyntax#findAll}; {@code !} and {@code |alias} are kept.
 */
final class ObsidianWikiLinks {

    /** A rewrite of {@code [start, end)} inside the token spanning {@code [refStart, refEnd)}. */
    record Span( int refStart, int refEnd, int start, int end, String replacement ) {}

    private ObsidianWikiLinks() {}

    static List< Span > collect( final String body, final ConversionState state ) {
        final List< Span > out = new ArrayList<>();
        for ( final WikiLinkSyntax.WikiLinkRef ref : WikiLinkSyntax.findAll( body ) ) {
            if ( ref.isSamePage() ) {
                continue;
            }
            final Span span = spanFor( ref, state );
            if ( span != null ) {
                out.add( span );
            }
        }
        return out;
    }

    private static Span spanFor( final WikiLinkSyntax.WikiLinkRef ref, final ConversionState state ) {
        final String owner = ref.isAttachment() ? ref.pageName() : state.currentPage;
        final Optional< String > attachment = state.ctx.attachmentTarget( owner, ref.fileName() );
        if ( ref.isAttachment() || attachment.isPresent() ) {
            if ( attachment.isEmpty() ) {
                return null;
            }
            state.attachments.add( new AttachmentRef( owner, ref.fileName() ) );
            return new Span( ref.start(), ref.end(), ref.nameFrom(), ref.nameFrom() + ref.target().length(),
                    attachment.get() );
        }
        final String target = ObsidianLinkRenderer.linkTarget( ref.target(), state );
        return target.equals( ref.target() ) ? null
                : new Span( ref.start(), ref.end(), ref.nameFrom(), ref.nameTo(), target );
    }
}
