// Fork stand-in: VERBATIM EXTRACTION of `object AppChangelog`, reduced to `CURRENT_VERSION` (upstream lines 23-29)
// from upstream ui/AppChangelog.kt
// at commit f36b82d22a87f88e81532dc76016943f33764a0b.
// The declaration body below is copied byte-for-byte by line range (see
// core-spike-tools/gen_extractions.py); only the package line and imports are written here.
//
// Members removed because they reference deleted code and nothing kept uses them:
//  - Release, releases, Expectation, expectations and everything else after line 29 (upstream lines
//    30-2744): the release notes and onboarding copy, which resolve UI strings through the deleted
//    com.lhoop.ui.uiString and Compose icons. No kept file reads them.
//
// CURRENT_VERSION is NOT cosmetic for the foundation: ble/WhoopBleClient.kt passes it to
// RawHistoryArchive.replayIfNeeded as the once-per-version gate for re-decoding archived history
// frames. It must be bumped to upstream's value on every upstream sync.
package com.lhoop.ui

object AppChangelog {

    /**
     * Bump this when you add a release below. The "What's New" sheet shows automatically when the
     * stored last-seen version is behind this. (Decoupled from the bundle version on purpose.)
     */
    const val CURRENT_VERSION = "11.8.0"
}
