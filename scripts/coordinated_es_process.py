"""Children stay in the original bounded process group and check the fixed peer lifecycle."""
import coordinated_es_handoff as mailbox
import token_paired_process as shared


def live(profile):
    return shared.live(profile,mailbox=mailbox)


def run_child(profile,command,log):
    return shared.run_child(profile,command,log,mailbox=mailbox)
