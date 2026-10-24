package io.github.xxfast.t3code.plugin

import androidx.compose.ui.graphics.toArgb
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.BigPopupUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.TextComponentEmptyText
import com.intellij.ui.TextIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.tabbedPaneHeader
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.xxfast.t3code.plugin.ui.BADGE_INITIALS_SIZE
import io.github.xxfast.t3code.plugin.ui.BADGE_OFFSET
import io.github.xxfast.t3code.plugin.ui.BADGE_SIZE
import io.github.xxfast.t3code.plugin.ui.FAVORITES_TAB
import io.github.xxfast.t3code.plugin.ui.INITIALS_SIZE
import io.github.xxfast.t3code.plugin.ui.ModelPickerEntry
import io.github.xxfast.t3code.plugin.ui.ModelPickerModel
import io.github.xxfast.t3code.plugin.ui.ModelPickerRow
import io.github.xxfast.t3code.plugin.ui.ProviderLogo
import io.github.xxfast.t3code.plugin.ui.getDisplayModelName
import io.github.xxfast.t3code.plugin.ui.getProviderRowLabel
import io.github.xxfast.t3code.plugin.ui.initialExpandedLegacy
import io.github.xxfast.t3code.plugin.ui.initialModelPickerTab
import io.github.xxfast.t3code.plugin.ui.modelPickerFlatModels
import io.github.xxfast.t3code.plugin.ui.modelPickerRows
import io.github.xxfast.t3code.plugin.ui.parseHexColor
import io.github.xxfast.t3code.plugin.ui.providerInitials
import io.github.xxfast.t3code.plugin.ui.providerLogo
import io.github.xxfast.t3code.plugin.ui.providerModelKey
import io.github.xxfast.t3code.plugin.ui.resolveProviderInstanceDisplayName
import io.github.xxfast.t3code.plugin.ui.showInstanceBadge
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import java.util.function.Predicate
import javax.swing.event.DocumentEvent
import kotlin.math.max

/** What the picker lists and what picking does. The host builds it from the footer's state. */
class ModelPickerRequest(
  val entries: List<ModelPickerEntry>,
  val activeInstanceId: String?,
  val activeModel: String?,
  val favorites: Set<String>,
  /** Why a model can't be picked in this thread, null when it can. */
  val disabledReason: (instanceId: String, slug: String) -> String?,
  val onToggleFavorite: (instanceId: String, slug: String) -> Unit,
  val onPick: (instanceId: String, slug: String) -> Unit,
)

/**
 * Opens the model picker centred on [project]'s window, the way Search Everywhere and Run
 * Anything open (`RunAnythingManager.show`). Upstream's `ProviderModelPicker.tsx` anchors it to
 * the composer's control; the IDE's big popups don't anchor, so neither does this.
 */
fun showModelPicker(project: Project, request: ModelPickerRequest) {
  val ui = ModelPickerPopupUI(project, request)
  val popup: JBPopup = JBPopupFactory.getInstance()
    .createComponentPopupBuilder(ui, ui.searchField)
    .setProject(project)
    .setModalContext(false)
    .setCancelOnClickOutside(true)
    .setRequestFocus(true)
    .setCancelKeyEnabled(true)
    .setResizable(true)
    .setMovable(true)
    .createPopup()
  Disposer.register(popup, ui)
  ui.onPick = { instanceId, slug ->
    popup.cancel()
    request.onPick(instanceId, slug)
  }
  popup.showCenteredInCurrentWindow(project)
}

/** The "All" tab. */
private val ALL_TAB: String? = null

/**
 * The picker as one of the IDE's big popups (`BigPopupUI`, what `SearchEverywhereUI` and
 * `RunAnythingPopupUI` extend): a header of tabs, the big search field, the results. The tabs
 * are All, then one per provider, as Search Everywhere's new-UI header builds them
 * (`SENewUIHeaderView`: a `tabbedPaneHeader` in the complex popup's header colours). The rules
 * for what lists are upstream's, in `ModelPickerContentLogic.kt`.
 */
private class ModelPickerPopupUI(
  project: Project,
  private val request: ModelPickerRequest,
) : BigPopupUI(project) {
  var onPick: (instanceId: String, slug: String) -> Unit = { _, _ -> }

  private val models: List<ModelPickerModel> = modelPickerFlatModels(request.entries)
  private val instanceOrder: List<String> = request.entries.map { it.instanceId }
  private val activeKey: String? = request.activeInstanceId?.let { instance ->
    request.activeModel?.let { providerModelKey(instance, it) }
  }
  private var favorites: Set<String> = request.favorites
  private var tab: String? = initialModelPickerTab(request.activeInstanceId, request.entries)
  private var expandedLegacy: Set<String> =
    initialExpandedLegacy(models, request.activeInstanceId, request.activeModel)

  /** The tabs in header order: All, Favorites (a star), then every provider. */
  private val tabs: List<String?> =
    listOf(ALL_TAB, FAVORITES_TAB) + request.entries.map { it.instanceId }

  private val listModel = DefaultListModel<Any>()
  private lateinit var tabbedPane: JBTabbedPane

  init {
    init()
    mySearchField.emptyText.text = "Search models"
    // The hint hides while the field has focus unless told otherwise, as Search Everywhere tells it
    mySearchField.putClientProperty(
      TextComponentEmptyText.STATUS_VISIBLE_FUNCTION,
      Predicate<JBTextField> { it.text.isEmpty() },
    )
    searchField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = refresh()
    })
    // Tab cycles the tabs, as Search Everywhere; the field would otherwise move focus
    searchField.focusTraversalKeysEnabled = false
    searchField.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        when (e.keyCode) {
          KeyEvent.VK_TAB -> {
            selectAdjacentTab(if (e.isShiftDown) -1 else 1)
            e.consume()
          }
          KeyEvent.VK_ENTER -> {
            (myResultsList.selectedValue as? ModelPickerRow)?.let(::activate)
            e.consume()
          }
        }
      }
    })
    myResultsList.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val index: Int = myResultsList.locationToIndex(e.point)
        if (index < 0 || !myResultsList.getCellBounds(index, index).contains(e.point)) return
        val row: ModelPickerRow = listModel.getElementAt(index) as? ModelPickerRow ?: return
        if (row is ModelPickerRow.Model && e.x >= myResultsList.width - STAR_WIDTH) {
          toggleFavorite(row.model)
        } else {
          activate(row)
        }
      }
    })
    refresh()
    // `BigPopupUI` measures itself short (header and field, no list) until a subclass says it's
    // full; Search Everywhere flips when its list fills, this one always has rows
    updateViewType(ViewType.FULL)
  }

  override fun createList(): JBList<Any> = JBList(listModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    setExpandableItemsEnabled(false)
  }

  /** Search Everywhere's pane wants the screen's worth of rows; a dozen is plenty here. */
  override fun createListPane(): JScrollPane =
    super.createListPane().apply { preferredSize = JBUI.size(WIDTH_UNSCALED, LIST_HEIGHT_UNSCALED) }

  override fun createCellRenderer(): ListCellRenderer<Any> = Renderer()

  override fun createHeader(): JComponent {
    val header = panel {
      row {
        tabbedPaneHeader(tabs.map(::tabName)).applyToComponent {
          font = JBFont.regular()
          background = JBUI.CurrentTheme.ComplexPopup.HEADER_BACKGROUND
          isFocusable = false
          tabbedPane = this
        }
      }
    }
    // `JBTabbedPane` draws its own label per tab and ignores the icon, so the star is the label
    tabbedPane.setTabComponentAt(
      tabs.indexOf(FAVORITES_TAB),
      JBLabel(AllIcons.Nodes.Favorite).apply { border = JBUI.Borders.empty(0, 2) },
    )
    tabs.forEachIndexed { index, id ->
      val entry: ModelPickerEntry? = request.entries.find { it.instanceId == id }
      if (entry != null && !entry.ready) {
        tabbedPane.setEnabledAt(index, false)
        tabbedPane.setToolTipTextAt(index, entry.tooltip)
      }
    }
    tabbedPane.selectedIndex = tabs.indexOf(tab).coerceAtLeast(0)
    tabbedPane.addChangeListener {
      val picked: String? = tabs.getOrNull(tabbedPane.selectedIndex)
      if (picked != tab) selectTab(picked)
    }
    header.border = JBUI.Borders.compound(
      JBUI.CurrentTheme.BigPopup.headerBorder(),
      JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
    )
    return header
  }

  override fun getInitialHint(): String = "Enter picks a model, Tab switches providers"

  override fun dispose() {}

  override fun getAccessibleName(): String = "Model picker"

  private fun tabName(id: String?): String = when (id) {
    ALL_TAB -> "All"
    FAVORITES_TAB -> "" // the star alone, set as the tab's icon
    else -> request.entries.find { it.instanceId == id }?.displayName ?: id.orEmpty()
  }

  private fun refresh() {
    val rows: List<ModelPickerRow> = modelPickerRows(
      models = models,
      tab = tab,
      query = searchField.text,
      favorites = favorites,
      expandedLegacy = expandedLegacy,
      instanceOrder = instanceOrder,
    )
    listModel.clear()
    rows.forEach(listModel::addElement)
    myResultsList.emptyText.text = when {
      searchField.text.isNotBlank() -> "No models found"
      tab == FAVORITES_TAB -> "No favourites yet. Star a model to add one."
      else -> "No models"
    }
    // `autoHighlight`: the current model when it's listed, else the first pickable row
    val current: Int =
      rows.indexOfFirst { it is ModelPickerRow.Model && key(it.model) == activeKey }
    val first: Int = rows.indexOfFirst { !disabled(it) }
    val index: Int = if (current >= 0) current else first
    if (index >= 0) {
      myResultsList.selectedIndex = index
      myResultsList.ensureIndexIsVisible(index)
    }
  }

  private fun selectTab(next: String?) {
    tab = next
    searchField.text = ""
    val index: Int = tabs.indexOf(next)
    if (index >= 0 && tabbedPane.selectedIndex != index) tabbedPane.selectedIndex = index
    refresh()
  }

  /** The next enabled tab in [direction], wrapping. */
  private fun selectAdjacentTab(direction: Int) {
    var index: Int = tabs.indexOf(tab).coerceAtLeast(0)
    repeat(tabs.size) {
      index = Math.floorMod(index + direction, tabs.size)
      if (tabbedPane.isEnabledAt(index)) {
        selectTab(tabs[index])
        return
      }
    }
  }

  private fun activate(row: ModelPickerRow) {
    when (row) {
      is ModelPickerRow.LegacySection -> {
        expandedLegacy =
          if (row.expanded) expandedLegacy - row.instanceId else expandedLegacy + row.instanceId
        refresh()
      }
      is ModelPickerRow.Model -> if (!disabled(row)) onPick(row.model.instanceId, row.model.slug)
    }
  }

  private fun toggleFavorite(model: ModelPickerModel) {
    val key: String = key(model)
    favorites = if (key in favorites) favorites - key else favorites + key
    request.onToggleFavorite(model.instanceId, model.slug)
    val selected: Int = myResultsList.selectedIndex
    refresh()
    if (selected in 0..<listModel.size) myResultsList.selectedIndex = selected
  }

  private fun key(model: ModelPickerModel): String = providerModelKey(model.instanceId, model.slug)

  private fun disabled(row: ModelPickerRow): Boolean = row is ModelPickerRow.Model &&
    request.disabledReason(row.model.instanceId, row.model.slug) != null

  /**
   * A row as the IDE's lists draw them: the provider's icon, the model's name (bold when it's
   * the thread's), a New tag, the provider in grey, a star on the right for a favourite.
   */
  private inner class Renderer : ColoredListCellRenderer<Any>() {
    private var star: Icon? = null

    override fun customizeCellRenderer(
      list: JList<out Any>,
      value: Any,
      index: Int,
      selected: Boolean,
      hasFocus: Boolean,
    ) {
      star = null
      when (value) {
        is ModelPickerRow.LegacySection -> {
          icon = if (value.expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
          val verb: String = if (value.expanded) "Hide" else "Show"
          append("$verb ${value.count} legacy models", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        is ModelPickerRow.Model -> {
          val model: ModelPickerModel = value.model
          val reason: String? = request.disabledReason(model.instanceId, model.slug)
          val entry: ModelPickerEntry? = request.entries.find { it.instanceId == model.instanceId }
          icon = entry?.let { providerIcon(it, selected) }
          toolTipText = reason
          val name: SimpleTextAttributes = when {
            reason != null -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            key(model) == activeKey -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
          }
          append(getDisplayModelName(model, preferShortName = true), name)
          if (model.isNew) append("  New", NEW_ATTRIBUTES)
          append(
            "  ${getProviderRowLabel(model.providerDisplayName, model.subProvider)}",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
          )
          // The star sits at the right edge, painted below; the selected row offers one too
          star = when {
            key(model) in favorites -> AllIcons.Nodes.Favorite
            selected -> AllIcons.Nodes.NotFavoriteOnHover
            else -> null
          }
        }
      }
    }

    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      star?.let { icon ->
        val x: Int = width - STAR_WIDTH + (STAR_WIDTH - icon.iconWidth) / 2
        val y: Int = (height - icon.iconHeight) / 2
        icon.paintIcon(this, g, x, y)
      }
    }
  }

  /** The Swing side of `ProviderIcon`: the same logo, brand colour, initials and badge. */
  private fun providerIcon(entry: ModelPickerEntry, selected: Boolean): Icon {
    val size: Int = JBUIScale.scale(ICON_SIZE_UNSCALED)
    val initials: String = providerInitials(resolveProviderInstanceDisplayName(entry.provider))
    val logo: ProviderLogo? = providerLogo(entry.driver)
    val icon: Icon = if (logo != null) {
      val svg: Icon =
        IconLoader.getIcon("/icons/providers/${logo.name}.svg", ModelPickerPopupUI::class.java)
      val tint: Color = logo.brand?.let { Color(it.toArgb(), true) } ?: UIUtil.getLabelForeground()
      TintedIcon(IconUtil.scale(svg, null, size.toFloat() / svg.iconWidth), tint)
    } else {
      // A `TextIcon` measures nothing until it has a font
      val text: TextIcon = TextIcon(initials, UIUtil.getLabelForeground(), null, 0)
        .apply { setFont(JBFont.label().deriveFont(Font.BOLD, JBUIScale.scale(INITIALS_SIZE))) }
      IconUtil.toSize(text, size, size)
    }
    val badged: Boolean = showInstanceBadge(entry.provider, request.entries.map { it.provider })
    val badge: Badge? = if (badged) {
      val accent: Color? = entry.provider.accentColor?.let(::parseHexColor)
        ?.let { Color(it.toArgb(), true) }
      Badge(initials, accent, UIUtil.getListBackground(selected, true))
    } else {
      null
    }
    return BadgedIcon(icon, badge)
  }

  /**
   * Compose's `ColorFilter.tint` for Swing: [tint] under [icon]'s alpha. `IconUtil.colorize`
   * keeps the source's brightness, so the black logos stayed black whatever the tint.
   */
  private class TintedIcon(private val icon: Icon, private val tint: Color) : Icon {
    override fun getIconWidth(): Int = icon.iconWidth

    override fun getIconHeight(): Int = icon.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      val image: BufferedImage =
        ImageUtil.createImage(g, iconWidth, iconHeight, BufferedImage.TYPE_INT_ARGB)
      val buffer: Graphics2D = image.createGraphics()
      icon.paintIcon(c, buffer, 0, 0)
      buffer.composite = AlphaComposite.SrcIn
      buffer.color = tint
      buffer.fillRect(0, 0, iconWidth, iconHeight)
      buffer.dispose()
      UIUtil.drawImage(g, image, x, y, null)
    }
  }

  /** `ProviderIcon`'s badge: [initials] on [accent], ringed in the row's [background]. */
  private class Badge(val initials: String, val accent: Color?, val background: Color)

  /**
   * [icon] with `ProviderIcon`'s badge hanging off its bottom right. Every row's icon is as
   * wide as a badged one, so the names line up; the badge's drop below the icon is the row's
   * own padding.
   */
  private class BadgedIcon(private val icon: Icon, private val badge: Badge?) : Icon {
    private val offset: Int = JBUIScale.scale(BADGE_OFFSET)

    override fun getIconWidth(): Int = icon.iconWidth + offset

    override fun getIconHeight(): Int = icon.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      icon.paintIcon(c, g, x, y)
      if (badge == null) return
      val g2: Graphics2D = g.create() as Graphics2D
      try {
        GraphicsUtil.setupAntialiasing(g2)
        GraphicsUtil.setupAAPainting(g2)
        g2.font = JBFont.label().deriveFont(Font.BOLD, JBUIScale.scale(BADGE_INITIALS_SIZE))
        val metrics: FontMetrics = g2.fontMetrics
        val height: Int = JBUIScale.scale(BADGE_SIZE)
        val width: Int = max(height, metrics.stringWidth(badge.initials) + JBUIScale.scale(4))
        val left: Int = x + icon.iconWidth + offset - width
        val top: Int = y + icon.iconHeight + offset - height
        val ring: Int = JBUIScale.scale(1)
        g2.color = badge.background
        g2.fillRoundRect(left, top, width, height, height, height)
        g2.color = badge.accent ?: badge.background
        g2.fillRoundRect(
          left + ring,
          top + ring,
          width - 2 * ring,
          height - 2 * ring,
          height - 2 * ring,
          height - 2 * ring,
        )
        g2.color = if (badge.accent != null) Color.WHITE else INFO_FOREGROUND
        val textX: Int = left + (width - metrics.stringWidth(badge.initials)) / 2
        val textY: Int = top + (height - metrics.ascent - metrics.descent) / 2 + metrics.ascent
        g2.drawString(badge.initials, textX, textY)
      } finally {
        g2.dispose()
      }
    }
  }

  private companion object {
    /** Search Everywhere's width, and room for a dozen rows. */
    const val WIDTH_UNSCALED: Int = 670
    const val LIST_HEIGHT_UNSCALED: Int = 300
    const val ICON_SIZE_UNSCALED: Int = 16

    /** Jewel's `text.info`, what the Compose badge's initials use without an accent. */
    val INFO_FOREGROUND: Color =
      JBColor.namedColor("Component.infoForeground", UIUtil.getContextHelpForeground())
    val STAR_WIDTH: Int = JBUI.scale(28)
    val NEW_ATTRIBUTES: SimpleTextAttributes = SimpleTextAttributes(
      SimpleTextAttributes.STYLE_SMALLER,
      JBUI.CurrentTheme.Link.Foreground.ENABLED,
    )
  }
}
