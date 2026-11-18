"""Source-registered second TOKEN step, using only the originally sealed96 FINAL."""
from functools import partial

import token_focus_contract as base

KIND='token-focus-second-campaign-1'
PREFIX='token-focus-second'
STUDY=97
METHOD_SHA='6193986dbd71fad6cd227562132d198ecd147469360187b79582d804788a7b7c'
PARENT_PROTOCOL_SHA='9e176d701c681c552b763a21bfda48c5dbe96ba751740365ef34e567ed44064b'
PARENT_TERMINAL_KIND='token-focus-step-terminal-1'
PARENT_CLOSURE_KIND='token-focus-step-physical-closure-1'
SCHEDULE_SHA='7e1ea6c141dddcc6512dc7ee7491c070b4bf8a0c3a6571229054f86e285ee000'
RNG_AUDIT_SHA='9615271e297987f85b039f9ab55d7b2d67d73ec5864b67ef1786bdc4f689cc93'
ROOT_RNG_PROOF_SHA=None
NUMERIC_READY_SHA=None
REGISTERED_STAGE=base.StageSpec(STUDY,METHOD_SHA,KIND,PREFIX,96,PARENT_PROTOCOL_SHA,
    PARENT_TERMINAL_KIND,PARENT_CLOSURE_KIND,__name__,SCHEDULE_SHA,RNG_AUDIT_SHA,ROOT_RNG_PROOF_SHA,NUMERIC_READY_SHA)
read_method=partial(base.read_method,stage=REGISTERED_STAGE)
origin_policy=partial(base.origin_policy,stage=REGISTERED_STAGE)
validate_worlds=partial(base.validate_worlds,stage=REGISTERED_STAGE)
validate=partial(base.validate,stage=REGISTERED_STAGE)
prepare_protocol=partial(base.prepare_protocol,stage=REGISTERED_STAGE)
native_source=partial(base.native_source,stage=REGISTERED_STAGE)
input_subjects=base.input_subjects
profile_fields=base.profile_fields
native_chunk=base.native_chunk
check_prepared=base.check_prepared
check_inputs=base.check_inputs
BANK_ORDER=base.BANK_ORDER
CASES=base.CASES
CASE_IDS=base.CASE_IDS
EXECUTABLES=base.EXECUTABLES
CONFIDENCE=base.CONFIDENCE
