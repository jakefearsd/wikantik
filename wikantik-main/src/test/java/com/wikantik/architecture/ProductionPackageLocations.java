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
package com.wikantik.architecture;

import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.LocationProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The production locations of the {@code com.wikantik} package, for {@code @AnalyzeClasses(locations = …)}.
 *
 * <p>{@code @AnalyzeClasses(packages = "com.wikantik")} asks ArchUnit's {@code Locations.ofPackage}, which, besides
 * the package's own URLs, walks <em>every</em> classpath root looking for an entry under the package (for jars
 * without directory entries). A directory root is walked in full, and {@code target/test-classes} holds the page
 * repositories that {@code TestEngine} instances in parallel test forks create and delete
 * ({@code <millis>-<origin>/testrepository}); the walk then dies with {@code NoSuchFileException} on a directory
 * that vanished mid-scan. This provider uses only the URLs the class loader returns for {@code com/wikantik/}
 * itself, minus the test-classes ones (which {@code DoNotIncludeTests} would drop anyway), so no classpath root is
 * walked. Maven-built jars carry directory entries, so none is missed; the tests that use it assert a few known
 * classes were imported, so a missed jar could not leave a rule silently vacuous.
 */
public final class ProductionPackageLocations implements LocationProvider {

    private static final String PACKAGE = "com/wikantik/";

    @Override
    public Set< Location > get( final Class< ? > testClass ) {
        final Set< Location > out = new LinkedHashSet<>();
        try {
            final Enumeration< URL > urls = testClass.getClassLoader().getResources( PACKAGE );
            while ( urls.hasMoreElements() ) {
                final URL url = urls.nextElement();
                if ( !url.toString().contains( "/test-classes/" ) ) {
                    out.add( Location.of( url ) );
                }
            }
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Could not list the locations of " + PACKAGE, e );
        }
        return out;
    }
}
