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
package com.wikantik.mcp.tools;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared metadata operation logic used by both {@link UpdateMetadataTool}
 * and {@link BatchUpdateMetadataTool}.
 */
public final class MetadataOperations {

    private MetadataOperations() {
    }

    /**
     * Applies a single metadata operation to the given metadata map.
     *
     * @param metadata the mutable metadata map to modify
     * @param field    the frontmatter field name
     * @param action   one of: set, append_to_list, remove_from_list, delete
     * @param value    the value (not needed for delete)
     * @return null on success, or an error message string on failure
     */
    public static String apply( final Map< String, Object > metadata,
                                 final String field, final String action, final Object value ) {
        if ( field == null || action == null ) {
            return "Operation missing required 'field' or 'action'";
        }

        switch ( action ) {
            case "set":
                metadata.put( field, value );
                return null;

            case "append_to_list":
                return applyAppendToList( metadata, field, value );

            case "remove_from_list":
                return applyRemoveFromList( metadata, field, value );

            case "delete":
                metadata.remove( field );
                return null;

            default:
                return "Unknown action: " + action + ". Valid actions: set, append_to_list, remove_from_list, delete";
        }
    }

    @SuppressWarnings( "unchecked" )
    static String applyAppendToList( final Map< String, Object > metadata, final String field, final Object value ) {
        final Object existing = metadata.get( field );
        if ( existing == null ) {
            final List< Object > list = new ArrayList<>();
            list.add( value );
            metadata.put( field, list );
        } else if ( existing instanceof List ) {
            final List< Object > list = new ArrayList<>( ( List< Object > ) existing );
            if ( !list.contains( value ) ) {
                list.add( value );
            }
            metadata.put( field, list );
        } else {
            return "Field '" + field + "' exists but is not a list (type: " +
                    existing.getClass().getSimpleName() + "). Use 'set' to overwrite it.";
        }
        return null;
    }

    @SuppressWarnings( "unchecked" )
    static String applyRemoveFromList( final Map< String, Object > metadata, final String field, final Object value ) {
        final Object existing = metadata.get( field );
        if ( existing == null ) {
            return null;
        }
        if ( !( existing instanceof List ) ) {
            return "Field '" + field + "' is not a list (type: " +
                    existing.getClass().getSimpleName() + "). Cannot remove from non-list field.";
        }
        final List< Object > list = new ArrayList<>( ( List< Object > ) existing );
        list.remove( value );
        metadata.put( field, list );
        return null;
    }
}
