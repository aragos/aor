"""One lossless capsule per completed triple batch; carry deletion needs its actual consumer."""
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile

import automata_experiments as exp
import purchase_storage as storage

VERSION = 'triple-mc-batch-capsule-1'
read, sha, require = exp.read, exp.sha, exp.require
OWNERS = {'collection', 'reproduction', 'numeric'}
TOP_FILES = {'joint-source.json', 'collection-source.json', 'numeric-complete.json',
    'collection.log', 'reproduction.log', 'numeric.log'}


def relative(name):
    path = PurePosixPath(name)
    require(isinstance(name, str) and str(path) == name and not path.is_absolute()
        and '..' not in path.parts and name not in ('', '.'), 'Unsafe triple capsule member.')
    require(path.parts[0] in OWNERS or name in TOP_FILES, 'Undeclared triple batch output: '+name)
    return path


def members(directory):
    files = {}
    for path in sorted(directory.rglob('*')):
        require(not path.is_symlink(), 'Triple evidence cannot contain symlinks.')
        if path.is_dir():
            require(path.relative_to(directory).parts[0] in OWNERS, 'Undeclared triple evidence directory.')
            continue
        if path.name == '.DS_Store':continue
        name = path.relative_to(directory).as_posix();relative(name)
        require(path.is_file(), 'Triple evidence must contain regular files only.')
        files[name] = dict(sha256=sha(path), size=path.stat().st_size, originalPath=str(path))
    return files


def immutable(path, value):
    require(not path.exists() and not path.with_name(path.name+'.writing').exists(),
        'A capsule or receipt permits one publication without replacement or retry.')
    storage.publish(path, lambda stream:stream.write(
        (json.dumps(value,sort_keys=True,indent=2,allow_nan=False)+'\n').encode()))


def archive_batch(directory, archive, arm, index):
    directory, archive = Path(directory).resolve(strict=True), Path(archive).absolute()
    require(arm in ('JOINT','E_ONLY') and type(index) is int and 1 <= index <= 32,
        'Triple capsule requires its explicit arm and original batch index.')
    done = read(directory/'collection/finished.json');numeric = read(directory/'numeric/finished.json')
    require(done['kind'] == 'triple-on-policy-collect-1' and done['complete'] is True
        and done['games'] == 256 and numeric['complete'] is True and numeric['kind'] ==
        ('triple-mc-update-1' if arm == 'JOINT' else 'triple-mc-e-only-control-1'),
        'Only a completely checked collection and numeric transaction can be archived.')
    files = members(directory)
    require(TOP_FILES <= set(files) and {'reproduction/replay.json','numeric/identity.json',
        'numeric/finished.json','numeric/triple-bundle.json'} <= set(files),
        'A batch capsule must include all source, reproduction, numerical and log evidence.')
    archive.parent.mkdir(parents=True,exist_ok=True)
    index_path = archive.with_name(archive.name+'.json')
    require(not archive.exists() and not archive.with_name(archive.name+'.writing').exists()
        and not index_path.exists(), 'Triple batch capsules never replace an existing attempt.')
    def write(stream):
        with tarfile.open(fileobj=stream,mode='w:gz',compresslevel=6) as target:
            for name in files:target.add(directory/name,arcname=name,recursive=False)
    storage.publish(archive,write)
    digest = sha(archive);storage.verify_members(archive,files,digest)
    require(members(directory) == files, 'Original evidence changed during capsule publication.')
    manifest = dict(kind=VERSION,arm=arm,batch=index,originalRoot=str(directory),
        archive=archive.name,archiveSha256=digest,files=files)
    immutable(index_path,manifest)
    return dict(path=str(archive),sha256=digest,indexPath=str(index_path),indexSha256=sha(index_path))


class BatchIndex:
    """Verify once, then stream archived bytes without reopening pruned original paths."""
    def __init__(self, archive):
        self.archive = Path(archive).absolute()
        self.path = self.archive.with_name(self.archive.name+'.json')
        require(not self.path.is_symlink(), 'Capsule index cannot be a symlink.')
        self.document = read(self.path);self.index_sha = sha(self.path)
        m = self.document;self.files = m['files']
        require(m['kind'] == VERSION and m['archive'] == self.archive.name
            and m['arm'] in ('JOINT','E_ONLY') and type(m['batch']) is int and 1 <= m['batch'] <= 32
            and Path(m['originalRoot']).is_absolute(), 'Invalid triple capsule index identity.')
        for name,item in self.files.items():
            relative(name)
            require(set(item) == {'sha256','size','originalPath'} and type(item['size']) is int
                and item['size'] >= 0 and item['originalPath'] == str(Path(m['originalRoot'])/name),
                'Capsule members must retain exact original absolute-path provenance.')
        storage.verify_members(self.archive,self.files,m['archiveSha256'])
        self.original_hashes = {item['originalPath']:item['sha256'] for item in self.files.values()}

    def iter_members(self, names=None, prefixes=None):
        wanted = set(self.files) if names is None else set(names)
        require(wanted <= set(self.files), 'Requested evidence is absent from the completed capsule.')
        if prefixes is not None:wanted = {n for n in wanted if any(n.startswith(p) for p in prefixes)}
        require(sha(self.path) == self.index_sha and sha(self.archive) == self.document['archiveSha256'],
            'Capsule changed after verification.')
        seen = set()
        with tarfile.open(self.archive,'r|gz') as source:
            for item in source:
                require(item.isfile() and item.name in self.files and item.name not in seen,
                    'Unexpected or duplicate archived member.')
                seen.add(item.name)
                if item.name not in wanted:continue
                raw = source.extractfile(item).read();expected = self.files[item.name]
                require(len(raw) == expected['size'] and hashlib.sha256(raw).hexdigest() == expected['sha256'],
                    'Archived bytes changed: '+item.name)
                yield item.name,raw
        require(seen == set(self.files) and sha(self.path) == self.index_sha
            and sha(self.archive) == self.document['archiveSha256'], 'Completed capsule inventory changed.')

    def read(self, name):
        values = list(self.iter_members(names=[name]))
        raw = values[0][1]
        return json.loads(gzip.decompress(raw) if name.endswith('.gz') else raw)


def prune(index, *, numeric=False):
    """Delete verified duplicates only; all remaining scientific files are checked before any delete."""
    root = Path(index.document['originalRoot']);actual = members(root)
    require(set(actual) <= set(index.files) and all(actual[n] == index.files[n] for n in actual),
        'Changed or undeclared loose bytes prevent duplicate removal.')
    require(sha(index.path) == index.index_sha and sha(index.archive) == index.document['archiveSha256'],
        'Only unchanged durable capsules permit duplicate removal.')
    for name in actual:
        if name.startswith('numeric/') == numeric:(root/name).unlink()
    storage.sync_directory(root)


def release_numeric(index, consumed_receipt, output):
    consumed_receipt = Path(consumed_receipt).resolve(strict=True);receipt = read(consumed_receipt)
    m = index.document;active = ('expansion','card','purchase') if m['arm'] == 'JOINT' else ('expansion',)
    subject = lambda name:dict(path=index.files[name]['originalPath'],sha256=index.files[name]['sha256'])
    require(receipt['kind'] == 'triple-mc-matched-batch-1' and receipt['complete'] is True
        and receipt['arm'] == m['arm'] and receipt['batch'] == m['batch']+1
        and receipt['previousCompletion'] == subject('numeric/finished.json')
        and receipt['previousCheckpoints'] == {p:subject(f'numeric/{p}/checkpoint.json') for p in active},
        'Only the completed next same-arm transaction can consume and release original numeric carry.')
    # The consumer itself is durable before its predecessor can be removed.
    capsule = receipt['capsule'];consumer = BatchIndex(capsule['path'])
    require(consumer.document['arm'] == receipt['arm'] and consumer.document['batch'] == receipt['batch']
        and sha(consumer.path) == capsule['indexSha256'] and sha(consumer.archive) == capsule['sha256'],
        'The consuming transaction must have its own verified lossless capsule.')
    names = ['numeric/identity.json']+[f'numeric/{p}/identity.json' for p in active]
    identities = {name:json.loads(raw) for name,raw in consumer.iter_members(names=names)}
    require(identities['numeric/identity.json']['previous'] == dict(path=str(Path(m['originalRoot'])/'numeric'),
        completionSha256=receipt['previousCompletion']['sha256']) and all(
        identities[f'numeric/{p}/identity.json']['optimizerFromSha256'] == receipt['previousCheckpoints'][p]['sha256']
        for p in active), 'Actual next phase identities must bind the consumed original checkpoints.')
    require(not Path(output).exists(), 'Numeric carry release is once-only.')
    prune(index,numeric=True)
    immutable(Path(output),dict(kind='triple-mc-carry-release-1',arm=m['arm'],batch=m['batch'],
        capsuleSha256=m['archiveSha256'],consumedBy=dict(path=str(consumed_receipt),sha256=sha(consumed_receipt)),
        previousCompletion=receipt['previousCompletion'],previousCheckpoints=receipt['previousCheckpoints'],
        verifiedDuplicatesRemoved=True))
