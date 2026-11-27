package com.wikantik.auth.authorize;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.security.Principal;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pins WebContainerAuthorizer's role lookup, request/session role checks and constraint matching, on synthetic descriptors. */
class WebContainerAuthorizerRoleTest {

    private static final String NS = "xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"";

    private static String constraint( final String url, final String role ) {
        return "<security-constraint><web-resource-collection><url-pattern>" + url + "</url-pattern></web-resource-collection>"
             + "<auth-constraint><role-name>" + role + "</role-name></auth-constraint></security-constraint>";
    }

    private static WebContainerAuthorizer authorizerFor( final String body ) {
        final String xml = "<web-app " + NS + ">" + body + "</web-app>";
        final WebContainerAuthorizer a = new WebContainerAuthorizer() {
            @Override
            protected Document getWebXml() throws JDOMException, IOException {
                return new SAXBuilder().build( new StringReader( xml ) );
            }
        };
        a.initialize( mock( Engine.class ), new Properties() );
        return a;
    }

    @Test
    void requestRoleCheckDelegatesToTheContainerWithTheRoleName() {
        final WebContainerAuthorizer a = authorizerFor( "" );
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.isUserInRole( "Admin" ) ).thenReturn( true );
        assertTrue( a.isUserInRole( request, new Role( "Admin" ) ) );
        assertFalse( a.isUserInRole( request, new Role( "Other" ) ) );
        verify( request ).isUserInRole( "Other" );
    }

    @Test
    void sessionRoleCheckIsFalseWhenSessionOrRoleIsNull() {
        final WebContainerAuthorizer a = authorizerFor( "" );
        final Session session = mock( Session.class );
        when( session.hasPrincipal( any() ) ).thenReturn( true );
        assertFalse( a.isUserInRole( (Session) null, new Role( "Admin" ) ) );
        assertFalse( a.isUserInRole( session, null ) );
    }

    @Test
    void sessionRoleCheckAsksTheSessionForThatPrincipal() {
        final WebContainerAuthorizer a = authorizerFor( "" );
        final Session session = mock( Session.class );
        final Principal admin = new Role( "Admin" );
        when( session.hasPrincipal( admin ) ).thenReturn( true );
        assertTrue( a.isUserInRole( session, admin ) );
        assertFalse( a.isUserInRole( session, new Role( "Other" ) ) );
    }

    @Test
    void aDescriptorThatConstrainsDeleteAndLoginIsContainerAuthorizedWithItsRoles() {
        final WebContainerAuthorizer a = authorizerFor(
            constraint( "/delete", "Admin" ) + constraint( "/login", "Authenticated" )
          + "<security-role><role-name>Extra</role-name></security-role>" );
        assertTrue( a.isContainerAuthorized() );
        final Principal[] roles = a.getRoles();
        assertEquals( 3, roles.length );
        assertTrue( Arrays.asList( roles ).containsAll(
            Arrays.asList( new Role( "Admin" ), new Role( "Authenticated" ), new Role( "Extra" ) ) ) );
        assertEquals( new Role( "Admin" ), a.findRole( "Admin" ) );
        assertEquals( new Role( "Extra" ), a.findRole( "Extra" ) );
        assertNull( a.findRole( "Nobody" ) );
        assertNull( a.findRole( null ) );
    }

    @Test
    void getRolesReturnsADefensiveCopy() {
        final WebContainerAuthorizer a = authorizerFor( constraint( "/delete", "Admin" ) + constraint( "/login", "Authenticated" ) );
        final Principal[] first = a.getRoles();
        first[ 0 ] = null;
        for ( final Principal p : a.getRoles() ) {
            assertNotNull( p );
        }
    }

    @Test
    void aDescriptorWithoutTheLoginConstraintIsNotContainerAuthorizedAndExposesNoRoles() {
        final WebContainerAuthorizer a = authorizerFor( constraint( "/delete", "Admin" ) );
        assertFalse( a.isContainerAuthorized() );
        assertEquals( 0, a.getRoles().length );
        assertNull( a.findRole( "Admin" ) );
    }

    @Test
    void aDescriptorWithoutTheDeleteConstraintIsNotContainerAuthorized() {
        final WebContainerAuthorizer a = authorizerFor( constraint( "/login", "Authenticated" ) );
        assertFalse( a.isContainerAuthorized() );
    }

    @Test
    void isConstrainedMatchesUrlAndRoleInTheSameConstraint() {
        final WebContainerAuthorizer a = authorizerFor(
            constraint( "/delete", "Admin" ) + constraint( "/login", "Authenticated" ) + constraint( "/other", "Admin" ) );
        assertTrue( a.isConstrained( "/delete", new Role( "Admin" ) ) );
        // the URL is constrained and the role exists, but never together
        assertFalse( a.isConstrained( "/delete", new Role( "Authenticated" ) ) );
        assertFalse( a.isConstrained( "/login", new Role( "Admin" ) ) );
        // unknown URL
        assertFalse( a.isConstrained( "/nowhere", new Role( "Admin" ) ) );
        assertFalse( a.isConstrained( "/nowhere", Role.ALL ) );
        // Role.ALL matches any role on a constrained URL
        assertTrue( a.isConstrained( "/delete", Role.ALL ) );
    }

    @Test
    void isConstrainedIsFalseForARoleNoConstraintNames() {
        final WebContainerAuthorizer a = authorizerFor( constraint( "/delete", "Admin" ) );
        assertFalse( a.isConstrained( "/delete", new Role( "Ghost" ) ) );
    }
}
