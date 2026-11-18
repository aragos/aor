"""All-head probability barriers and equal-phase paired estimates; no checkpoint selection."""
from dataclasses import asdict
from pathlib import Path

import purchase_policy as b
import cpe_paired_data as data
import cpe_paired_fit as fit
import cpe_paired_policy as policy


def seal_validation(fit_directory, original, current, dictionaries, output, load_public):
    """Open public VAL only after atomic completion; publish one seal for all three heads."""
    directory=Path(fit_directory);done=fit.completed(directory)
    b.require(isinstance(dictionaries,dict) and set(dictionaries)==set(policy.HEADS),
        'All three raw dictionaries must be explicitly supplied.')
    output=Path(output);b.require(not output.exists(),'All-head VAL probability publication is once-only.')
    finished_sha=b.file_digest(directory/'finished.json');selected=fit.load_completed_bundle(directory)
    heads={}
    for head in policy.HEADS:
        context=data.Context.from_record(done['identity']['contexts'][head.value])
        context.bind(original,current,dictionaries[head])
        roots=tuple(load_public(head))
        b.require(len(roots)==128 and all(isinstance(root,data.PublicRoot) and root.context==context and
            root.record['split']=='VALIDATION' and root.record['index']==512+index for index,root in enumerate(roots)),
            'Each head must retain every original128 VAL public positions in order.')
        child=fit.child_completed(directory/'heads'/head.value)
        b.require(sum(root.ready for root in roots)==child['identity']['publicCoverage']['validationReady']>=64,
            'Sealed VAL support must equal the outcome-blind pre-fit admission.')
        actor=policy.CpePolicy(selected.actor(head).model,head)
        behavior=policy.CpePolicy(current.actor(head).model,head)
        anchor=policy.CpePolicy(original.actor(head).model,head)
        entries=[]
        with b.torch.no_grad():
            for root in roots:
                row=root.record;values=None
                if row['status']!='MISSING':
                    view=row['observation'];old,_=behavior([view]);initial,_=anchor([view]);new,_=actor([view])
                    for logs,expected in ((old,row['currentProbabilities']),(initial,row['originalProbabilities'])):
                        b.require(max(abs(left-right) for left,right in zip(logs[0].exp().tolist(),expected))<=2e-12,
                            'VAL behavior/original full probabilities must match their exact head actors.')
                    values=new[0].exp().tolist();data.probabilities(values,len(view['actions']),'Sealed VAL probabilities')
                entries.append(dict(index=row['index'],status=row['status'],publicSha256=root.sha256,
                    requestSha256=row['requestSha256'],futureSeeds=row['futureSeeds'],referenceIndex=row['referenceIndex'],
                    proposedIndices=row['proposedIndices'],inclusionProbability=row['inclusionProbability'],
                    currentProbabilities=row['currentProbabilities'],probabilities=values))
        heads[head.value]=dict(context=asdict(context),modelSha256=selected.actor(head).sha256,rows=entries,
            counts={status:sum(row['status']==status for row in entries) for status in ('READY','MISSING')})
    b.require(fit.completed(directory)==done and b.file_digest(directory/'finished.json')==finished_sha,
        'The same complete three-head transaction must survive all public probability generation.')
    receipt=dict(kind='cpe-paired-validation-probabilities-1',complete=True,iteration=done['identity']['iteration'],
        fitDirectory=str(directory.resolve()),fitFinishedSha256=finished_sha,bundleSha256=selected.sha256,
        originalBundleSha256=original.sha256,currentBundleSha256=current.sha256,
        bundleScopeSha256=done['identity']['bundleScopeSha256'],heads=heads,semanticLabelsDecoded=False)
    b.atomic_json(output,receipt)
    return dict(path=str(output.resolve()),sha256=b.file_digest(output))


admitted_seal=fit.completion_owner.admitted_seal
validate_seal_pair=fit.completion_owner.validate_seal_pair
estimate=fit.completion_owner.estimate
equal_phase=fit.completion_owner.equal_phase
_effects=fit.completion_owner.effects


def evaluate_validation(sealed_subjects, output, load_labels):
    """Both atomic iterations/all-head probability seals precede the first semantic callback."""
    output=Path(output);b.require(not output.exists(),'All-head VAL effects are evaluated once-only after both barriers.')
    # The pure owner uses canonical head strings; the public numeric callback remains typed.
    report=fit.completion_owner.build_effects(sealed_subjects,
        lambda iteration,head,subject:load_labels(iteration,policy.Head(head),subject))
    b.atomic_json(output,report);return report
