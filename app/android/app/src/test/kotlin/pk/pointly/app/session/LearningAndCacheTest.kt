package pk.pointly.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pk.pointly.app.screen.TestScreens.el
import pk.pointly.app.screen.TestScreens.snap

class LearningAndCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun levelsFadeWithSuccesses() {
        val store = LearningStore(null) { 0 }
        val key = LearningStore.key("settings_font_size", "com.android.settings", "id:title")
        val levels = (0..5).map { n ->
            store.level(key).also { if (n < 5) store.recordSuccess(key) }
        }
        assertEquals(
            listOf(GuidanceLevel.FULL, GuidanceLevel.FULL, GuidanceLevel.HINT, GuidanceLevel.HINT, GuidanceLevel.SILENT, GuidanceLevel.SILENT),
            levels,
        )
    }

    @Test fun learningPersistsAndResets() {
        val f = tmp.newFile("learning.json")
        LearningStore(f) { 42 }.apply { recordSuccess("k"); recordSuccess("k") }
        val again = LearningStore(f) { 0 }
        assertEquals(2, again.successes("k"))
        again.reset()
        assertEquals(0, LearningStore(f) { 0 }.successes("k"))
    }

    @Test fun corruptLearningFileStartsFresh() {
        val f = tmp.newFile("learning.json").apply { writeText("{not json") }
        assertEquals(GuidanceLevel.FULL, LearningStore(f) { 0 }.level("k"))
    }

    @Test fun targetKeyNeverStoresLabelText() {
        val row = el(2, label = "Sara · Kal milte hain", clickable = true)
        val key = LearningStore.targetKey(row, "tap")
        assertFalse(key.contains("sara", ignoreCase = true))
        assertEquals("id:fab", LearningStore.targetKey(el(1, viewId = "fab"), "tap"))
        assertEquals("nav:home", LearningStore.targetKey(null, "home"))
    }

    private val chats = snap(
        el(1, text = "Chats", clickable = true),
        el(2, label = "Sara · Kal milte hain", viewId = "row", clickable = true),
        el(3, label = "Ali · Salam", viewId = "row", clickable = true),
        pkg = "com.whatsapp",
    )

    private fun cachedTapOn(id: Int) = PlanCache.Step(
        goalKey = "whatsapp_send_photo",
        signature = chats.signature,
        action = "tap",
        target = PlanCache.targetOf(chats.element(id)),
        sayUr = "سارہ کے نام پر دبائیں",
        sayEn = "Tap Sara's name",
    )

    @Test fun cacheFindsTargetBySharedIdAndLabelHash() {
        val cache = PlanCache(null)
        cache.store("whatsapp_send_photo", listOf(cachedTapOn(2)))
        val hit = cache.lookup("whatsapp_send_photo", chats)
        assertNotNull(hit)
        assertEquals(2, hit!!.element!!.id)
    }

    @Test fun cacheMissesOnOtherScreenOrGoalOrMissingTarget() {
        val cache = PlanCache(null)
        cache.store("whatsapp_send_photo", listOf(cachedTapOn(2)))
        assertNull(cache.lookup("other_goal", chats))
        assertNull(cache.lookup("whatsapp_send_photo", snap(el(1, text = "Calls"), pkg = "com.whatsapp")))
    }

    @Test fun cachePersistsWithoutLabelText() {
        val f = tmp.newFile("plan_cache.json")
        PlanCache(f).store("whatsapp_send_photo", listOf(cachedTapOn(2)))
        val text = f.readText()
        assertFalse(text.contains("Kal milte hain"))
        assertNotNull(PlanCache(f).lookup("whatsapp_send_photo", chats))
    }

    @Test fun newerStepReplacesSameScreen() {
        val cache = PlanCache(null)
        cache.store("g", listOf(cachedTapOn(2).copy(goalKey = "g")))
        cache.store("g", listOf(cachedTapOn(3).copy(goalKey = "g")))
        assertEquals(1, cache.size)
        assertEquals(3, cache.lookup("g", chats)!!.element!!.id)
    }

    @Test fun firstClause() {
        assertEquals("Sara ke naam pe dabayein", GuidanceSession.firstClause("Sara ke naam pe dabayein, phir photo chunein"))
        assertEquals("نیلا بٹن دبائیں", GuidanceSession.firstClause("نیلا بٹن دبائیں، پھر بھیجیں"))
        assertEquals("Dabayein", GuidanceSession.firstClause("Dabayein"))
    }
}
