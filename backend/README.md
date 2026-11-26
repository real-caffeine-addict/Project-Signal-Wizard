# Backend tests

Use JDK 21, Maven, Python 3 and a running Docker daemon:

```bash
cd backend
python3 test.py
```

The runner creates a disposable MariaDB 11.4 container (matching dev Compose),
binds an available loopback port, supplies temporary test configuration and runs
a clean Maven reactor build. It stops the container on exit; database files live in tmpfs.
It uses generated database credentials and does not read development credentials.
The first run may pull the image. Set `JAVA_HOME` to your JDK 21 installation if needed.

Select modules or test classes using Maven arguments:

```bash
python3 test.py -pl documents-service -am -Dtest=DocumentPersistenceIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false
python3 test.py --image mariadb:11.8
python3 test.py --coverage
```

The persistence/schema tests clean their configured schema. Run them through this
runner; any manually supplied `psw.test.db.*` properties must reference a disposable
database. Module application smoke tests also run against the isolated database.
`--coverage` produces per-module JaCoCo HTML/XML reports under `target/site/jacoco`;
generated code coverage is not a target for additional tests.

Service/controller tests use JUnit 5, AssertJ, Mockito and MockMvc. Database tests
exercise real migrations, constraints, mapping and transaction rollback. Gateway
HTTP tests use the real Feign client and a local stub endpoint; incident persistence
is tested separately. They do not establish a deployed multi-service end-to-end flow.
The test configuration disables Spring Cloud's compatibility verifier, following the
existing gateway test setup; runtime version compatibility is not certified by these tests.

Tests characterize implemented behavior. Authentication, gateway routing and later
document workflows remain WIP. No tests are added to the stale consultant portal.
