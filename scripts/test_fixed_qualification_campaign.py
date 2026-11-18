"""Constructed fixed-contract fixtures; no scientific games, fits or RNG draws."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import robust_token_campaign as c
import mac_qualification_campaign as shared
import fixed_qualification_campaign as f
from test_mac_qualification_campaign import plan as full_plan, native_fixture


def plan():
    value=full_plan()
    value.update(kind=f.KIND,contract=f.SMALL.version,maximumScienceSeconds=10800,
        maximumTerminalExecutions=2064,priorTrialProtocolSha256='a'*64)
    for index,name in enumerate(f.BANKS):
        value['banks'][name].update(count=512,seeds=list(range(index*512,(index+1)*512)),
            replayIndices=list(f.SMALL.replay_indices))
    return value


class FixedContractTests(unittest.TestCase):
    def test_registered_two_bank_contract(self):
        self.assertEqual(f.validate(plan())['maximumTerminalExecutions'],2064)

    def test_full_trial_cannot_be_silently_downsampled(self):
        value=plan();value['banks']['mixed']['seeds']=value['banks']['mixed']['seeds'][:256]
        with self.assertRaisesRegex(ValueError,'registered count'):f.validate(value)

    def test_distinct_worlds_are_required_across_banks(self):
        value=plan();value['banks']['homogeneous']['seeds'][0]=0
        with self.assertRaisesRegex(ValueError,'disjoint'):f.validate(value)

    def test_reproductions_cannot_reuse_old_full_trial_indices(self):
        value=plan();value['banks']['mixed']['replayIndices']=shared.REPLAYS
        with self.assertRaisesRegex(ValueError,'replays'):f.validate(value)

    def test_four_chunks_reserve2064_actual_terminal_executions(self):
        value=plan();profile={name:{} for name in ('inferenceAsset','nomination','arena','candidateBundle')}
        sources=[f.source_value(value,'a'*64,profile,name,start) for name in f.BANKS for start in range(0,512,256)]
        self.assertEqual(len(sources),4)
        self.assertEqual([f.expected_executions(source) for source in sources],[516]*4)
        self.assertEqual(sum(f.expected_executions(source) for source in sources),2064)

    def test_original_science_allowance_cannot_be_renewed(self):
        value=plan();value['maximumScienceSeconds']+=1
        with self.assertRaisesRegex(ValueError,'clock allowances'):f.validate(value)

    def test_contract_does_not_modify_shared_full_trial_constants(self):
        f.validate(plan());self.assertEqual(shared.MAXIMUM,8208)
        self.assertEqual(shared.REPLAYS,[0,682,1365,2047]);self.assertIs(f.execute,shared.execute)

    def test_shared_native_admission_retains_actual_reproductions(self):
        with tempfile.TemporaryDirectory() as directory:
            source,native=native_fixture(Path(directory));self.assertEqual(len(f.validate_chunk(source,native)),2)

    def setup_launch(self,root,closed=True):
        protocol,profile,approval=root/'protocol.json',root/'profile.json',root/'approval.json'
        closure=root/'prior-closure.json';c.write(protocol,plan());c.write(profile,{})
        c.write(closure,dict(passed=True,allOwnedProcessesExited=closed,scienceComplete=False,
            qualified=False,protocolSha256='a'*64))
        c.write(approval,dict(launchAuthorized=True,protocolSha256=c.sha(protocol),profileSha256=c.sha(profile),
            priorTrialClosure=dict(path=str(closure),sha256=c.sha(closure))))
        return protocol,profile,approval

    def test_prior_owned_processes_block_a_new_clock(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,profile,approval=self.setup_launch(root,closed=False)
            with patch.object(f,'verify_profile',return_value=dict(root=str(root))):
                with self.assertRaisesRegex(ValueError,'physically closed'):
                    f.Campaign(protocol,profile,approval,c.sha(approval),root/'execution')
            self.assertFalse((root/'execution').exists())

    def test_new_clock_has_exact_cap_and_cannot_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol,profile,approval=self.setup_launch(root)
            with patch.object(f,'verify_profile',return_value=dict(root=str(root))), \
                    patch.object(f.time,'time',return_value=f.USER_END-100):
                instance=f.Campaign(protocol,profile,approval,c.sha(approval),root/'execution')
                self.assertEqual(instance.clock['deadlineEpoch'],f.USER_END)
                self.assertEqual(instance.clock['auditDeadlineEpoch'],f.USER_END+600)
                with self.assertRaisesRegex(ValueError,'once-only'):
                    f.Campaign(protocol,profile,approval,c.sha(approval),root/'execution')


if __name__=='__main__':unittest.main()
