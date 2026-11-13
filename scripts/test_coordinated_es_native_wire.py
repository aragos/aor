"""Constructed native store→Python public/archive bridge, without terminal effect decoding.

The fixture is explicitly invented and uses copied temporary provenance. This proves wire
compatibility, not scientific admission: the strict real protocol reader is never bypassed
by production code. Invoke with the native export directory and a new proof JSON path.
"""
import argparse
import copy
from pathlib import Path
import tempfile

import coordinated_es_plan as p
import coordinated_es_storage as storage
from token_paired_campaign_audit import Pins,stream_archive
from test_coordinated_es_plan import protocol


def verify(root):
    root=Path(root);marker=p.read(root/'CONSTRUCTED-FIXTURE.json')
    p.require(marker['constructedOnly'] is True and marker['scientificAdmission'] is False and
        marker['nativeGames']==marker['newFits']==0 and marker['constructedStoredGames']==32,
        'Only the explicitly invented native wire fixture is permitted.')
    for item in marker['originalPathMap'].values():p.checked_subject(item)
    collection=root/'collection';files=storage.inventory(collection)
    p.require(marker['files']=={name:row['sha256'] for name,row in files.items() if name!='finished.json'},
        'Every exact native fixture member must still match its exported identity.')
    request=p.read(collection/'source.json');prepared=p.read(collection/'prepared.json')
    finished=p.read(collection/'finished.json')
    value=protocol();value['generations'][0]['worldBank']=request['bank']
    value['original'].update(bundleSha256=prepared['originalBundle']['sha256'],
        projectionSha256=request['projection']['sha256'],scopeSha256=prepared['scopeSha256'],
        ecologySha256=prepared['ecologySha256'])
    for head in p.HEADS:value['original']['heads'][head]['dictionarySha256']=p.digest(prepared['dictionaries'][head])
    # Deliberately constructed-only plan: these handwritten heads are not qualified model subjects.
    plan=p.Plan(p.canonical(value))
    receipt=storage.generation_record(plan,request,p.file_sha(collection/'source.json'),prepared,
        finished,files,1,0,'controls',{})
    worlds=[storage.panel_world(p.read(collection/f'panel-{index}.json'),receipt,plan,index) for index in range(32)]
    malformed=copy.deepcopy(prepared);malformed['subjects']['center']['originalPath']='/substituted/center.json'
    try:storage.generation_record(plan,request,p.file_sha(collection/'source.json'),malformed,finished,files,1,0,'controls',{})
    except ValueError:pass
    else:raise AssertionError('Changed original subject provenance must fail.')
    with tempfile.TemporaryDirectory() as folder:
        # Archive a copy: the native fixture remains immutable and available for independent review.
        import shutil
        duplicate=Path(folder)/'native';shutil.copytree(collection,duplicate)
        archive=Path(folder)/'evidence.tar.gz';storage.archive_collection(duplicate,archive,receipt)
        retained=p.read(archive.with_name(archive.name+'.json'));pins=Pins()
        public=stream_archive(archive,retained,pins,receipt['panels'])
        p.require(len(public)==32 and all('terminal' not in row and 'canonical' not in row for row in public.values()),
            'Archive decoding must remain confined to public sidecars.')
        pins.unchanged()
    return dict(kind='coordinated-es-native-python-wire-proof-1',passed=True,constructedOnly=True,
        scientificAdmission=False,nativeGames=0,newFits=0,terminalEffectsDecoded=False,
        marker=p.subject(root/'CONSTRUCTED-FIXTURE.json'),members=len(files),worlds=len(worlds),
        publicStates={head:sum(len(world['heads'][head]) for world in worlds) for head in ('EXPANSION','TOKEN')},
        originalSubjectTamperRejected=True,losslessArchiveVerified=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();storage.publish_json(args.output,verify(args.fixture));print('Constructed native/public/archive wire verified.')
