package com.wikantik.search.embedding;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresPostgres
class EmbeddingIndexStatsRepositoryTest {

    private DataSource ds;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "content_chunk_embeddings", "kg_content_chunks" );
        ds = PostgresTestDb.createDataSource();
        jdbc = new Jdbc( ds );
    }

    private UUID chunk( final String page, final int idx ) throws Exception {
        final UUID id = UUID.randomUUID();
        jdbc.update( "INSERT INTO kg_content_chunks ( id, page_name, chunk_index, text, char_count, "
                   + "token_count_estimate, content_hash ) VALUES ( ?, ?, ?, 't', 1, 1, 'h' )",
                ps -> { ps.setObject( 1, id ); ps.setString( 2, page ); ps.setInt( 3, idx ); } );
        return id;
    }

    private void embed( final UUID chunkId, final String model ) throws Exception {
        jdbc.update( "INSERT INTO content_chunk_embeddings ( chunk_id, model_code, dim, vec ) VALUES ( ?, ?, 4, ? )",
                ps -> { ps.setObject( 1, chunkId ); ps.setString( 2, model ); ps.setBytes( 3, new byte[ 16 ] ); } );
    }

    @Test
    void emptyTablesGiveZeros() {
        final var s = new EmbeddingIndexStatsRepository( ds ).stats( "m" );
        assertEquals( 0, s.chunksTotal() );
        assertEquals( 0, s.chunksEmbedded() );
        assertEquals( 0, s.pagesEmbedded() );
    }

    @Test
    void countsFilterByModelAndDistinctPages() throws Exception {
        final UUID a0 = chunk( "A", 0 );
        final UUID a1 = chunk( "A", 1 );
        final UUID b0 = chunk( "B", 0 );
        chunk( "C", 0 );
        embed( a0, "m" );
        embed( a1, "m" );
        embed( b0, "other" );

        final var s = new EmbeddingIndexStatsRepository( ds ).stats( "m" );
        assertEquals( 4, s.chunksTotal() );
        assertEquals( 2, s.chunksEmbedded() );
        assertEquals( 1, s.pagesEmbedded() );
    }
}
