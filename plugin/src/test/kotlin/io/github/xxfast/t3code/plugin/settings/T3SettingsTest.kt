package io.github.xxfast.t3code.plugin.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
