"""Portable, versioned logical backups of the local ShopWise development DB."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.parse import unquote, urlsplit
import zipfile


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def pg_command(env, name, compose):
    if env["SHOPWISE_DB_MODE"] == "compose":
        return compose("exec", "-T", "db", name, "-U", "shopwise", "-d", "shopwise"), env
    url = urlsplit(env["DATABASE_URL"])
    if url.scheme not in ("postgres", "postgresql") or url.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("跨机导入导出仅支持本机 PostgreSQL 开发库")
    if url.query or not url.path.strip("/"):
        raise ValueError("本地迁移需要无查询参数且有库名的 DATABASE_URL")
    bin_dir = env.get("SHOPWISE_PG_BIN")
    search_path = bin_dir + os.pathsep + env["PATH"] if bin_dir else env["PATH"]
    client = shutil.which(name, path=search_path)
    if not client:
        raise ValueError(f"请安装 PostgreSQL 客户端 {name}")
    credentials = {"PGHOST": url.hostname, "PGPORT": str(url.port or 5432),
                   "PGDATABASE": unquote(url.path.lstrip("/")), "PGUSER": unquote(url.username or env["DB_USER"]),
                   "PGPASSWORD": unquote(url.password or env["DB_PASSWORD"])}
    return [client, "--no-password", "--dbname", credentials["PGDATABASE"]], {**env, **credentials}


def unpack(archive, dump):
    try:
        with zipfile.ZipFile(archive) as package:
            if sorted(package.namelist()) != ["database.dump", "manifest.json"]:
                raise ValueError("备份文件组成无效")
            if package.getinfo("manifest.json").file_size > 4096:
                raise ValueError("备份清单无效")
            manifest = json.loads(package.read("manifest.json"))
            if not isinstance(manifest, dict) or manifest.get("format") != "shopwise-dev" or manifest.get("version") != 1:
                raise ValueError("不支持的 ShopWise 开发库备份版本")
            with package.open("database.dump") as src, dump.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            if digest(dump) != manifest.get("sha256"):
                raise ValueError("备份摘要不匹配")
    except (zipfile.BadZipFile, KeyError, UnicodeError) as exc:
        raise ValueError("备份损坏") from exc


def transfer(action, archive, env, database, compose):
    archive = archive.expanduser().absolute()
    if action == "export" and archive.exists():
        raise ValueError("目标归档已存在，请指定新文件名")
    with tempfile.TemporaryDirectory() as tmp:
        dump = Path(tmp) / "database.dump"
        if action == "import":
            unpack(archive, dump)
            cmd, child_env = pg_command(env, "pg_restore", compose)
            database()
            with dump.open("rb") as stream:
                subprocess.run([*cmd, "--single-transaction", "--exit-on-error", "--clean", "--if-exists",
                                "--no-owner", "--no-acl"], env=child_env, stdin=stream, check=True)
            print(f"已恢复 {archive.name}")
            return
        cmd, child_env = pg_command(env, "pg_dump", compose)
        database()
        with dump.open("wb") as stream:
            subprocess.run([*cmd, "--format=custom", "--no-owner", "--no-acl"],
                           env=child_env, stdout=stream, check=True)
        manifest = {"format": "shopwise-dev", "version": 1, "sha256": digest(dump)}
        # The exclusive open prevents a concurrent export from replacing a good backup.
        with archive.open("xb") as stream:
            try:
                with zipfile.ZipFile(stream, "w", allowZip64=True) as package:
                    package.write(dump, "database.dump")
                    package.writestr("manifest.json", json.dumps(manifest))
            except BaseException:
                stream.close()
                archive.unlink(missing_ok=True)
                raise
        print(f"已导出 {archive}")
