package com.pauldillon.webchathelper.toolwindow

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.pauldillon.webchathelper.attach.AttachmentService
import javax.swing.TransferHandler

/**
 * Accepts files and folders dragged into the tool window and attaches them (#LI7), from the Project view
 * or the OS file explorer (#9T1); both offer the dragged items as a file list.
 *
 * Installed on the [WebChatPanel], it receives drops over the browser too: the IDE's browser is drawn
 * off-screen into a Swing component with no drop handling of its own, so ChatGPT never sees the drop and
 * its own drop handling is not used (#5PD).
 */
class AttachDropHandler(private val panel: WebChatPanel) : TransferHandler() {

    override fun canImport(support: TransferSupport): Boolean {
        if (!support.isDrop || !FileCopyPasteUtil.isFileListFlavorAvailable(support.dataFlavors)) return false
        // Always copy. Accepting a move would let the drag source (e.g. the OS file explorer) delete the files.
        if (support.sourceDropActions and COPY == 0) return false
        support.dropAction = COPY
        return true
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val paths = FileCopyPasteUtil.getFiles(support.transferable) ?: return false
        AttachmentService.getInstance(panel.project).attachDropped(panel.bridge, paths)
        return true
    }
}
