"""Source contract for legacy WHOOP 5 score preservation (Android; the upstream Swift twin is not in this fork)."""

from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ANDROID_PERSISTENCE = (
    ROOT
    / "android/app/src/main/java/com/lhoop/analytics/IntelligencePersistence.kt"
)


class LegacyScorePreservationContractTests(unittest.TestCase):
    def test_android_also_protects_a_separate_persistence_copy(self) -> None:
        source = ANDROID_PERSISTENCE.read_text()
        preparation_start = source.index("suspend fun prepareComputedWindow")
        preparation_end = source.index("fun scoreProvenance", preparation_start)
        preparation = source[preparation_start:preparation_end]

        self.assertIn("val mutableDailies = dailies.toMutableList()", preparation)
        self.assertIn(
            "repo, computedId, from, to, mutableDailies, ownerByDay,",
            preparation,
        )
        self.assertIn("dailies = mutableDailies", preparation)
        self.assertIn("respRateBpm = existing.respRateBpm", source)
        self.assertIn("avgSdnn = existing.avgSdnn", source)


if __name__ == "__main__":
    unittest.main()
