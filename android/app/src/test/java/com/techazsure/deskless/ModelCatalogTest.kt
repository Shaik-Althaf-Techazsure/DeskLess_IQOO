package com.techazsure.deskless

import com.techazsure.deskless.models.ModelCatalog
import com.techazsure.deskless.models.ModelFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun everyFileIsNamedSizedAndPinned() {
        assertEquals(
            listOf("silero_vad.onnx", "small-tokens.txt", "small-encoder.int8.onnx", "small-decoder.int8.onnx", "gemma-4-E2B-it.litertlm"),
            ModelCatalog.FILES.map { it.name },
        )
        for (file in ModelCatalog.FILES) {
            assertTrue(file.name, file.url.startsWith("https://"))
            assertTrue(file.name, file.url.endsWith("/" + file.name))
            assertTrue(file.name, Regex("[0-9a-f]{64}").matches(file.sha256))
            assertTrue(file.name, file.bytes > 0)
        }
        // What comes from Hugging Face is asked for by the exact version, not by "whatever is newest".
        for (file in ModelCatalog.FILES.filter { "huggingface.co" in it.url }) {
            assertTrue(file.name, Regex(".*/resolve/[0-9a-f]{40}/.*").matches(file.url))
        }
        assertEquals(2_964_276_893, ModelCatalog.TOTAL_BYTES)
    }

    @Test
    fun sizesReadAsAPersonSaysThem() {
        assertEquals("643 KB", ModelCatalog.size(643_854))
        assertEquals("112 MB", ModelCatalog.size(112_442_483))
        assertEquals("2.59 GB", ModelCatalog.size(2_588_147_712))
        assertEquals("2.96 GB", ModelCatalog.size(ModelCatalog.TOTAL_BYTES))
    }

    @Test
    fun whatThePhoneHasCountsInFullAndWhatIsComingByWhatHasArrived() {
        assertEquals(0, ModelCatalog.arrived(emptySet(), emptyMap()))
        assertEquals(ModelCatalog.TOTAL_BYTES, ModelCatalog.arrived(ModelCatalog.FILES.map { it.name }.toSet(), emptyMap()))
        val speech = setOf("silero_vad.onnx", "small-tokens.txt", "small-encoder.int8.onnx", "small-decoder.int8.onnx")
        // A phone that only lacks the large model starts well along the bar.
        assertEquals(376_129_181, ModelCatalog.arrived(speech, emptyMap()))
        assertEquals(376_129_181 + 1_000_000_000L, ModelCatalog.arrived(speech, mapOf("gemma-4-E2B-it.litertlm" to 1_000_000_000L)))
        // A count the download service has not settled yet (it says -1) or that overshoots is kept within the file.
        assertEquals(0, ModelCatalog.arrived(emptySet(), mapOf("silero_vad.onnx" to -1L)))
        assertEquals(643_854, ModelCatalog.arrived(emptySet(), mapOf("silero_vad.onnx" to 9_000_000L)))
        // What is on the phone is not counted twice when a download for it is still listed.
        assertEquals(643_854, ModelCatalog.arrived(setOf("silero_vad.onnx"), mapOf("silero_vad.onnx" to 600_000L)))
    }

    @Test
    fun theStateSaysWhereItStands() {
        val idle = ModelFetch(phase = ModelFetch.Phase.IDLE, missingBytes = ModelCatalog.TOTAL_BYTES)
        assertFalse(idle.busy)
        assertEquals("The AI models are not on this phone yet: 2.96 GB to download.", idle.words)
        assertEquals("Not all of the AI models are on this phone yet: 112 MB to download.", idle.copy(missingBytes = 112_442_483).words)

        val running = ModelFetch(phase = ModelFetch.Phase.RUNNING, arrivedBytes = 1_000_000_000)
        assertTrue(running.busy)
        assertEquals(33, running.percent)
        assertEquals("Downloading the AI models: 1.00 GB of 2.96 GB", running.words)

        val waiting = ModelFetch(phase = ModelFetch.Phase.WAITING_FOR_WIFI, arrivedBytes = 376_129_181)
        assertTrue(waiting.busy)
        assertEquals("Waiting for Wi-Fi to download the AI models (2.59 GB to go).", waiting.words)

        val done = ModelFetch(phase = ModelFetch.Phase.COMPLETE, arrivedBytes = ModelCatalog.TOTAL_BYTES)
        assertFalse(done.busy)
        assertEquals(100, done.percent)
    }
}
