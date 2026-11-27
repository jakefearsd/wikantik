package com.wikantik.auth;

import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.AdminPermission;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a policy_grants row's permission_type / target / actions become a Permission. */
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class DatabasePolicyPermissionTypesTest {

    private static final Role R = new Role( "Probe" );

    private DataSource ds;

    @BeforeAll
    void connect() {
        ds = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void clear() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DELETE FROM policy_grants" );
        }
    }

    private DatabasePolicy withGrant( final String principalType, final String permType, final String target, final String actions )
            throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) VALUES (?, 'Probe', ?, ?, ?)" ) ) {
            ps.setString( 1, principalType );
            ps.setString( 2, permType );
            ps.setString( 3, target );
            ps.setString( 4, actions );
            ps.executeUpdate();
        }
        return new DatabasePolicy( ds, "policy_grants" );
    }

    @Test
    void pageGrantWithABareTargetAppliesToThatPageInEveryWikiOnly() throws Exception {
        final DatabasePolicy p = withGrant( "role", "page", "Main", "view" );
        assertTrue( p.implies( R, new PagePermission( "any:Main", "view" ) ) );
        assertFalse( p.implies( R, new PagePermission( "any:Other", "view" ) ) );
        assertFalse( p.implies( R, new PagePermission( "any:Main", "edit" ) ), "only view was granted" );
    }

    @Test
    void pageGrantWithAWikiQualifiedTargetIsKeptAsGiven() throws Exception {
        final DatabasePolicy p = withGrant( "role", "page", "wikiA:Main", "view" );
        assertTrue( p.implies( R, new PagePermission( "wikiA:Main", "view" ) ) );
        assertFalse( p.implies( R, new PagePermission( "wikiB:Main", "view" ) ), "a qualified target is not widened to every wiki" );
    }

    @Test
    void permissionTypeIsCaseInsensitive() throws Exception {
        assertTrue( withGrant( "role", "PAGE", "Main", "view" ).implies( R, new PagePermission( "w:Main", "view" ) ) );
        clear();
        assertTrue( withGrant( "role", "Wiki", "*", "createPages" ).implies( R, WikiPermission.CREATE_PAGES ) );
        clear();
        assertTrue( withGrant( "role", "ALL", "*", "*" ).implies( R, new AllPermission( "*" ) ) );
    }

    @Test
    void wikiGrantConfersOnlyTheListedWikiActions() throws Exception {
        final DatabasePolicy p = withGrant( "role", "wiki", "*", "createPages" );
        assertTrue( p.implies( R, WikiPermission.CREATE_PAGES ) );
        assertFalse( p.implies( R, WikiPermission.CREATE_GROUPS ) );
    }

    @Test
    void groupGrantWithABareTargetIsQualifiedToEveryWiki() throws Exception {
        final DatabasePolicy p = withGrant( "role", "group", "Editors", "view" );
        assertTrue( p.implies( R, new GroupPermission( "any:Editors", "view" ) ) );
        assertFalse( p.implies( R, new GroupPermission( "any:Other", "view" ) ) );
    }

    @Test
    void adminGrantScopesToOneAreaAndNeverToAnother() throws Exception {
        final DatabasePolicy p = withGrant( "role", "admin", "insights", "view" );
        assertTrue( p.implies( R, new AdminPermission( "*:insights", "view" ) ) );
        assertFalse( p.implies( R, new AdminPermission( "*:security", "view" ) ) );
    }

    @Test
    void allTypeIsScopedToTheStoredWiki() throws Exception {
        final DatabasePolicy p = withGrant( "role", "all", "wikiA", "*" );
        assertTrue( p.implies( R, new AllPermission( "wikiA" ) ) );
        assertFalse( p.implies( R, new AllPermission( "wikiB" ) ), "a scoped 'all' grant is not global" );
    }

    @Test
    void anUnknownPermissionTypeGrantsNothing() throws Exception {
        final DatabasePolicy p = withGrant( "role", "bogus", "*", "view" );
        assertFalse( p.implies( R, new PagePermission( "w:Main", "view" ) ) );
        assertFalse( p.implies( R, WikiPermission.CREATE_PAGES ) );
    }

    @Test
    void principalTypeIsTrimmedAndCaseInsensitive() throws Exception {
        assertTrue( withGrant( " ROLE ", "wiki", "*", "createPages" ).implies( R, WikiPermission.CREATE_PAGES ) );
        clear();
        assertTrue( withGrant( "User", "wiki", "*", "createPages" )
                .implies( new WikiPrincipal( "Probe", WikiPrincipal.LOGIN_NAME ), WikiPermission.CREATE_PAGES ) );
    }

    @Test
    void aWildcardActionOnASpecificTargetOfAnyTypeGrantsNothing() throws Exception {
        final DatabasePolicy p = withGrant( "role", "wiki", "special", "*" );
        assertFalse( p.implies( R, new AllPermission( "special" ) ) );
        assertFalse( p.implies( R, WikiPermission.CREATE_PAGES ) );
    }
}
