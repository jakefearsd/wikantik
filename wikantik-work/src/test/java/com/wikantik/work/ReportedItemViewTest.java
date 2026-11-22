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

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The reporter's view is built from the item alone: its components are pinned, and none can carry another record. */
class ReportedItemViewTest {

    private static final List< Class< ? > > FORBIDDEN = List.of( Item.class, Link.class, Change.class, Deployment.class,
        Sprint.class, Project.class, Member.class, Agent.class );

    @Test
    void carriesOnlyTheAllowedComponents() {
        assertEquals( List.of( "key", "projectKey", "projectName", "level", "type", "status", "title", "body", "bodyHtml",
            "reporter", "createdAt", "updatedAt", "comments", "events", "archived" ),
            Arrays.stream( ReportedItemView.class.getRecordComponents() ).map( RecordComponent::getName ).toList() );
        for ( final RecordComponent c : ReportedItemView.class.getRecordComponents() ) {
            assertFalse( FORBIDDEN.contains( c.getType() ), c.getName() );
            if ( c.getGenericType() instanceof ParameterizedType p ) {
                for ( final java.lang.reflect.Type arg : p.getActualTypeArguments() ) {
                    assertFalse( FORBIDDEN.contains( arg ), c.getName() + " of " + arg );
                }
            }
        }
    }

    @Test
    void theListLineIsJustTheItem() {
        assertEquals( List.of( "key", "projectKey", "projectName", "level", "type", "status", "title", "createdAt",
            "updatedAt", "commentCount" ),
            Arrays.stream( ReportedItem.class.getRecordComponents() ).map( RecordComponent::getName ).toList() );
    }
}
