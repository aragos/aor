"""The unchanged original-group process owner with the continuation's finite mailbox."""
import coordinated_win_handoff as mailbox
import token_paired_process as shared


def live(profile):return shared.live(profile,mailbox=mailbox)
def run_child(profile,command,log):return shared.run_child(profile,command,log,mailbox=mailbox)
