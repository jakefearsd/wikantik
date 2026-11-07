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
package com.wikantik.collab.adapter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.wikantik.collab.model.Contribution;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.ChangeSetJson;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Column lists, JSON codec and row mappers of {@link JdbcCollabStore}'s tables. */
final class CollabRows {

    private CollabRows() {
    }

    static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();
    /** The one meta codec (A14), shared with the session's comparisons. */
    static final Gson GSON = com.wikantik.collab.MetaJson.GSON;

    static final String SESSION_COLUMNS = "id, page_name, status, base_page_version, base_body, base_meta::text,"
        + " base_content_hash, head_version, meta_seq, has_unpublished, pending_publish_version,"
        + " pending_publish_base_version, pending_publish_author, created_by, created_at, last_edit_at,"
        + " pending_publish_saved_version, pending_publish_canonical_id, pending_publish_saved_hash";

    static void setInteger( final PreparedStatement ps, final int i, final Integer v ) throws SQLException {
        if( v == null ) {
            ps.setNull( i, Types.INTEGER );
        } else {
            ps.setInt( i, v );
        }
    }

    static void setInstant( final PreparedStatement ps, final int i, final Instant v ) throws SQLException {
        if( v == null ) {
            ps.setNull( i, Types.TIMESTAMP_WITH_TIMEZONE );
        } else {
            ps.setTimestamp( i, Timestamp.from( v ) );
        }
    }

    static Integer getInteger( final ResultSet rs, final int i ) throws SQLException {
        final int v = rs.getInt( i );
        return rs.wasNull() ? null : v;
    }

    static Map< String, Object > parseMeta( final String json ) {
        return GSON.fromJson( json, MAP_TYPE );
    }

    static ChangeSet parseChanges( final String json ) {
        return ChangeSetJson.fromJson( JsonParser.parseString( json ).getAsJsonArray() );
    }

    static SessionRecord mapSession( final ResultSet rs ) throws SQLException {
        final Timestamp lastEdit = rs.getTimestamp( 16 );
        return new SessionRecord( rs.getObject( 1, UUID.class ), rs.getString( 2 ), rs.getString( 3 ), rs.getInt( 4 ),
            rs.getString( 5 ), parseMeta( rs.getString( 6 ) ), rs.getString( 7 ), rs.getInt( 8 ), rs.getLong( 9 ),
            rs.getBoolean( 10 ), getInteger( rs, 11 ), getInteger( rs, 12 ), rs.getString( 13 ), getInteger( rs, 17 ),
            rs.getString( 18 ), rs.getString( 19 ), rs.getString( 14 ), rs.getTimestamp( 15 ).toInstant(),
            lastEdit == null ? null : lastEdit.toInstant() );
    }

    static StoredUpdate mapUpdate( final ResultSet rs ) throws SQLException {
        return new StoredUpdate( rs.getInt( 1 ), rs.getString( 2 ), rs.getString( 3 ), rs.getString( 4 ),
            parseChanges( rs.getString( 5 ) ) );
    }

    /** The columns {@link #mapSnapshot} reads, in order. */
    static final String SNAPSHOT_COLUMNS = "version, meta_seq, base_page_version, body, meta::text, local_changes::text,"
        + " contributors::text";

    static Snapshot mapSnapshot( final ResultSet rs ) throws SQLException {
        return new Snapshot( rs.getInt( 1 ), rs.getLong( 2 ), rs.getInt( 3 ), rs.getString( 4 ),
            parseMeta( rs.getString( 5 ) ), parseChanges( rs.getString( 6 ) ), parseContributors( rs.getString( 7 ) ) );
    }

    // ---- collab_snapshot.contributors (V067) ---------------------------------------------------------------
    // An array in first-contribution order, because JSONB does not keep object key order:
    // [{"a":"<authorKey>","v":[first,last],"m":[first,last]}], [0,0] meaning "none of this kind".

    static String contributorsToJson( final Map< String, Contribution > contributors ) {
        final JsonArray out = new JsonArray();
        contributors.forEach( ( key, c ) -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "a", key );
            o.add( "v", pair( c.firstVersion(), c.lastVersion() ) );
            o.add( "m", pair( c.firstMetaSeq(), c.lastMetaSeq() ) );
            out.add( o );
        } );
        return out.toString();
    }

    private static JsonArray pair( final long first, final long last ) {
        final JsonArray a = new JsonArray();
        a.add( first );
        a.add( last );
        return a;
    }

    static Map< String, Contribution > parseContributors( final String json ) {
        final Map< String, Contribution > out = new LinkedHashMap<>();
        for( final JsonElement e : JsonParser.parseString( json ).getAsJsonArray() ) {
            final JsonObject o = e.getAsJsonObject();
            final JsonArray v = o.getAsJsonArray( "v" );
            final JsonArray m = o.getAsJsonArray( "m" );
            out.put( o.get( "a" ).getAsString(), new Contribution(
                v == null ? Contribution.NONE : v.get( 0 ).getAsInt(), v == null ? Contribution.NONE : v.get( 1 ).getAsInt(),
                m == null ? Contribution.NONE : m.get( 0 ).getAsLong(), m == null ? Contribution.NONE : m.get( 1 ).getAsLong() ) );
        }
        return out;
    }
}
