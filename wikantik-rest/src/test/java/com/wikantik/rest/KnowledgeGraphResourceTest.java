package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.PostgresTestContainer;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KnowledgeGraphResourceTest {

    private static DataSource dataSource;
    private TestEngine engine;
    private KnowledgeGraphResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        final DefaultKnowledgeGraphService service = DefaultKnowledgeGraphService.builder(
            new KgNodeRepository( dataSource ),
            new KgEdgeRepository( dataSource ),
            new KgProposalRepository( dataSource ),
            new KgRejectionRepository( dataSource ),
            dataSource ).engine( engine ).build();
        engine.setManager( KnowledgeGraphService.class, service );

        servlet = new KnowledgeGraphResource();
        servlet.setEngine( engine );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void doGet_authenticated_returns200WithSnapshot() throws Exception {
        final KnowledgeGraphService svc = engine.getManager( KnowledgeGraphService.class );
        svc.upsertNode( "TestPage", "page", "TestPage",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( createAuthenticatedRequest(), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertFalse( obj.has( "error" ), "Should not be an error: " + sw );
        assertTrue( obj.has( "generatedAt" ) );
        assertEquals( 1, obj.get( "nodeCount" ).getAsInt() );
    }

    @Test
    void doGet_emptyGraph_returns200WithZeroCounts() throws Exception {
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( createAuthenticatedRequest(), response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 0, obj.get( "nodeCount" ).getAsInt() );
        assertEquals( 0, obj.get( "edgeCount" ).getAsInt() );
    }

    @Test
    void doGet_kgDisabled_returns503CitingFlag() throws Exception {
        // Fresh engine WITHOUT a KnowledgeGraphService — the shape
        // wikantik.knowledge.enabled=false produces (factory returns null kgService).
        final TestEngine kgOffEngine = new TestEngine( TestEngine.getTestProperties() );
        try {
            final KnowledgeGraphResource kgOffServlet = new KnowledgeGraphResource();
            kgOffServlet.setEngine( kgOffEngine );

            final StringWriter sw = new StringWriter();
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/knowledge/graph" );
            Mockito.doReturn( null ).when( request ).getPathInfo();
            kgOffServlet.doGet( request, response );

            Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            assertTrue( sw.toString().contains( "wikantik.knowledge.enabled" ),
                    "503 body must cite the flag: " + sw );
        } finally {
            kgOffEngine.stop();
        }
    }

    @Test
    void doGet_anonymous_returns200() throws Exception {
        // D27: knowledge graph reads are now public to match /api/structure/*. The graph
        // contains only canonical ids and relationship types — no ACL-restricted content —
        // so anonymous readers and agents can use it freely.
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/knowledge/graph" );
        Mockito.doReturn( null ).when( request ).getPathInfo();
        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertFalse( obj.has( "error" ), "Anonymous access should be permitted: " + sw );
        assertTrue( obj.has( "nodeCount" ) );
    }

    private HttpServletRequest createAuthenticatedRequest() {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/knowledge/graph" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final Session session = Wiki.session().guest( engine );
        SessionMonitor.getInstance( engine )
                .register( request.getSession(), session );

        final WikiPrincipal admin = new WikiPrincipal( "admin" );
        final AuthenticationManager authMgr = engine.getManager( AuthenticationManager.class );
        authMgr.fireEvent( WikiSecurityEvent.LOGIN_AUTHENTICATED, admin, session );

        return request;
    }
}
