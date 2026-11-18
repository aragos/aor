"""Fixed Float64 normalized antithetic ES; no model, RNG, file or campaign execution."""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import math

DIMENSIONS = 256
DIRECTIONS = 16
WORLDS = 256
GENERATIONS = 16
RECIPE = dict(kind='coordinated-normalized-antithetic-es-1', dimensions=DIMENSIONS,
    directions=DIRECTIONS, worlds=WORLDS, generations=GENERATIONS, sigma=1., stepRms=1.,
    centerRmsLimit=4., minimumGradientRms=1e-12)


class Head(str, Enum):
    CARD='CARD'
    PURCHASE='PURCHASE'
    EXPANSION='EXPANSION'
    TOKEN='TOKEN'


HEADS=tuple(Head)


class OperationalStop(ValueError):
    """A declared numerical failure yields no usable next center or replacement."""
    def __init__(self, reason, diagnostics=None):
        super().__init__(reason)
        self.reason=reason
        self.diagnostics=diagnostics


def require(condition, message):
    if not condition:raise ValueError(message)


def exact(value, fields, name):
    require(type(value) is dict and set(value)==set(fields), name+' has unexpected or missing fields.')


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()


def digest(value):return hashlib.sha256(canonical(value)).hexdigest()


def sha(value, name):
    require(isinstance(value,str) and len(value)==64 and all(c in '0123456789abcdef' for c in value),
        name+' must be an explicit lowercase SHA256.')
    return value


def finite(value, name):
    require(type(value) in (float,int), name+' must be a real number, not a boolean.')
    try:result=float(value)
    except OverflowError:raise OperationalStop('nonfinite-'+name) from None
    if not math.isfinite(result):raise OperationalStop('nonfinite-'+name)
    return result


def vector(value, size, name):
    require(type(value) in (list,tuple) and len(value)==size, name+' has the wrong fixed dimension.')
    return tuple(finite(x,name) for x in value)


def rms(values):
    """Stable Float64 RMS; no overflow from squaring a finite large input."""
    scale=max(map(abs,values),default=0.)
    return 0. if scale==0 else scale*math.sqrt(math.fsum((x/scale)**2 for x in values)/len(values))


@dataclass(frozen=True)
class Scope:
    scope_sha256: str
    original_bundle_sha256: str
    projection_sha256: str

    def __post_init__(self):
        for name,value in vars(self).items():sha(value,name)

    @property
    def record(self):
        return dict(scopeSha256=self.scope_sha256,originalBundleSha256=self.original_bundle_sha256,
            projectionSha256=self.projection_sha256)

    @classmethod
    def from_record(cls, record):
        exact(record,('scopeSha256','originalBundleSha256','projectionSha256'),'ES scope')
        return cls(record['scopeSha256'],record['originalBundleSha256'],record['projectionSha256'])


@dataclass(frozen=True)
class Center:
    scope: Scope
    generation: int
    coordinates: tuple

    def __post_init__(self):
        require(isinstance(self.scope,Scope),'A center requires its explicit immutable scope.')
        require(type(self.generation) is int and 0<=self.generation<=GENERATIONS,'Center generation is0..16.')
        object.__setattr__(self,'coordinates',vector(self.coordinates,DIMENSIONS,'center'))
        require(rms(self.coordinates)<=4.+2e-15,'Only centers obey the fixed RMS4 radius.')
        require(self.generation!=0 or all(x==0 for x in self.coordinates),'The sole initial center is exactly zero.')

    @property
    def record(self):
        return dict(kind='coordinated-es-center-1',scope=self.scope.record,generation=self.generation,
            coordinates=list(self.coordinates))

    @classmethod
    def from_record(cls, record):
        exact(record,('kind','scope','generation','coordinates'),'ES center')
        require(record['kind']=='coordinated-es-center-1','An old policy/checkpoint is not an ES center.')
        return cls(Scope.from_record(record['scope']),record['generation'],tuple(record['coordinates']))


@dataclass(frozen=True)
class DirectionTable:
    """Frozen values; the separate generation receipt owns its prospective RNG provenance."""
    scope: Scope
    generation: int
    vectors: tuple

    def __post_init__(self):
        require(isinstance(self.scope,Scope),'Directions require an explicit ES scope.')
        require(type(self.generation) is int and 1<=self.generation<=GENERATIONS,'Direction generation is1..16.')
        require(type(self.vectors) in (list,tuple) and len(self.vectors)==DIRECTIONS,'Exactly16 directions are required.')
        object.__setattr__(self,'vectors',tuple(vector(row,DIMENSIONS,'direction') for row in self.vectors))

    @property
    def record(self):
        return dict(kind='coordinated-es-directions-1',scope=self.scope.record,generation=self.generation,
            vectors=[list(row) for row in self.vectors])

    @classmethod
    def from_record(cls, record):
        exact(record,('kind','scope','generation','vectors'),'ES directions')
        require(record['kind']=='coordinated-es-directions-1','Only the fixed ES direction table is accepted.')
        return cls(Scope.from_record(record['scope']),record['generation'],tuple(record['vectors']))


def bind(center, directions):
    require(isinstance(center,Center) and isinstance(directions,DirectionTable) and
        center.scope==directions.scope and center.generation+1==directions.generation,
        'Mutants require the immediate same-scope center and next fixed directions.')


def mutant_coordinates(coordinates, vectors):
    """Shared antithetic coordinates; typed callers own scope and predecessor admission."""
    z=vector(coordinates,DIMENSIONS,'center')
    require(type(vectors) in (list,tuple) and len(vectors)==DIRECTIONS,
        'Exactly16 fixed antithetic directions are required.')
    rows=tuple(vector(row,DIMENSIONS,'direction') for row in vectors)
    # Deliberately do not project mutant coordinates onto the center radius.
    return tuple(dict(direction=index,sign=sign,coordinates=[finite(value+sign*e,'mutant-coordinate')
        for value,e in zip(z,row)]) for index,row in enumerate(rows) for sign in (1,-1))


def mutants(center, directions):
    bind(center,directions)
    return mutant_coordinates(center.coordinates,directions.vectors)


def step_coordinates(coordinates, vectors, differences):
    """One unchanged Float64 ascent/radius calculation, without a center wire version."""
    z=vector(coordinates,DIMENSIONS,'center')
    require(type(vectors) in (list,tuple) and len(vectors)==DIRECTIONS,
        'Exactly16 fixed gradient directions are required.')
    rows=tuple(vector(row,DIMENSIONS,'direction') for row in vectors)
    values=vector(differences,DIRECTIONS,'fitness-difference')
    try:
        gradient=tuple(finite(math.fsum(finite(value*row[k],'gradient-product')
            for value,row in zip(values,rows))/32,'gradient') for k in range(DIMENSIONS))
    except (OverflowError,ValueError) as failure:
        if isinstance(failure,OperationalStop):raise
        raise OperationalStop('nonfinite-gradient') from failure
    norm=rms(gradient)
    if not math.isfinite(norm):raise OperationalStop('nonfinite-gradient-rms')
    if norm<=RECIPE['minimumGradientRms']:raise OperationalStop('zero-gradient')
    proposed=tuple(finite(value+g/norm,'proposed-center') for value,g in zip(z,gradient))
    proposed_rms=rms(proposed)
    if not math.isfinite(proposed_rms):raise OperationalStop('nonfinite-proposed-center-rms')
    divisor=max(1.,proposed_rms/4.)
    result=tuple(value/divisor for value in proposed)
    return dict(gradient=list(gradient),gradientRms=norm,unprojectedRms=proposed_rms,
        radiusDivisor=divisor,coordinates=result,centerRms=rms(result))


def normalized_step(center, directions, differences):
    """The same fixed arithmetic serves real paired returns and the outcome-free probe."""
    bind(center,directions)
    step=step_coordinates(center.coordinates,directions.vectors,differences)
    result=Center(center.scope,directions.generation,step.pop('coordinates'))
    return dict(step,center=result.record)


def paired_member_means(expected_members, population_members, fitness_members, value_field):
    """Check the exact portable sign/member matrix before averaging one explicit return field."""
    require(value_field in ('rewards','winShares'),'A typed caller must select the recorded return units.')
    require(all(type(rows) in (tuple,list) and len(rows)==32
        for rows in (expected_members,population_members,fitness_members)),
        'All32 original signed members are required.')
    means=[]
    for expected,subject,row in zip(expected_members,population_members,fitness_members):
        exact(subject,('direction','sign','path','coordinatesSha256','bundleSha256'),'ES member identity')
        require(subject['path']==f"members/{'PLUS' if expected['sign']==1 else 'MINUS'}-{expected['direction']:02d}/bundle.json",
            'Each member must retain its exact portable relative bundle path.')
        exact(row,('direction','sign','bundleSha256',value_field),'ES member returns')
        require(all(subject[key]==row[key]==expected[key] for key in ('direction','sign')) and
            subject['coordinatesSha256']==digest(expected['coordinates']) and
            subject['bundleSha256']==sha(row['bundleSha256'],'member bundle'),
            'Original sign, direction, coordinates and member bundle must remain bound.')
        values=vector(row[value_field],WORLDS,'normalized-return')
        require(all(0<=value<=1 for value in values),'Authoritative normalized returns must lie in[0,1].')
        means.append(math.fsum(values)/WORLDS)
    return means


def update(center, directions, population, fitness):
    """Complete32-by256 paired population only; no ranks, imputation or candidate choice."""
    members=mutants(center,directions)
    exact(population,('kind','scope','generation','protocolSha256','materializationSha256','centerSha256','directionsSha256','calibrationSha256',
        'worldBankSha256','worldIds','members'),'ES population identity')
    require(population['kind']=='coordinated-es-population-1' and population['scope']==center.scope.record and
        population['generation']==directions.generation and population['centerSha256']==digest(center.record) and
        population['directionsSha256']==digest(directions.record),'Population center/directions/scope changed.')
    for key in ('protocolSha256','materializationSha256','calibrationSha256','worldBankSha256'):sha(population[key],key)
    unsealed={key:value for key,value in population.items() if key not in ('protocolSha256','materializationSha256','calibrationSha256')}
    unsealed['kind']='coordinated-es-materialization-1'
    require(digest(unsealed)==population['materializationSha256'],'The executable population must bind its exact unsealed materialization.')
    worlds=population['worldIds']
    require(type(worlds) is list and len(worlds)==WORLDS and all(type(i) is int and i>=0 for i in worlds) and
        len(set(worlds))==WORLDS,'Exactly256 ordered distinct common worlds must be retained.')
    require(type(population['members']) is list and len(population['members'])==32,'All32 original mutants are required.')
    exact(fitness,('kind','populationSha256','worldBankSha256','worldIds','members'),'ES paired fitness')
    require(fitness['kind']=='coordinated-es-fitness-1' and fitness['populationSha256']==digest(population) and
        fitness['worldBankSha256']==population['worldBankSha256'] and fitness['worldIds']==worlds,
        'Fitness requires the exact population and common ordered world bank.')
    require(type(fitness['members']) is list and len(fitness['members'])==32,'Incomplete populations cannot produce a mean.')
    means=paired_member_means(members,population['members'],fitness['members'],'rewards')
    result=normalized_step(center,directions,tuple(means[2*j]-means[2*j+1] for j in range(DIRECTIONS)))
    return dict(kind='coordinated-es-update-1',complete=True,recipe=RECIPE,
        protocolSha256=population['protocolSha256'],previousCenterSha256=digest(center.record),directionsSha256=digest(directions.record),
        populationSha256=digest(population),fitnessSha256=digest(fitness),memberMeanRewards=means,**result)


def prescribed_mean_probe(directions):
    """One known-sign, outcome-free mean; never choose a probe by movement or returns."""
    require(isinstance(directions,DirectionTable) and directions.generation==1,
        'Calibration uses only the original first-generation directions.')
    center=Center(directions.scope,0,(0.,)*DIMENSIONS)
    differences=tuple(2*math.fsum((1 if k%2==0 else -1)*value for k,value in enumerate(row))/DIMENSIONS
        for row in directions.vectors)
    return dict(kind='coordinated-es-prescribed-mean-1',directionsSha256=digest(directions.record),
        artificialDifferences=list(differences),**normalized_step(center,directions,differences))
