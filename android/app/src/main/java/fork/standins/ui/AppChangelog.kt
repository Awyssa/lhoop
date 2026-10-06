// Fork stand-in: `object AppChangelog`, reduced to `CURRENT_VERSION`, EXTRACTED from upstream
// ui/AppChangelog.kt (upstream lines 23-29) at upstream commit 6ce65730.
// The declaration body was copied by line range, by a one-off script that is not in the repository; only
// the package line and imports were written here. Since the app was renamed on 2026-10-06 it differs from
// upstream's in the app's name: identifiers, comments and, in the settings code, the names of the
// preference file and its keys. Defaults and logic are unchanged.
//
// Members removed because they reference deleted code and nothing kept uses them:
//  - Release, releases, Expectation, expectations and everything else after line 29 (upstream lines
//    30-2744): the release notes and onboarding copy, which resolve UI strings through the deleted
//    com.lhoop.ui.uiString and Compose icons. No kept file reads them.
//
// CURRENT_VERSION is NOT cosmetic for the foundation: ble/WhoopBleClient.kt passes it to
// RawHistoryArchive.replayIfNeeded as the once-per-version gate for re-decoding archived history
// frames. Upstream raised it with every release. Raise it here whenever the decoding of history changes
// (for example when one of upstream's decoder fixes is applied by hand), or archived frames are not
// decoded again.
package com.lhoop.ui

object AppChangelog {

    /**
     * Bump this when you add a release below. The "What's New" sheet shows automatically when the
     * stored last-seen version is behind this. (Decoupled from the bundle version on purpose.)
     */
    const val CURRENT_VERSION = "11.8.0"
}
