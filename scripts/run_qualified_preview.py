"""Check an immutable prepared local preview; --start executes it after the owner's review."""
import argparse
import hashlib
import json
import os
from pathlib import Path


def sha(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', action='store_true')
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parent
    inventory = json.loads((root/'immutable-inputs.json').read_bytes())
    config = json.loads((root/'configuration.json').read_bytes())
    for name, expected in inventory['files'].items():
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts or sha(root/relative) != expected:
            raise ValueError('An immutable preview input changed: '+name)
    java = Path(config['java'])
    if sha(java) != config['javaSha256']:
        raise ValueError('The reviewed local JDK changed.')
    if sha(root/'asset/manifest.json') != config['manifestSha256']:
        raise ValueError('The complete qualified policy identity changed.')
    if (config['host'] != '127.0.0.1' or type(config['port']) is not int or
            not 1 <= config['port'] <= 65535):
        raise ValueError('This prepared local preview has an explicit loopback address and port.')
    if not (root/'data').is_dir():
        raise ValueError('The preview requires its own persistent data directory.')
    if not arguments.start:
        print(json.dumps(dict(verified=True, serverStarted=False, port=config['port'],
            manifestSha256=config['manifestSha256']), sort_keys=True))
        return
    environment = os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
        environment.pop(name, None)
    environment.update(AOR_STORAGE='file', AOR_AUTH_MODE='local', AOR_HTTP_HOST=config['host'],
        AOR_HTTP_PORT=str(config['port']), AOR_DATA_DIR=str(root/'data'),
        AOR_WEB_DIR=str(root/'web'), AOR_COORDINATED_BOT_ASSET=str(root/'asset/manifest.json'))
    command = [str(java), '-Xmx2048m', '-cp', str(root/'runtime/lib/*'),
        'dev.aragos.aor.ApplicationKt']
    os.chdir(root)
    os.execve(java, command, environment)


if __name__ == '__main__':
    main()
