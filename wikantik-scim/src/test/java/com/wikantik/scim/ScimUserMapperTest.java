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
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimUserMapperTest {

    @Test
    void toScimEmitsCoreSchemaAndActiveFromLock() {
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-1" );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( p.getFullname() ).thenReturn( "Alice Smith" );
        when( p.getEmail() ).thenReturn( "alice@example.com" );
        when( p.getWikiName() ).thenReturn( "AliceSmith" );
        when( p.isLocked() ).thenReturn( false );
        Map<String,java.io.Serializable> attrs = new HashMap<>();
        attrs.put( "sso.subject", "ext-123" );
        when( p.getAttributes() ).thenReturn( attrs );

        JsonObject o = ScimUserMapper.toScim( p, "https://host/scim/v2/Users" );

        assertTrue( o.getAsJsonArray( "schemas" ).toString().contains( "urn:ietf:params:scim:schemas:core:2.0:User" ) );
        assertEquals( "u-1", o.get( "id" ).getAsString() );
        assertEquals( "alice", o.get( "userName" ).getAsString() );
        assertEquals( "ext-123", o.get( "externalId" ).getAsString() );
        assertTrue( o.get( "active" ).getAsBoolean() );
        assertEquals( "alice@example.com",
                o.getAsJsonArray( "emails" ).get( 0 ).getAsJsonObject().get( "value" ).getAsString() );
        assertTrue( o.getAsJsonObject( "meta" ).get( "location" ).getAsString().endsWith( "/u-1" ) );
    }

    @Test
    void lockedProfileMapsToActiveFalse() {
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-2" );
        when( p.getLoginName() ).thenReturn( "bob" );
        when( p.isLocked() ).thenReturn( true );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        JsonObject o = ScimUserMapper.toScim( p, "https://host/scim/v2/Users" );
        assertFalse( o.get( "active" ).getAsBoolean() );
    }

    @Test
    void readCreateFieldsExtractsUserNameEmailExternalId() {
        JsonObject in = JsonParser.parseString(
            "{\"userName\":\"carol\",\"externalId\":\"ext-9\","
          + "\"name\":{\"formatted\":\"Carol Jones\"},"
          + "\"emails\":[{\"primary\":true,\"value\":\"carol@x.com\"}]}" ).getAsJsonObject();
        ScimUserMapper.CreateFields f = ScimUserMapper.readCreate( in );
        assertEquals( "carol", f.userName() );
        assertEquals( "ext-9", f.externalId() );
        assertEquals( "Carol Jones", f.fullName() );
        assertEquals( "carol@x.com", f.email() );
    }
}
