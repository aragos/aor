"""Pure completed-evidence audit. No Torch, inference, optimizer, native or network calls."""
import gzip
import hashlib
import json
import math
from pathlib import Path
import tarfile

import purchase_storage

ACTOR = 'aor-expansion-policy-2'
ENCODER = 'aor-expansion-observation-2'
KIND = 'expansion-on-policy-collect-1'
PARAMETERS = ('stateWeights','stateBias','actionWeights','actionBias','actorWeights')
ESTIMATOR = dict(version='expansion-terminal-mc-1',gamma=1.,**{'lambda':1.},baseline=.5,
    intermediateReward=0.,forcedRowsIncluded=True,weighting='uniform-recorded-decision-mean',
    terminalReward='original-complete-world')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            value.update(block)
    return value.hexdigest()


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()


def decode(raw):
    def unique(pairs):
        result = {}
        for key,value in pairs:
            require(key not in result,'Duplicate evidence field: '+key)
            result[key] = value
        return result
    return json.loads(raw,object_pairs_hook=unique,
        parse_constant=lambda token: require(False,'Nonfinite evidence JSON: '+token))


def finite(value, low=-math.inf, high=math.inf):
    return type(value) in (int,float) and math.isfinite(value) and low <= value <= high


class Pins:
    def __init__(self):
        self.values = {}

    def pin(self, path):
        path = Path(path).absolute()
        require(path.is_file() and not path.is_symlink(),'Evidence must be a regular file: '+str(path))
        value = sha(path)
        require(str(path) not in self.values or self.values[str(path)] == value,'Evidence changed during audit.')
        self.values[str(path)] = value
        return value

    def read(self, path):
        self.pin(path)
        return decode(Path(path).read_bytes())

    def unchanged(self):
        require(all(sha(path)==value for path,value in self.values.items()),'Audited evidence changed.')


class Archived:
    """Resolve exact archived member hashes; at most one decoded trajectory at a time."""
    def __init__(self, directory, pins):
        self.directory = Path(directory)
        self.manifest = pins.read(self.directory/'storage.json')
        require(self.manifest['kind']=='joint-state-chunk-storage-1' and
            self.manifest['archive']=='evidence.tar.gz','Unexpected archive owner.')
        self.path = self.directory/'evidence.tar.gz'
        self.files = self.manifest['files']
        require(all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in self.files),
            'Archive membership must be relative and confined.')
        require(len(self.manifest['memberOrder'])==len(self.files) and
            set(self.manifest['memberOrder'])==set(self.files),'Archive ordering/membership changed.')
        require(pins.pin(self.path)==self.manifest['archiveSha256'],'Archive identity changed.')
        purchase_storage.verify_members(self.path,self.files,self.manifest['archiveSha256'])
        self.metadata = {}
        with tarfile.open(self.path,'r|gz') as archive:
            for member in archive:
                if member.name in ('collection/collection.json','collection/finished.json',
                    'collection/source.json','collection/arena.json','reproduction/replay.json',
                    'collection/ecology/candidate.json','collection/ecology/incumbent.json',
                    'collection/ecology/opponent-0.json') or (
                    member.name.startswith('collection/replay-') and member.name.endswith('.json')):
                    with archive.extractfile(member) as stream:
                        self.metadata[member.name] = decode(stream.read())

    def hash(self, name):
        require(name in self.files,'Required archived member absent: '+name)
        return self.files[name]['sha256']

    def games(self):
        with tarfile.open(self.path,'r|gz') as archive:
            for member in archive:
                if member.name.startswith('collection/game-') and member.name.endswith('.json.gz'):
                    with archive.extractfile(member) as compressed, gzip.GzipFile(fileobj=compressed) as stream:
                        yield member.name, decode(stream.read())


def validated_terminal_scores(scores):
    """Authoritative public totals and winner flags, including all-chaos and tied games."""
    require(len(scores)==4 and {s['player'] for s in scores}=={'p1','p2','p3','p4'},'Missing terminal seats.')
    for score in scores:
        require(all(type(score[k]) is int and 0<=score[k]<2**31 for k in ('cash','advancePoints'))
            and (score['misery'] is None or type(score['misery']) is int and score['misery']>=0)
            and (score['total'] is None or type(score['total']) is int)
            and type(score['winner']) is bool,'Invalid terminal public fields.')
        expected = None if score['misery'] is None else score['cash']+score['advancePoints']-score['misery']
        require(score['total']==expected,'Terminal total differs from public scores.')
    totals = [s['total'] for s in scores if s['total'] is not None]
    maximum = max(totals) if totals else None
    require(all(s['winner']==(s['total'] is not None and s['total']==maximum) for s in scores),'Terminal winners differ.')
    return scores


def terminal_win_share(game):
    """Derive win share from validated terminal winners; never read reward or rank labels."""
    terminal=game['terminal'];scores=validated_terminal_scores(terminal['scores'])
    require(type(game['controlledSeat']) is int and 0<=game['controlledSeat']<4,'A terminal win label needs a valid controlled seat.')
    own=next(score for score in scores if score['player']=='p'+str(game['controlledSeat']+1))
    result=1/sum(score['winner'] for score in scores) if own['winner'] else 0.
    require(finite(terminal['winShare'],0,1) and abs(terminal['winShare']-result)<1e-12,
        'Terminal win share differs from authoritative winners.')
    require(type(terminal['gameDecisions']) is int and terminal['gameDecisions']>0,'Complete world needs decisions.')
    return result


def terminal_reward(game):
    """Independent score arithmetic check of the existing .8 win + .2 rank label."""
    terminal = game['terminal']; scores = validated_terminal_scores(terminal['scores'])
    own = next(s for s in scores if s['player']=='p'+str(game['controlledSeat']+1))
    win = 1/sum(s['winner'] for s in scores) if own['winner'] else 0.
    rank = 0. if own['total'] is None else math.fsum(1. if s['total'] is None or own['total']>s['total']
        else .5 if own['total']==s['total'] else 0. for s in scores if s is not own)/3
    for key,value in dict(winShare=win,rankReward=rank,reward=.8*win+.2*rank).items():
        require(finite(terminal[key],0,1) and abs(terminal[key]-value)<1e-12,'MC terminal label differs: '+key)
    require(type(terminal['gameDecisions']) is int and terminal['gameDecisions']>0,'Complete world needs decisions.')
    return terminal['reward']


def signed(value):
    return (value+2**31)%2**32-2**31


def trajectory(game, index, manifest, actor_sha):
    expected = dict(schemaVersion=1,kind='expansion-on-policy-trajectory-1',signature=manifest['signature'],
        index=index,configuration=manifest['configuration'],gameSeed=manifest['seeds'][index],controlledSeat=index%4,
        opponentIndices=[0]*4,actorModelSha256=actor_sha,arenaSourceSha256=manifest['arena']['sha256'],
        frozenCardPurchaseBundleSha256=manifest['frozenCardPurchaseBundleSha256'],
        referenceExpansionModelSha256=manifest['referenceExpansionModelSha256'])
    require(all(game.get(k)==v for k,v in expected.items()),'Original world or role identity changed.')
    reward = terminal_reward(game); rows = game['expansionDecisions']; previous = -1; turn = 0
    dictionaries = manifest['dictionaries']; states = set(dictionaries['stateFeatureNames']); actions = set(dictionaries['actionFeatureNames'])
    card = False; branching = False
    for row in rows:
        number = row['number']; current_turn = row['turn']
        require(type(number) is int and previous<number<game['terminal']['gameDecisions'] and
            row['batchStartNumber']==number and row['decision']=='expand' and type(current_turn) is int and current_turn>=max(1,turn),
            'Original expansion chronology changed.')
        # Exact Simulation.simulationPolicySeed, with signed32 arithmetic at its wire boundary.
        bot_seed = signed(game['gameSeed'] ^ signed(number*65537+(index%4)*8191))
        require(row['botSeed']==bot_seed and row['actorSeed']==signed(bot_seed ^ 0x9b05688c),'Original sampler seed differs.')
        observation = row['observation']; offered = observation['actions']
        require(observation['encoderVersion']==ENCODER and 1<=len(offered)<=1024 and
            [a['command'] for a in offered]==sorted({a['command'] for a in offered}) and
            type(observation['baselineIndex']) is int and 0<=observation['baselineIndex']<len(offered),
            'Full canonical offered-action encoding changed.')
        for values,names in [(observation['stateFeatures'],states),*[(a['features'],actions) for a in offered]]:
            require(set(values)<=names and all(finite(v,-16,16) for v in values.values()),'Actor sparse dictionary/bounds differ.')
        require(type(row['selectedIndex']) is int and 0<=row['selectedIndex']<len(offered) and
            finite(row['behaviorLogProbability'],high=0) and finite(row['behaviorValue'],0,1),'Invalid retained actor behavior.')
        previous=number;turn=current_turn;branching |= len(offered)>1
        card |= any(a['features'].get('kind.buyCard',0)>0 for a in offered)
    return reward,len(rows),branching,card


def snapshots(archive, manifest, source, protocol, actor, pins):
    require(archive.hash('collection/source.json')==sha(source) and
        manifest['source']==dict(path=str(source),sha256=sha(source),snapshot='source.json'),'Original source snapshot differs.')
    for key,path in [('arena',Path(protocol['arena'])),('actor',actor)]:
        item = manifest[key]
        require(Path(item['path'])==path and item['sha256']==pins.pin(path)==archive.hash('collection/'+item['snapshot']),
            'Original '+key+' snapshot differs.')
    def visit(value):
        if isinstance(value,dict):
            if {'path','sha256','snapshot'} & set(value):
                require({'path','sha256','snapshot'}<=set(value) and Path(value['snapshot']).name==value['snapshot'],
                    'Incomplete ecology snapshot subject.')
                require(pins.pin(value['path'])==value['sha256']==archive.hash('collection/ecology/'+value['snapshot']),
                    'Ecology snapshot bytes differ.')
            for item in value.values():visit(item)
        elif isinstance(value,list):
            for item in value:visit(item)
    visit(manifest['policyBindings'])
    arena=archive.metadata['collection/arena.json'];base=Path(protocol['arena']).parent
    for role,name in [('candidate',arena['candidate']),('incumbent',arena['incumbent']),
        ('opponent-0',arena['opponents'][0])]:
        require(pins.read(base/name)==archive.metadata['collection/ecology/'+role+'.json'],
            'Original ordinary-model snapshot differs from its source.')


def adam_steps(payload, model, inherited, config):
    optimizer=payload['optimizer']; groups=optimizer['param_groups']; state=optimizer['state']
    require(len(groups)==1 and groups[0]['params']==list(range(5)) and all(type(i) is int for i in groups[0]['params'])
        and groups[0]['lr']==config['learningRate']
        and groups[0]['betas']==[.9,.999] and groups[0]['eps']==1e-8 and groups[0]['weight_decay']==0
        and groups[0]['amsgrad'] is False and groups[0]['maximize'] is False and groups[0]['foreach'] is False
        and groups[0]['capturable'] is False and groups[0]['differentiable'] is False and groups[0]['fused'] is None
        and groups[0].get('decoupled_weight_decay',False) is False,
        'Five-slot Adam recipe differs.')
    require(set(state)==set(map(str,range(5))) or (not state and payload['epoch']==0 and inherited==0),
        'Missing actor moments after accepted/inherited steps.')
    steps=[]
    for index,name in enumerate(PARAMETERS):
        slot=state.get(str(index))
        if slot is None:continue
        require(set(slot)=={'step','exp_avg','exp_avg_sq'},'Unexpected Adam slot.')
        for key in ('exp_avg','exp_avg_sq'):
            item=slot[key]; value=item['tensor']
            expected_columns = len(model['stateFeatureNames']) if name=='stateWeights' else len(model['actionFeatureNames']) if name=='actionWeights' else None
            require(item['dtype']=='torch.float64' and isinstance(value,list) and len(value)==64,'Adam tensor rows differ.')
            flat = [x for row in value for x in row] if expected_columns is not None else value
            require(expected_columns is None or all(isinstance(row,list) and len(row)==expected_columns for row in value),'Adam matrix dimensions differ.')
            require(all(finite(x,0 if key=='exp_avg_sq' else -math.inf) for x in flat),'Invalid finite Adam moments.')
        step=slot['step'];require(step['dtype']=='torch.float32' and finite(step['tensor'],1) and int(step['tensor'])==step['tensor'],'Invalid Adam step.')
        steps.append(int(step['tensor']))
    require(not steps or len(set(steps))==1 and steps[0]>=inherited,'Adam counters reset or diverged.')
    return steps[0] if steps else 0

