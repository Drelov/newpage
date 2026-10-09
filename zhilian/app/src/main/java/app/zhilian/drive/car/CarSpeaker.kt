package app.zhilian.drive.car

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class CarSpeaker {
    private var track: AudioTrack? = null

    fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val player = track ?: start()
        player.write(pcm, 0, pcm.size)
    }

    fun stop() {
        val player = track
        track = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
    }

    private fun start(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(min.coerceAtLeast(3840) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        player.play()
        track = player
        return player
    }

    companion object {
        private const val SAMPLE_RATE = 48000
    }
}
