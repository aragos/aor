"""Opt into joint12-coordinate learning using the existing target package preparer."""
import argparse
from pathlib import Path

import expansion_joint_models as joint
import target_portability
import prepare_expansion_target_search as target_prepare


def configure(root,protocol,options,copy):
    """Freeze the selected qualified anchor before any game or host binding."""
    normalization = copy(options.normalization_source,'evidence/normalization/fit-run.json')
    settings = dict(anchor='E+T',expansionNormalization=joint.read(normalization)['normalization'],
        normalizationSource=dict(path=normalization,sha256=joint.sha(normalization)),militaryQualification=None)
    if options.military_root is not None:
        military = options.military_root.resolve()
        joint.require(joint.sha(military/'protocol.json') == joint.MILITARY_PROTOCOL,
                      'Use only the original fixed military qualification protocol.')
        ordinary = copy(military/'inputs/ordinary-plus-8.json','inputs/ordinary-military8.json')
        expansion = copy(military/'inputs/expansion-plus-8.json','inputs/expansion-military8.json')
        protocol['baseline'],protocol['expansionModel'] = ordinary,expansion
        settings['anchor'] = 'E+T+M8'
        paths = {'audit':'completion-audit.json','confirm':'run/confirm-report.json','heldout':'run/heldout-report.json'}
        settings['militaryQualification'] = {}
        for role,name in paths.items():
            path = copy(military/name,'evidence/military/'+name)
            settings['militaryQualification'][role] = dict(path=path,sha256=joint.sha(path))
        copy(military/'protocol.json','evidence/military/protocol.json')
    else:
        for key in ('baseline','expansionModel'):
            protocol[key] = str(root/protocol[key])
    protocol['jointExpansion'] = settings
    joint.validate_anchor(protocol)
    profile = joint.profile(settings)
    protocol.update(kind=joint.VERSION,featureNames=list(joint.FEATURES),
        normalization=profile.normalization(),coordinateBounds=list(profile.bounds),
        qualificationRule='Only selected candidate:2048 fresh mixed then4096 homogeneous current-anchor pairs; both reward/win99 percent lower bounds>0;8 exact replays per gate.',
        limitation='Joint12-coordinate search around the current qualified anchor; eight coordinates affect expansion only, four public target coefficients affect both model files. Every mixed rival shares the frozen military setting. No human-strength or activation claim.')
    protocol['portability']['coordinates'] = target_portability.JOINT_COORDINATES
    for key in ('baseline','expansionModel'):
        protocol[key] = str(Path(protocol[key]).relative_to(root))
    settings['normalizationSource']['path'] = str(Path(normalization).relative_to(root))
    for item in (settings['militaryQualification'] or {}).values():
        item['path'] = str(Path(item['path']).relative_to(root))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--combination-root',type=Path,required=True)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--normalization-source',type=Path,required=True)
    parser.add_argument('--military-root',type=Path)
    options = parser.parse_args()
    options.joint_expansion = True
    target_prepare.prepare(options)
