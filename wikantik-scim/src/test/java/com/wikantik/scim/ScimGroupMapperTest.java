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
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimGroupMapperTest {

    private Principal principal( String name ) {
        Principal p = mock( Principal.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    @Test
    void toScimEmitsIdDisplayNameAndMembersWithUid() {
        com.wikantik.auth.authorize.Group g = mock( com.wikantik.auth.authorize.Group.class );
        when( g.getName() ).thenReturn( "Engineers" );
        Principal p1 = principal( "alice" );
        Principal p2 = principal( "bob" );
        when( g.members() ).thenReturn( new Principal[]{ p1, p2 } );
        Map<String,String> loginToUid = Map.of( "alice", "u-1", "bob", "u-2" );

        JsonObject o = ScimGroupMapper.toScim( g, "https://h/scim/v2/Users", "https://h/scim/v2/Groups",
                loginToUid::get );

        assertTrue( o.getAsJsonArray( "schemas" ).toString().contains(
                "urn:ietf:params:scim:schemas:core:2.0:Group" ) );
        assertEquals( "Engineers", o.get( "id" ).getAsString() );
        assertEquals( "Engineers", o.get( "displayName" ).getAsString() );
        assertEquals( 2, o.getAsJsonArray( "members" ).size() );
        JsonObject m0 = o.getAsJsonArray( "members" ).get( 0 ).getAsJsonObject();
        assertEquals( "u-1", m0.get( "value" ).getAsString() );
        assertEquals( "alice", m0.get( "display" ).getAsString() );
        assertTrue( m0.get( "$ref" ).getAsString().endsWith( "/u-1" ) );
        assertTrue( o.getAsJsonObject( "meta" ).get( "location" ).getAsString().endsWith( "/Engineers" ) );
    }

    @Test
    void toScimSkipsUnresolvableMembers() {
        com.wikantik.auth.authorize.Group g = mock( com.wikantik.auth.authorize.Group.class );
        when( g.getName() ).thenReturn( "G" );
        Principal p = principal( "ghost" );
        when( g.members() ).thenReturn( new Principal[]{ p } );
        JsonObject o = ScimGroupMapper.toScim( g, "u", "grp", login -> null );
        assertEquals( 0, o.getAsJsonArray( "members" ).size() );
    }

    @Test
    void readMemberUidsAndDisplayName() {
        JsonObject in = JsonParser.parseString(
            "{\"displayName\":\"Engineers\",\"members\":[{\"value\":\"u-1\"},{\"value\":\"u-2\"}]}" )
            .getAsJsonObject();
        assertEquals( "Engineers", ScimGroupMapper.readDisplayName( in ) );
        assertEquals( List.of( "u-1", "u-2" ), ScimGroupMapper.readMemberUids( in ) );
    }

    @Test
    void nestedGroupMemberRejected() {
        JsonObject in = JsonParser.parseString(
            "{\"displayName\":\"X\",\"members\":[{\"value\":\"g-9\",\"type\":\"Group\"}]}" )
            .getAsJsonObject();
        assertThrows( ScimGroupMapper.NestedGroupUnsupportedException.class,
                () -> ScimGroupMapper.readMemberUids( in ) );
    }
}
