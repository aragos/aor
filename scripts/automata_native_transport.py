"""One bounded local/SSH native task, shared by frozen-plan campaign owners.

The caller owns scientific identity, host assignment and execution reservations. This owner only
transports the exact request and independently hashes confined returned native files. It never
retries, chooses a model, reserves a stream or interprets a game outcome.
"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import time


def invoke(profile, profile_sha, request, directory, *, register, release):
    import robust_token_campaign as shared
    directory = Path(directory)
    host = profile['host']; deadline = request['clock']['deadlineEpoch']
    shared.require(time.time() < deadline, 'No native transport may start after the original science deadline.')
    worker = [profile['python'], '-B', profile['workerScript'], '--profile', profile['actualProfile']]
    command = worker if host == 'MAC' else ['ssh', '-o', 'BatchMode=yes', '-o',
        'ConnectTimeout=8', profile['sshTarget'], *worker]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=False, start_new_session=True)
    register(host, process)
    try:
        stdout, stderr = process.communicate(json.dumps(request, allow_nan=False).encode(),
            timeout=max(.1, deadline-time.time()+15))
    except BaseException:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
        raise
    finally:
        release(host)
    (directory/'worker-stderr.log').write_bytes(stderr)
    shared.require(process.returncode == 0,
        f'{host} native task failed; no retry is allowed: {request["taskId"]}.')
    shared.require(len(stdout) <= 96*1024**2,
        'A native chunk transport exceeded its compact output allowance.')
    response = json.loads(stdout)
    shared.require(response['kind'] == 'robust-token-worker-result-1' and response['complete'] is True and
        response['taskId'] == request['taskId'] and response['profileSha256'] == profile_sha,
        'Completed transport must identify this exact original task and frozen host.')
    for name, item in response['files'].items():
        path = Path(name)
        shared.require(not path.is_absolute() and path.parts and
            all(part not in ('.', '..') for part in path.parts),
            'Transported native members must remain inside their original chunk.')
        raw = base64.b64decode(item['base64'], validate=True)
        shared.require(hashlib.sha256(raw).hexdigest() == item['sha256'],
            'A transported original native output changed in transit.')
        target = directory/'native'/path
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(raw)
    return directory/'native'
