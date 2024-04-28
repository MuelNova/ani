package me.him188.ani.app.torrent.api

import me.him188.ani.app.torrent.api.pieces.Piece
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.swig.torrent_handle

interface AniTorrentHandle {
    val name: String

    fun addTracker(url: String)

    val contents: TorrentContents

    fun resume()
    fun pause()

    fun setPieceDeadline(pieceIndex: Int, deadline: Int)
}

interface TorrentContents {
    @TorrentThread
    fun createPieces(): List<Piece>

    @TorrentThread
    val files: List<TorrentFile>

    @TorrentThread
    fun getFileProgresses(): List<Pair<TorrentFile, Long>>
}

interface TorrentFile {
    val path: String
    val size: Long

    @TorrentThread
    var priority: FilePriority
}


/**
 * 标记一个 API, 必须在 BT 线程中调用.
 *
 * 访问 libtorrent 的 API 必须在 BT 线程中调用, 否则会导致 native crash.
 */
@Target(
    AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CONSTRUCTOR
)
@RequiresOptIn(message = "This function must be accessed in torrent thread")
annotation class TorrentThread


@TorrentThread
internal fun TorrentHandle.asAniTorrentHandle(): AniTorrentHandle = Torrent4jHandle(this)

internal class Torrent4jHandle
@TorrentThread
constructor(
    private val handle: TorrentHandle,
) : AniTorrentHandle {
    override val name: String get() = handle.name

    // Note: this initialization requires TorrentThread
    private val pieceCount = handle.torrentFile().numPieces()

    override fun addTracker(url: String) {
        handle.addTracker(AnnounceEntry(url))
    }

    override val contents: TorrentContents = Torrent4JContents(handle)
    override fun resume() {
        handle.resume()
    }

    override fun pause() {
        handle.pause()
    }

    override fun setPieceDeadline(pieceIndex: Int, deadline: Int) {
        check(pieceIndex in 0 until pieceCount) {
            "Piece index $pieceIndex out of range [0, ${pieceCount})"
        }
        handle.setPieceDeadline(pieceIndex, deadline)
    }
}

class Torrent4JContents(
    private val handle: TorrentHandle,
) : TorrentContents {
    @TorrentThread
    override fun createPieces(): List<Piece> {
        val info = torrentInfo
        val numPieces = info.numPieces()
        val pieces =
            Piece.buildPieces(numPieces) { info.pieceSize(it).toUInt().toLong() }
        return pieces
    }

    @TorrentThread
    override val files: List<TorrentFile> by lazy {
        val files: FileStorage = torrentInfo.files()
        List(files.numFiles()) { Torrent4jFile(handle, files, it) }
    }

    @TorrentThread
    override fun getFileProgresses(): List<Pair<TorrentFile, Long>> {
        return files.zip(handle.fileProgress(torrent_handle.piece_granularity).toList())
    }

    @TorrentThread
    private val torrentInfo: org.libtorrent4j.TorrentInfo
        get() {
            val torrentInfo: org.libtorrent4j.TorrentInfo? = handle.torrentFile()
            check(torrentInfo != null) {
                "${handle.name}: Actual torrent info is null"
            }
            return torrentInfo
        }
}

private class Torrent4jFile @TorrentThread constructor(
    private val handle: TorrentHandle,
    files: FileStorage,
    private val index: Int,
) : TorrentFile {
    init {
        val numFiles = files.numFiles()
        check(index in 0 until numFiles) {
            "Index $index out of range [0, $numFiles)"
        }
    }

    override val size: Long = files.fileSize(index)
    override val path: String = files.filePath(index)

    @TorrentThread
    override var priority: FilePriority
        get() = handle.filePriority(index).toFilePriority()
        set(value) {
            handle.filePriority(index, value.toLibtorrentPriority())
        }
}

internal fun FilePriority.toLibtorrentPriority(): Priority = when (this) {
    FilePriority.HIGH -> Priority.TOP_PRIORITY
    FilePriority.NORMAL -> Priority.DEFAULT
    FilePriority.LOW -> Priority.TWO
    FilePriority.IGNORE -> Priority.IGNORE
}

internal fun Priority.toFilePriority(): FilePriority {
    return when (this) {
        Priority.IGNORE -> FilePriority.IGNORE
        Priority.LOW -> FilePriority.LOW
        Priority.TWO -> FilePriority.NORMAL
        Priority.THREE -> FilePriority.NORMAL
        Priority.DEFAULT -> FilePriority.NORMAL
        Priority.FIVE -> FilePriority.NORMAL
        Priority.SIX -> FilePriority.NORMAL
        Priority.TOP_PRIORITY -> FilePriority.HIGH
    }
}