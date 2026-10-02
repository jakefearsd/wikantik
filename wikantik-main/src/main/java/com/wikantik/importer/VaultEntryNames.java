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

import java.util.List;
import java.util.regex.Pattern;

/** Name policy for vault zip entries: safety checks, wrapper-folder detection and ignore rules. */
final class VaultEntryNames {

    private static final String MACOSX = "__MACOSX/";
    private static final String EXPORT_MARKER = "Wikantik Export.md";
    private static final Pattern DRIVE_PREFIX = Pattern.compile( "^[A-Za-z]:.*", Pattern.DOTALL );

    private VaultEntryNames() {
    }

    static void requireSafe( final String name ) throws VaultArchiveException {
        final String why = unsafeReason( name );
        if ( why != null ) {
            throw new VaultArchiveException( "unsafe zip entry '" + name + "': " + why );
        }
    }

    private static String unsafeReason( final String name ) {
        if ( name.isEmpty() ) {
            return "empty name";
        }
        if ( name.startsWith( "/" ) ) {
            return "absolute path";
        }
        if ( DRIVE_PREFIX.matcher( name ).matches() ) {
            return "drive-letter path";
        }
        if ( name.indexOf( '\\' ) >= 0 || name.indexOf( '\0' ) >= 0 ) {
            return "backslash or NUL in name";
        }
        if ( hasControlChar( name ) ) {
            return "control character in name";
        }
        return badSegment( name.endsWith( "/" ) ? name.substring( 0, name.length() - 1 ) : name );
    }

    /** C0 controls (tab, CR, LF, ...) and DEL would leak into page titles, generated hub bodies and warnings. */
    private static boolean hasControlChar( final String name ) {
        for ( int i = 0; i < name.length(); i++ ) {
            final char c = name.charAt( i );
            if ( c < 0x20 || c == 0x7f ) {
                return true;
            }
        }
        return false;
    }

    private static String badSegment( final String trimmed ) {
        for ( final String seg : trimmed.split( "/", -1 ) ) {
            if ( seg.isEmpty() || ".".equals( seg ) || "..".equals( seg ) ) {
                return "path traversal or empty segment";
            }
        }
        return null;
    }

    static String wrapperPrefix( final List< String > names ) {
        String first = null;
        for ( final String name : names ) {
            if ( name.endsWith( "/" ) || name.startsWith( MACOSX ) || name.startsWith( "." ) ) {
                continue;
            }
            final int slash = name.indexOf( '/' );
            if ( slash < 0 || first != null && !first.equals( name.substring( 0, slash ) ) ) {
                return "";
            }
            first = name.substring( 0, slash );
        }
        return first == null ? "" : first + "/";
    }

    static String relative( final String name, final String prefix ) {
        return name.startsWith( prefix ) ? name.substring( prefix.length() ) : name;
    }

    /**
     * True when {@code name} is a note that will be imported whatever the wrapper folder turns out to be. Used to count
     * notes while streaming, before the wrapper is known: it may under-count (a sub-folder note named like the export
     * marker), never over-count.
     */
    static boolean surelyImportableNote( final String name ) {
        if ( !VaultPaths.isNote( name ) || ignored( name, name ) ) {
            return false;
        }
        final boolean couldBeMarker = EXPORT_MARKER.equals( VaultPaths.basename( name ) )
            && name.indexOf( '/' ) == name.lastIndexOf( '/' );
        return !couldBeMarker;
    }

    static boolean ignored( final String name, final String rel ) {
        if ( name.endsWith( "/" ) || name.startsWith( MACOSX ) || EXPORT_MARKER.equals( rel ) ) {
            return true;
        }
        for ( final String seg : rel.split( "/" ) ) {
            if ( seg.startsWith( "." ) ) {
                return true;
            }
        }
        return false;
    }

}
