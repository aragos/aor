"""Bounded children remain in the original controller group; peer checks read lifecycle only."""
from pathlib import Path
from functools import partial
import subprocess
import time

import token_paired_campaign_plan as p
import token_paired_handoff as handoff


def live(profile, *, mailbox=handoff):
    root=profile['_root'];clock=profile['_clock'];clock.remaining(time.time())
    p.require(mailbox.health(root)['status']=='active','The local finite worker has ended.')
    if profile['host']=='MAC':
        result=profile['_peer'].remote('health','MAC')
        if result['status']=='terminal' and result['terminal'].get('status')=='stopped':
            raise mailbox.PeerStopped(result)
        p.require(result['status']=='active' and result['protocolSha256']==profile['protocolSha256'] and
            result['deadlineEpoch']==clock.deadline,'Desktop lifecycle ended or changed its original clock.')
    else:
        p.require(not (Path(root)/'peer-failed.json').exists(),'The Mac worker failed; do not continue learning.')


def run_child(profile, command, log, *, mailbox=handoff):
    """No new session/process group: the reviewed outer controller can always clean descendants."""
    check=partial(live,profile) if mailbox is handoff else partial(live,profile,mailbox=mailbox)
    check()
    with Path(log).open('ab') as stream:
        child=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT)
        try:
            next_peer_check=time.monotonic()+30
            while child.poll() is None:
                profile['_clock'].remaining(time.time())
                if time.monotonic()>=next_peer_check:
                    check();next_peer_check=time.monotonic()+30
                time.sleep(min(1,profile['_clock'].remaining(time.time())))
            p.require(child.returncode==0,'Fixed child failed; retain evidence without retry.')
            check()
        except BaseException:
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill();child.wait(timeout=5)
            raise
