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

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.NullableColumns;
import com.wikantik.jdbc.RowMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.setNullableLong;

/** The PostgreSQL {@link WorkAttachmentStore}. Bytes are bound with {@code setBytes} and read with {@code getBytes}. */
final class JdbcWorkAttachments implements WorkAttachmentStore {

    private static final String COLUMNS = "id, item_id, comment_id, filename, content_type, size_bytes, sha256, "
        + "uploaded_by, uploaded_by_agent_id, uploaded_at, removed_at";
    private static final String A_COLUMNS = "a.id, a.item_id, a.comment_id, a.filename, a.content_type, a.size_bytes, "
        + "a.sha256, a.uploaded_by, a.uploaded_by_agent_id, a.uploaded_at, a.removed_at";

    /**
     * Excludes a file whose comment has been removed: {@code removeComment} only stamps the comment, and the file then
     * stops being reachable (the row and bytes stay). {@code %s} is the attachment's alias.
     */
    private static final String COMMENT_LIVE = " AND NOT EXISTS ( SELECT 1 FROM work_comment rc WHERE rc.id = %s.comment_id "
        + "AND rc.removed_at IS NOT NULL )";

    private static final RowMapper< Attachment > ROW = rs -> new Attachment( rs.getLong( "id" ), rs.getLong( "item_id" ),
        NullableColumns.getNullableLong( rs, "comment_id" ), rs.getString( "filename" ), rs.getString( "content_type" ),
        rs.getLong( "size_bytes" ), rs.getString( "sha256" ), rs.getString( "uploaded_by" ),
        NullableColumns.getNullableLong( rs, "uploaded_by_agent_id" ), getInstant( rs, "uploaded_at" ),
        getInstant( rs, "removed_at" ) );

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkAttachments( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override
    public Attachment add( final Item item, final Long commentId, final NewAttachment file, final long maxItemBytes,
                           final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            lockItem( conn, item, by );
            if ( commentId != null ) {
                requireComment( conn, item, commentId );
            }
            final long live = jdbc.queryOne( conn, "SELECT COALESCE(SUM(a.size_bytes), 0) FROM work_attachment a "
                    + "WHERE a.item_id = ? AND a.removed_at IS NULL" + COMMENT_LIVE.formatted( "a" ), ps -> ps.setLong( 1, item.id() ),
                rs -> rs.getLong( 1 ) ).orElse( 0L );
            if ( live + file.content().length > maxItemBytes ) {
                throw new WorkConflictException( "This item's attachments would pass the limit of " + maxItemBytes
                    + " bytes.", WorkConflictException.ITEM_FULL );
            }
            final Attachment added = jdbc.queryOne( conn, "INSERT INTO work_attachment (item_id, comment_id, filename, "
                    + "content_type, size_bytes, sha256, uploaded_by, uploaded_by_agent_id) VALUES (?,?,?,?,?,?,?,?) "
                    + "RETURNING " + COLUMNS, ps -> {
                        ps.setLong( 1, item.id() );
                        setNullableLong( ps, 2, commentId );
                        ps.setString( 3, file.filename() );
                        ps.setString( 4, file.contentType() );
                        ps.setLong( 5, file.content().length );
                        ps.setString( 6, file.sha256() );
                        ps.setString( 7, by.login() );
                        setNullableLong( ps, 8, by.agentId() );
                    }, ROW ).orElseThrow( () -> new SQLException( "attachment insert returned no row" ) );
            jdbc.update( conn, "INSERT INTO work_attachment_blob (attachment_id, content) VALUES (?,?)", ps -> {
                ps.setLong( 1, added.id() );
                ps.setBytes( 2, file.content() );
            } );
            event( conn, item, by, WorkEvent.ATTACHED, added, commentId == null ? null : "comment:" + commentId );
            return added;
        } );
    }

    @Override
    public Optional< Attachment > attachment( final long itemId, final long attachmentId ) throws SQLException {
        return jdbc.queryOne( "SELECT " + A_COLUMNS + " FROM work_attachment a WHERE a.id = ? AND a.item_id = ? "
            + "AND a.removed_at IS NULL" + COMMENT_LIVE.formatted( "a" ), ps -> {
                ps.setLong( 1, attachmentId );
                ps.setLong( 2, itemId );
            }, ROW );
    }

    @Override
    public Optional< AttachmentContent > content( final long itemId, final long attachmentId ) throws SQLException {
        return jdbc.queryOne( "SELECT " + A_COLUMNS + ", b.content FROM work_attachment a JOIN work_attachment_blob b "
            + "ON b.attachment_id = a.id WHERE a.id = ? AND a.item_id = ? AND a.removed_at IS NULL" + COMMENT_LIVE.formatted( "a" ), ps -> {
                ps.setLong( 1, attachmentId );
                ps.setLong( 2, itemId );
            }, rs -> new AttachmentContent( ROW.map( rs ), rs.getBytes( "content" ) ) );
    }

    @Override
    public Attachment remove( final Item item, final long attachmentId, final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            lockItem( conn, item, by );
            final Attachment gone = jdbc.queryOne( conn, "UPDATE work_attachment a SET removed_at = NOW() WHERE a.id = ? "
                    + "AND a.item_id = ? AND a.removed_at IS NULL" + COMMENT_LIVE.formatted( "a" ) + " RETURNING " + COLUMNS, ps -> {
                        ps.setLong( 1, attachmentId );
                        ps.setLong( 2, item.id() );
                    }, ROW ).orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
            jdbc.update( conn, "DELETE FROM work_attachment_blob WHERE attachment_id = ?",
                ps -> ps.setLong( 1, attachmentId ) );
            event( conn, item, by, WorkEvent.ATTACHMENT_REMOVED, gone, null );
            return gone;
        } );
    }

    @Override
    public List< Attachment > forItem( final long itemId ) throws SQLException {
        return jdbc.query( "SELECT " + A_COLUMNS + " FROM work_attachment a WHERE a.item_id = ? AND a.removed_at IS NULL"
            + COMMENT_LIVE.formatted( "a" ) + " ORDER BY a.id", ps -> ps.setLong( 1, itemId ), ROW );
    }

    /**
     * Locks the item row {@code FOR NO KEY UPDATE}: uploads to one item queue here (so the size check and the insert
     * cannot interleave), it does not block the attachment's own foreign key check, and an agent is fenced when
     * another agent holds the item.
     */
    private void lockItem( final Connection conn, final Item item, final Actor by ) throws SQLException {
        final Long holder = jdbc.queryOne( conn,
            "SELECT claimed_by_agent FROM work_item WHERE id = ? FOR NO KEY UPDATE", ps -> ps.setLong( 1, item.id() ),
            rs -> new Long[] { NullableColumns.getNullableLong( rs, "claimed_by_agent" ) } )
            .orElseThrow( () -> new SQLException( "work_item " + item.id() + " not found" ) )[ 0 ];
        if ( by.kind() == Actor.Kind.AGENT && holder != null && !holder.equals( by.agentId() ) ) {
            throw new ClaimFenceException();
        }
    }

    private void requireComment( final Connection conn, final Item item, final long commentId ) throws SQLException {
        final boolean live = jdbc.queryOne( conn, "SELECT 1 FROM work_comment WHERE id = ? AND item_id = ? "
            + "AND removed_at IS NULL", ps -> {
                ps.setLong( 1, commentId );
                ps.setLong( 2, item.id() );
            }, rs -> Boolean.TRUE ).isPresent();
        if ( !live ) {
            throw new WorkValidationException( "comment", "That comment is not on this item." );
        }
    }

    private void event( final Connection conn, final Item item, final Actor by, final String type,
                        final Attachment a, final String note ) throws SQLException {
        catalog.appendEvent( conn, new WorkEvent( 0, item.projectId(), item.id(), null, null, by.login(), by.kind(),
            by.agentId(), type, "attachment:" + a.id(), a.filename(), note ) );
    }
}
