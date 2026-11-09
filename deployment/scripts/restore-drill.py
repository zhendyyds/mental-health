"""Run the SQL and uploads recovery against an isolated native MySQL instance."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import time
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "mental-health-assistant-backend"
ASSETS = ROOT / "deployment/mysql"
MIGRATIONS = [
    BACKEND / "db/baseline/20261106_schema.sql",
    BACKEND / "db/migrations/20261107_add_knowledge_index.sql",
    BACKEND / "db/migrations/20261107_allow_knowledge_qa_requests.sql",
]
SOURCE = "batch7_source"
TARGET = "batch7_restore"
ARTICLE = "00000000-0000-0000-0000-000000000701"
GENERATION = "00000000-0000-0000-0000-000000000702"
CHUNK = "00000000-0000-0000-0000-000000000703"
COVER = f"ARTICLE/{ARTICLE}/cover_1.png"
FIXTURE = f"""
INSERT INTO `user` (id,username,email,password)
VALUES (701,'fictional_restore','fictional_restore@example.invalid','fixture-no-login');
INSERT INTO knowledge_article (id,title,content,author_id,cover_image,status,index_ready,
    index_config_version,index_generation,active_index_revision,active_index_config_version,active_index_generation)
VALUES ('{ARTICLE}','虚构恢复文章','<p>虚构来源，恢复验证。</p>',701,'/upload/{COVER}',1,TRUE,
    'fixture-v1','{GENERATION}',1,'fixture-v1','{GENERATION}');
INSERT INTO knowledge_index_config (index_config_version,embedding_provider,embedding_endpoint,embedding_model,
    dimension,collection_name,extractor_version,chunker_version,chunk_size,chunk_overlap,created_at)
VALUES ('fixture-v1','fictional','https://embedding.example.invalid','fixture-model',3,
    'fictional_recovery','jsoup-v1','fixed-v1',256,32,UTC_TIMESTAMP(6));
INSERT INTO knowledge_index_job (article_id,revision,index_config_version,index_generation,operation,status,
    attempt_count,next_retry_at,lease_owner,lease_token,lease_until,created_at,updated_at)
VALUES ('{ARTICLE}',1,'fixture-v1','{GENERATION}','BUILD','RUNNING',1,UTC_TIMESTAMP(6),'fixture-worker',
    '00000000-0000-0000-0000-000000000704',UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO knowledge_chunk (chunk_id,article_id,revision,index_config_version,index_generation,chunk_index,
    content,content_hash,vector_id)
VALUES ('{CHUNK}','{ARTICLE}',1,'fixture-v1','{GENERATION}',0,'Fictional source.',SHA2('Fictional source.',256),'{CHUNK}');
"""


def assert_that(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify_database(client, dump, evidence):
    def query(sql, database=None, check=True):
        command = client + ([f"--database={database}"] if database else [])
        payload = sql.encode("utf-8") if isinstance(sql, str) else sql
        return subprocess.run(command, input=payload, capture_output=True, check=check, timeout=30)

    for database in (SOURCE, TARGET):
        query(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
        for migration in MIGRATIONS:
            query(migration.read_bytes(), database)
    query(FIXTURE, SOURCE)

    schema_query = (ASSETS / "schema-state.sql").read_bytes()
    schema = query(schema_query, SOURCE).stdout
    assert_that(schema == query(schema_query, TARGET).stdout, "Target schema differed from source")
    empty_query = (ASSETS / "assert-empty.sql").read_bytes()
    assert_that(query(empty_query, TARGET).stdout.strip() == b"0", "Restore target was not empty")
    with (evidence / "database.sql").open("wb") as output:
        subprocess.run(dump + [SOURCE], stdout=output, check=True, timeout=30)

    data = (evidence / "database.sql").read_bytes()
    invalidation = (ASSETS / "reset-derived-index.sql").read_bytes()
    transaction = b"START TRANSACTION;\n" + data + b"\n" + invalidation
    broken = query(transaction + b"\nINSERT INTO fixture_missing_table VALUES (1);\nCOMMIT;\n", TARGET, check=False)
    assert_that(broken.returncode != 0, "Injected restore failure was not observed")
    assert_that(query(empty_query, TARGET).stdout.strip() == b"0", "Failed import was partly committed")
    query(transaction + b"\nCOMMIT;\n", TARGET)

    article = query("SELECT title,content,cover_image,status,index_ready,index_generation IS NULL,"
                    "active_index_generation IS NULL FROM knowledge_article;", TARGET).stdout.decode().strip().split("\t")
    assert_that(article == ["虚构恢复文章", "<p>虚构来源，恢复验证。</p>", f"/upload/{COVER}",
                            "1", "0", "1", "1"], "Article data or restored visibility differed")
    jobs = query("SELECT status,retryable,lease_token IS NULL,error_code FROM knowledge_index_job;", TARGET).stdout.strip()
    assert_that(jobs == b"SUPERSEDED\t0\t1\tRESTORE_REBUILD_REQUIRED", "Old job remained eligible")
    assert_that(query("SELECT COUNT(*) FROM knowledge_chunk;", TARGET).stdout.strip() == b"1", "Old chunk metadata was lost")
    citeable = query("SELECT COUNT(*) FROM knowledge_article a JOIN knowledge_chunk c ON a.id=c.article_id "
                     "AND a.index_ready AND a.active_index_generation=c.index_generation;", TARGET).stdout.strip()
    assert_that(citeable == b"0", "Old chunk was still referenceable")
    assert_that(query("SELECT index_ready FROM knowledge_article;", SOURCE).stdout.strip() == b"1", "Source changed")
    return {"schemaMatch": True, "atomicRollback": True, "rowsRestored": True,
            "oldJobSuperseded": True, "oldChunkNotReferenceable": True, "sourceUnchanged": True}


def verify_uploads(evidence):
    source = evidence / "source-uploads"
    target = evidence / "target-uploads"
    original = source / COVER
    original.parent.mkdir(parents=True)
    original.write_bytes(b"fictional upload\x00\xff\r\n")
    target.mkdir()
    archive = evidence / "uploads.tar.gz"
    subprocess.run(["tar", "-czf", str(archive), "-C", str(source), "."], check=True, timeout=20)
    validator = [sys.executable, str(ASSETS / "check-upload-archive.py")]
    subprocess.run(validator + [str(archive)], check=True, timeout=20)
    subprocess.run(["tar", "--no-same-owner", "--no-same-permissions", "-xzf", str(archive), "-C", str(target)],
                   check=True, timeout=20)
    assert_that(original.read_bytes() == (target / COVER).read_bytes(), "Upload bytes changed")

    bad_archive = evidence / "unsafe-upload.tar.gz"
    with tarfile.open(bad_archive, "w:gz") as unsafe:
        unsafe.addfile(tarfile.TarInfo("../escaped"))
    rejection = subprocess.run(validator + [str(bad_archive)], capture_output=True, timeout=20)
    assert_that(rejection.returncode != 0, "Unsafe archive entry accepted")
    return {"uploadSha256": hashlib.sha256(original.read_bytes()).hexdigest(), "unsafeArchiveRejected": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-base", type=Path, default=Path(r"E:\MySQL\MySQL Server 9.4"))
    mysql_base = parser.parse_args().mysql_base.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    tools = {name: str(mysql_base / "bin" / (name + suffix)) for name in ("mysqld", "mysql", "mysqldump", "mysqladmin")}
    assert_that(all(Path(path).is_file() for path in tools.values()), "MySQL programs are missing")

    evidence = BACKEND / "target" / ("batch7-native-restore-" + uuid4().hex[:10])
    datadir = evidence / "data"
    datadir.mkdir(parents=True)
    options = [tools["mysqld"], "--no-defaults", f"--basedir={mysql_base}", f"--datadir={datadir}"]
    with (evidence / "initialize.log").open("wb") as init_log:
        subprocess.run(options + ["--initialize-insecure", "--console"], stdout=init_log,
                       stderr=subprocess.STDOUT, check=True, timeout=45)

    with socket.socket() as port_check:
        port_check.bind(("127.0.0.1", 0))
        port = port_check.getsockname()[1]
    connection = ["--no-defaults", "--protocol=TCP", "--host=127.0.0.1", f"--port={port}", "--user=root", "--skip-password"]
    client = [tools["mysql"], *connection, "--batch", "--skip-column-names", "--raw", "--binary-mode", "--default-character-set=utf8mb4"]
    dump = [tools["mysqldump"], *connection, *(ASSETS / "dump-data.args").read_text().splitlines()]
    with (evidence / "server.log").open("wb") as server_log:
        with subprocess.Popen(options + [f"--port={port}", "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin", "--console"],
                              stdout=server_log, stderr=subprocess.STDOUT,
                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)) as server:
            owned = False
            try:
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline and server.poll() is None:
                    probe = subprocess.run(client + ["--connect-timeout=2", "-e", "SELECT @@datadir;"],
                                           capture_output=True, timeout=4)
                    if probe.returncode == 0:
                        observed = Path(probe.stdout.decode().strip()).resolve()
                        assert_that(observed == datadir, "Port belonged to a different MySQL data directory")
                        owned = True
                        break
                    time.sleep(0.2)
                assert_that(owned, "The isolated MySQL service did not become ready")
                result = verify_database(client, dump, evidence)
                result.update(verify_uploads(evidence))
            finally:
                if owned and server.poll() is None:
                    try:
                        shutdown = subprocess.run([tools["mysqladmin"], *connection, "shutdown"], capture_output=True, timeout=10)
                        assert_that(shutdown.returncode == 0 and server.wait(timeout=10) == 0, "MySQL shutdown failed")
                    finally:
                        if server.poll() is None:
                            server.kill()
                elif server.poll() is None:
                    server.kill()
    result.update({"mode": "native-mysql-not-compose", "serverStopped": True, "fictionalDataOnly": True})
    (evidence / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(evidence / "summary.json")


if __name__ == "__main__":
    main()
