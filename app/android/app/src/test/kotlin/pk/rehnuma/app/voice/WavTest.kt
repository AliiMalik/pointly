package pk.rehnuma.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test fun headerDescribes16kMonoPcm() {
        val pcm = ByteArray(32000) // 1 s
        val wav = Wav.wrap(pcm, 16000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))            // byte rate
        assertEquals(16, b.getShort(34).toInt())     // bits
        assertEquals(pcm.size, b.getInt(40))
    }

    @Test fun bestTextSkipsBlank() {
        assertEquals("Turn on WiFi", SpeechResult("Turn on WiFi", "cloud", 1).bestText)
        assertEquals(null, SpeechResult(" ", "device", 1).bestText)
        assertEquals(null, SpeechResult(null, "device", 1).bestText)
    }
}
