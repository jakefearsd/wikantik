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
package com.wikantik.work;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item's level, direct-commit permission and done-means are decided in {@link WorkAutonomy} and nowhere else: a
 * second reader would see an item's own column without its epic, its project or its product's ceiling. Classes
 * that only carry the columns (the stores, the patch applier, the event diff, input normalising) are exempt, and
 * {@code WorkService} may compare a project's default to detect a settings change. The resolved policy's own
 * accessors ({@code level}, {@code directAllowed}, {@code doneWhen}) are named differently on purpose.
 */
class WorkAutonomySourceRuleTest {

    private static final Path MAIN = Path.of( "src", "main", "java", "com", "wikantik", "work" );

    private static final Set< String > CARRIERS = Set.of( "WorkAutonomy.java", "ItemPatchApplier.java",
        "PatchValues.java", "WorkInputs.java", "WorkEventDiff.java", "Item.java", "Project.java", "Product.java",
        "PolicyChain.java", "EffectivePolicy.java" );

    private static final List< String > READS = List.of( ".autonomy()", ".allowDirect()", ".doneMeans()",
        ".autonomyCeiling()" );

    @Test
    void onlyWorkAutonomyDecidesFromThePolicyColumns() throws IOException {
        final List< String > offenders = new ArrayList<>();
        int scanned = 0;
        try ( Stream< Path > files = Files.list( MAIN ) ) {
            for ( final Path file : files.filter( f -> f.toString().endsWith( ".java" ) ).toList() ) {
                final String name = file.getFileName().toString();
                if ( CARRIERS.contains( name ) || name.startsWith( "Jdbc" ) ) {
                    continue;
                }
                scanned++;
                final String source = Files.readString( file );
                final boolean reads = READS.stream().anyMatch( source::contains )
                    || ( source.contains( ".defaultAutonomy()" ) && !name.equals( "WorkService.java" ) );
                if ( reads ) {
                    offenders.add( name );
                }
            }
        }
        assertTrue( scanned > 20, "the scan found the module's sources (" + scanned + ")" );
        assertEquals( List.of(), offenders, "these classes read a policy column past WorkAutonomy" );
    }
}
