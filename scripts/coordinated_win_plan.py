"""Fixed win-continuation method; experiment-specific original/final bytes live in the protocol.

The inherited parameter space and calibration are distinct from the new supported mixed ecology.
This owner performs no model, outcome or random-number access. Old zero-start/reward plans stay strict.
"""
from dataclasses import dataclass
import json
import hashlib
from pathlib import Path

import coordinated_es_continuation as continuation
import coordinated_es_math as es
import coordinated_es_plan as base
from token_paired_campaign_plan import canonical, digest, integer, read, require, sha

PREFIX = 'coordinated-win'
KIND = PREFIX + '-continuation-1'
HEADS, HOSTS = base.HEADS, base.HOSTS
SCIENCE_SECONDS, AUDIT_SECONDS = base.SCIENCE_SECONDS, base.AUDIT_SECONDS
MAXIMUM_TERMINALS = 141358
GENERATION_CHUNKS, SCREEN_CHUNKS = base.GENERATION_CHUNKS, base.SCREEN_CHUNKS
SCREEN_REPLAYS = base.SCREEN_REPLAYS
host_limits, generation, owner, indices = base.host_limits, base.generation, base.owner, base.indices
mutant, replays, screen_replays = base.mutant, base.replays, base.screen_replays
Clock = base.Clock
RECIPE = dict(base.RECIPE, kind=PREFIX+'-normalized-antithetic-1',
    reward=continuation.OBJECTIVE, initialization='ADMITTED_PARENT_FINAL_COORDINATES',
    parameterOrigin='UNCHANGED_ORIGINAL_PROJECTION', radiusOrigin='ORIGINAL_ZERO')
SCREEN_GATE = dict(worlds=2048, roles=['FINAL','START','CONTROL'],
    candidate='FINAL_MEAN_16_ONLY', intervalMultiplier=1.96,
    comparisons=['FINAL_MINUS_START','FINAL_MINUS_CONTROL'], winLower95Positive=True,
    reward='DIAGNOSTIC_ONLY', qualification=False, fallback=False)
ECOLOGY = dict(kind='coordinated-supported-mixed-1', supportProfile='known-own-chaos-support-1',
    supportRoles='ALL_SEATS_CP', control='ORIGINAL_G3_WITH_SUPPORT1',
    rivals=['E_OFF_T_OFF','E_ON_T_OFF','E_OFF_T_ON','E_ON_T_ON'],
    seat='GLOBAL_INDEX_MOD_4', opponentIndices='(GLOBAL_INDEX_DIV_4_PLUS_SEAT)_MOD_4')
REFERENCE_FIELDS = ('qualifiedOrdinarySha256','cardM8Sha256','purchaseF0Sha256',
    'expansionESha256','tokenControlVersion')


def expected_budget():
    return dict(supportedControls=4096, mutants=131072, populationReproductions=32,
        screenGames=6144, screenReproductions=12, historicalReferenceRegressions=2,
        maximumTerminalExecutions=MAXIMUM_TERMINALS)


def exact(value, fields, label):
    require(type(value) is dict and set(value)==set(fields),label+' has missing or unexpected fields.')


def subject(value):
    require(type(value) is dict and set(value) in ({'path','sha256'},{'path','sha256','originalPath'}),
        'An executable subject requires its path and exact raw SHA256.')
    require(type(value['path']) is str and bool(value['path']),'A readable subject path is required.')
    sha(value['sha256'])
    if 'originalPath' in value:
        require(type(value['originalPath']) is str and Path(value['originalPath']).is_absolute(),
            'Original provenance remains an explicit absolute path.')


def native_bank(value,count):
    # Reuse the original seed arithmetic, retaining strict integer rather than bool identity.
    result=base.native_bank(value,count)
    require(type(value['count']) is int and type(value['seeds']) is list and
        all(type(seed) is int for seed in value['seeds']), 'Native world counts/seeds are exact integers.')
    return result


@dataclass(frozen=True)
class Plan:
    wire: bytes

    @classmethod
    def from_file(cls,path):
        value=cls.from_record(read(path))
        require(Path(path).read_bytes()==value.wire+b'\n',
            'The continuation protocol requires canonical JSON and exactly one framing LF.')
        return value

    @classmethod
    def from_record(cls,value):
        exact(value,('kind','recipe','parameterSpace','warmStart','calibration','evaluation',
            'prefixGate','gameLimits','budget','scienceSeconds','auditSeconds','hosts','generations',
            'screenBank','screenGate','historicalControls','automaticRetry','automaticSuccessor'),
            'Win continuation protocol')
        require(value['kind']==KIND and value['recipe']==RECIPE and
            value['prefixGate']==base.PREFIX_GATE and value['gameLimits']==base.GAME_LIMITS,
            'Only fixed win-only whole-game continuation and the original resource/game guards are admitted.')
        require(value['budget']==expected_budget() and value['scienceSeconds']==SCIENCE_SECONDS and
            value['auditSeconds']==AUDIT_SECONDS and value['automaticRetry'] is False and
            value['automaticSuccessor'] is False and value['hosts']=={h:host_limits(h) for h in HOSTS},
            'The original finite clocks, both host limits and no-retry budget cannot change.')
        space=value['parameterSpace']
        exact(space,('scopeSha256','originalBundleSha256','projectionSha256','projectionRecipeSha256','heads'),
            'Inherited parameter space')
        for key in ('scopeSha256','originalBundleSha256','projectionSha256','projectionRecipeSha256'):
            sha(space[key])
        exact(space['heads'],HEADS,'Original parameter heads')
        for row in space['heads'].values():
            exact(row,('actorSha256','dictionarySha256'),'Original head');sha(row['actorSha256']);sha(row['dictionarySha256'])
        warm=value['warmStart']
        exact(warm,('identitySha256','protocolSha256','admissionSha256','proofSha256','centerSha256',
            'bundleSha256','materializationSha256'),'Complete parent warm start')
        for key in warm:
            if key!='proofSha256':sha(warm[key])
        exact(warm['proofSha256'],HOSTS,'Host-local origin admission proofs')
        for item in warm['proofSha256'].values():sha(item)
        calibration=value['calibration']
        exact(calibration,('kind','originProtocolSha256','originCalibrationSha256','recipe','scales',
            'recalibration'),'Inherited calibration')
        require(calibration['kind']=='coordinated-es-inherited-calibration-1' and
            calibration['recipe'] in (base.calibration_recipe(1),base.calibration_recipe(2)) and
            calibration['recalibration'] is False,'Only one unchanged versioned original calibration may execute.')
        sha(calibration['originProtocolSha256']);sha(calibration['originCalibrationSha256'])
        exact(calibration['scales'],('EXPANSION','TOKEN'),'Inherited selector scales')
        require(all(type(x) in (int,float) and 1/32<=x<=32 for x in calibration['scales'].values()),
            'Both fixed inherited E/T scales retain their admitted finite bounds.')
        evaluation=value['evaluation']
        exact(evaluation,('recipe','ecologySha256','references'),'Supported mixed evaluation')
        require(evaluation['recipe']==ECOLOGY,'Every controlled/rival C/P role uses exact Support1 and the fixed mixed lineup.')
        sha(evaluation['ecologySha256']);exact(evaluation['references'],REFERENCE_FIELDS,'Fixed evaluation references')
        for key in REFERENCE_FIELDS[:-1]:sha(evaluation['references'][key])
        require(evaluation['references']['tokenControlVersion']=='FIXED_16_TO_15','The raw token reference remains fixed.')
        streams=[]
        require(type(value['generations']) is list and len(value['generations'])==16,
            'Exactly16 ordered complete generation banks are required.')
        for number,row in enumerate(value['generations'],1):
            exact(row,('generation','worldBank','directionSeed'),'Fresh generation declaration')
            require(type(row['generation']) is int and row['generation']==number,'Continuation step order cannot change.')
            streams+=native_bank(row['worldBank'],256)
            streams.append(integer(row['directionSeed'],1,2**31-1,'Fresh direction stream'))
        streams+=native_bank(value['screenBank'],2048)
        require(len(streams)==6160 and len(set(streams))==len(streams),
            'All6160 fresh worlds/directions must be distinct; no new projection/initialization stream exists.')
        require(value['screenGate']==SCREEN_GATE,
            'Only final16 may pass both fixed win95 comparisons; start/control cannot become fallback nominees.')
        controls=value['historicalControls']
        require(type(controls) is list and len(controls)==2,'Exactly two original raw reference regressions are declared.')
        for row,(global_index,native_index) in zip(controls,((0,0),(4095,511))):
            exact(row,('globalIndex','index','side','purpose','sourceSha256','pairSha256','replaySha256'),
                'Historical reference regression')
            require(type(row['globalIndex']) is int and type(row['index']) is int and
                row['globalIndex']==global_index and row['index']==native_index and row['side']=='candidate' and
                row['purpose']=='RAW_REFERENCE_REGRESSION_ONLY','Old raw histories are reference checks, never target-ecology strength.')
            for key in ('sourceSha256','pairSha256','replaySha256'):sha(row[key])
        return cls(canonical(value))

    @property
    def record(self):return json.loads(self.wire)

    @property
    def sha256(self):return digest(self.record)

    @property
    def file_sha256(self):return hashlib.sha256(self.wire+b'\n').hexdigest()

    @property
    def scope(self):
        value=self.record['parameterSpace']
        return es.Scope(value['scopeSha256'],value['originalBundleSha256'],value['projectionSha256'])

    def bank(self,number):return self.record['generations'][generation(number)-1]

    def verify_warm_start(self,warm):
        value=self.record;expected=value['warmStart']
        require(isinstance(warm,continuation.WarmStart) and warm.scope==self.scope and
            warm.sha256==expected['identitySha256'] and warm.origin_protocol_sha256==expected['protocolSha256'] and
            warm.origin_admission_sha256==expected['admissionSha256'] and
            warm.origin_center_sha256==expected['centerSha256'] and warm.origin_bundle_sha256==expected['bundleSha256'] and
            warm.calibration_sha256==value['calibration']['originCalibrationSha256'],
            'The complete admitted parent center, bundle, calibration and original parameter space must match the protocol.')

    def generation_source(self,number,chunk,phase,subjects):
        generation(number);host=owner(chunk)
        require(phase in ('CONTROLS','POPULATION'),'A fixed native generation phase is required.')
        exact(subjects,('protocol','arena','originalBundle','projection','warmStart','originProof','center',
            'directions','population','inheritedCalibration','previousUpdate','historical'), 'Native continuation subjects')
        for key,item in subjects.items():
            if item is None:require(key in ('population','previousUpdate'),'Every inherited source remains required from step1.')
            else:subject(item)
        require((subjects['population'] is None)==(phase=='CONTROLS'),
            'Controls never receive mutants; every population must be sealed before execution.')
        require((subjects['previousUpdate'] is None)==(number==1),'Later steps require their immediate predecessor update.')
        value=self.record
        for key,expected in (('protocol',self.file_sha256),
                ('originalBundle',value['parameterSpace']['originalBundleSha256']),
                ('projection',value['parameterSpace']['projectionSha256']),
                ('warmStart',value['warmStart']['identitySha256']),('originProof',value['warmStart']['proofSha256'][host])):
            require(subjects[key]['sha256']==expected,'Native sources retain their exact original/warm-start subjects.')
        return dict(kind=PREFIX+'-generation-1',stage=phase,generation=number,host=host,
            globalStart=chunk*32,count=32,bank=self.bank(number)['worldBank'],**subjects)


def prefix_gate(remaining_seconds,generation_seconds,retention):
    result=base.prefix_gate(remaining_seconds,generation_seconds,retention)
    return dict(result,kind=PREFIX+'-operational-prefix-1')
