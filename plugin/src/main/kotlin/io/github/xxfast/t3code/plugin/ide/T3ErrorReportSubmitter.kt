package io.github.xxfast.t3code.plugin.ide

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.datatransfer.StringSelection

/**
 * "Report to T3 Code" in the IDE's error dialog. Opens a prefilled GitHub issue in the browser;
 * nothing is posted from here and no token is involved. Registered as `errorHandler`.
 */
class T3ErrorReportSubmitter : ErrorReportSubmitter() {
  override fun getReportActionText(): String = "Report to T3 Code"

  override fun submit(
    events: Array<out IdeaLoggingEvent>,
    additionalInfo: String?,
    parentComponent: Component,
    consumer: Consumer<in SubmittedReportInfo>,
  ): Boolean {
    val event: IdeaLoggingEvent = events.firstOrNull() ?: return false
    val info: ApplicationInfo = ApplicationInfo.getInstance()
    val environment = ErrorReportEnvironment(
      pluginVersion = pluginDescriptor?.version ?: "unknown",
      ide = "${info.fullApplicationName} (${info.build.asString()})",
      os = "${SystemInfo.getOsNameAndVersion()} ${SystemInfo.OS_ARCH}",
      jbr = SystemInfo.JAVA_RUNTIME_VERSION,
    )
    val report: ErrorReportUrl = errorReportUrl(event.throwableText, environment, additionalInfo)
    report.clipboard?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
    BrowserUtil.browse(report.url)
    consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
    return true
  }
}
