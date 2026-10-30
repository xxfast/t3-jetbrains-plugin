package io.github.xxfast.t3code.plugin.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jdom.Element

class T3SettingsTest {
  @Test
  fun followUpsSteerUnlessQueueWasPicked() {
    val settings = T3Settings()
    assertEquals(T3Settings.FOLLOW_UP_STEER, settings.followUpBehavior, "Fresh settings steer")

    settings.state.followUpBehavior = "later"
    assertEquals(T3Settings.FOLLOW_UP_STEER, settings.followUpBehavior, "Unknown reads as steer")

    settings.followUpBehavior = T3Settings.FOLLOW_UP_QUEUE
    assertEquals(T3Settings.FOLLOW_UP_QUEUE, settings.followUpBehavior)
  }

  @Test
  fun aQueuePickSurvivesARestart() {
    val before = T3Settings()
    before.followUpBehavior = T3Settings.FOLLOW_UP_QUEUE

    val after = T3Settings()
    after.loadState(
      XmlSerializer.deserialize(XmlSerializer.serialize(before.state), T3Settings.State::class.java),
    )

    assertEquals(T3Settings.FOLLOW_UP_QUEUE, after.followUpBehavior)
  }

  @Test
  fun threadDetailsItemsSurviveARestart() {
    val before = T3Settings()
    // `retired` stands for an id a newer plugin wrote and this one doesn't know
    before.threadDetailsItems = mapOf("lineage" to false, "usage" to true, "retired" to false)

    // What the IDE writes to t3code.xml on exit, and reads back on start
    val xml: Element = XmlSerializer.serialize(before.state)
    val after = T3Settings()
    after.loadState(XmlSerializer.deserialize(xml, T3Settings.State::class.java))

    val expected: Map<String, Boolean> =
      mapOf("lineage" to false, "usage" to true, "retired" to false)
    assertEquals(expected, after.threadDetailsItems)
    assertEquals(expected, after.threadDetailsItemsFlow.value)
  }
}
