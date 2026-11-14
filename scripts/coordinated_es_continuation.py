"""Typed warm continuation and win-only identities; the original zero/reward contracts stay strict.

The parameter space is the inherited original projection. Execution ecology belongs to the new
protocol, not to this coordinate type. No model, RNG, filesystem or simulator is imported here.
"""
from dataclasses import dataclass

import coordinated_es_math as es

OBJECTIVE='AUTHORITATIVE_WIN_SHARE'
WARM_KIND='coordinated-es-warm-start-1'
CENTER_KIND='coordinated-es-continuation-center-1'
MATERIALIZATION_KIND='coordinated-es-continuation-materialization-1'
POPULATION_KIND='coordinated-es-continuation-population-1'
FITNESS_KIND='coordinated-win-fitness-1'
UPDATE_KIND='coordinated-es-continuation-update-1'


@dataclass(frozen=True)
class WarmStart:
    """Admitted parent final coordinates; byte subjects are rehashed by the numeric boundary."""
    scope: es.Scope
    origin_center_sha256: str
    origin_bundle_sha256: str
    origin_protocol_sha256: str
    origin_admission_sha256: str
    calibration_sha256: str
    coordinates: tuple

    def __post_init__(self):
        es.require(isinstance(self.scope,es.Scope),'A warm start requires the unchanged original parameter space.')
        for name in ('origin_center_sha256','origin_bundle_sha256','origin_protocol_sha256',
                     'origin_admission_sha256','calibration_sha256'):
            es.sha(getattr(self,name),name)
        object.__setattr__(self,'coordinates',es.vector(self.coordinates,es.DIMENSIONS,'warm-start'))
        es.require(es.rms(self.coordinates)<=4.+2e-15,'The inherited center remains in the original RMS4 ball.')

    @property
    def record(self):
        return dict(kind=WARM_KIND,parameterSpace=self.scope.record,
            originCenterSha256=self.origin_center_sha256,originBundleSha256=self.origin_bundle_sha256,
            originProtocolSha256=self.origin_protocol_sha256,originAdmissionSha256=self.origin_admission_sha256,
            calibrationSha256=self.calibration_sha256,coordinates=list(self.coordinates))

    @property
    def sha256(self):return es.digest(self.record)

    @classmethod
    def from_record(cls,record):
        es.exact(record,('kind','parameterSpace','originCenterSha256','originBundleSha256',
            'originProtocolSha256','originAdmissionSha256','calibrationSha256','coordinates'),'Warm-start identity')
        es.require(record['kind']==WARM_KIND,'A warm start cannot be an unadmitted model/checkpoint alias.')
        return cls(es.Scope.from_record(record['parameterSpace']),record['originCenterSha256'],
            record['originBundleSha256'],record['originProtocolSha256'],record['originAdmissionSha256'],
            record['calibrationSha256'],tuple(record['coordinates']))

    def verify_origin(self,center):
        """A future continuation may inherit another complete continuation, with the same space."""
        if isinstance(center,es.Center):complete=center.generation==es.GENERATIONS
        elif isinstance(center,Center):complete=center.step==es.GENERATIONS
        else:complete=False
        es.require(complete and center.scope==self.scope and center.coordinates==self.coordinates and
            es.digest(center.record)==self.origin_center_sha256,
            'Only the exact admitted complete parent center supplies the warm-start coordinates.')


@dataclass(frozen=True)
class Center:
    warm_start: WarmStart
    step: int
    coordinates: tuple

    def __post_init__(self):
        es.require(isinstance(self.warm_start,WarmStart),'A continuation center needs its explicit immutable warm start.')
        es.require(type(self.step) is int and 0<=self.step<=es.GENERATIONS,'Continuation step is0..16.')
        object.__setattr__(self,'coordinates',es.vector(self.coordinates,es.DIMENSIONS,'continuation-center'))
        es.require(es.rms(self.coordinates)<=4.+2e-15,'The continuation uses the original global RMS4 radius.')
        es.require(self.step!=0 or self.coordinates==self.warm_start.coordinates,
            'Local step0 equals the exact parent final center; it is never zeroed or rebased.')

    @property
    def scope(self):return self.warm_start.scope

    @property
    def record(self):
        return dict(kind=CENTER_KIND,parameterSpace=self.scope.record,warmStartSha256=self.warm_start.sha256,
            step=self.step,coordinates=list(self.coordinates))

    @classmethod
    def initial(cls,warm_start):return cls(warm_start,0,warm_start.coordinates)

    @classmethod
    def from_record(cls,record,warm_start):
        es.exact(record,('kind','parameterSpace','warmStartSha256','step','coordinates'),'Continuation center')
        es.require(record['kind']==CENTER_KIND and record['parameterSpace']==warm_start.scope.record and
            record['warmStartSha256']==warm_start.sha256,'The center must retain its exact typed origin and parameter space.')
        return cls(warm_start,record['step'],tuple(record['coordinates']))


def bind(center,directions):
    es.require(isinstance(center,Center) and isinstance(directions,es.DirectionTable) and
        center.scope==directions.scope and center.step+1==directions.generation,
        'Continuation mutants require the immediate center and fresh same-space next direction table.')


def mutants(center,directions):
    bind(center,directions)
    return es.mutant_coordinates(center.coordinates,directions.vectors)


def normalized_step(center,directions,differences):
    bind(center,directions)
    result=es.step_coordinates(center.coordinates,directions.vectors,differences)
    successor=Center(center.warm_start,directions.generation,result.pop('coordinates'))
    return dict(result,center=successor.record)


def seal_population(materialization,protocol_sha256,inherited_calibration_sha256):
    es.require(materialization['kind']==MATERIALIZATION_KIND,'Only a typed continuation materialization may be sealed.')
    es.sha(protocol_sha256,'Continuation protocol');es.sha(inherited_calibration_sha256,'Inherited calibration')
    return dict(materialization,kind=POPULATION_KIND,protocolSha256=protocol_sha256,
        materializationSha256=es.digest(materialization),inheritedCalibrationSha256=inherited_calibration_sha256)


def update(center,directions,population,fitness):
    """Only the complete win-share matrix can change a continuation center."""
    members=mutants(center,directions)
    es.exact(population,('kind','parameterSpace','warmStartSha256','step','protocolSha256',
        'materializationSha256','centerSha256','directionsSha256','inheritedCalibrationSha256',
        'worldBankSha256','worldIds','members'),'Continuation population')
    es.require(population['kind']==POPULATION_KIND and population['parameterSpace']==center.scope.record and
        population['warmStartSha256']==center.warm_start.sha256 and population['step']==directions.generation and
        population['centerSha256']==es.digest(center.record) and population['directionsSha256']==es.digest(directions.record),
        'Population step, center, directions and immutable origin must match exactly.')
    for name in ('protocolSha256','materializationSha256','inheritedCalibrationSha256','worldBankSha256'):
        es.sha(population[name],name)
    unsealed={k:v for k,v in population.items() if k not in
        ('protocolSha256','materializationSha256','inheritedCalibrationSha256')}
    unsealed['kind']=MATERIALIZATION_KIND
    es.require(es.digest(unsealed)==population['materializationSha256'],'The sealed population must retain exact materialization bytes.')
    worlds=population['worldIds']
    es.require(type(worlds) is list and all(type(i) is int for i in worlds) and worlds==list(range(es.WORLDS)),'All256 ordered original world slots are required.')
    es.exact(fitness,('kind','objective','protocolSha256','populationSha256','worldBankSha256','worldIds','members'),
        'Win-only paired fitness')
    es.require(fitness['kind']==FITNESS_KIND and fitness['objective']==OBJECTIVE and
        fitness['protocolSha256']==population['protocolSha256'] and
        fitness['populationSha256']==es.digest(population) and fitness['worldBankSha256']==population['worldBankSha256'] and
        fitness['worldIds']==worlds,'Fitness must be the exact current population in authoritative win-share units.')
    means=es.paired_member_means(members,population['members'],fitness['members'],'winShares')
    result=normalized_step(center,directions,tuple(means[2*j]-means[2*j+1] for j in range(es.DIRECTIONS)))
    return dict(kind=UPDATE_KIND,complete=True,objective=OBJECTIVE,mathRecipe=es.RECIPE,
        protocolSha256=population['protocolSha256'],warmStartSha256=center.warm_start.sha256,
        previousCenterSha256=es.digest(center.record),directionsSha256=es.digest(directions.record),
        populationSha256=es.digest(population),fitnessSha256=es.digest(fitness),memberMeanWinShares=means,**result)
