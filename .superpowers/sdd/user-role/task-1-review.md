# Task 1 Review — Add 3 jjwt dependencies to pom.xml

## Spec compliance

1. **Three jjwt dependencies added before `</dependencies>`** — PASS.
   - `io.jsonwebtoken:jjwt-api:0.12.6` (no `<scope>` element, default = compile) at pom.xml lines 55-59.
   - `io.jsonwebtoken:jjwt-impl:0.12.6` with `<scope>runtime</scope>` at lines 60-65.
   - `io.jsonwebtoken:jjwt-jackson:0.12.6` with `<scope>runtime</scope>` at lines 66-71.
   - All three appear before `</dependencies>` on line 72.

2. **No other parts of pom.xml changed** — PASS.
   - `<parent>` (lines 8-13), `<properties>` (lines 21-25), the existing 5 dependencies (lines 28-52), `<build>` (lines 74-82), and project coordinates (lines 15-19) all intact. Total dependency count went from 5 to 8 (the 3 new ones), with no edits elsewhere.

3. **Three jars findable in offline `.m2-repo` mirror** — PASS.
   - Verified directly: `.m2-repo/io/jsonwebtoken/jjwt-api/0.12.6/jjwt-api-0.12.6.jar`, `.m2-repo/io/jsonwebtoken/jjwt-impl/0.12.6/jjwt-impl-0.12.6.jar`, `.m2-repo/io/jsonwebtoken/jjwt-jackson/0.12.6/jjwt-jackson-0.12.6.jar` all present (dated Aug 21), each accompanied by `.pom` and `.sha1` sidecars. No online fetch was required because the mirror already contained them.

4. **No git operations** — PASS. Project is not a git repository; no commits made or expected.

**Overall spec compliance:** ✅

## Task quality

The XML is well-formed: every `<dependency>` block is properly closed, scopes are wrapped in their own tags, and the indentation is consistent with the surrounding existing entries. Scope usage is correct per jjwt's recommended layout — `api` stays at the default `compile` scope so callers can use the types, while `impl` and `jackson` are correctly set to `runtime` because they are implementation details that should not leak into downstream compile classpaths. Artifact IDs (`j jwt-api`, `jjwt-impl`, `jjwt-jackson`) and the `0.12.6` version are exact — no copy-paste swaps. Group ID `io.jsonwebtoken` is consistent across all three. No defects.

**Verdict:** Approved