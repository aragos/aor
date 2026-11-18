"""Portable final16-only identity after complete numerical and native evidence admission."""
from pathlib import Path

import coordinated_es_plan as p
import coordinated_es_numeric_stage as numeric
import coordinated_es_storage as storage


def relative(path,owner):
    path,owner=Path(path).resolve(),Path(owner).resolve()
    p.require(path.is_relative_to(owner.parent),'Portable final subjects remain inside their admission directory.')
    return dict(path=storage.safe(path.relative_to(owner.parent).as_posix()),sha256=p.file_sha(path))


def resolve(subject,owner):
    target=Path(owner).resolve().parent/storage.safe(subject['path'])
    p.require(target.resolve().is_relative_to(Path(owner).resolve().parent),
        'A final-admission subject cannot leave its portable closure.')
    return p.checked_subject(dict(path=str(target),sha256=subject['sha256']))


def copy_bundle(subject,directory):
    source=p.checked_subject(subject);record=p.read(source);directory=Path(directory)
    p.require(record['kind']==p.PREFIX+'-bundle-1' and set(record)=={'kind','heads'} and
        set(record['heads'])==set(p.HEADS),'One complete four-head bundle is required.')
    for head,row in record['heads'].items():
        p.require(set(row)=={'path','sha256','dictionarySha256'},'Head subjects preserve model and full dictionary identity.')
        path=source.parent/storage.safe(row['path'])
        p.require(path.resolve().is_relative_to(source.parent.resolve()),'A head cannot escape its original bundle directory.')
        numeric.copy_file(dict(path=str(path),sha256=row['sha256']),directory/row['path'])
    return numeric.copy_file(subject,directory/'bundle.json')


def publish(protocol,profile,run,final_directory,numerical_audit,native_audit,output):
    """No selection: only the exact16 complete updates may publish their final mean."""
    plan=p.Plan.from_file(p.checked_subject(protocol));run=Path(run);output=Path(output)
    numeric_row=p.read(p.checked_subject(numerical_audit));native_row=p.read(p.checked_subject(native_audit))
    p.require(numeric_row['kind']==p.PREFIX+'-numerical-audit-1' and native_row['kind']==p.PREFIX+'-training-audit-1' and
        all(row['passed'] is True and row['complete'] is True and row['completedGenerations']==16 and
            row['protocolSha256']==protocol['sha256'] for row in (numeric_row,native_row)),
        'A final admission requires both complete fixed numerical and native evidence, never a prefix.')
    p.require(native_row['rawControls']==4096 and native_row['mutants']==131072 and
        native_row['reproductions']==32,'Every original common world and prescribed reproduction must be complete.')
    p.require(not output.exists(),'The sole final mean has one immutable portable publication.')
    output.mkdir(parents=True);owner=output/'final-admission.json'
    numeric.copy_file(protocol,output/'protocol.json')
    copy_bundle(profile['originalBundle'],output/'original')
    numeric.copy_file(profile['projection'],output/'projection.json')
    numeric.copy_tree(run/'generations/generation-1/calibrate',output/'calibration')
    numeric.copy_tree(run/'historical',output/'historical')
    numeric.copy_tree(final_directory,output/'final')
    numeric.copy_file(numerical_audit,output/'numerical-audit.json')
    numeric.copy_file(native_audit,output/'native-audit.json')
    rows=[]
    for number in range(1,17):
        receipt_path=run/'generations'/f'generation-{number}'/'receipt.json'
        receipt=p.read(receipt_path)
        p.require(numeric_row['artifactPins'].get(str(receipt_path.absolute()))==p.file_sha(receipt_path),
            'Portable publication must use each exact previously audited generation receipt.')
        p.require(receipt['kind']==p.PREFIX+'-generation-receipt-1' and receipt['complete'] is True and
            receipt['generation']==number and receipt['protocolSha256']==protocol['sha256'],
            'All16 original generation receipts must remain present and ordered.')
        directory=output/'generations'/f'generation-{number}'
        update=numeric.copy_file(receipt['update'],directory/'update.json')
        center=numeric.copy_file(receipt['outputCenter'],directory/'center.json')
        rows.append(dict(generation=number,inputCenterSha256=receipt['inputCenter']['sha256'],
            outputCenterSha256=center['sha256'],outputCenter=relative(center['path'],output/'generation-audit.json'),
            update=relative(update['path'],output/'generation-audit.json'),populationSha256=receipt['population']['sha256'],
            controls=256,mutants=8192,reproductions=2))
    materialization=p.read(output/'final/center-materialization.json')
    p.require(materialization['kind']==p.PREFIX+'-center-materialization-1' and
        materialization['protocolSha256']==protocol['sha256'] and materialization['generation']==16 and
        materialization['finalCandidate'] is True and materialization['centerSha256']==rows[-1]['outputCenterSha256'] and
        materialization['bundleSha256']==p.file_sha(output/'final/bundle.json'),
        'The only executable final bundle must be the unchanged materialization of mean16.')
    p.require(numeric_row['eligibleFinalCenter']['sha256']==rows[-1]['outputCenterSha256'],
        'The complete independent numerical audit must identify this exact final mean.')
    audit=dict(kind=p.PREFIX+'-generation-audit-1',passed=True,complete=True,
        protocolSha256=protocol['sha256'],scopeSha256=plan.record['original']['scopeSha256'],
        ecologySha256=plan.record['original']['ecologySha256'],originalBundleSha256=profile['originalBundle']['sha256'],
        projectionSha256=profile['projection']['sha256'],calibrationSha256=p.file_sha(output/'calibration/calibration.json'),
        completedGenerations=16,finalCenterSha256=rows[-1]['outputCenterSha256'],generations=rows)
    storage.publish_json(output/'generation-audit.json',audit)
    fields=dict(protocol='protocol.json',originalBundle='original/bundle.json',projection='projection.json',
        calibration='calibration/calibration.json',center='generations/generation-16/center.json',
        finalBundle='final/bundle.json',centerMaterialization='final/center-materialization.json',
        generationAudit='generation-audit.json',historical='historical/historical-admission.json')
    record=dict(kind=p.PREFIX+'-final-admission-1',complete=True,completedGenerations=16,
        scopeSha256=plan.record['original']['scopeSha256'],ecologySha256=plan.record['original']['ecologySha256'],
        validationEffectsUsedForSelection=False,**{key:relative(output/name,owner) for key,name in fields.items()})
    storage.publish_json(owner,record)
    return p.subject(owner)
