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
import static org.junit.jupiter.api.Assertions.*;

class ScimPatchApplierTest {

    private JsonObject patch( String ops ) {
        return JsonParser.parseString(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + ops + "}"
        ).getAsJsonObject();
    }

    @Test
    void replaceActiveFalseIsDetected() {
        ScimPatchApplier.Result r = ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]" ) );
        assertEquals( Boolean.FALSE, r.activeChange() );
    }

    @Test
    void replaceActiveTrueIsDetected() {
        ScimPatchApplier.Result r = ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]" ) );
        assertEquals( Boolean.TRUE, r.activeChange() );
    }

    @Test
    void replaceWithoutPathButActiveInValueObject() {
        // Entra often sends {op:replace, value:{active:false}}
        ScimPatchApplier.Result r = ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"value\":{\"active\":false}}]" ) );
        assertEquals( Boolean.FALSE, r.activeChange() );
    }

    @Test
    void complexValuePathRejected() {
        assertThrows( ScimPatchApplier.UnsupportedPatchException.class, () -> ScimPatchApplier.apply(
            patch( "[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"x\"}]" ) ) );
    }
}
