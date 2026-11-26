#!/usr/bin/env python3
"""Run backend tests against a disposable MariaDB; never uses the development DB."""

import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="mariadb:11.4", help="MariaDB Docker image (default: dev Compose version)")
    parser.add_argument("--coverage", action="store_true", help="Generate JaCoCo reports in each module's target/site/jacoco")
    options, maven_args = parser.parse_known_args()
    password = secrets.token_hex(24)
    name = "psw-tests-" + uuid.uuid4().hex[:12]
    backend = Path(__file__).resolve().parent
    started = False
    try:
        subprocess.run([
            "docker", "run", "--detach", "--rm", "--name", name,
            "--tmpfs", "/var/lib/mysql", "-p", "127.0.0.1::3306",
            "-e", "MARIADB_RANDOM_ROOT_PASSWORD=yes", "-e", "MARIADB_DATABASE=psw_test",
            "-e", "MARIADB_USER=psw_test", "-e", "MARIADB_PASSWORD=" + password,
            options.image,
        ], check=True, stdout=subprocess.DEVNULL)
        started = True
        for _ in range(60):
            ready = subprocess.run(["docker", "exec", name, "healthcheck.sh", "--connect", "--innodb_initialized"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Isolated MariaDB did not become ready within 60 seconds")
        port = subprocess.check_output(["docker", "port", name, "3306/tcp"], text=True).strip().rsplit(":", 1)[1]
        url = "jdbc:mariadb://127.0.0.1:" + port + "/psw_test"
        with tempfile.TemporaryDirectory(prefix="psw-test-config-") as directory:
            config = Path(directory) / "application.yaml"
            config.write_text("\n".join([
                "spring:", "  datasource:", "    url: " + url, "    username: psw_test",
                "    password: " + json.dumps(password),
                "  cloud:", "    compatibility-verifier:", "      enabled: false",
                "server:", *["  " + service + ": {port: 0}" for service in
                              ["gateway", "identities", "documents", "audit", "projects", "incidents"]],
                "routes:", "  audit: http://127.0.0.1:9", "  incident: http://127.0.0.1:9", "",
            ]))
            config.chmod(0o600)
            environment = os.environ | {"PSW_CONFIG_FILE": str(config)}
            goals = ["test"]
            if options.coverage:
                jacoco = "org.jacoco:jacoco-maven-plugin:0.8.14:"
                goals = [jacoco + "prepare-agent", "test", jacoco + "report"]
            return subprocess.run([
                "mvn", "-B", "-ntp", "-Dpsw.test.db.url=" + url, "-Dpsw.test.db.user=psw_test",
                "-Dpsw.test.db.password=" + password, *maven_args, *goals,
            ], cwd=backend, env=environment).returncode
    finally:
        if started:
            subprocess.run(["docker", "stop", name], stdout=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    raise SystemExit(run())
