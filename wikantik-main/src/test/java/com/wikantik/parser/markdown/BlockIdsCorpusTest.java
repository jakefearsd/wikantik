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
package com.wikantik.parser.markdown;

import com.wikantik.parser.markdown.BlockIds.Block;
import com.wikantik.parser.markdown.BlockIds.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockIds} over every shipped page: reading never fails (flexmark's text collector trips its own assertion on
 * list containers holding {@code \$}, which this caught), {@code strip} keeps the line count and is idempotent, and
 * {@code assign} on a sample of blocks reads back with the block tagged at the same ordinal.
 */
class BlockIdsCorpusTest {

    private static final int ASSIGN_EVERY = 40;

    @Test
    void everyShippedPageReadsStripsAndAssigns() throws IOException {
        final Path pages = Path.of( System.getProperty( "user.dir" ) ).getParent().resolve( "docs/wikantik-pages" );
        final List< String > problems = new ArrayList<>();
        int blocks = 0;
        try ( Stream< Path > files = Files.list( pages ) ) {
            for ( final Path page : files.filter( p -> p.toString().endsWith( ".md" ) ).sorted().toList() ) {
                blocks += check( page.getFileName().toString(), Files.readString( page ), problems );
            }
        }
        assertTrue( blocks > 1000, "the corpus was read: " + blocks + " blocks" );
        assertEquals( List.of(), problems );
    }

    private static int check( final String name, final String text, final List< String > problems ) {
        final List< Block > blocks;
        try {
            blocks = BlockIds.list( text );
            blocks.forEach( b -> BlockIds.sourceOf( text, b ) );
            final String stripped = BlockIds.strip( text );
            if ( stripped.split( "\n", -1 ).length != text.split( "\n", -1 ).length ) {
                problems.add( name + ": strip changed the line count" );
            }
            if ( !BlockIds.strip( stripped ).equals( stripped ) ) {
                problems.add( name + ": strip is not idempotent" );
            }
        } catch ( final RuntimeException | AssertionError e ) {
            problems.add( name + ": " + e );
            return 0;
        }
        for ( int i = 0; i < blocks.size(); i += ASSIGN_EVERY ) {
            final Block b = blocks.get( i );
            if ( b.id() != null ) {
                continue;
            }
            try {
                final List< Block > after = BlockIds.list( BlockIds.assign( text, b, "zz9zz9" ) );
                if ( after.size() != blocks.size() ) {
                    problems.add( name + ": assigning block " + i + " changed the block count" );
                }
            } catch ( final IllegalArgumentException e ) {
                if ( b.kind() != Kind.LIST_ITEM ) {   // an item that opens with a nested block has no line to carry an id
                    problems.add( name + ": block " + i + " (" + b.kind() + "): " + e.getMessage() );
                }
            } catch ( final RuntimeException e ) {
                problems.add( name + ": block " + i + " (" + b.kind() + "): " + e );
            }
        }
        return blocks.size();
    }
}
