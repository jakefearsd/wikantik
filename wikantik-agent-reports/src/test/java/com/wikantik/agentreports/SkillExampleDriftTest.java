package com.wikantik.agentreports;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fails when the agent-field-report skill's example call drifts from the real validator. */
class SkillExampleDriftTest {

    private static final Path SKILL = Path.of( "..", ".claude", "skills", "agent-field-report", "SKILL.md" );

    private static String skillText() throws IOException {
        assertTrue( Files.isRegularFile( SKILL ), "missing skill file " + SKILL.toAbsolutePath() );
        return Files.readString( SKILL, StandardCharsets.UTF_8 );
    }

    @Test
    void skillExampleIsValid() throws IOException {
        final Matcher m = Pattern.compile( "```json\\s*\\n(.*?)\\n```", Pattern.DOTALL ).matcher( skillText() );
        assertTrue( m.find(), "no ```json block in the skill" );
        final Map< String, Object > args = new Gson().fromJson( m.group( 1 ),
                new TypeToken< Map< String, Object > >() { }.getType() );
        final Validation v = AgentReportValidator.validate( args );
        assertTrue( v.isOk(), "skill example refused: " + v.reasons() );
    }

    @Test
    void skillDeclaresVersionOne() throws IOException {
        assertTrue( Pattern.compile( "(?m)^version: 1\\s*$" ).matcher( skillText() ).find(),
                "frontmatter lacks 'version: 1'" );
    }
}
