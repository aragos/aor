"""Lossless old joint numeric evidence, released only after both next-batch carries complete.

The latest numeric directory stays loose. This owner verifies bytes and completed provenance;
actor/optimizer validity remains the pair updater's responsibility. Original paths are retained
as evidence and never reopened when reading an archived member. No archive extraction occurs.
"""
import hashlib
import json
from pathlib import Path
import re
import tarfile

from automata_benchmark import digest
import purchase_storage as storage

read, sha, require = storage.read, storage.sha, storage.require
ARCHIVE, MANIFEST = 'numeric-evidence.tar.gz', 'numeric-storage.json'
VERSION, PAIR = 'joint-credit-numeric-storage-1', 'joint-credit-two-actor-update-1'
PHASES = ('card', 'purchase')
MEMBER = re.compile(r'(values\.json|targets/[a-zA-Z0-9_-]+\.json|pair/(bundle|finished|identity|card-completed|purchase-completed)\.json|pair/(card|purchase)/[a-zA-Z0-9_-]+\.json)')


def member_name(name):
    require(isinstance(name, str) and len(name) <= 128 and MEMBER.fullmatch(name) is not None,
            'Numeric evidence requires a bounded relative member path in its declared layout.')
    return name


def regular(path):
    path = Path(path)
    require(path.is_file() and not any(p.is_symlink() for p in (path, *path.parents)),
            'Numeric evidence requires original regular files without symlink parents.')
    return path


def receipt(path):
    path = regular(path)
    value = read(path)
    require(value.get('completed') is True and isinstance(value.get('artifacts'), dict),
            'Numeric storage requires a completed batch receipt with original artifact hashes.')
    return value


class NumericIndex:
    """Verify an archive once per audit; each read still checks its requested member's exact bytes."""
    def __init__(self, batch):
        self.batch = Path(batch).resolve(strict=True)
        self.numeric = self.batch/'numeric'
        self.receipt = receipt(self.batch/'receipt.json')
        expected = {member_name(name[8:]): value for name, value in self.receipt['artifacts'].items()
                    if name.startswith('numeric/')}
        require(expected and len(expected) <= 128 and all(re.fullmatch('[0-9a-f]{64}', h) for h in expected.values()),
                'The completed receipt must bind every bounded numeric member by SHA-256.')
        required = {'values.json', 'pair/identity.json', 'pair/finished.json', 'pair/bundle.json'} | {
            f'pair/{phase}/{name}.json' for phase in PHASES for name in ('identity','finished','policy','checkpoint')
        } | {f'pair/{phase}-completed.json' for phase in PHASES}
        require(required <= set(expected) and any(name.startswith('targets/') for name in expected),
                'Numeric storage requires values, targets and both complete phase evidence sets.')
        self.archive = None
        if (self.batch/MANIFEST).exists():
            manifest = read(regular(self.batch/MANIFEST))
            require(manifest.get('kind') == VERSION and manifest.get('schemaVersion') == 1
                and manifest.get('archive') == ARCHIVE and manifest.get('numericRoot') == str(self.numeric)
                and manifest.get('receipt') == dict(path=str(self.batch/'receipt.json'), sha256=sha(self.batch/'receipt.json')),
                'Numeric storage manifest must retain its original batch and completed receipt.')
            self.files = manifest['files']
            require(set(self.files) == set(expected), 'Numeric archive coverage differs from its completed receipt.')
            self.archive = self.batch/ARCHIVE
            for name, item in self.files.items():
                require(item['sha256'] == expected[name] and item['originalPath'] == str(self.numeric/name)
                    and type(item['size']) is int and 0 <= item['size'] <= 2**31,
                    'Numeric member hashes, sizes and original paths must match the completed batch.')
            storage.verify_members(self.archive, self.files, manifest['archiveSha256'])
            consumed = manifest['consumedBy']
            require(sha(regular(consumed['path'])) == consumed['sha256'], 'The consuming receipt changed.')
            self.require_consumed(consumed['path'])
        else:
            require(self.numeric.is_dir() and not self.numeric.is_symlink(), 'Completed numeric evidence is missing.')
            actual = {str(p.relative_to(self.numeric)): regular(p) for p in self.numeric.rglob('*') if not p.is_dir()}
            require(set(actual) == set(expected), 'Loose numeric coverage differs from its completed receipt.')
            self.files = {name:dict(sha256=sha(path),size=path.stat().st_size,originalPath=str(path))
                          for name,path in actual.items()}
            require(all(self.files[name]['sha256'] == value for name,value in expected.items()),
                    'Loose numeric evidence changed after completion.')
        self.require_pair()

    def read(self, relative_name):
        name = member_name(relative_name)
        require(name in self.files, 'Numeric evidence member is absent from the completed index.')
        if self.archive is None:
            data = regular(self.numeric/name).read_bytes()
        else:
            with tarfile.open(self.archive, 'r:gz') as source:
                with source.extractfile(name) as stream:
                    data = stream.read()
        require(len(data) == self.files[name]['size']
            and hashlib.sha256(data).hexdigest() == self.files[name]['sha256'],
            'Numeric evidence member changed after index verification.')
        return json.loads(data)

    def require_consumed(self, path):
        path = regular(path).resolve()
        require(path != self.batch/'receipt.json', 'A batch cannot consume its own numeric carry.')
        consumed = receipt(path)
        expected = {phase:dict(path=str(self.numeric/f'pair/{phase}/checkpoint.json'),
            sha256=self.files[f'pair/{phase}/checkpoint.json']['sha256']) for phase in PHASES}
        require(consumed.get('previousCheckpoints') == expected,
                'The completed next receipt must consume both exact preceding phase checkpoints.')
        return dict(path=str(path),sha256=sha(path))

    def require_pair(self):
        report, identity, bundle = (self.read('pair/'+name+'.json') for name in ('finished','identity','bundle'))
        require(report.get('kind') == identity.get('kind') == PAIR
            and report['identitySha256'] == digest(identity) and report.get('allInputHashesUnchanged') is True
            and report['outputBundleSha256'] == self.files['pair/bundle.json']['sha256'],
            'Numeric storage requires the complete unchanged two-phase pair publication.')
        outputs = {str(self.numeric/f'pair/{phase}/{name}.json'): self.files[f'pair/{phase}/{name}.json']['sha256']
                   for phase in PHASES for name in ('identity','finished','policy','checkpoint')}
        outputs.update({str(self.numeric/f'pair/{phase}-completed.json'):self.files[f'pair/{phase}-completed.json']['sha256']
                        for phase in PHASES})
        require(report['outputHashes'] == outputs, 'Both completed phase evidence sets must be bound before storage.')
        for phase in PHASES:
            component = report['phases'][phase]
            finished, phase_identity = (self.read(f'pair/{phase}/{name}.json') for name in ('finished','identity'))
            require(component == self.read(f'pair/{phase}-completed.json')
                and component['identitySha256'] == digest(phase_identity) == finished['identitySha256']
                and component['completionSha256'] == self.files[f'pair/{phase}/finished.json']['sha256']
                and component['checkpointSha256'] == self.files[f'pair/{phase}/checkpoint.json']['sha256']
                and component['policySha256'] == self.files[f'pair/{phase}/policy.json']['sha256'] == finished['outputModelSha256']
                and bundle[phase] == dict(path=str(self.numeric/f'pair/{phase}/policy.json'),sha256=component['policySha256'])
                and finished['stopReason'] in ('epochs-complete','behavior-kl-limit'),
                'The published numeric pair must retain both complete phase identities and outputs.')


def index_numeric(batch):
    return NumericIndex(batch)


def read_numeric(batch, relative_name, *, index=None):
    index = index_numeric(batch) if index is None else index
    require(isinstance(index, NumericIndex) and index.batch == Path(batch).resolve(strict=True),
            'Reuse only the verified numeric index for this exact batch.')
    return index.read(relative_name)


def archive_numeric(batch, consumed_receipt):
    index = index_numeric(batch)
    consumed = index.require_consumed(consumed_receipt)
    target, manifest_path = index.batch/ARCHIVE, index.batch/MANIFEST
    if not manifest_path.exists():
        if not target.exists():
            def write(stream):
                with tarfile.open(fileobj=stream, mode='w:gz', compresslevel=6) as archive:
                    for name in sorted(index.files):
                        archive.add(index.numeric/name, arcname=name, recursive=False)
            storage.publish(target, write)
        manifest = dict(schemaVersion=1,kind=VERSION,archive=ARCHIVE,archiveSha256=sha(target),
            numericRoot=str(index.numeric),receipt=dict(path=str(index.batch/'receipt.json'),
            sha256=sha(index.batch/'receipt.json')),consumedBy=consumed,files=index.files)
        storage.verify_members(target,index.files,manifest['archiveSha256'])
        storage.publish(manifest_path,lambda stream:stream.write((json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()))
    verified = index_numeric(index.batch)
    require(read(manifest_path)['consumedBy'] == consumed, 'Numeric storage cannot substitute its original consuming receipt.')
    remaining = [p for p in index.numeric.rglob('*') if not p.is_dir()] if index.numeric.exists() else []
    for path in remaining:
        name = member_name(str(path.relative_to(index.numeric)))
        require(name in verified.files and sha(regular(path)) == verified.files[name]['sha256'],
                'Numeric duplicates changed before verified removal.')
    for path in remaining:
        require(sha(regular(path)) == verified.files[str(path.relative_to(index.numeric))]['sha256'],
                'Numeric duplicate changed during removal.')
        path.unlink()
    if index.numeric.exists(): storage.sync_directory(index.numeric)
    return read(manifest_path)
