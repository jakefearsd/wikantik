package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.user.DummyUserDatabase;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Boundaries of the display-name and wiki-name rules, run against a mocked user store. */
class ProfileNameRulesBoundaryTest {

    private Engine engine;
    private UserDatabase db;

    @BeforeEach
    void setUp() {
        engine = mock( Engine.class );
        db = mock( UserDatabase.class );
    }

    private static UserProfile accountWithLogin( final String login ) {
        final UserProfile p = new DummyUserDatabase().newProfile();
        p.setLoginName( login );
        return p;
    }

    private Optional< ProfileNameRules.NameViolation > violation( final String candidate ) {
        return ProfileNameRules.displayNameViolation( engine, db, "me", candidate );
    }

    // ---- shape

    @Test
    void anOrdinaryNameIsAccepted() {
        assertTrue( violation( "Ada Lovelace" ).isEmpty() );
    }

    @Test
    void lengthBoundariesAreInclusive() {
        assertTrue( violation( "ab" ).isEmpty(), "minimum" );
        assertTrue( violation( "a" ).isPresent(), "minimum - 1" );
        assertTrue( violation( "a".repeat( ProfileNameRules.DISPLAY_NAME_MAX ) ).isEmpty(), "maximum" );
        assertTrue( violation( "a".repeat( ProfileNameRules.DISPLAY_NAME_MAX + 1 ) ).isPresent(), "maximum + 1" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "   ", "  ", " ", " " } )
    void blankInAnyUnicodeSenseIsRequiredError( final String blank ) {
        final var v = violation( blank ).orElseThrow();
        assertFalse( v.isConflict() );
        assertTrue( v.message().contains( "required" ), v.message() );
    }

    @Test
    void nullIsRequiredError() {
        assertTrue( violation( null ).orElseThrow().message().contains( "required" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "Ad​am", "Ad‮am", "Ad﻿am", "Adam", "Ad͸am", "Ad am" } )
    void invisibleFormattingPrivateUseUnassignedAndOddSpacesAreRefused( final String name ) {
        final var v = violation( name ).orElseThrow();
        assertFalse( v.isConflict() );
        assertTrue( v.message().contains( "invisible" ), v.message() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "Ad\tam", "Ad\nam", "Ad\u0000am", "Ad am", "Ad am" } )
    void controlAndLineBreakCharactersAreRefused( final String name ) {
        assertTrue( violation( name ).orElseThrow().message().contains( "control" ) );
    }

    @Test
    void anAtSignIsRefused() {
        assertTrue( violation( "ada@example.com" ).orElseThrow().message().contains( "@" ) );
    }

    @Test
    void ordinaryNonAsciiLettersAndNoBreakSpaceInsideAreFine() {
        assertTrue( violation( "Zoë Muñoz" ).isEmpty() );
        assertTrue( violation( "Ada Lovelace" ).isEmpty(), "NBSP folds to a plain space" );
    }

    // ---- reserved, login clash, in use

    @ParameterizedTest
    @ValueSource( strings = { "admin", "Admin", "ALL", "Anonymous", "asserted", "Authenticated", "Ad min" } )
    void reservedNamesAreInvalidNotConflicts( final String name ) {
        final var v = violation( name );
        if ( name.equals( "Ad min" ) ) {
            assertTrue( v.isPresent(), "whitespace-stripped form is reserved too" );
        }
        assertTrue( v.isPresent() );
        assertFalse( v.get().isConflict() );
        assertTrue( v.get().message().contains( "reserved" ), v.get().message() );
    }

    @Test
    void anotherAccountsLoginIsATakenConflict() throws Exception {
        when( db.findByLoginName( "Bob" ) ).thenReturn( accountWithLogin( "Bob" ) );
        final var v = violation( "Bob" ).orElseThrow();
        assertTrue( v.isConflict() );
        assertTrue( v.message().contains( "login name" ), v.message() );
    }

    @Test
    void theWhitespaceStrippedFormMatchingAnotherLoginIsAConflict() throws Exception {
        when( db.findByLoginName( "BobSmith" ) ).thenReturn( accountWithLogin( "BobSmith" ) );
        assertTrue( violation( "Bob Smith" ).orElseThrow().message().contains( "login name" ) );
    }

    @Test
    void ownLoginIsNotAClash() throws Exception {
        when( db.findByLoginName( "me" ) ).thenReturn( accountWithLogin( "me" ) );
        assertTrue( violation( "me" ).isEmpty() );
    }

    @Test
    void caseInsensitiveLoginProbeOnTheNameIsAConflict() throws Exception {
        when( db.isLoginNameTakenIgnoreCase( "Carol", "me" ) ).thenReturn( true );
        assertTrue( violation( "Carol" ).orElseThrow().message().contains( "login name" ) );
    }

    @Test
    void caseInsensitiveLoginProbeOnTheStrippedNameIsAConflict() throws Exception {
        when( db.isLoginNameTakenIgnoreCase( "CarolJones", "me" ) ).thenReturn( true );
        assertTrue( violation( "Carol Jones" ).orElseThrow().message().contains( "login name" ) );
    }

    @Test
    void aFailingLoginProbeDoesNotBlockAnOtherwiseFreeName() throws Exception {
        when( db.isLoginNameTakenIgnoreCase( any(), any() ) ).thenThrow( new com.wikantik.auth.WikiSecurityException( "down" ) );
        assertTrue( violation( "Dana" ).isEmpty() );
    }

    @Test
    void aDisplayNameHeldByAnotherAccountIsAConflict() throws Exception {
        when( db.isFullNameTakenIgnoreCase( "Erin", "me" ) ).thenReturn( true );
        final var v = violation( "Erin" ).orElseThrow();
        assertTrue( v.isConflict() );
        assertTrue( v.message().contains( "already in use" ), v.message() );
    }

    @Test
    void aFailingFullNameProbeDoesNotBlockTheName() throws Exception {
        when( db.isFullNameTakenIgnoreCase( any(), any() ) ).thenThrow( new com.wikantik.auth.WikiSecurityException( "down" ) );
        assertTrue( violation( "Frank" ).isEmpty() );
    }

    @Test
    void withoutAStoreOnlyTheShapeAndReservedRulesApply() {
        assertTrue( ProfileNameRules.displayNameViolation( engine, null, "me", "Grace" ).isEmpty() );
        assertTrue( ProfileNameRules.displayNameViolation( engine, null, "me", "admin" ).isPresent() );
        assertTrue( ProfileNameRules.displayNameViolation( engine, null, "me", "x" ).isPresent() );
    }

    // ---- change validation

    @Test
    void anUnchangedFullNameIsNotRevalidatedEvenWhenTheStoreSaysTaken() throws Exception {
        when( db.isFullNameTakenIgnoreCase( any(), any() ) ).thenReturn( true );
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", "Heidi", "Heidi", " Heidi ", "Heidi" ).isEmpty() );
    }

    @Test
    void nullAndBlankOldFullNameCountAsTheSameNoName() throws Exception {
        // blank -> blank is "no change", so the "A display name is required" rule must not fire
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", null, null, "   ", null ).isEmpty() );
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", "   ", null, null, null ).isEmpty() );
        // but a real change from blank to a bad name is checked
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", null, null, "x", null ).isPresent() );
    }

    @Test
    void aNullNewFullNameIsNeverValidated() {
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", "Ivan", "Ivan", null, "Ivan" ).isEmpty() );
    }

    @Test
    void aChangedWikiNameThatIsReservedIsInvalid() {
        final var v = ProfileNameRules.nameChangeViolation( engine, db, "me", "Judy", "Judy", "Judy", "Admin" ).orElseThrow();
        assertFalse( v.isConflict() );
        assertTrue( v.message().contains( "reserved" ), v.message() );
    }

    @Test
    void aChangedWikiNameThatIsAnotherAccountsLoginIsATakenConflict() throws Exception {
        when( db.findByLoginName( "Mallory" ) ).thenReturn( accountWithLogin( "Mallory" ) );
        final var v = ProfileNameRules.nameChangeViolation( engine, db, "me", "Judy", "Judy", "Judy", "Mallory" ).orElseThrow();
        assertTrue( v.isConflict() );
        assertTrue( v.message().contains( "login name" ), v.message() );
    }

    @Test
    void anUnchangedOrBlankWikiNameIsNotChecked() throws Exception {
        when( db.findByLoginName( any() ) ).thenReturn( accountWithLogin( "Mallory" ) );
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", "Judy", "Admin", "Judy", " Admin " ).isEmpty() );
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", "Judy", "Judy", "Judy", "  " ).isEmpty() );
        assertTrue( ProfileNameRules.nameChangeViolation( engine, db, "me", "Judy", "Judy", "Judy", null ).isEmpty() );
    }

    // ---- helpers

    @Test
    void displayNameFormAndKeyAreNullSafeAndNormalised() {
        assertNull( ProfileNameRules.displayNameForm( null ) );
        assertNull( ProfileNameRules.displayNameKey( null ) );
        assertEquals( "Ada Lovelace", ProfileNameRules.displayNameForm( "  Ada  　Lovelace " ) );
        assertEquals( "ada lovelace", ProfileNameRules.displayNameKey( "  ADA   Lovelace" ) );
    }

    @Test
    void derivedWikiNameStripsAllWhitespace() {
        assertNull( ProfileNameRules.derivedWikiName( null ) );
        assertEquals( "AdaKingLovelace", ProfileNameRules.derivedWikiName( " Ada \tKing\nLovelace " ) );
    }

    @Test
    void applyFullNameTrimsAndDerivesTheWikiName() {
        final UserProfile p = new DummyUserDatabase().newProfile();
        p.setLoginName( "me" );
        ProfileNameRules.applyFullName( engine, db, p, "  Ada Lovelace  " );
        assertEquals( "Ada Lovelace", p.getFullname() );
        assertEquals( "AdaLovelace", p.getWikiName() );
    }

    @Test
    void applyFullNameWithNullClearsTheFullName() {
        final UserProfile p = new DummyUserDatabase().newProfile();
        p.setLoginName( "me" );
        p.setFullname( "Old Name" );
        ProfileNameRules.applyFullName( engine, db, p, null );
        assertNull( p.getFullname() );
    }

    @Test
    void applyFullNameNumbersAWikiNameAnotherAccountHolds() throws Exception {
        when( db.findByWikiName( "AdaLovelace" ) ).thenReturn( accountWithLogin( "someoneElse" ) );
        final UserProfile p = new DummyUserDatabase().newProfile();
        p.setLoginName( "me" );
        ProfileNameRules.applyFullName( engine, db, p, "Ada Lovelace" );
        assertEquals( "AdaLovelace2", p.getWikiName() );
    }

    // ---- availableWikiName

    @Test
    void aFreeWikiNameIsReturnedAsIs() {
        assertEquals( "Zed", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", null ) );
    }

    @Test
    void aTakenWikiNameGetsTheLowestFreeNumber() throws Exception {
        when( db.findByWikiName( "Zed" ) ).thenReturn( accountWithLogin( "other" ) );
        when( db.findByWikiName( "Zed2" ) ).thenReturn( accountWithLogin( "other2" ) );
        assertEquals( "Zed3", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", null ) );
    }

    @Test
    void aNumberedCurrentNameIsKeptWhileStillFree() throws Exception {
        when( db.findByWikiName( "Zed" ) ).thenReturn( accountWithLogin( "other" ) );
        assertEquals( "Zed7", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", "Zed7" ) );
    }

    @Test
    void aNonNumericOrTakenCurrentNameIsNotKept() throws Exception {
        when( db.findByWikiName( "Zed" ) ).thenReturn( accountWithLogin( "other" ) );
        assertEquals( "Zed2", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", "Zedx" ) );
        when( db.findByWikiName( "Zed7" ) ).thenReturn( accountWithLogin( "other7" ) );
        assertEquals( "Zed2", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", "Zed7" ) );
    }

    @Test
    void theLastNumberedFormIsStillUsableAndExhaustionFallsBackToThePreferredName() throws Exception {
        // 1000 is the highest suffix tried
        when( db.findByWikiName( argThat( n -> n != null && !n.equals( "Zed1000" ) ) ) ).thenReturn( accountWithLogin( "other" ) );
        assertEquals( "Zed1000", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", null ) );
        when( db.findByWikiName( any() ) ).thenReturn( accountWithLogin( "other" ) );
        assertEquals( "Zed", ProfileNameRules.availableWikiName( engine, db, "me", "Zed", null ) );
    }

    @Test
    void nullStoreNullOrBlankPreferredAndUnchangedAreReturnedUntouched() {
        assertEquals( "Zed", ProfileNameRules.availableWikiName( engine, null, "me", "Zed", null ) );
        assertNull( ProfileNameRules.availableWikiName( engine, db, "me", null, null ) );
        assertEquals( " ", ProfileNameRules.availableWikiName( engine, db, "me", " ", null ) );
        assertEquals( "Admin", ProfileNameRules.availableWikiName( engine, db, "me", "Admin", "Admin" ),
                "an unchanged wiki name is never re-checked" );
    }

    @Test
    void aReservedPreferredWikiNameIsNumbered() {
        assertEquals( "Admin2", ProfileNameRules.availableWikiName( engine, db, "me", "Admin", null ) );
    }

    // ---- account lookups

    @Test
    void anotherAccountsLoginAndUsedByAnotherAccountHandleNullBlankAndNoStore() throws Exception {
        when( db.findByLoginName( any() ) ).thenReturn( accountWithLogin( "x" ) );
        when( db.findByFullName( any() ) ).thenReturn( accountWithLogin( "x" ) );
        when( db.findByWikiName( any() ) ).thenReturn( accountWithLogin( "x" ) );
        assertFalse( ProfileNameRules.isAnotherAccountsLogin( null, "me", "x" ) );
        assertFalse( ProfileNameRules.isAnotherAccountsLogin( db, "me", null ) );
        assertFalse( ProfileNameRules.isAnotherAccountsLogin( db, "me", "  " ) );
        assertTrue( ProfileNameRules.isAnotherAccountsLogin( db, "me", "x" ) );
        assertFalse( ProfileNameRules.usedByAnotherAccount( null, "me", "x" ) );
        assertFalse( ProfileNameRules.usedByAnotherAccount( db, "me", null ) );
        assertFalse( ProfileNameRules.usedByAnotherAccount( db, "me", " " ) );
        assertTrue( ProfileNameRules.usedByAnotherAccount( db, "me", "x" ) );
    }

    @Test
    void usedByAnotherAccountChecksLoginFullAndWikiNamesAndIgnoresOwnAccount() throws Exception {
        final UserDatabase byFull = mock( UserDatabase.class );
        when( byFull.findByFullName( "F" ) ).thenReturn( accountWithLogin( "other" ) );
        assertTrue( ProfileNameRules.usedByAnotherAccount( byFull, "me", "F" ) );
        final UserDatabase byWiki = mock( UserDatabase.class );
        when( byWiki.findByWikiName( "W" ) ).thenReturn( accountWithLogin( "other" ) );
        assertTrue( ProfileNameRules.usedByAnotherAccount( byWiki, "me", "W" ) );
        final UserDatabase own = mock( UserDatabase.class );
        when( own.findByFullName( "F" ) ).thenReturn( accountWithLogin( "me" ) );
        assertFalse( ProfileNameRules.usedByAnotherAccount( own, "me", "F" ) );
        final UserDatabase none = mock( UserDatabase.class );
        when( none.findByLoginName( any() ) ).thenThrow( new com.wikantik.auth.NoSuchPrincipalException( "none" ) );
        assertFalse( ProfileNameRules.usedByAnotherAccount( none, "me", "F" ) );
    }
}
