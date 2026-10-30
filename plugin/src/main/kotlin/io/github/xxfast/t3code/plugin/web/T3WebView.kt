package io.github.xxfast.t3code.plugin.web

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import io.github.xxfast.t3code.plugin.ide.notify
import io.github.xxfast.t3code.plugin.settings.T3Credentials
import io.github.xxfast.t3code.plugin.settings.T3Settings
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

/**
 * Opens [route] of the server's web UI in a T3 web view tab, or focuses the one it has. The
 * escape hatch for what the plugin doesn't own. Without JCEF it goes to the system browser,
 * which has its own sign-in: the plugin's pairing doesn't carry over there.
 */
fun openWebView(project: Project, title: String, route: String) {
  val serverUrl: String = T3Settings.get().serverUrl
  val url: String = webViewUrl(serverUrl, route)
  if (!isJcefSupported()) {
    BrowserUtil.browse(url)
    notify(
      project,
      "This IDE has no JCEF, so $title opened in the browser. It isn't signed in with the " +
        "plugin's pairing there, so the web UI may ask to pair first.",
      NotificationType.WARNING,
    )
    return
  }
  val manager: FileEditorManager = FileEditorManager.getInstance(project)
  val file: WebViewVirtualFile = manager.openFiles
    .filterIsInstance<WebViewVirtualFile>()
    .find { it.address == url }
    ?: WebViewVirtualFile(serverUrl, url, title)
  manager.openFile(file, true)
}

/**
 * Whether this IDE can host a web view. JCEF is core on 2025.3 and its own bundled plugin from
 * 262, which this class loader only sees through the optional `com.intellij.modules.jcef`
 * depends; where that plugin is missing `JBCefApp` doesn't resolve at all. Its own function so
 * the resolution fails, and is caught, in this frame.
 */
private fun isJcefSupported(): Boolean =
  try {
    JBCefApp.isSupported()
  } catch (e: LinkageError) {
    false
  }

/** A route of the web UI as an editor tab, named after what it shows. */
class WebViewVirtualFile(val serverUrl: String, val address: String, title: String) :
  LightVirtualFile(title, T3WebViewFileType, "") {
  init {
    isWritable = false
  }
}

/** Only here to give the tab an icon; never registered, so no file is ever detected as one. */
object T3WebViewFileType : FileType {
  override fun getName(): String = "T3 Web View"

  override fun getDescription(): String = "T3 Code web view"

  override fun getDefaultExtension(): String = ""

  override fun getIcon(): Icon = AllIcons.General.Web

  override fun isBinary(): Boolean = true

  override fun isReadOnly(): Boolean = true

  override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}

class T3WebViewEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is WebViewVirtualFile

  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    T3WebViewEditor(file as WebViewVirtualFile)

  override fun getEditorTypeId(): String = "t3-web-view"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The web UI in JCEF, signed in with the plugin's bearer: every request to the server gets
 * `Authorization: Bearer`, as the plugin's own requests do, so `/api/auth/session` says
 * authenticated. The browser-session cookie isn't an option, `/api/auth/browser-session` takes
 * a one-time pairing credential and minting one needs `access:write`, which the plugin doesn't
 * ask for. The page loads once the credential is read, so the first request has it too.
 */
class T3WebViewEditor(private val file: WebViewVirtualFile) :
  UserDataHolderBase(), FileEditor, DumbAware {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Read on CEF's IO thread, so a plain value; null until loaded, or when not paired. */
  @Volatile
  private var token: String? = null

  private val browser: JBCefBrowser = JBCefBrowser.createBuilder().build()

  init {
    Disposer.register(this, browser)
    browser.jbCefClient.addRequestHandler(BearerRequestHandler(), browser.cefBrowser)
    scope.launch {
      token = T3Credentials.load(file.serverUrl)?.accessToken
      // dispose cancels the scope on the EDT too, so a closed tab never loads
      withContext(Dispatchers.EDT) { browser.loadURL(file.address) }
    }
  }

  private inner class BearerRequestHandler : CefRequestHandlerAdapter() {
    private val resources: CefResourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
      override fun onBeforeResourceLoad(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
      ): Boolean {
        val url: String = request?.url ?: return false
        if (!isServerOrigin(url, file.serverUrl)) return false
        val bearer: String = token ?: return false
        request.setHeaderByName("Authorization", "Bearer $bearer", true)
        // The path only, never the header: says whether the socket upgrade came through here
        if (LOG.isDebugEnabled) LOG.debug("T3 web view authorized ${url.substringBefore('?')}")
        return false
      }
    }

    override fun getResourceRequestHandler(
      browser: CefBrowser?,
      frame: CefFrame?,
      request: CefRequest?,
      isNavigation: Boolean,
      isDownload: Boolean,
      requestInitiator: String?,
      disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler = resources
  }

  override fun getComponent(): JComponent = browser.component

  override fun getPreferredFocusedComponent(): JComponent = browser.component

  override fun getName(): String = "T3 Web View"

  override fun getFile(): VirtualFile = file

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    scope.cancel()
  }

  private companion object {
    val LOG: Logger = Logger.getInstance(T3WebViewEditor::class.java)
  }
}
