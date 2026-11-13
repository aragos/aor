"""Handwritten kernel inputs only; no retained actors, worlds, fit or candidate publication."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import purchase_policy as b
import paired_policy_math as math_owner
import cpe_paired_policy as policy
import cpe_paired_data as data
import cpe_paired_cuda as cuda

BASE='8b7c6f66305e3e4b34e0a704595b7a9da336887d'


def model(head):
    return dict(schemaVersion=head.contract.schemaVersion,encoderVersion=head.contract.encoder,
        hiddenSize=64,baselineProbability=.97,stateFeatureNames=['a','b','c'],actionFeatureNames=['x','y'],
        stateWeights=[.01*((i%7)-3) for i in range(192)],stateBias=[.02*(i%3) for i in range(64)],
        actionWeights=[.03*((i%5)-2) for i in range(128)],actionBias=[.01]*64,
        actorWeights=[0.]*64 if head is policy.Head.EXPANSION else [.02*((i%3)-1) for i in range(64)],
        valueWeights=[.04]*64,valueBias=.21)


def view(head,count=3,zero=False):
    return dict(encoderVersion=head.contract.encoder,stateFeatures={} if zero else {'a':.25,'b':-.5,'c':.75},
        baselineIndex=0,actions=[dict(command=f'unit-{i:04d}',features={'x':.03*(i%5),'y':-.02*(i%7)})
            for i in range(count)])


def training(head,count=3,ready=64):
    """512 numerical slots, not a fabricated native campaign; constants name no real world."""
    artifact=model(head);raw=b.canonical(artifact);model_sha=hashlib.sha256(raw).hexdigest()
    actor=policy.FrozenActor(head,raw,model_sha);dictionary=b.canonical({key:artifact[key] for key in
        ('encoderVersion','stateFeatureNames','actionFeatureNames')})
    scope=b.digest('CUDA-CONSTRUCTED-ONLY')
    context=data.Context(head,1,b.digest('synthetic source'),b.digest(dict(bundleScopeSha256=scope,head=head.value)),
        scope,b.digest('synthetic ecology'),b.digest('synthetic requests'),hashlib.sha256(dictionary).hexdigest(),
        actor.dictionary_sha256,model_sha,model_sha,b.digest('synthetic bundle'),b.digest('synthetic bundle'))
    roots=[];targets=[];anchors=[]
    for index in range(512):
        present=index<ready;observation=view(head,count if present else 2)
        probabilities=b.reference_forward(artifact,observation,contract=head.contract)['probabilities']
        reference=len(probabilities)-1;indices=[reference,*list(range(min(8,reference)))]
        row=dict(head=head.value,index=index,split='TRAIN',status='READY' if present else 'MISSING',
            requestSha256=b.digest(['constructed',head.value,index]),decisionNumber=index*2 if present else None,
            batchStartNumber=index*2 if present else None,observation=observation if present else None,
            currentProbabilities=probabilities if present else None,originalProbabilities=probabilities if present else None,
            sampledIndex=reference if present else None,referenceIndex=reference if present else None,
            proposedIndices=indices if present else None,inclusionProbability=min(8,reference)/reference if present else None,
            futureSeeds=[-2*index-1,-2*index-2])
        root=data.PublicRoot.from_record(row,context,artifact,split='TRAIN');roots.append(root)
        target=None if not present else data.PairedTarget.from_record(root,dict(publicSha256=root.sha256,
            futureSeeds=row['futureSeeds'],actions=[dict(actionIndex=i,rewards=[.3,.5] if i==reference else [.4,.6]) for i in indices]))
        targets.append(target)
        anchors.append(data.AnchorWorld.from_record(dict(head=head.value,originalModelSha256=model_sha,index=index,
            split='TRAIN',observations=[dict(decisionNumber=index*2,batchStartNumber=index*2,
                observation=observation,originalProbabilities=probabilities)]),actor))
    return data.Training(context,tuple(roots),tuple(targets),tuple(anchors),64),artifact


def adam(actor):return b.torch.optim.Adam([getattr(actor,name) for name in b.PARAMETERS[:-2]],lr=.0003)


def maximum(left,right):
    if isinstance(left,b.torch.Tensor):
        return float(b.torch.max(b.torch.abs(left.detach().cpu()-right.detach().cpu()))) if left.numel() else 0.
    if isinstance(left,dict):
        if set(left)!=set(right):raise AssertionError('Dictionary shape changed.')
        return max((maximum(left[key],right[key]) for key in left),default=0.)
    if isinstance(left,(list,tuple)):
        if len(left)!=len(right):raise AssertionError('Sequence shape changed.')
        return max((maximum(a,z) for a,z in zip(left,right)),default=0.)
    if isinstance(left,(int,float)) and not isinstance(left,bool):return abs(left-right)
    if left!=right:raise AssertionError('Non-numeric metadata changed.')
    return 0.


def original_module(name):
    """Local-only CPU regression against exact frozen source, never scientific artifacts."""
    raw=subprocess.check_output(['git','show',BASE+':scripts/'+name+'.py'],cwd=Path(__file__).resolve().parents[1])
    temporary=tempfile.TemporaryDirectory();path=Path(temporary.name)/(name+'.py');path.write_bytes(raw)
    module_name='cuda_frozen_'+name
    spec=importlib.util.spec_from_file_location(module_name,path);module=importlib.util.module_from_spec(spec)
    sys.modules[module_name]=module;spec.loader.exec_module(module)
    return module,temporary


class CpuDefaults(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        b.torch.set_num_threads(1)
        cls.old,cls.folder=original_module('purchase_policy')
        cls.old_math,cls.math_folder=original_module('paired_policy_math')

    @classmethod
    def tearDownClass(cls):cls.folder.cleanup();cls.math_folder.cleanup()

    def test_all_head_forward_and_gradient_exact_old_cpu(self):
        for head in policy.HEADS:
            artifact=model(head);old=self.old.ResidualPolicy(artifact,head.contract);new=policy.CpePolicy(artifact,head)
            observations=[view(head,1,True),view(head,3),view(head,head.contract.maxActions)]
            before,old_values=old(observations);after,new_values=new(observations)
            self.assertTrue(b.torch.equal(old_values,new_values))
            self.assertTrue(all(b.torch.equal(a,z) for a,z in zip(before,after)))
            sum(row.sum() for row in before).backward();sum(row.sum() for row in after).backward()
            self.assertTrue(all(b.torch.equal(getattr(old,n).grad,getattr(new,n).grad) for n in b.PARAMETERS[:-2]))

    def test_one_cpu_minibatch_matches_original_math_exactly(self):
        rows,artifact=training(policy.Head.CARD)
        old=policy.CpePolicy(artifact,policy.Head.CARD);new=policy.CpePolicy(artifact,policy.Head.CARD)
        old_opt=adam(old);new_opt=adam(new)
        before=self.old_math.minibatch(old,old_opt,self.old_math.Prepared(rows,old),list(range(64)))
        after=math_owner.minibatch(new,new_opt,math_owner.Prepared(rows,new),list(range(64)))
        self.assertEqual(before,after)
        self.assertEqual(maximum(old.state_dict(),new.state_dict()),0.)
        self.assertEqual(maximum(old_opt.state_dict(),new_opt.state_dict()),0.)

    def test_old_fit_configuration_has_no_cuda_switch(self):
        import cpe_paired_fit as fit
        with self.assertRaises(TypeError):fit.Config(tuple(range(20)),time.time()+60,backend=cuda.VERSION)

    def test_explicit_configuration_rejects_missing_workspace(self):
        with patch.dict(os.environ,{'CUBLAS_WORKSPACE_CONFIG':''}):
            with self.assertRaisesRegex(ValueError,'CUBLAS_WORKSPACE_CONFIG'):cuda.configure()

    def test_explicit_configuration_rejects_existing_context(self):
        with patch.dict(os.environ,{'CUBLAS_WORKSPACE_CONFIG':':16:8'}),patch.object(b.torch.cuda,'is_initialized',return_value=True):
            with self.assertRaisesRegex(ValueError,'before any CUDA'):cuda.configure()

    def test_no_cuda_transaction_or_publication_api(self):
        self.assertFalse(hasattr(cuda,'fit_bundle'));self.assertFalse(hasattr(cuda,'seal_validation'))
        self.assertFalse(hasattr(cuda,'completed'))


def gpu_proof():
    started=time.monotonic();mem={line.split(':')[0]:int(line.split()[1])*1024
        for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')}
    assert mem['MemAvailable']>=8*1024**3,'At least8GiB headroom is required.'
    smi='/usr/lib/wsl/lib/nvidia-smi'
    def used():return int(subprocess.check_output([smi,'--query-gpu=memory.used','--format=csv,noheader,nounits'],text=True).strip().splitlines()[0])
    initial_gpu=used();backend=cuda.configure();b.torch.cuda.set_per_process_memory_fraction(.025)
    assert '4090' in backend['gpu'],'The bounded proof requires the existing4090.'
    peak_delta=0;proofs=[]
    def guard():
        nonlocal peak_delta
        peak_delta=max(peak_delta,used()-initial_gpu)
        assert time.monotonic()-started<110 and peak_delta<1024
        assert resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024<3*1024**3
        assert b.torch.cuda.max_memory_reserved()<1024**3
    for head in policy.HEADS:
        rows,artifact=training(head);cpu=policy.CpePolicy(artifact,head);gpu=cuda.Actor(artifact,head,backend=backend)
        cpu_cache=math_owner.Prepared(rows,cpu);gpu_cache=cuda.Prepared(rows,gpu)
        views=[view(head,1,True),view(head,3),view(head,head.contract.maxActions)]
        with b.torch.no_grad():
            old,ov=cpu(views);new,nv=gpu([gpu.prepare(row) for row in views])
        forward=max(maximum(old,new),maximum(ov,nv));assert forward<=cuda.TOLERANCE
        try:gpu(views)
        except ValueError:pass
        else:raise AssertionError('CUDA accepted unprepared hot-loop input.')
        try:gpu.prepare(cpu.prepare(views[0]))
        except ValueError:pass
        else:raise AssertionError('CUDA silently migrated CPU prepared input.')
        try:
            with b.torch.autocast(device_type='cuda'):gpu([gpu.prepare(views[0])])
        except ValueError:pass
        else:raise AssertionError('CUDA admitted autocast.')
        wrong=cuda.Actor(artifact,head,backend=backend).float()
        try:wrong([gpu.prepare(views[0])])
        except ValueError:pass
        else:raise AssertionError('CUDA admitted non-Float64 parameters.')
        del wrong
        cpu_opt=adam(cpu);gpu_opt=adam(gpu)
        before=math_owner.minibatch(cpu,cpu_opt,cpu_cache,list(range(64)))
        after=cuda.minibatch(gpu,gpu_opt,gpu_cache,list(range(64)))
        error=max(maximum(cpu.state_dict(),gpu.state_dict()),maximum(cpu_opt.state_dict(),gpu_opt.state_dict()))
        gradient=max(maximum(getattr(cpu,n).grad,getattr(gpu,n).grad) for n in b.PARAMETERS[:-2])
        assert max(error,gradient,maximum(before,after))<=cuda.TOLERANCE
        assert all(b.torch.equal(getattr(gpu,n).detach().cpu(),b.torch.tensor(artifact[n],dtype=b.DTYPE)) for n in b.PARAMETERS[-2:])
        baseline=copy.deepcopy(gpu.state_dict());moments=copy.deepcopy(gpu_opt.state_dict())
        # The existing epoch owner must restore nonempty Adam moments and all arrays.
        result=cuda.epoch(gpu,gpu_opt,gpu_cache,11,SimpleNamespace(deadline=time.time()+90),
            metrics=lambda *_:dict(mean=.03,maximum=.11,worlds=512,states=512,singletonStates=0))
        assert not result['accepted'] and maximum(baseline,gpu.state_dict())==0 and maximum(moments,gpu_opt.state_dict())==0
        actual=cuda.anchor_metrics(gpu,gpu_cache);expected=math_owner.anchor_metrics(cpu,cpu_cache)
        assert maximum(actual,expected)<=cuda.TOLERANCE
        proofs.append(dict(head=head.value,maximumForwardError=forward,maximumGradientError=gradient,
            maximumParameterAndMomentError=error,maximumAnchorError=maximum(actual,expected),rollbackExact=True,valueFrozen=True))
        guard()
    # 17x1024 gain/anchor offers exceed one forward chunk; still one64-slot clip/step.
    rows,artifact=training(policy.Head.EXPANSION,1024,17)
    cpu=policy.CpePolicy(artifact,policy.Head.EXPANSION);gpu=cuda.Actor(artifact,policy.Head.EXPANSION,backend=backend)
    cpu_opt=adam(cpu);gpu_opt=adam(gpu)
    with patch.object(gpu_opt,'step',wraps=gpu_opt.step) as steps,patch.object(b.torch.nn.utils,'clip_grad_norm_',wraps=b.torch.nn.utils.clip_grad_norm_) as clips:
        cuda.minibatch(gpu,gpu_opt,cuda.Prepared(rows,gpu),list(range(64)))
        assert steps.call_count==clips.call_count==1
    math_owner.minibatch(cpu,cpu_opt,math_owner.Prepared(rows,cpu),list(range(64)))
    large=max(maximum(cpu.state_dict(),gpu.state_dict()),maximum(cpu_opt.state_dict(),gpu_opt.state_dict()))
    assert large<=cuda.TOLERANCE;guard()
    return dict(kind='cpe-cuda-constructed-kernel-proof-1',passed=True,backend=backend,heads=proofs,
        largeOfferMaximumError=large,largeOfferSingleClipAndStep=True,elapsedSeconds=time.monotonic()-started,
        peakGpuMemoryIncreaseMiB=peak_delta,peakCudaReservedBytes=b.torch.cuda.max_memory_reserved(),
        peakHostRssBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024,
        tolerance=cuda.TOLERANCE,scientificModelsRead=0,scientificRowsRead=0,newGames=0,scientificFits=0,candidateArtifacts=0)


if __name__=='__main__':
    if sys.argv[1:]==['--gpu-proof']:print(json.dumps(gpu_proof(),sort_keys=True))
    else:unittest.main()
