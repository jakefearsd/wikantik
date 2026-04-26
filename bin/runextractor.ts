#!/usr/bin/env bun
// runextractor.ts — Bun version of bin/runextractor.sh.
//
// Launches the standalone entity-extractor CLI against the local Wikantik
// database with sensible defaults pulled from the deployed Tomcat config.
// Mirrors the bash version's behaviour but uses Bun's $ shell tag and file
// APIs instead of bash idioms.
//
// Usage:
//   bin/runextractor.ts                       # resume / incremental run
//   bin/runextractor.ts --force               # clear prior mentions per chunk
//   bin/runextractor.ts --prefilter           # enable the chunk prefilter
//   bin/runextractor.ts --prefilter-dry-run   # see would-skip counts only
//   bin/runextractor.ts --concurrency 1       # single in-flight
//   bin/runextractor.ts --help                # forward to the jar's --help
//
// Behaviour:
//   - Builds wikantik-extract-cli if target/wikantik-extract-cli.jar is
//     missing or older than any .java in the CLI module or in the extraction
//     package of wikantik-main (the CLI links that bytecode through the fat
//     jar, so a stale wikantik-main jar silently ships old behaviour).
//   - Pulls JDBC URL/user/password from
//     tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml. Falls back to
//     PG_JDBC_URL / PG_USER / PG_PASSWORD env vars if ROOT.xml is missing.
//   - Forwards every CLI flag straight through to the jar.
//   - Pipes the password through an env var so it never lands on the
//     command line (no /proc/*/cmdline leak).

import { $ } from "bun";
import { dirname, resolve } from "node:path";
import { existsSync, statSync } from "node:fs";
import { readdir } from "node:fs/promises";

const SCRIPT_DIR = dirname(Bun.fileURLToPath(import.meta.url));
const ROOT_DIR = resolve(SCRIPT_DIR, "..");
const JAR = resolve(ROOT_DIR, "wikantik-extract-cli/target/wikantik-extract-cli.jar");
const CONTEXT_XML = resolve(ROOT_DIR, "tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml");

const GREEN = "\x1b[0;32m";
const YELLOW = "\x1b[1;33m";
const RED = "\x1b[0;31m";
const NC = "\x1b[0m";

const info = (m: string) => console.log(`${GREEN}[runextractor]${NC} ${m}`);
const warn = (m: string) => console.error(`${YELLOW}[runextractor]${NC} ${m}`);
const die = (m: string): never => {
    console.error(`${RED}[runextractor]${NC} ${m}`);
    process.exit(1);
};

// Walk a directory tree once, returning the newest mtime among .java files.
// One traversal beats spawning find(1) and parses cleanly across platforms.
async function newestJavaMtime(root: string): Promise<number> {
    if (!existsSync(root)) return 0;
    let newest = 0;
    const stack: string[] = [root];
    while (stack.length > 0) {
        const dir = stack.pop()!;
        const entries = await readdir(dir, { withFileTypes: true });
        for (const e of entries) {
            const p = resolve(dir, e.name);
            if (e.isDirectory()) stack.push(p);
            else if (e.isFile() && e.name.endsWith(".java")) {
                const m = statSync(p).mtimeMs;
                if (m > newest) newest = m;
            }
        }
    }
    return newest;
}

async function rebuildIfStale(): Promise<void> {
    let needsBuild = false;
    if (!existsSync(JAR)) {
        needsBuild = true;
    } else {
        const jarMtime = statSync(JAR).mtimeMs;
        const cliSrcMtime = await newestJavaMtime(resolve(ROOT_DIR, "wikantik-extract-cli/src"));
        const mainExtractionMtime = await newestJavaMtime(
            resolve(ROOT_DIR, "wikantik-main/src/main/java/com/wikantik/knowledge/extraction"),
        );
        if (cliSrcMtime > jarMtime || mainExtractionMtime > jarMtime) {
            needsBuild = true;
        }
    }
    if (!needsBuild) return;

    info("Building wikantik-extract-cli (jar is missing or stale)…");
    const result =
        await $`mvn install -pl wikantik-extract-cli -am -Dmaven.test.skip -q`
            .cwd(ROOT_DIR)
            .nothrow();
    if (result.exitCode !== 0) {
        die("build failed — run 'mvn install -pl wikantik-extract-cli -am' for details");
    }
}

type Creds = { jdbcUrl: string; jdbcUser: string; jdbcPassword: string };

async function resolveCredentials(): Promise<Creds> {
    let jdbcUrl = "";
    let jdbcUser = "";
    let jdbcPassword = "";

    if (existsSync(CONTEXT_XML)) {
        const xml = await Bun.file(CONTEXT_XML).text();
        // The ROOT.xml may declare multiple Resource elements; the first
        // url=/username=/password= wins, matching the bash version's `head -1`.
        jdbcUrl = xml.match(/url="([^"]+)"/)?.[1] ?? "";
        jdbcUser = xml.match(/username="([^"]+)"/)?.[1] ?? "";
        jdbcPassword = xml.match(/password="([^"]+)"/)?.[1] ?? "";
    } else {
        warn(`${CONTEXT_XML} not found; falling back to env vars`);
    }

    jdbcUrl ||= Bun.env.PG_JDBC_URL ?? "jdbc:postgresql://localhost:5432/jspwiki";
    jdbcUser ||= Bun.env.PG_USER ?? "jspwiki";
    jdbcPassword ||= Bun.env.PG_PASSWORD ?? "";

    if (!jdbcPassword) {
        die("No JDBC password available. Either deploy ROOT.xml or export PG_PASSWORD.");
    }
    return { jdbcUrl, jdbcUser, jdbcPassword };
}

async function main(): Promise<void> {
    await rebuildIfStale();
    const { jdbcUrl, jdbcUser, jdbcPassword } = await resolveCredentials();

    const forwarded = process.argv.slice(2);
    info(`DB: ${jdbcUrl} as ${jdbcUser}`);
    info(`Launching: java -jar ${JAR} ${forwarded.join(" ")}`);
    info("Progress lines will appear every --poll-seconds (default 30).");
    console.log();

    // Spawn java directly so its exit code propagates as ours and the user's
    // SIGINT (Ctrl-C) flows straight to the JVM's shutdown hook. Inheriting
    // stdio means we don't buffer the CLI's progress lines.
    const proc = Bun.spawn(
        [
            "java",
            "-jar",
            JAR,
            "--jdbc-url",
            jdbcUrl,
            "--jdbc-user",
            jdbcUser,
            "--jdbc-password-env",
            "WIKANTIK_EXTRACT_PG_PASSWORD",
            ...forwarded,
        ],
        {
            stdin: "inherit",
            stdout: "inherit",
            stderr: "inherit",
            env: { ...process.env, WIKANTIK_EXTRACT_PG_PASSWORD: jdbcPassword },
        },
    );
    const code = await proc.exited;
    process.exit(code);
}

main().catch((err) => die(err instanceof Error ? err.message : String(err)));
