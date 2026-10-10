package fork.app

import com.lhoop.data.DataBackup
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Import button's question for a backup over the core's restore ceiling. */
class OversizeBackupTest {

    @Test
    fun theQuestionNamesTheCeilingTheCoreReported() {
        assertEquals(
            "The database in this backup is over 2 GB. Restoring it needs about twice its size free " +
                "on this phone while it works, and takes a few minutes. Nothing has been changed yet.",
            oversizeBackupQuestion(DataBackup.MAX_BACKUP_SQLITE_BYTES),
        )
    }
}
