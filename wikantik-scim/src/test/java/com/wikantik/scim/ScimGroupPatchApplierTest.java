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
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ScimGroupPatchApplierTest {

    private JsonObject patch( String ops ) {
        return JsonParser.parseString(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + ops + "}"
        ).getAsJsonObject();
    }

    @Test
    void addMembers() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1" ),
            patch( "[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"u-2\"}]}]" ) );
        assertEquals( Set.of( "u-1", "u-2" ), r );
    }

    @Test
    void removeByValuePath() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1", "u-2" ),
            patch( "[{\"op\":\"remove\",\"path\":\"members[value eq \\\"u-1\\\"]\"}]" ) );
        assertEquals( Set.of( "u-2" ), r );
    }

    @Test
    void replaceAll() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1", "u-2" ),
            patch( "[{\"op\":\"replace\",\"path\":\"members\",\"value\":[{\"value\":\"u-9\"}]}]" ) );
        assertEquals( Set.of( "u-9" ), r );
    }

    @Test
    void removeAllMembers() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1", "u-2" ),
            patch( "[{\"op\":\"remove\",\"path\":\"members\"}]" ) );
        assertTrue( r.isEmpty() );
    }

    @Test
    void entraPathlessValueObject() {
        // Entra: {op:add, value:{members:[{value:..}]}}
        Set<String> r = ScimGroupPatchApplier.apply( List.of(),
            patch( "[{\"op\":\"add\",\"value\":{\"members\":[{\"value\":\"u-5\"}]}}]" ) );
        assertEquals( Set.of( "u-5" ), r );
    }

    @Test
    void nonMembersValuePathRejected() {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class,
            () -> ScimGroupPatchApplier.apply( List.of(),
                patch( "[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"x\"}]" ) ) );
    }
}
