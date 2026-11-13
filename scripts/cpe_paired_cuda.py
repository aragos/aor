"""Explicit future CPE CUDA Float64 kernels, with no fit/publication or campaign entrypoint."""
from dataclasses import dataclass
import math
import os

import purchase_policy as b
import paired_policy_math as math_owner
import cpe_paired_data as data
import cpe_paired_policy as policy

VERSION='cpe-cuda-float64-1'
DEVICE='cuda:0'
TOLERANCE=2e-12


def configure():
    """Reject implicit device/environment choices before constructing any CUDA tensor."""
    b.require(os.environ.get('CUBLAS_WORKSPACE_CONFIG')==':16:8',
        'CUDA Float64 requires explicit CUBLAS_WORKSPACE_CONFIG=:16:8 before process startup.')
    b.require(not b.torch.cuda.is_initialized(),
        'Configure the explicit CPE backend before any CUDA context or tensor exists.')
    b.require(b.torch.cuda.is_available(),'The explicitly requested CUDA device is unavailable.')
    b.torch.set_num_threads(1)
    b.torch.use_deterministic_algorithms(True)
    b.torch.backends.cuda.matmul.allow_tf32=False
    b.torch.backends.cudnn.allow_tf32=False
    return identity()


def identity():
    b.require(os.environ.get('CUBLAS_WORKSPACE_CONFIG')==':16:8' and
        b.torch.get_num_threads()==1 and b.torch.are_deterministic_algorithms_enabled() and
        not b.torch.backends.cuda.matmul.allow_tf32 and not b.torch.backends.cudnn.allow_tf32 and
        not b.torch.is_autocast_enabled('cuda') and not b.torch.is_autocast_enabled('cpu') and
        str(b.torch.get_default_device())=='cpu',
        'The explicit deterministic Float64 backend settings changed.')
    return dict(kind=VERSION,device=DEVICE,dtype='float64',cpuThreads=1,
        cublasWorkspaceConfig=':16:8',deterministic=True,tf32=False,amp=False,
        torch=b.torch.__version__,cuda=b.torch.version.cuda,tolerance=TOLERANCE,
        gpu=b.torch.cuda.get_device_name(0),capability=list(b.torch.cuda.get_device_capability(0)),
        scientificFitAdmission=False,publicationEnabled=False)


@dataclass(frozen=True,eq=False)
class Observation(b._PreparedObservation):
    log_prior: b.torch.Tensor
    local_owners: b.torch.Tensor


class Actor(policy.CpePolicy):
    """Cold preparation copies once; forward requires already prepared device-local input."""
    def __init__(self, model, head, *, backend):
        b.require(backend==identity(),'An exact explicit future backend identity is required.')
        super().__init__(model,head)
        self.to(device=DEVICE,dtype=b.DTYPE)
        self.backend=backend

    def prepare(self, observation):
        if isinstance(observation,Observation):
            b.require(observation.dictionary==self.dictionary and all(value.device==b.torch.device(DEVICE)
                for value in (observation.state_indices,observation.state_values,observation.action_indices,
                    observation.action_values,observation.log_prior,observation.local_owners)),
                'CUDA prepared inputs require the same dictionary and explicit device.')
            b.require(observation.state_values.dtype==observation.action_values.dtype==observation.log_prior.dtype==b.DTYPE,
                'CUDA prepared input values must remain Float64.')
            b.require(observation.state_indices.dtype==observation.action_indices.dtype==observation.local_owners.dtype==b.torch.long,
                'CUDA prepared indices must remain integer tensors.')
            return observation
        b.require(not isinstance(observation,b._PreparedObservation),
            'CPU prepared objects cannot silently migrate inside the CUDA forward path.')
        original=super().prepare(observation)
        values={key:getattr(original,key).to(DEVICE) for key in
            ('state_indices','state_values','action_indices','action_values')}
        return Observation(dictionary=original.dictionary,**values,action_count=original.action_count,
            baseline_index=original.baseline_index,
            log_prior=b.torch.tensor(b.prior_probabilities(original.action_count,original.baseline_index),
                dtype=b.DTYPE,device=DEVICE).log(),
            local_owners=b.torch.zeros(original.action_count,dtype=b.torch.long,device=DEVICE))

    def forward(self, observations):
        b.require(self.backend==identity(),'CUDA forward requires the same explicit deterministic backend.')
        b.require(all(isinstance(row,Observation) for row in observations),
            'CUDA forward accepts only explicitly prepared device-local observations.')
        b.require(all(value.device==b.torch.device(DEVICE) and value.dtype==b.DTYPE for value in self.parameters()),
            'All CUDA actor/value arrays must remain on the explicit Float64 device.')
        return super().forward(observations)

    def _zeros(self, shape):return b.torch.zeros(shape,dtype=b.DTYPE,device=DEVICE)
    def _owners(self, prepared, owners):
        return b.torch.cat([row.local_owners+index for index,row in enumerate(prepared)])
    def _prior(self, observation):return observation.log_prior
    def _exact_prior(self, log_probability, prior, residual):
        correction=b.torch.where(b.torch.all(residual==residual[0]),
            prior-log_probability,b.torch.zeros_like(log_probability))
        return log_probability+correction.detach()


class Prepared(math_owner.Prepared):
    """Typed TRAIN cache; all data constants are copied once before any gradient loop."""
    def __init__(self, training, actor):
        b.require(isinstance(training,data.Training) and isinstance(actor,Actor),
            'CUDA kernel preparation requires typed CPE TRAIN data and the explicit actor.')
        super().__init__(training,actor)
        self.terms={index:(b.torch.tensor(root['currentProbabilities'],dtype=b.DTYPE,device=DEVICE),
            b.torch.tensor(root['proposedIndices'][1:],dtype=b.torch.long,device=DEVICE),
            b.torch.tensor(training.targets[index].deltas[1:],dtype=b.DTYPE,device=DEVICE),
            root['inclusionProbability']) for index,root in enumerate(self.root_records) if root['status']=='READY'}
        self.groups=tuple(tuple(tuple((observation,b.torch.tensor(original,dtype=b.DTYPE,device=DEVICE))
            for observation,original in group) for group in groups) for groups in self.groups)

    def gain_term(self, log, index):
        old,chosen,values,inclusion=self.terms[index]
        return math_owner.gain_tensors(log.exp(),old,chosen,values,inclusion)

    def kl_term(self, log, original):return math_owner.original_kl_tensors(log,original)
    def add_diagnostic(self, previous, value):return previous+value.detach()
    def finish_diagnostic(self, value):
        # Explicit diagnostics transfer only at the64-world boundary, after accumulation.
        return float(value.detach().cpu()) if isinstance(value,b.torch.Tensor) else value


def anchor_metrics(actor, prepared):
    total=b.torch.zeros((),dtype=b.DTYPE,device=DEVICE)
    maximum=b.torch.full((),-math.inf,dtype=b.DTYPE,device=DEVICE)
    count=0;singleton=0
    with b.torch.no_grad():
        for chunk in math_owner.batches(prepared.anchor_rows(range(512))):
            logs,_=actor([item[0] for item in chunk])
            values=b.torch.stack([prepared.kl_term(log,item[1]) for log,item in zip(logs,chunk)])
            weighted=b.torch.stack([value*item[2] for value,item in zip(values,chunk)])
            total=total+weighted.sum();maximum=b.torch.maximum(maximum,values.max())
            count+=len(chunk);singleton+=sum(item[0].action_count==1 for item in chunk)
    # One explicit small transfer at the full-anchor epoch boundary.
    measured=b.torch.stack((total,maximum)).cpu().tolist()
    b.require(all(math.isfinite(value) for value in measured),'Nonfinite original-policy CUDA KL aborts the attempt.')
    return dict(mean=measured[0]/prepared.anchor_count,maximum=measured[1],
        worlds=prepared.anchor_count,states=count,singletonStates=singleton)


def minibatch(actor, optimizer, prepared, slots):
    b.require(isinstance(actor,Actor) and isinstance(prepared,Prepared) and actor.backend==identity(),
        'Only the explicit unchanged CUDA backend may enter this kernel.')
    return math_owner.minibatch(actor,optimizer,prepared,slots)


def epoch(actor,optimizer,prepared,seed,config,*,metrics=None):
    b.require(isinstance(actor,Actor) and isinstance(prepared,Prepared) and actor.backend==identity(),
        'Only the explicit unchanged CUDA backend may enter this epoch.')
    return math_owner.epoch(actor,optimizer,prepared,seed,config,
        metrics=anchor_metrics if metrics is None else metrics)
