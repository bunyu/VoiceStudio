package com.voicestudio.reader.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.voicestudio.mobile.network.ClientFailure
import com.voicestudio.reader.data.Library
import com.voicestudio.reader.data.OpenBook
import com.voicestudio.reader.data.ReaderSettings
import com.voicestudio.reader.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class NarrationState(
    val bookId: String? = null,
    val title: String = "",
    val chapter: Int = 0,
    val chapterTitle: String = "",
    val piece: Int = 0,
    val totalPieces: Int = 0,
    /** The listener asked to play (it may still be waiting for audio). */
    val playing: Boolean = false,
    /** Playback has caught up with the voice engine and is waiting for the next piece. */
    val waiting: Boolean = false,
    /** Pieces ready from the current position onward, without a gap. */
    val readyAhead: Int = 0,
    val rendering: Boolean = false,
    val preparingAll: Boolean = false,
    val preparedTotal: Int = 0,
    val sleepAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val skipped: Int = 0,
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Owns the narration: which piece is playing, which pieces are being generated ahead of it, the
 * background music and the sleep timer. Lives for the process; [ReaderService] keeps the process
 * in the foreground while it plays or prepares.
 */
class Narration(private val context: Context, private val library: Library, private val settings: ReaderSettings, private val renderer: Renderer) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = mutable.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    // Music never takes audio focus: it follows the narration instead of competing with it.
    private val music: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build().apply { repeatMode = Player.REPEAT_MODE_ALL }

    private var book: OpenBook? = null
    private var voice: ResolvedVoice? = null
    private var queuedUntil = -1          // last book piece index added to the player
    private var position = 0              // book piece index being heard
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var renderJob: Job? = null
    private var sleepChapter = -1

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = mediaItem?.mediaId?.toIntOrNull() ?: return
                arrived(index)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) caughtUp()
                if (playbackState == Player.STATE_READY) mutable.value = mutable.value.copy(waiting = false)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                mutable.value = mutable.value.copy(playing = playWhenReady)
                syncMusic()
                if (playWhenReady) ReaderService.start(context)
            }
            override fun onPlayerError(error: PlaybackException) {
                // A damaged cached piece: delete it so it is generated again, and carry on.
                player.currentMediaItem?.localConfiguration?.uri?.path?.let { File(it).delete() }
                mutable.value = mutable.value.copy(error = "playback")
                reload(position, play = mutable.value.playing)
            }
        })
        scope.launch {
            settings.flow.map { Triple(it.voice, it.dialect, it.pronunciations) }.distinctUntilChanged().collect {
                if (book != null) { voice = null; reload(position, play = player.playWhenReady) }
            }
        }
        scope.launch { settings.flow.collect { applyAudio(it) } }
    }

    private fun applyAudio(s: Settings) {
        player.setPlaybackSpeed(s.speed)
        music.volume = s.musicVolume
        val uri = s.musicUri?.takeIf { s.musicOn }
        val current = music.currentMediaItem?.localConfiguration?.uri?.toString()
        if (uri == null) { music.stop(); music.clearMediaItems() }
        else if (uri != current) { music.setMediaItem(MediaItem.fromUri(Uri.parse(uri))); music.prepare() }
        syncMusic()
    }

    private fun syncMusic() {
        music.playWhenReady = player.playWhenReady && settings.value.musicOn && settings.value.musicUri != null
    }

    /** Opens a book at its saved position (or [chapter] when given) without starting playback. */
    suspend fun open(bookId: String, chapter: Int? = null) {
        if (book?.book?.id == bookId && chapter == null) return
        val opened = library.open(bookId)
        val summary = library.books.value.firstOrNull { it.id == bookId }
        book = opened
        val start = when {
            chapter != null -> opened.chapterStarts.getOrElse(chapter) { 0 }
            summary != null -> summary.piece.coerceIn(0, (opened.pieces.size - 1).coerceAtLeast(0))
            else -> 0
        }
        mutable.value = NarrationState(bookId = bookId, title = opened.book.title, totalPieces = opened.pieces.size)
        reload(start, play = false)
    }

    fun current(): OpenBook? = book

    fun play() {
        if (book == null) return
        mutable.value = mutable.value.copy(error = null, finished = false)
        player.playWhenReady = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (mutable.value.waiting) wake.trySend(Unit)
        ReaderService.start(context)
    }
    fun pause() { player.playWhenReady = false }
    fun toggle() = if (player.playWhenReady) pause() else play()

    fun seekPiece(index: Int) { reload(index, play = player.playWhenReady) }
    fun seekChapter(chapter: Int) { book?.let { reload(it.chapterStarts.getOrElse(chapter) { 0 }, play = player.playWhenReady) } }
    fun next() = seekPiece((position + 1).coerceAtMost((book?.pieces?.size ?: 1) - 1))
    fun previous() = seekPiece((position - 1).coerceAtLeast(0))

    /** Generate every remaining piece of the book, e.g. overnight while charging, so playback never waits. */
    fun prepareAll(on: Boolean) {
        mutable.value = mutable.value.copy(preparingAll = on)
        if (on) { ReaderService.start(context); wake.trySend(Unit) }
    }

    fun sleepAfter(minutes: Int?) {
        main.removeCallbacks(sleepFade)
        sleepFade.step = 0
        player.volume = 1f; music.volume = settings.value.musicVolume
        sleepChapter = -1
        if (minutes == null) { mutable.value = mutable.value.copy(sleepAt = null, sleepAtChapterEnd = false); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        mutable.value = mutable.value.copy(sleepAt = at, sleepAtChapterEnd = false)
        main.postDelayed(sleepFade, minutes * 60_000L)
    }
    fun sleepAtChapterEnd() {
        main.removeCallbacks(sleepFade)
        sleepChapter = book?.pieces?.getOrNull(position)?.chapter ?: -1
        mutable.value = mutable.value.copy(sleepAt = null, sleepAtChapterEnd = true)
    }

    private val sleepFade = object : Runnable {
        var step = 0
        override fun run() {
            // Fade both narration and music over about ten seconds, then stop and restore levels.
            step++
            val level = 1f - step / 20f
            if (level <= 0f) {
                step = 0
                player.playWhenReady = false
                player.volume = 1f; music.volume = settings.value.musicVolume
                mutable.value = mutable.value.copy(sleepAt = null)
                return
            }
            player.volume = level; music.volume = settings.value.musicVolume * level
            main.postDelayed(this, 500)
        }
    }

    private fun arrived(index: Int) {
        val opened = book ?: return
        val piece = opened.pieces.getOrNull(index) ?: return
        if (mutable.value.sleepAtChapterEnd && sleepChapter >= 0 && piece.chapter != sleepChapter) {
            player.playWhenReady = false
            sleepChapter = -1
            mutable.value = mutable.value.copy(sleepAtChapterEnd = false)
        }
        position = index
        // Drop pieces already heard so the player's list stays short on long books.
        val currentItem = player.currentMediaItemIndex
        if (currentItem > 3) player.removeMediaItems(0, currentItem - 3)
        publish()
        scope.launch { library.savePosition(opened.book.id, piece.chapter, index) }
        wake.trySend(Unit)
    }

    private fun caughtUp() {
        val opened = book ?: return
        if (queuedUntil >= opened.pieces.size - 1) {
            mutable.value = mutable.value.copy(finished = true, playing = false)
            player.playWhenReady = false
            return
        }
        mutable.value = mutable.value.copy(waiting = true)
        wake.trySend(Unit)
    }

    private fun publish() {
        val opened = book ?: return
        val piece = opened.pieces.getOrNull(position)
        mutable.value = mutable.value.copy(
            piece = position, chapter = piece?.chapter ?: 0,
            chapterTitle = piece?.let { opened.book.chapters[it.chapter].title }.orEmpty(),
            readyAhead = (queuedUntil - position + 1).coerceAtLeast(0),
            waiting = mutable.value.waiting || (player.playWhenReady && queuedUntil < position),
        )
    }

    /** Restarts the player at [index]: queues whatever is already generated and lets the render loop do the rest. */
    private fun reload(index: Int, play: Boolean) {
        val opened = book ?: return
        position = index.coerceIn(0, (opened.pieces.size - 1).coerceAtLeast(0))
        queuedUntil = position - 1
        player.stop(); player.clearMediaItems()
        player.playWhenReady = play
        renderJob?.cancel()
        renderJob = scope.launch { renderLoop() }
        publish()
    }

    private suspend fun ensureVoice(): ResolvedVoice? {
        voice?.let { return it }
        return try {
            renderer.resolve(settings.value).also { voice = it }
        } catch (e: VoiceUnavailable) {
            mutable.value = mutable.value.copy(error = e.reason, waiting = false); player.playWhenReady = false; null
        }
    }

    /** Queues every contiguous generated piece after [queuedUntil]. */
    private fun queueReady(voice: ResolvedVoice) {
        val opened = book ?: return
        val folder = library.audioDir(opened.book.id)
        val startCount = player.mediaItemCount
        while (queuedUntil + 1 < opened.pieces.size && queuedUntil + 1 - position < 400) {
            val piece = opened.pieces[queuedUntil + 1]
            val file = renderer.file(folder, voice, piece)
            if (!file.isFile) break
            val meta = MediaMetadata.Builder().setTitle(opened.book.chapters[piece.chapter].title).setArtist(opened.book.title).build()
            player.addMediaItem(MediaItem.Builder().setMediaId(piece.index.toString()).setUri(Uri.fromFile(file)).setMediaMetadata(meta).build())
            queuedUntil++
        }
        if (player.mediaItemCount > startCount) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) { player.seekTo(startCount, 0); }
        }
        publish()
    }

    private suspend fun renderLoop() {
        val opened = book ?: return
        val folder = library.audioDir(opened.book.id)
        val resolved = ensureVoice() ?: return
        queueReady(resolved)
        if (mutable.value.preparingAll) mutable.value = mutable.value.copy(preparedTotal = countReady(opened, folder, resolved))
        while (true) {
            val ahead = if (mutable.value.preparingAll) opened.pieces.size else settings.value.prepareAhead
            val limit = (position + ahead).coerceAtMost(opened.pieces.size - 1)
            val target = (queuedUntil + 1..limit).firstOrNull { !renderer.file(folder, resolved, opened.pieces[it]).isFile }
            if (target == null) {
                mutable.value = mutable.value.copy(rendering = false)
                if (mutable.value.preparingAll && limit >= opened.pieces.size - 1) mutable.value = mutable.value.copy(preparingAll = false)
                wake.receive(); queueReady(resolved); continue
            }
            mutable.value = mutable.value.copy(rendering = true)
            try {
                renderer.render(folder, resolved, opened.pieces[target])
            } catch (e: CancellationException) { throw e }
            catch (e: ClientFailure) {
                if (e.key == "mobile.local.licence_required" || e.key == "mobile.local.runtime_failed") {
                    mutable.value = mutable.value.copy(error = e.key, rendering = false); player.playWhenReady = false; return
                }
                // One unreadable piece must not stop a whole novel: skip it with a short silence.
                withContext(Dispatchers.IO) { skip(folder, resolved, opened, target) }
                mutable.value = mutable.value.copy(skipped = mutable.value.skipped + 1)
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { skip(folder, resolved, opened, target) }
                mutable.value = mutable.value.copy(skipped = mutable.value.skipped + 1)
            }
            if (mutable.value.preparingAll) mutable.value = mutable.value.copy(preparedTotal = mutable.value.preparedTotal + 1)
            queueReady(resolved)
        }
    }

    private suspend fun skip(folder: File, voice: ResolvedVoice, opened: OpenBook, index: Int) {
        val silent = opened.pieces[index].copy(text = "")
        val file = renderer.render(folder, voice, silent)
        file.copyTo(renderer.file(folder, voice, opened.pieces[index]), overwrite = true)
    }

    private suspend fun countReady(opened: OpenBook, folder: File, voice: ResolvedVoice) = withContext(Dispatchers.IO) {
        opened.pieces.count { renderer.file(folder, voice, it).isFile }
    }

    /** Forgets the open book, e.g. when it is deleted. */
    fun close() {
        stop()
        renderJob?.cancel()
        player.stop(); player.clearMediaItems()
        book = null; voice = null
        mutable.value = NarrationState()
    }

    /** Stops everything when the user closes the notification. */
    fun stop() {
        pause()
        prepareAll(false)
        sleepAfter(null)
    }
}
