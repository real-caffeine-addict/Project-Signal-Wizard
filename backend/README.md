# Backend tests

Use Maven and JDK 21; JDK 25 can also compile the Java 21 target. Docker is required
for integration tests. Test dependencies use Testcontainers 2.0.5, matching the
merged Phase 1 integration module.

```bash
cd backend
mvn test
mvn clean verify
mvn clean verify -Pcoverage
```

`test` runs fast unit, MockMvc and isolated HTTP tests with Surefire. It does not
start database containers. `verify` also runs `*IT` classes through Failsafe, after
`package` has built the service JARs. JaCoCo HTML/XML reports are generated under
each module's `target/site/jacoco` when the coverage profile is enabled.

Database tests use JUnit-managed Testcontainers MariaDB 11.4 instances. Each suite
gets its own disposable database, so Flyway cleanup cannot affect another suite or
the development database. Spring smoke tests receive container connection details
through `DynamicPropertySource`; no local secrets or configuration files are needed.
The first integration run may pull container images. Docker unavailability is a
failure, rather than a silent skip.

The existing `integration-tests` module retains the document routing tests and the
separate-process document/revision/audit E2E tests. Plain service JARs remain usable
as Java dependencies; executable service JARs have an `-exec.jar` suffix. E2E tests
start those executables only after Maven has packaged them. The current E2E fixture
uses loopback ports 18000, 18003 and 18004; those ports must be available.

Select a database suite (include upstream modules with `-am`):

```bash
mvn verify -pl documents-service -am -Dit.test=DocumentPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false
```

Gateway HTTP-stub tests complement the real document E2E flow by covering incident
transport failures without requiring an incident deployment. The older three-case
`IncidentFlowEndToEndTests` class is preserved with its upstream `@Disabled` marker;
it still requires a separately configured incident service/database and is not
counted as verified coverage.

Test-only configuration disables Spring Cloud's compatibility verifier, as the
existing gateway setup did. These results do not certify runtime version compatibility.
Generated model accessors are not targets for extra tests. The stale consultant
portal is excluded from this work.
