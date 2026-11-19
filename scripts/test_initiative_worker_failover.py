import contextlib
from io import StringIO
from pathlib import Path
import threading
import unittest

from initiative_campaign import Campaign, WorkerUnavailable


class InitiativeWorkerFailoverTest(unittest.TestCase):
    def test_unavailable_desktop_shard_returns_to_mac(self):
        campaign = Campaign.__new__(Campaign)
        campaign.stop = threading.Event()
        unavailable = threading.Event()
        completed = []

        def execute(_, task, host):
            if host == "DESKTOP":
                unavailable.set()
                raise WorkerUnavailable("Test connection ended.")
            self.assertTrue(unavailable.wait(2), "The desktop must attempt the other shard.")
            completed.append(task[0])

        campaign.execute = execute
        with contextlib.redirect_stdout(StringIO()):
            campaign.execute_jobs(Path("study"), [("first", None), ("second", None)])

        self.assertCountEqual(completed, ["first", "second"])

    def test_two_unavailable_workers_report_incomplete_work(self):
        campaign = Campaign.__new__(Campaign)
        campaign.stop = threading.Event()

        def execute(_, __, ___):
            raise WorkerUnavailable("Test worker unavailable.")

        campaign.execute = execute
        with contextlib.redirect_stdout(StringIO()):
            with self.assertRaisesRegex(WorkerUnavailable, "incomplete simulation shards"):
                campaign.execute_jobs(Path("study"), [("sample", None)])


if __name__ == "__main__":
    unittest.main()
