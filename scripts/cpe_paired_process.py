"""C/P/E lifecycle identities over the same bounded child/cleanup process owner."""
import cpe_paired_handoff as handoff
import token_paired_process as shared


def run_child(profile, command, log):
    return shared.run_child(profile,command,log,mailbox=handoff)
