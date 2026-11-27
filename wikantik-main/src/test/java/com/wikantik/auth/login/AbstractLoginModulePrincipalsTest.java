package com.wikantik.auth.login;

import com.wikantik.auth.WikiPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import java.security.Principal;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Pins how AbstractLoginModule adds and clears principals across commit / abort / logout. */
class AbstractLoginModulePrincipalsTest {

    private static final Principal ALICE = new WikiPrincipal( "alice" );

    /** Logs in by adding ALICE unless told not to. */
    private static final class Module extends AbstractLoginModule {
        boolean addPrincipal = true;
        @Override
        public boolean login() throws LoginException {
            if ( addPrincipal ) {
                principals.add( ALICE );
            }
            return addPrincipal;
        }
    }

    private Subject subject;
    private Module module;

    @BeforeEach
    void setUp() throws LoginException {
        subject = new Subject();
        module = new Module();
        module.initialize( subject, callbacks -> { }, new HashMap<>(), new HashMap<>() );
        module.login();
    }

    @Test
    void commitAddsThePrincipalToTheSubject() {
        assertTrue( module.commit() );
        assertTrue( subject.getPrincipals().contains( ALICE ) );
    }

    @Test
    void commitWithoutASuccessfulLoginReportsFalseAndAddsNothing() throws LoginException {
        final Subject s = new Subject();
        final Module m = new Module();
        m.addPrincipal = false;
        m.initialize( s, callbacks -> { }, new HashMap<>(), new HashMap<>() );
        m.login();
        assertFalse( m.commit() );
        assertTrue( s.getPrincipals().isEmpty() );
    }

    @Test
    void abortRemovesThePrincipalsAndForgetsThem() {
        subject.getPrincipals().add( ALICE );
        assertTrue( module.abort() );
        assertFalse( subject.getPrincipals().contains( ALICE ) );
        // principals were forgotten: a later commit has nothing to add and reports failure
        assertFalse( module.commit() );
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    @Test
    void logoutRemovesThePrincipalsAndForgetsThem() {
        module.commit();
        assertTrue( module.logout() );
        assertFalse( subject.getPrincipals().contains( ALICE ) );
        assertFalse( module.commit() );
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    @Test
    void abortLeavesUnrelatedPrincipalsAlone() {
        final Principal other = new WikiPrincipal( "bob" );
        subject.getPrincipals().add( other );
        subject.getPrincipals().add( ALICE );
        module.abort();
        assertTrue( subject.getPrincipals().contains( other ) );
        assertFalse( subject.getPrincipals().contains( ALICE ) );
    }

    @Test
    void aFailedCommitForgetsPrincipalsAndDoesNotTouchAnEmptyModuleSubject() throws LoginException {
        // commit after a failed login must clear the module's own set so a later commit cannot resurrect it
        final Subject s = new Subject();
        final Module m = new Module();
        m.initialize( s, callbacks -> { }, new HashMap<>(), new HashMap<>() );
        m.login();
        m.abort();
        m.addPrincipal = false;
        m.login();
        assertFalse( m.commit() );
        assertTrue( s.getPrincipals().isEmpty() );
    }
}
