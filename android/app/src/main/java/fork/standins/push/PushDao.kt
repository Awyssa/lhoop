// Fork stand-in (no-op). Replaces push/PushDao.kt as of upstream-fork commit
// f36b82d22a87f88e81532dc76016943f33764a0b. Self-hosted push is removed in this fork.
//
// Upstream's PushDao is NOT a Room @Dao: it is a plain class of raw read-only SQL, built by the
// concrete (non-abstract) WhoopDatabase.pushDao(). Room only needs the TYPE to resolve, so this
// empty class keeps data/WhoopDatabase.kt byte-identical and leaves the Room schema untouched.
package com.lhoop.push

import com.lhoop.data.WhoopDatabase

class PushDao internal constructor(db: WhoopDatabase)
