"""Portable final16 continuation identity, shared by publication and pure final-screen audit.

All model subjects are opaque byte hashes. The old scalar calibration remains associated with
its original protocol, while the new envelope, warm start and supported ecology remain explicit.
"""
from pathlib import Path

import coordinated_es_admission as base
import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_es_storage as storage
import coordinated_win_generation_audit as audit
import coordinated_win_numeric_stage as numeric
import coordinated_win_plan as p
import coordinated_win_storage as wins

relative,resolve,copy_bundle=base.relative,base.resolve,base.copy_bundle
SUBJECTS=('protocol','originalBundle','projection','warmStart','startBundle','inheritedCalibration',
    'calibration','numericalPreparation','center','finalBundle','centerMaterialization','generationAudit','historical')


def opaque_bundle(item,pins,expected_heads=None):
    owner=pins.subject(item);value=pins.read(owner)
    p.exact(value,('kind','heads'),'Portable complete bundle')
    p.require(value['kind']=='coordinated-es-bundle-1' and set(value['heads'])==set(p.HEADS),
        'A portable continuation retains one complete four-head descriptor.')
    for head,row in value['heads'].items():
        p.exact(row,('path','sha256','dictionarySha256'),'Opaque head subject')
        path=resolve(dict(path=row['path'],sha256=row['sha256']),owner);pins.pin(path,row['sha256'])
        if expected_heads is not None:
            p.require(row['sha256']==expected_heads[head]['actorSha256'] and
                row['dictionarySha256']==expected_heads[head]['dictionarySha256'],
                'Original raw heads and complete dictionary identities cannot change.')
    return value


def final_bindings(plan,protocol_sha,final_subject,pins):
    """Rehash the complete portable lineage; native admission owns tensor/value validation."""
    owner=pins.subject(final_subject);record=pins.read(owner)
    p.exact(record,('kind','complete','completedGenerations','scopeSha256','ecologySha256','warmStartSha256',
        'validationEffectsUsedForSelection',*SUBJECTS),'Final continuation admission')
    p.require(protocol_sha==plan.file_sha256 and record['kind']==p.PREFIX+'-final-admission-1' and
        record['complete'] is True and type(record['completedGenerations']) is int and record['completedGenerations']==16 and
        record['scopeSha256']==plan.scope.scope_sha256 and record['ecologySha256']==plan.record['evaluation']['ecologySha256'] and
        record['warmStartSha256']==plan.record['warmStart']['identitySha256'] and
        record['validationEffectsUsedForSelection'] is False,
        'Only the complete fixed final continuation can become an executable admission.')
    subjects={key:dict(path=str(resolve(record[key],owner)),sha256=record[key]['sha256']) for key in SUBJECTS}
    for item in subjects.values():pins.subject(item)
    expected=dict(protocol=protocol_sha,originalBundle=plan.scope.original_bundle_sha256,
        projection=plan.scope.projection_sha256,warmStart=plan.record['warmStart']['identitySha256'],
        startBundle=plan.record['warmStart']['bundleSha256'],calibration=plan.record['calibration']['originCalibrationSha256'])
    p.require(all(subjects[key]['sha256']==digest for key,digest in expected.items()),
        'Portable final admission retains every exact inherited original, warm parent and old scalar seal.')
    p.require(p.Plan.from_file(subjects['protocol']['path']).wire==plan.wire,'The final raw protocol cannot change.')
    warm=c.WarmStart.from_record(audit.subject(pins,subjects['warmStart'],True));plan.verify_warm_start(warm)
    envelope=wins.inherited_calibration(plan,protocol_sha,subjects['inheritedCalibration'])
    for name in ('originProtocol','originCalibration'):pins.subject(wins.sibling(subjects['inheritedCalibration'],envelope[name]))
    p.require(envelope['originCalibration']['sha256']==subjects['calibration']['sha256'],
        'The portable raw calibration is exactly the old seal named by the inherited envelope.')
    for head in ('EXPANSION','TOKEN'):
        seal=pins.read(subjects['calibration']['path'])
        pins.pin(Path(subjects['calibration']['path']).parent/f'gap-{head}.json',seal['heads'][head]['panelSha256'])
    original=opaque_bundle(subjects['originalBundle'],pins,plan.record['parameterSpace']['heads'])
    for name in ('startBundle','finalBundle'):
        bundle=opaque_bundle(subjects[name],pins)
        p.require(all(bundle['heads'][head]['dictionarySha256']==original['heads'][head]['dictionarySha256'] for head in p.HEADS),
            'All continuation head descriptors retain the original complete dictionaries.')
    prepared=pins.read(subjects['numericalPreparation']['path']);prep_root=Path(subjects['numericalPreparation']['path']).parent
    p.require(prepared['kind']==p.PREFIX+'-numeric-complete-1' and prepared['verb']=='prepare' and
        prepared['passed'] is True and prepared['complete'] is True and prepared['protocolSha256']==protocol_sha and
        prepared['execution']==audit.ZERO_EXECUTION and
        prepared['artifacts']['warm-start.json']==subjects['warmStart']['sha256'] and
        prepared['artifacts']['projection.json']==subjects['projection']['sha256'] and
        prepared['artifacts']['warm-bundle/bundle.json']==subjects['startBundle']['sha256'] and
        prepared['artifacts']['calibration/inherited-calibration.json']==subjects['inheritedCalibration']['sha256'],
        'Final lineage retains the exact completed zero-game preparation and its inherited executing subjects.')
    expected_prep={'finished.json'}|{f'directions/generation-{number:02d}-generation.json' for number in range(1,17)}
    p.require(set(storage.inventory(prep_root))==expected_prep,'The portable preparation proof has exactly its17 original byte subjects.')
    direction_shas=[]
    for number in range(1,17):
        name=f'directions/generation-{number:02d}-generation.json';pins.pin(prep_root/name,prepared['artifacts'][name])
        generated=pins.read(prep_root/name);digest=prepared['artifacts'][f'directions/generation-{number:02d}.json']
        p.require(generated['kind']=='coordinated-es-direction-generation-1' and generated['generation']==number and
            generated['seed']==plan.bank(number)['directionSeed'] and generated['generator']=='numpy-PCG64' and
            generated['distribution']=='independent-standard-normal' and generated['shape']==[16,256] and
            generated['renormalized'] is False and generated['directionsSha256']==digest,
            'Every final update retains its prospectively prepared direction stream and original raw table identity.')
        direction_shas.append(digest)
    compact=pins.read(subjects['generationAudit']['path'])
    p.require(compact['kind']==p.PREFIX+'-generation-audit-1' and compact['passed'] is True and compact['complete'] is True and
        compact['completedGenerations']==16 and compact['protocolSha256']==protocol_sha and
        compact['scopeSha256']==plan.scope.scope_sha256 and compact['ecologySha256']==record['ecologySha256'] and
        compact['warmStartSha256']==warm.sha256 and compact['objective']==c.OBJECTIVE and
        compact['originalBundleSha256']==subjects['originalBundle']['sha256'] and
        compact['projectionSha256']==subjects['projection']['sha256'] and
        compact['inheritedCalibrationSha256']==subjects['inheritedCalibration']['sha256'] and
        compact['calibrationSha256']==subjects['calibration']['sha256'] and
        compact['numericalPreparationSha256']==subjects['numericalPreparation']['sha256'] and
        len(compact['generations'])==16,'The compact final audit binds the same complete numerical and execution identities.')
    previous=es.digest(c.Center.initial(warm).record)
    for number,row in enumerate(compact['generations'],1):
        p.require(row['generation']==number and row['inputCenterSha256']==previous and
            row['controls']==256 and row['mutants']==8192 and row['reproductions']==2,
            'Final publication retains all sixteen ordered complete world cohorts without fallback.')
        update_path=resolve(row['update'],subjects['generationAudit']['path']);pins.pin(update_path,row['update']['sha256'])
        center_path=resolve(row['outputCenter'],subjects['generationAudit']['path']);pins.pin(center_path,row['outputCenter']['sha256'])
        update=pins.read(update_path);center=c.Center.from_record(pins.read(center_path),warm)
        p.require(update['kind']==c.UPDATE_KIND and update['complete'] is True and update['objective']==c.OBJECTIVE and
            update['mathRecipe']==es.RECIPE and update['protocolSha256']==protocol_sha and update['warmStartSha256']==warm.sha256 and
            update['previousCenterSha256']==previous and update['directionsSha256']==direction_shas[number-1] and
            update['populationSha256']==row['populationSha256'] and update['center']==center.record and center.step==number and
            row['outputCenterSha256']==row['outputCenter']['sha256']==es.digest(center.record),
            'Every compact update must bind its actual previous center, prepared directions and exact next coordinates.')
        previous=row['outputCenterSha256']
    final=c.Center.from_record(audit.subject(pins,subjects['center'],True),warm)
    p.require(final.step==16 and previous==subjects['center']['sha256']==compact['finalCenterSha256'],
        'The final admission cannot replace the sixteenth completed mean with an earlier center or mutant.')
    materialized=audit.subject(pins,subjects['centerMaterialization'],True)
    p.require(materialized==dict(kind='coordinated-es-continuation-center-materialization-1',protocolSha256=protocol_sha,
        centerSha256=subjects['center']['sha256'],step=16,warmStartSha256=warm.sha256,
        projectionSha256=subjects['projection']['sha256'],originalBundleSha256=subjects['originalBundle']['sha256'],
        inheritedCalibrationSha256=subjects['inheritedCalibration']['sha256'],bundleSha256=subjects['finalBundle']['sha256'],
        finalCandidate=True),'The final actor is the exact admitted materialization of the sixteenth global center.')
    binding=dict(finalBundleSha256=subjects['finalBundle']['sha256'],startBundleSha256=subjects['startBundle']['sha256'],
        originalBundleSha256=subjects['originalBundle']['sha256'],projectionSha256=subjects['projection']['sha256'],
        warmStartSha256=warm.sha256,inheritedCalibrationSha256=subjects['inheritedCalibration']['sha256'],
        calibrationSha256=subjects['calibration']['sha256'],centerSha256=subjects['center']['sha256'])
    return subjects,binding


def publish(protocol,profile,run,final_directory,numerical_audit,native_audit,output):
    """Copy one final mean after both exact complete audits; never select among earlier centers."""
    plan=p.Plan.from_file(p.checked_subject(protocol));run=Path(run);output=Path(output);final_directory=Path(final_directory)
    numerical=p.read(p.checked_subject(numerical_audit));native=p.read(p.checked_subject(native_audit))
    p.require(numerical['kind']==p.PREFIX+'-numerical-audit-1' and native['kind']==p.PREFIX+'-training-audit-1' and
        all(row['passed'] is True and row['complete'] is True and row['completedGenerations']==16 and
            row['protocolSha256']==protocol['sha256'] for row in (numerical,native)) and
        native['supportedControls']==4096 and native['mutants']==131072 and native['reproductions']==32,
        'Final publication requires both complete numerical and supported-native evidence, never a prefix.')
    p.require(numerical['objective']==c.OBJECTIVE and numerical['scopeSha256']==plan.scope.scope_sha256 and
        numerical['warmStartSha256']==profile['warmStart']['sha256'] and
        numerical['ecologySha256']==plan.record['evaluation']['ecologySha256'] and
        numerical['inheritedCalibrationSha256']==profile['inheritedCalibration']['sha256'],
        'The sole numerical audit must name this exact inherited selector and supported continuation.')
    pins=audit.Pins();clock=audit.clock_record(pins,run,protocol)[1]
    complete,request=audit.numeric(pins,p.subject(final_directory/'finished.json'),'center',protocol,clock)
    audit.request_identity(request,profile,protocol)
    p.require(request['center']['sha256']==numerical['eligibleFinalCenter']['sha256'],
        'Final materialization consumes the exact independently admitted sixteenth center.')
    p.require(not output.exists(),'The sole complete final mean has one immutable portable publication.')
    output.mkdir(parents=True);owner=output/'final-admission.json'
    numeric.copy_file(protocol,output/'protocol.json');copy_bundle(profile['originalBundle'],output/'original')
    numeric.copy_file(profile['projection'],output/'projection.json');numeric.copy_file(profile['warmStart'],output/'warm-start.json')
    prep_path=p.checked_subject(profile['numericalPreparation']['finished']);prep=p.read(prep_path)
    copy_bundle(p.subject(prep_path.parent/'warm-bundle/bundle.json'),output/'start')
    numeric.copy_tree(Path(profile['inheritedCalibration']['path']).parent,output/'calibration')
    numeric.copy_file(profile['numericalPreparation']['finished'],output/'preparation/finished.json')
    for number in range(1,17):
        name=f'directions/generation-{number:02d}-generation.json'
        numeric.copy_file(dict(path=str(prep_path.parent/name),sha256=prep['artifacts'][name]),output/'preparation'/name)
    numeric.copy_tree(run/'historical',output/'historical');numeric.copy_tree(final_directory,output/'final')
    numeric.copy_file(numerical_audit,output/'numerical-audit.json');numeric.copy_file(native_audit,output/'native-audit.json')
    rows=[]
    for number in range(1,17):
        path=run/'generations'/f'generation-{number}'/'receipt.json';receipt=p.read(path)
        p.require(numerical['artifactPins'].get(str(path.absolute()))==p.file_sha(path) and
            receipt['kind']==p.PREFIX+'-generation-receipt-1' and receipt['complete'] is True and
            receipt['generation']==number and receipt['protocolSha256']==protocol['sha256'],
            'Final publication uses each exact wrapper receipt retained by the full numerical audit.')
        directory=output/'generations'/f'generation-{number}'
        update=numeric.copy_file(receipt['update'],directory/'update.json');center=numeric.copy_file(receipt['outputCenter'],directory/'center.json')
        rows.append(dict(generation=number,inputCenterSha256=receipt['inputCenter']['sha256'],
            outputCenterSha256=center['sha256'],outputCenter=relative(center['path'],output/'generation-audit.json'),
            update=relative(update['path'],output/'generation-audit.json'),populationSha256=receipt['population']['sha256'],
            controls=256,mutants=8192,reproductions=2))
    compact=dict(kind=p.PREFIX+'-generation-audit-1',passed=True,complete=True,completedGenerations=16,
        protocolSha256=protocol['sha256'],scopeSha256=plan.scope.scope_sha256,ecologySha256=plan.record['evaluation']['ecologySha256'],
        warmStartSha256=profile['warmStart']['sha256'],objective=c.OBJECTIVE,originalBundleSha256=profile['originalBundle']['sha256'],
        projectionSha256=profile['projection']['sha256'],inheritedCalibrationSha256=profile['inheritedCalibration']['sha256'],
        calibrationSha256=plan.record['calibration']['originCalibrationSha256'],numericalPreparationSha256=profile['numericalPreparation']['finished']['sha256'],
        finalCenterSha256=rows[-1]['outputCenterSha256'],generations=rows)
    storage.publish_json(output/'generation-audit.json',compact)
    paths=dict(protocol='protocol.json',originalBundle='original/bundle.json',projection='projection.json',warmStart='warm-start.json',
        startBundle='start/bundle.json',inheritedCalibration='calibration/inherited-calibration.json',calibration='calibration/origin/calibration.json',
        numericalPreparation='preparation/finished.json',center='generations/generation-16/center.json',finalBundle='final/bundle.json',
        centerMaterialization='final/center-materialization.json',generationAudit='generation-audit.json',historical='historical/historical-admission.json')
    record=dict(kind=p.PREFIX+'-final-admission-1',complete=True,completedGenerations=16,scopeSha256=plan.scope.scope_sha256,
        ecologySha256=plan.record['evaluation']['ecologySha256'],warmStartSha256=profile['warmStart']['sha256'],
        validationEffectsUsedForSelection=False,**{key:relative(output/name,owner) for key,name in paths.items()})
    storage.publish_json(owner,record);final_subject=p.subject(owner)
    subjects,_=final_bindings(plan,protocol['sha256'],final_subject,pins)
    p.require(subjects['center']['sha256']==numerical['eligibleFinalCenter']['sha256'],
        'The portable final center must remain the one admitted by the full numerical chain.')
    pins.unchanged()
    return final_subject
