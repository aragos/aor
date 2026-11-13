"""Bounded children remain in the original controller group; peer checks read lifecycle only."""
from pathlib import Path
import subprocess
import time

import token_paired_campaign_plan as p
import token_paired_handoff as handoff


def live(profile):
    root=profile['_root'];clock=profile['_clock'];clock.remaining(time.time())
    p.require(handoff.health(root)['status']=='active','The local finite worker has ended.')
    if profile['host']=='MAC':
        result=profile['_peer'].remote('health','MAC')
        if result['status']=='terminal' and result['terminal'].get('status')=='stopped':
            raise handoff.PeerStopped(result)
        p.require(result['status']=='active' and result['protocolSha256']==profile['protocolSha256'] and
            result['deadlineEpoch']==clock.deadline,'Desktop lifecycle ended or changed its original clock.')
    else:
        p.require(not (Path(root)/'peer-failed.json').exists(),'The Mac worker failed; do not continue learning.')


def run_child(profile, command, log):
    """No new session/process group: the reviewed outer controller can always clean descendants."""
    live(profile)
    with Path(log).open('ab') as stream:
        child=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT)
        try:
            next_peer_check=time.monotonic()+30
            while child.poll() is None:
                profile['_clock'].remaining(time.time())
                if time.monotonic()>=next_peer_check:
                    live(profile);next_peer_check=time.monotonic()+30
                time.sleep(min(1,profile['_clock'].remaining(time.time())))
            p.require(child.returncode==0,'Fixed child failed; retain evidence without retry.')
            live(profile)
        except BaseException:
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill();child.wait(timeout=5)
            raise
