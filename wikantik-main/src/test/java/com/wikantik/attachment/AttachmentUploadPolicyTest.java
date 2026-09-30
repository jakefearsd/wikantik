package com.wikantik.attachment;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentUploadPolicyTest {

    @Test
    void emptyPolicyAllowsEverything() {
        final AttachmentUploadPolicy p = AttachmentUploadPolicy.fromProperties( new Properties() );
        assertTrue( p.isTypeAllowed( "a.exe" ) );
        assertTrue( p.isSizeAllowed( Integer.MAX_VALUE ) );
    }

    @Test
    void blankPropertiesMeanNoRestriction() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.attachment.maxsize", "" );
        props.setProperty( "wikantik.attachment.allowed", "" );
        props.setProperty( "wikantik.attachment.forbidden", "" );
        final AttachmentUploadPolicy p = AttachmentUploadPolicy.fromProperties( props );
        assertTrue( p.isTypeAllowed( "a.exe" ) );
        assertTrue( p.isSizeAllowed( 10_000_000L ) );
    }

    @Test
    void forbiddenSuffixRejectsCaseInsensitively() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.attachment.forbidden", ".exe .BAT" );
        final AttachmentUploadPolicy p = AttachmentUploadPolicy.fromProperties( props );
        assertFalse( p.isTypeAllowed( "setup.EXE" ) );
        assertFalse( p.isTypeAllowed( "run.bat" ) );
        assertTrue( p.isTypeAllowed( "notes.txt" ) );
    }

    @Test
    void allowListRestrictsAndForbiddenWins() {
        final AttachmentUploadPolicy p = new AttachmentUploadPolicy(
                new String[]{ ".png", ".pdf" }, new String[]{ ".pdf" }, 100 );
        assertTrue( p.isTypeAllowed( "a.png" ) );
        assertFalse( p.isTypeAllowed( "a.pdf" ), "forbidden wins over allowed" );
        assertFalse( p.isTypeAllowed( "a.txt" ), "not on the allow list" );
        assertFalse( p.isTypeAllowed( "" ) );
        assertFalse( p.isTypeAllowed( null ) );
    }

    @Test
    void sizeLimitIsInclusive() {
        final AttachmentUploadPolicy p = new AttachmentUploadPolicy( null, null, 100 );
        assertTrue( p.isSizeAllowed( 100 ) );
        assertFalse( p.isSizeAllowed( 101 ) );
        assertEquals( 100, p.maxSize() );
    }
}
