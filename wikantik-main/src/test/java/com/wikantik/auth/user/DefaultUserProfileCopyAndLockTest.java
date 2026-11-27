package com.wikantik.auth.user;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/** Pins DefaultUserProfile.copyOf, equals/hashCode and lock-expiry behaviour. */
class DefaultUserProfileCopyAndLockTest {

    private static DefaultUserProfile full() {
        final DefaultUserProfile p = new DefaultUserProfile();
        p.setUid( "uid-1" );
        p.setLoginName( "jdoe" );
        p.setFullname( "John Doe" );
        p.setWikiName( "JohnDoe2" );
        p.setEmail( "John@Example.com" );
        p.setPassword( "{SHA-256}abc" );
        p.setBio( "a bio" );
        p.setCreated( new Date( 1_000L ) );
        p.setLastModified( new Date( 2_000L ) );
        p.setLastLogin( new Date( 3_000L ) );
        p.setLockExpiry( new Date( System.currentTimeMillis() + 3_600_000L ) );
        p.setPasswordMustChange( true );
        p.getAttributes().put( "k", "v" );
        return p;
    }

    @Test
    void copyOfCarriesEveryField() {
        final DefaultUserProfile src = full();
        final DefaultUserProfile c = DefaultUserProfile.copyOf( src );
        assertAll(
            () -> assertEquals( "uid-1", c.getUid() ),
            () -> assertEquals( "jdoe", c.getLoginName() ),
            () -> assertEquals( "John Doe", c.getFullname() ),
            () -> assertEquals( "JohnDoe2", c.getWikiName() ),
            () -> assertEquals( "John@Example.com", c.getEmail() ),
            () -> assertEquals( "{SHA-256}abc", c.getPassword() ),
            () -> assertEquals( "a bio", c.getBio() ),
            () -> assertEquals( new Date( 1_000L ), c.getCreated() ),
            () -> assertEquals( new Date( 2_000L ), c.getLastModified() ),
            () -> assertEquals( new Date( 3_000L ), c.getLastLogin() ),
            () -> assertEquals( src.getLockExpiry(), c.getLockExpiry() ),
            () -> assertTrue( c.isPasswordMustChange() ),
            () -> assertEquals( "v", c.getAttributes().get( "k" ) ) );
    }

    @Test
    void copyOfDoesNotShareDatesOrAttributes() {
        final DefaultUserProfile src = full();
        final DefaultUserProfile c = DefaultUserProfile.copyOf( src );
        assertNotSame( src.getCreated(), c.getCreated() );
        assertNotSame( src.getLastModified(), c.getLastModified() );
        assertNotSame( src.getLastLogin(), c.getLastLogin() );
        c.getAttributes().put( "other", (Serializable) "x" );
        assertFalse( src.getAttributes().containsKey( "other" ) );
        c.getCreated().setTime( 99L );
        assertEquals( new Date( 1_000L ), src.getCreated() );
    }

    @Test
    void copyOfKeepsNullDatesNull() {
        final DefaultUserProfile src = new DefaultUserProfile();
        src.setLoginName( "x" );
        final DefaultUserProfile c = DefaultUserProfile.copyOf( src );
        assertNull( c.getCreated() );
        assertNull( c.getLastModified() );
        assertNull( c.getLastLogin() );
        assertNull( c.getLockExpiry() );
        assertFalse( c.isPasswordMustChange() );
        assertTrue( c.getAttributes().isEmpty() );
    }

    @Test
    void copyOfIsEqualToItsSourceAndHashesTheSame() {
        final DefaultUserProfile src = full();
        final DefaultUserProfile c = DefaultUserProfile.copyOf( src );
        assertEquals( src, c );
        assertEquals( src.hashCode(), c.hashCode() );
    }

    @Test
    void hashCodeDependsOnEveryEqualityField() {
        final DefaultUserProfile base = full();
        final int h = base.hashCode();

        DefaultUserProfile o = DefaultUserProfile.copyOf( base ); o.setLoginName( "other" );
        assertNotEquals( h, o.hashCode() );
        o = DefaultUserProfile.copyOf( base ); o.setPassword( "different" );
        assertNotEquals( h, o.hashCode() );
        o = DefaultUserProfile.copyOf( base ); o.setWikiName( "Other" );
        assertNotEquals( h, o.hashCode() );
        o = DefaultUserProfile.copyOf( base ); o.setEmail( "zzz@example.com" );
        assertNotEquals( h, o.hashCode() );
        o = DefaultUserProfile.copyOf( base ); o.setFullname( "Someone Else" ); o.setWikiName( "JohnDoe2" );
        assertNotEquals( h, o.hashCode() );
    }

    @Test
    void hashCodeIsEmailCaseInsensitiveAndNullSafe() {
        final DefaultUserProfile a = full();
        final DefaultUserProfile b = DefaultUserProfile.copyOf( a );
        b.setEmail( "JOHN@EXAMPLE.COM" );
        assertEquals( a, b );
        assertEquals( a.hashCode(), b.hashCode() );
        assertEquals( 0, new DefaultUserProfile().hashCode() );
    }

    @Test
    void anyDifferingFieldMakesProfilesUnequal() {
        final DefaultUserProfile base = full();
        DefaultUserProfile o = DefaultUserProfile.copyOf( base ); o.setEmail( null );
        assertNotEquals( base, o );
        assertNotEquals( o, base );
        o = DefaultUserProfile.copyOf( base ); o.setLoginName( null );
        assertNotEquals( base, o );
        assertNotEquals( o, base );
        assertEquals( new DefaultUserProfile(), new DefaultUserProfile() );
        assertNotEquals( base, "a string" );
    }

    @Test
    void anExpiredLockReadsAsUnlockedAndIsCleared() {
        final DefaultUserProfile p = new DefaultUserProfile();
        p.setLockExpiry( new Date( System.currentTimeMillis() - 1_000L ) );
        assertFalse( p.isLocked() );
        assertNull( p.getLockExpiry() );
    }

    @Test
    void aFutureLockReadsAsLocked() {
        final DefaultUserProfile p = new DefaultUserProfile();
        final Date until = new Date( System.currentTimeMillis() + 60_000L );
        p.setLockExpiry( until );
        assertTrue( p.isLocked() );
        assertEquals( until, p.getLockExpiry() );
    }

    @Test
    void noLockMeansUnlocked() {
        final DefaultUserProfile p = new DefaultUserProfile();
        assertFalse( p.isLocked() );
        assertNull( p.getLockExpiry() );
    }
}
