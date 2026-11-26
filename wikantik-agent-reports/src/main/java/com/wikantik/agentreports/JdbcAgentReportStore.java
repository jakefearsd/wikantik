package com.wikantik.agentreports;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.setNullableString;

/** PostgreSQL store for agent reports. Insert-only: nothing here updates or deletes a row. */
public final class JdbcAgentReportStore implements AgentReportStore {

    private static final Logger LOG = LogManager.getLogger( JdbcAgentReportStore.class );

    private static final String INSERT_REPORT = """
        INSERT INTO agent_report
            (filed_at, client, session_hash, filed_by, workspace, kind_of_work, products, skills,
             window_start, window_end, task, outcome, context_missing, notes)
        VALUES (COALESCE(?, now()),?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;
    private static final String INSERT_CONTEXT =
        "INSERT INTO agent_report_context (report_id, ordinal, source, ref, helped) VALUES (?,?,?,?,?)";
    private static final String INSERT_FRICTION =
        "INSERT INTO agent_report_friction (report_id, ordinal, product, tool, what, blocking) VALUES (?,?,?,?,?,?)";

    private final Jdbc jdbc;

    public JdbcAgentReportStore( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert( final AgentReport r, final Filer filer ) {
        try {
            return jdbc.inTransaction( conn -> {
                final long id = jdbc.insertReturningKey( conn, INSERT_REPORT, ps -> bindReport( ps, r, filer, conn ),
                    rs -> rs.getLong( "id" ) ).orElseThrow( () -> new SQLException( "no id returned" ) );
                insertContexts( conn, id, r.contextUsed() );
                insertFriction( conn, id, r.friction() );
                return id;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "Could not store agent report from '{}': {}", filer.filedBy(), e.getMessage(), e );
            throw new ReportStoreException( "could not store agent report", e );
        }
    }

    private static void bindReport( final java.sql.PreparedStatement ps, final AgentReport r, final Filer f,
                                    final Connection conn ) throws SQLException {
        ps.setTimestamp( 1, r.filedAt() == null ? null : Timestamp.from( r.filedAt() ) );
        ps.setString( 2, f.client() );
        ps.setString( 3, f.sessionHash() );
        ps.setString( 4, f.filedBy() );
        setNullableString( ps, 5, r.workspace() );
        ps.setString( 6, r.kindOfWork() );
        ps.setArray( 7, conn.createArrayOf( "text", r.products().toArray() ) );
        ps.setArray( 8, conn.createArrayOf( "text", r.skills().toArray() ) );
        ps.setTimestamp( 9, r.windowStart() == null ? null : Timestamp.from( r.windowStart() ) );
        ps.setTimestamp( 10, r.windowEnd() == null ? null : Timestamp.from( r.windowEnd() ) );
        ps.setString( 11, r.task() );
        ps.setString( 12, r.outcome() );
        setNullableString( ps, 13, r.contextMissing() );
        setNullableString( ps, 14, r.notes() );
    }

    private void insertContexts( final Connection conn, final long id, final List< ContextUse > items )
            throws SQLException {
        if ( items.isEmpty() ) {
            return;
        }
        final List< SqlBinder > rows = new ArrayList<>();
        for ( int i = 0; i < items.size(); i++ ) {
            final int ordinal = i;
            final ContextUse c = items.get( i );
            rows.add( ps -> {
                ps.setLong( 1, id );
                ps.setInt( 2, ordinal );
                ps.setString( 3, c.source() );
                ps.setString( 4, c.ref() );
                ps.setString( 5, c.helped() );
            } );
        }
        jdbc.batch( conn, INSERT_CONTEXT, rows );
    }

    private void insertFriction( final Connection conn, final long id, final List< Friction > items )
            throws SQLException {
        if ( items.isEmpty() ) {
            return;
        }
        final List< SqlBinder > rows = new ArrayList<>();
        for ( int i = 0; i < items.size(); i++ ) {
            final int ordinal = i;
            final Friction f = items.get( i );
            rows.add( ps -> {
                ps.setLong( 1, id );
                ps.setInt( 2, ordinal );
                ps.setString( 3, f.product() );
                setNullableString( ps, 4, f.tool() );
                ps.setString( 5, f.what() );
                ps.setBoolean( 6, f.blocking() );
            } );
        }
        jdbc.batch( conn, INSERT_FRICTION, rows );
    }

    @Override
    public List< AgentReport > list( final ReportQuery q ) {
        try {
            final List< AgentReport > reports = jdbc.query( listSql( q ), SqlBinder.positional( listParams( q ) ),
                JdbcAgentReportStore::mapReport );
            if ( reports.isEmpty() ) {
                return reports;
            }
            return attachChildren( reports );
        } catch ( final SQLException e ) {
            LOG.warn( "Could not list agent reports: {}", e.getMessage(), e );
            throw new ReportStoreException( "could not list agent reports", e );
        }
    }

    private static String listSql( final ReportQuery q ) {
        final StringBuilder sql = new StringBuilder( "SELECT * FROM agent_report WHERE 1=1" );
        if ( q.from() != null ) sql.append( " AND filed_at >= ?" );
        if ( q.to() != null ) sql.append( " AND filed_at < ?" );
        if ( q.kindOfWork() != null ) sql.append( " AND kind_of_work = ?" );
        if ( q.product() != null ) sql.append( " AND ? = ANY(products)" );
        if ( q.client() != null ) sql.append( " AND client = ?" );
        if ( q.skill() != null ) sql.append( " AND ? = ANY(skills)" );
        if ( q.outcome() != null ) sql.append( " AND outcome = ?" );
        return sql.append( " ORDER BY filed_at DESC, id DESC LIMIT ?" ).toString();
    }

    private static List< Object > listParams( final ReportQuery q ) {
        final List< Object > p = new ArrayList<>();
        if ( q.from() != null ) p.add( Timestamp.from( q.from() ) );
        if ( q.to() != null ) p.add( Timestamp.from( q.to() ) );
        if ( q.kindOfWork() != null ) p.add( q.kindOfWork() );
        if ( q.product() != null ) p.add( q.product() );
        if ( q.client() != null ) p.add( q.client() );
        if ( q.skill() != null ) p.add( q.skill() );
        if ( q.outcome() != null ) p.add( q.outcome() );
        p.add( q.limit() );
        return p;
    }

    private static AgentReport mapReport( final ResultSet rs ) throws SQLException {
        return new AgentReport( rs.getLong( "id" ), getInstant( rs, "filed_at" ), rs.getString( "client" ),
            rs.getString( "session_hash" ), rs.getString( "filed_by" ), rs.getString( "workspace" ),
            rs.getString( "kind_of_work" ), textArray( rs.getArray( "products" ) ), textArray( rs.getArray( "skills" ) ),
            getInstant( rs, "window_start" ), getInstant( rs, "window_end" ), rs.getString( "task" ),
            rs.getString( "outcome" ), List.of(), rs.getString( "context_missing" ), List.of(), rs.getString( "notes" ) );
    }

    private static List< String > textArray( final Array a ) throws SQLException {
        if ( a == null ) {
            return List.of();
        }
        return List.of( ( String[] ) a.getArray() );
    }

    private List< AgentReport > attachChildren( final List< AgentReport > reports ) throws SQLException {
        final Long[] ids = reports.stream().map( AgentReport::id ).toArray( Long[]::new );
        final Map< Long, List< ContextUse > > contexts = new HashMap<>();
        final Map< Long, List< Friction > > frictions = new HashMap<>();
        jdbc.withConnection( conn -> {
            final SqlBinder binder = ps -> ps.setArray( 1, conn.createArrayOf( "bigint", ids ) );
            jdbc.query( conn, "SELECT * FROM agent_report_context WHERE report_id = ANY(?) ORDER BY report_id, ordinal",
                binder, rs -> contexts.computeIfAbsent( rs.getLong( "report_id" ), k -> new ArrayList<>() )
                    .add( new ContextUse( rs.getString( "source" ), rs.getString( "ref" ), rs.getString( "helped" ) ) ) );
            jdbc.query( conn, "SELECT * FROM agent_report_friction WHERE report_id = ANY(?) ORDER BY report_id, ordinal",
                binder, rs -> frictions.computeIfAbsent( rs.getLong( "report_id" ), k -> new ArrayList<>() )
                    .add( new Friction( rs.getString( "product" ), rs.getString( "tool" ), rs.getString( "what" ),
                        rs.getBoolean( "blocking" ) ) ) );
            return null;
        } );
        final List< AgentReport > out = new ArrayList<>();
        for ( final AgentReport r : reports ) {
            out.add( new AgentReport( r.id(), r.filedAt(), r.client(), r.sessionHash(), r.filedBy(), r.workspace(),
                r.kindOfWork(), r.products(), r.skills(), r.windowStart(), r.windowEnd(), r.task(), r.outcome(),
                List.copyOf( contexts.getOrDefault( r.id(), List.of() ) ), r.contextMissing(),
                List.copyOf( frictions.getOrDefault( r.id(), List.of() ) ), r.notes() ) );
        }
        return out;
    }
}
