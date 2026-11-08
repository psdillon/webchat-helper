package com.pauldillon.webchathelper.attach

import java.util.concurrent.ConcurrentHashMap

/**
 * The record of files attached to ChatGPT, used to find the project file that a file in ChatGPT's
 * response matches (#AD4). Held in memory only, so it is lost when the IDE restarts (#3JG).
 *
 * Every attached file is recorded, including files attached as part of a folder (#7P2). The match for a
 * file name is the most recently attached file with that name (#Z98).
 */
class AttachedFiles {
    /** File name to the project-relative path of the most recently attached file with that name. */
    private val latestByName = ConcurrentHashMap<String, String>()

    /** Records that the file at project-relative [relativePath] was attached. */
    fun recordAttached(relativePath: String) {
        latestByName[relativePath.substringAfterLast('/')] = relativePath
    }

    /** The project-relative path of the most recently attached file named [fileName], or null. */
    fun latestWithName(fileName: String): String? = latestByName[fileName]
}
