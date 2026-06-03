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
package com.wikantik.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcAuditRepository implements AuditRepository {

    private static final Logger LOG = LogManager.getLogger( JdbcAuditRepository.class );
    private static final long CHAIN_LOCK_KEY = 8423971L;

    private final DataSource dataSource;

    public JdbcAuditRepository( final DataSource dataSource ) { this.dataSource = dataSource; }

    @Override
    public ChainHead chainHead() {
        final String sql = "SELECT seq, row_hash FROM audit_log ORDER BY seq DESC LIMIT 1";
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            if ( rs.next() ) return new ChainHead( rs.getLong( 1 ), rs.getString( 2 ) );
            return new ChainHead( 0L, AuditChainHasher.GENESIS_PREV_HASH );
        } catch ( final java.sql.SQLException e ) {
            throw new IllegalStateException( "audit chainHead read failed", e );
        }
    }

    @Override
    public void append( final List<AuditEntry> entries ) {
        if ( entries.isEmpty() ) return;
        final String insert = "INSERT INTO audit_log ( seq, created_at, event_time, category, "
            + "event_type, actor_id, actor_principal, actor_type, target_type, target_id, "
            + "target_label, outcome, source_ip, user_agent, correlation_id, detail, prev_hash, "
            + "row_hash ) VALUES ( ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CAST(? AS JSONB), ?,? )";
        try ( Connection c = dataSource.getConnection() ) {
            c.setAutoCommit( false );
            try {
                lockChain( c );
                ensurePartition( c, Instant.now() );
                final ChainHead head = chainHeadTx( c );
                long seq = head.lastSeq();
                String prev = head.lastHash();
                final Timestamp now = Timestamp.from( Instant.now() );
                try ( PreparedStatement ps = c.prepareStatement( insert ) ) {
                    for ( final AuditEntry e : entries ) {
                        seq++;
                        final String rowHash = AuditChainHasher.hash( prev, e );
                        int i = 1;
                        ps.setLong( i++, seq );
                        ps.setTimestamp( i++, now );
                        ps.setTimestamp( i++, Timestamp.from( e.eventTime() ) );
                        ps.setString( i++, e.category().name() );
                        ps.setString( i++, e.eventType() );
                        ps.setString( i++, e.actorId() );
                        ps.setString( i++, e.actorPrincipal() );
                        ps.setString( i++, e.actorType() );
                        ps.setString( i++, e.targetType() );
                        ps.setString( i++, e.targetId() );
                        ps.setString( i++, e.targetLabel() );
                        ps.setString( i++, e.outcome().name() );
                        ps.setString( i++, e.sourceIp() );
                        ps.setString( i++, e.userAgent() );
                        ps.setString( i++, e.correlationId() );
                        ps.setString( i++, e.detail() );
                        ps.setString( i++, prev );
                        ps.setString( i++, rowHash );
                        ps.addBatch();
                        prev = rowHash;
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch ( final java.sql.SQLException e ) {
                c.rollback();
                throw new IllegalStateException( "audit append failed", e );
            } finally {
                c.setAutoCommit( true );
            }
        } catch ( final java.sql.SQLException e ) {
            throw new IllegalStateException( "audit append connection failed", e );
        }
    }

    private void lockChain( final Connection c ) throws java.sql.SQLException {
        try ( PreparedStatement ps = c.prepareStatement( "SELECT pg_advisory_xact_lock( ? )" ) ) {
            ps.setLong( 1, CHAIN_LOCK_KEY );
            ps.executeQuery();
        }
    }

    private ChainHead chainHeadTx( final Connection c ) throws java.sql.SQLException {
        try ( PreparedStatement ps = c.prepareStatement(
                "SELECT seq, row_hash FROM audit_log ORDER BY seq DESC LIMIT 1" );
              ResultSet rs = ps.executeQuery() ) {
            if ( rs.next() ) return new ChainHead( rs.getLong( 1 ), rs.getString( 2 ) );
            return new ChainHead( 0L, AuditChainHasher.GENESIS_PREV_HASH );
        }
    }

    /** Defensively creates the monthly partition for the given instant. */
    private void ensurePartition( final Connection c, final Instant when ) throws java.sql.SQLException {
        final ZonedDateTime z = when.atZone( ZoneOffset.UTC );
        final ZonedDateTime start = z.withDayOfMonth( 1 ).toLocalDate().atStartOfDay( ZoneOffset.UTC );
        final ZonedDateTime end = start.plusMonths( 1 );
        final String name = String.format( "audit_log_%04d_%02d", start.getYear(), start.getMonthValue() );
        final String ddl = "CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF audit_log "
            + "FOR VALUES FROM ('" + start.toLocalDate() + "') TO ('" + end.toLocalDate() + "')";
        try ( PreparedStatement ps = c.prepareStatement( ddl ) ) {
            ps.execute();
        }
    }

    @Override
    public List<PersistedAuditEntry> query( final AuditQuery q ) {
        final StringBuilder sql = new StringBuilder(
            "SELECT seq, created_at, event_time, category, event_type, actor_id, actor_principal, "
          + "actor_type, target_type, target_id, target_label, outcome, source_ip, user_agent, "
          + "correlation_id, detail::text, prev_hash, row_hash FROM audit_log WHERE seq < ?" );
        final List<Object> params = new ArrayList<>();
        params.add( q.beforeSeq() );
        if ( q.actorId() != null )   { sql.append( " AND actor_id = ?" );   params.add( q.actorId() ); }
        if ( q.category() != null )  { sql.append( " AND category = ?" );   params.add( q.category().name() ); }
        if ( q.eventType() != null ) { sql.append( " AND event_type = ?" ); params.add( q.eventType() ); }
        if ( q.targetId() != null )  { sql.append( " AND target_id = ?" );  params.add( q.targetId() ); }
        if ( q.outcome() != null )   { sql.append( " AND outcome = ?" );    params.add( q.outcome().name() ); }
        if ( q.from() != null )      { sql.append( " AND created_at >= ?" ); params.add( Timestamp.from( q.from() ) ); }
        if ( q.to() != null )        { sql.append( " AND created_at < ?" );  params.add( Timestamp.from( q.to() ) ); }
        sql.append( " ORDER BY seq DESC LIMIT ?" );
        params.add( q.limit() );

        final List<PersistedAuditEntry> out = new ArrayList<>();
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( mapRow( rs ) );
            }
        } catch ( final java.sql.SQLException e ) {
            throw new IllegalStateException( "audit query failed", e );
        }
        return out;
    }

    @Override
    public Optional<Long> verifyChain( final long fromSeq, final long toSeq ) {
        final String sql = "SELECT seq, event_time, category, event_type, actor_id, actor_principal, "
          + "actor_type, target_type, target_id, target_label, outcome, source_ip, user_agent, "
          + "correlation_id, detail::text, prev_hash, row_hash FROM audit_log "
          + "WHERE seq >= ? AND seq <= ? ORDER BY seq ASC";
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setLong( 1, fromSeq );
            ps.setLong( 2, toSeq );
            String prev = AuditChainHasher.GENESIS_PREV_HASH;
            boolean first = true;
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    if ( first ) { prev = rs.getString( "prev_hash" ); first = false; }
                    final AuditEntry e = mapEntry( rs );
                    final String expected = AuditChainHasher.hash( prev, e );
                    if ( !expected.equals( rs.getString( "row_hash" ) ) ) {
                        return Optional.of( rs.getLong( "seq" ) );
                    }
                    prev = rs.getString( "row_hash" );
                }
            }
        } catch ( final java.sql.SQLException e ) {
            throw new IllegalStateException( "audit verify failed", e );
        }
        return Optional.empty();
    }

    private AuditEntry mapEntry( final ResultSet rs ) throws java.sql.SQLException {
        return AuditEntry.builder()
            .eventTime( rs.getTimestamp( "event_time" ).toInstant() )
            .category( AuditCategory.valueOf( rs.getString( "category" ) ) )
            .eventType( rs.getString( "event_type" ) )
            .actorId( rs.getString( "actor_id" ) )
            .actorPrincipal( rs.getString( "actor_principal" ) )
            .actorType( rs.getString( "actor_type" ) )
            .targetType( rs.getString( "target_type" ) )
            .targetId( rs.getString( "target_id" ) )
            .targetLabel( rs.getString( "target_label" ) )
            .outcome( AuditOutcome.valueOf( rs.getString( "outcome" ) ) )
            .sourceIp( rs.getString( "source_ip" ) )
            .userAgent( rs.getString( "user_agent" ) )
            .correlationId( rs.getString( "correlation_id" ) )
            .detail( rs.getString( "detail" ) )
            .build();
    }

    private PersistedAuditEntry mapRow( final ResultSet rs ) throws java.sql.SQLException {
        return new PersistedAuditEntry( rs.getLong( "seq" ),
            rs.getTimestamp( "created_at" ).toInstant(),
            rs.getString( "prev_hash" ), rs.getString( "row_hash" ), mapEntry( rs ) );
    }
}
