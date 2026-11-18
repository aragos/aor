"""Bounded local metadata observer; no games, statistical reports or models are opened."""
import argparse
import os
from pathlib import Path
import subprocess
import time

import robust_token_campaign as c


def observe(output, owner_record, destination):
    output, destination = Path(output), Path(destination)
    destination.mkdir()
    clock, owner = c.read(output/'clock.json'), c.read(owner_record)
    clock_sha = c.sha(output/'clock.json')
    c.write(destination/'owner.json', dict(observerPid=os.getpid(), controllerPid=owner['controllerPid'],
        clockSha256=clock_sha))
    next_report = 0
    while time.time() <= clock['auditDeadlineEpoch']:
        c.require(c.sha(output/'clock.json') == clock_sha, 'The original clock cannot renew during observation.')
        status = c.read(output/'status.json')
        state = subprocess.run(['/bin/ps', '-p', str(owner['controllerPid']), '-o', 'stat='],
            capture_output=True, text=True, timeout=15)
        alive = state.returncode == 0 and bool(state.stdout.strip()) and not state.stdout.lstrip().startswith('Z')
        terminal = status['status'] in ('completed', 'failed', 'interrupted') and not alive
        row = dict(kind='mac-qualification-observation-1', sampledEpoch=time.time(), clockSha256=clock_sha,
            originalDeadlineEpoch=clock['deadlineEpoch'], maximumWaitEpoch=clock['auditDeadlineEpoch'],
            controllerAlive=alive, controllerStatus=status, controllerLost=not alive and status['status']=='running',
            terminal=terminal, newGames=0, modelReads=0, effectRecordsParsed=0)
        c.replace_status(destination/'latest.json', row)
        if time.time() >= next_report or terminal or row['controllerLost']:
            print(__import__('json').dumps(row, sort_keys=True), flush=True)
            next_report = time.time()+1800
        if terminal or row['controllerLost']:
            c.write(destination/'wake.json', row)
            return
        time.sleep(min(45, max(.01, clock['auditDeadlineEpoch']-time.time())))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('output', 'owner-record', 'destination'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    observe(args.output, args.owner_record, args.destination)
