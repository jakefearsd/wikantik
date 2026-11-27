package com.wikantik.auth.validate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NIST 800-63B limits, error precedence and the plain-English descriptions REST clients see. */
class PasswordValidatorPolicyTest {

    @Test
    void describeErrorNamesTheLimitsForEachKey() {
        assertEquals( "Password is too short (minimum " + PasswordValidator.DEFAULT_MIN_LENGTH + " characters)",
                PasswordValidator.describeError( PasswordValidator.KEY_TOO_SHORT ) );
        assertEquals( "Password is too long (maximum " + PasswordValidator.DEFAULT_MAX_LENGTH + " characters)",
                PasswordValidator.describeError( PasswordValidator.KEY_TOO_LONG ) );
        assertEquals( "Password is too common and easily guessed", PasswordValidator.describeError( PasswordValidator.KEY_COMMON ) );
    }

    @Test
    void describeErrorPassesAnUnknownKeyThrough() {
        assertEquals( "some.other.key", PasswordValidator.describeError( "some.other.key" ) );
    }

    @Test
    void lengthIsCheckedBeforeTheBlocklistAndOnlyOneKeyIsReturned() {
        assertEquals( List.of( PasswordValidator.KEY_TOO_SHORT ), PasswordValidator.validate( "password", 12, 128, true ) );
        assertEquals( List.of( PasswordValidator.KEY_TOO_LONG ), PasswordValidator.validate( "password", 1, 7, true ) );
    }

    @Test
    void nullIsTooShortEvenWithNoMinimum() {
        assertEquals( List.of( PasswordValidator.KEY_TOO_SHORT ), PasswordValidator.validate( null, 0, 128, false ) );
    }

    @Test
    void aBlocklistedPasswordWithinLimitsIsFlaggedCommonUnlessTheListIsOff() {
        assertEquals( List.of( PasswordValidator.KEY_COMMON ), PasswordValidator.validate( "password", 8, 128, true ) );
        assertEquals( List.of(), PasswordValidator.validate( "password", 8, 128, false ) );
        assertEquals( List.of( PasswordValidator.KEY_COMMON ), PasswordValidator.validate( "PASSWORD", 8, 128, true ) );
    }

    @Test
    void lengthLimitsAreMeasuredInCharactersAtBothEdges() {
        assertEquals( List.of(), PasswordValidator.validate( "x".repeat( 11 ), 11, 11, false ) );
        assertEquals( List.of( PasswordValidator.KEY_TOO_SHORT ), PasswordValidator.validate( "x".repeat( 10 ), 11, 11, false ) );
        assertEquals( List.of( PasswordValidator.KEY_TOO_LONG ), PasswordValidator.validate( "x".repeat( 12 ), 11, 11, false ) );
    }

    @Test
    void propertiesDriveTheLimitsAndTheBlocklistSwitch() {
        final Properties p = new Properties();
        p.setProperty( PasswordValidator.PROP_MIN_LENGTH, "4" );
        p.setProperty( PasswordValidator.PROP_MAX_LENGTH, "6" );
        p.setProperty( PasswordValidator.PROP_BLOCKLIST_ENABLED, "false" );
        assertEquals( List.of(), PasswordValidator.validate( "abcd", p ) );
        assertEquals( List.of(), PasswordValidator.validate( "abcdef", p ) );
        assertEquals( List.of( PasswordValidator.KEY_TOO_SHORT ), PasswordValidator.validate( "abc", p ) );
        assertEquals( List.of( PasswordValidator.KEY_TOO_LONG ), PasswordValidator.validate( "abcdefg", p ) );
        assertEquals( List.of(), PasswordValidator.validate( "qwerty", p ), "blocklist off" );
        p.setProperty( PasswordValidator.PROP_BLOCKLIST_ENABLED, "true" );
        assertEquals( List.of( PasswordValidator.KEY_COMMON ), PasswordValidator.validate( "qwerty", p ) );
    }

    @Test
    void emptyPropertiesUseTheDocumentedDefaults() {
        assertEquals( List.of( PasswordValidator.KEY_TOO_SHORT ), PasswordValidator.validate( "1234567", new Properties() ) );
        assertEquals( List.of( PasswordValidator.KEY_TOO_LONG ),
                PasswordValidator.validate( "x".repeat( PasswordValidator.DEFAULT_MAX_LENGTH + 1 ), new Properties() ) );
        assertEquals( List.of(), PasswordValidator.validate( "x".repeat( PasswordValidator.DEFAULT_MAX_LENGTH ), new Properties() ) );
    }
}
