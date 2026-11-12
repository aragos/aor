"""Losslessly retain explicitly selected, completed training evidence.

Plans contain schemaVersion=1, an absolute root, completion={path, sha256}, and
files=[{path, sha256}]. Paths are root-relative regular files. The completion JSON
must say passed=true and status=completed. No file discovery, active-run cleanup,
or automatic retention policy is performed. The caller selects closed evidence.

pack commits an archive and receipt before optional pruning. Interrupted pruning
can be resumed with prune; restore also works when only some originals remain.
Receipts retain each original path, content hash, mode and nanosecond timestamp.
Only file content is archived; symlinks and special files are rejected.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import tempfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_bytes())


def digest(path):
    with Path(path).open('rb') as stream:
        return stream_digest(stream)


def stream_digest(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        result.update(block)
    return result.hexdigest()


def relative_path(value):
    path = PurePosixPath(value)
    require(isinstance(value, str) and value and not path.is_absolute()
            and '..' not in path.parts and str(path) == value,
            'Evidence paths must be canonical relative paths without parent traversal.')
    return path


def local_path(root, name):
    relative_path(name)
    path = root
    for part in PurePosixPath(name).parts:
        path = path / part
        require(not path.is_symlink(), 'Evidence paths must not traverse symlinks.')
    return path


def regular(path):
    value = path.stat(follow_symlinks=False)
    require(stat.S_ISREG(value.st_mode), 'Evidence members must be regular files.')
    return value


def exclusive_json(path, document):
    # A receipt must never expose incomplete JSON or overwrite an earlier receipt.
    encoded = (json.dumps(document, indent=2, sort_keys=True, allow_nan=False) + '\n').encode()
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.evidence-', delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.link(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink()


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def rows(document):
    require(document['schemaVersion'] == 1 and isinstance(document['files'], list)
            and document['files'], 'An evidence manifest requires a nonempty version-one file list.')
    names = [row['path'] for row in document['files']]
    require(len(set(names)) == len(names), 'Evidence member paths must be unique.')
    for row in document['files']:
        relative_path(row['path'])
        require(len(row['sha256']) == 64 and all(c in '0123456789abcdef' for c in row['sha256']),
                'Each evidence member requires a lowercase SHA-256 hash.')
    return document['files']


def verify_archive(archive, members):
    expected = {row['path']: row for row in members}
    seen = set()
    with tarfile.open(archive, 'r|gz') as source:
        for member in source:
            require(member.name in expected and member.name not in seen and member.isfile(),
                    'Archive contains an undeclared, duplicate or non-file member.')
            row = expected[member.name]
            require(member.size == row['size'], 'An archived member has the wrong size.')
            stream = source.extractfile(member)
            require(stream is not None and stream_digest(stream) == row['sha256'],
                    'An archived member failed its original content hash.')
            seen.add(member.name)
    require(seen == set(expected), 'The archive is missing declared evidence members.')


def verified_receipt(receipt_path):
    receipt = read(receipt_path)
    members = rows(receipt)
    archive = Path(receipt['archive'])
    regular(archive)
    require(digest(archive) == receipt['archiveSha256'], 'The retained archive changed.')
    verify_archive(archive, members)
    return receipt, archive, members


def verify_existing(root, members, missing_allowed):
    for row in members:
        path = local_path(root, row['path'])
        if not path.exists():
            require(missing_allowed, 'A declared original evidence file is missing.')
            continue
        value = regular(path)
        require(value.st_size == row['size'] and digest(path) == row['sha256'],
                'An original evidence file changed; leave all originals untouched.')


def pack(plan_path, archive_path, receipt_path, remove=False):
    plan_hash = digest(plan_path)
    plan = read(plan_path)
    requested = rows(plan)
    root = Path(plan['root']).resolve(strict=True)
    require(root.is_dir() and Path(plan['root']).is_absolute(), 'The evidence root must be absolute.')
    completion = plan['completion']
    completed = local_path(root, completion['path'])
    regular(completed)
    require(digest(completed) == completion['sha256'], 'The completed-run admission changed.')
    admitted = read(completed)
    require(admitted.get('passed') is True and admitted.get('status') == 'completed',
            'Archive only successfully audited completed training evidence.')
    archive_path, receipt_path = Path(archive_path).absolute(), Path(receipt_path).absolute()
    require(archive_path != receipt_path and not archive_path.exists() and not receipt_path.exists(),
            'Archive and receipt destinations must be distinct new paths.')
    require(completion['path'] not in {row['path'] for row in requested},
            'Keep the small completion admission expanded.')
    members = []
    for row in requested:
        path = local_path(root, row['path'])
        value = regular(path)
        require(digest(path) == row['sha256'], 'A planned evidence file changed.')
        require(path not in (archive_path, receipt_path), 'Do not archive an output destination.')
        members.append(dict(row, size=value.st_size, mode=stat.S_IMODE(value.st_mode),
                            mtimeNs=value.st_mtime_ns))
    with tempfile.NamedTemporaryFile(dir=archive_path.parent, prefix='.evidence-', delete=False) as raw:
        temporary = Path(raw.name)
    try:
        with temporary.open('wb') as raw:
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=6) as zipped:
                with tarfile.open(fileobj=zipped, mode='w|', format=tarfile.PAX_FORMAT) as target:
                    for row in members:
                        info = tarfile.TarInfo(row['path'])
                        info.size, info.mode = row['size'], row['mode']
                        # Exact ns timestamps live in the receipt; avoid float rounding in tar headers.
                        info.mtime = row['mtimeNs'] // 1_000_000_000
                        with local_path(root, row['path']).open('rb') as source:
                            target.addfile(info, source)
            raw.flush()
            os.fsync(raw.fileno())
        verify_archive(temporary, members)
        verify_existing(root, members, missing_allowed=False)
        require(digest(completed) == completion['sha256'], 'Completion admission changed during packing.')
        require(digest(plan_path) == plan_hash, 'The explicit evidence plan changed during packing.')
        archive_hash = digest(temporary)
        os.link(temporary, archive_path)
        sync_directory(archive_path.parent)
        receipt = dict(schemaVersion=1, kind='training-evidence-archive-1', root=str(root),
                       completion=completion, planSha256=plan_hash, archive=str(archive_path),
                       archiveSha256=archive_hash, archiveBytes=archive_path.stat().st_size,
                       originalBytes=sum(row['size'] for row in members), files=members)
        exclusive_json(receipt_path, receipt)
    finally:
        temporary.unlink(missing_ok=True)
    if remove:
        prune(receipt_path)
    return receipt


def prune(receipt_path):
    receipt, _, members = verified_receipt(receipt_path)
    root = Path(receipt['root']).resolve(strict=True)
    # Validate the whole remaining set first, so a stale member prevents any further deletion.
    verify_existing(root, members, missing_allowed=True)
    for row in members:
        local_path(root, row['path']).unlink(missing_ok=True)
    return receipt


def restore(receipt_path, destination=None):
    receipt, archive, members = verified_receipt(receipt_path)
    root = Path(destination or receipt['root']).resolve(strict=True)
    verify_existing(root, members, missing_allowed=True)
    expected = {row['path']: row for row in members}
    with tarfile.open(archive, 'r|gz') as source:
        for member in source:
            row = expected[member.name]
            path = local_path(root, member.name)
            if path.exists():
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.evidence-', delete=False) as stream:
                temporary = Path(stream.name)
                with source.extractfile(member) as content:
                    shutil.copyfileobj(content, stream, length=1024 * 1024)
                stream.flush()
                os.fsync(stream.fileno())
            try:
                require(digest(temporary) == row['sha256'], 'Restored member failed its content hash.')
                temporary.chmod(row['mode'])
                os.utime(temporary, ns=(row['mtimeNs'], row['mtimeNs']))
                os.link(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)
    verify_existing(root, members, missing_allowed=False)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('pack')
    create.add_argument('--plan', type=Path, required=True)
    create.add_argument('--archive', type=Path, required=True)
    create.add_argument('--receipt', type=Path, required=True)
    create.add_argument('--prune', action='store_true')
    for command in ('verify', 'prune', 'restore'):
        child = commands.add_parser(command)
        child.add_argument('--receipt', type=Path, required=True)
        if command == 'restore':
            child.add_argument('--root', type=Path)
    args = parser.parse_args()
    if args.command == 'pack':
        result = pack(args.plan, args.archive, args.receipt, args.prune)
    elif args.command == 'prune':
        result = prune(args.receipt)
    elif args.command == 'restore':
        result = restore(args.receipt, args.root)
    else:
        result = verified_receipt(args.receipt)[0]
    print(json.dumps(dict(passed=True, command=args.command, files=len(result['files']),
                         originalBytes=result['originalBytes'], archiveBytes=result['archiveBytes'])))


if __name__ == '__main__':
    main()
