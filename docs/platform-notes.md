# IntelliJ Platform plugin dev notes (2026), for the T3 Code Jewel plugin

Audience: agents implementing `io.github.xxfast.t3code`. Read this file instead of the docs.
Snapshot: 2026-10-19. Repo state: IntelliJ Platform Gradle Plugin `2.19.0`, Kotlin `2.2.20`,
`sinceBuild = 253`, builds against a local IntelliJ IDEA 2026.3 EAP (`IU-263.6259.32`, runs on
JBR 25.0.4.1) via `local(...)` read from the `t3.localIde` Gradle property or `local.properties`.

Conventions: `[local]` = verified 2026-10-19 with `javap` / `unzip -l` on the jars in
`/Users/xxfast/Applications/IntelliJ IDEA.app/Contents/lib` (263.6259.32) or this repo's files;
ground truth for 263, not for 253. **UNVERIFIED** = inferred or untried.

Key source URLs (referenced by short name below):
- [DYN] https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html
- [DEVINST] https://plugins.jetbrains.com/docs/intellij/ide-development-instance.html
- [TASKS] https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html
- [EXT] https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
- [TESTEXT] https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-testing-extension.html
- [GPFAQ] https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-faq.html
- [TYPES] https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-types.html
- [JEWEL] https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/README.md
- [JEWELRN] https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/RELEASE%20NOTES.md
- [THREAD] https://plugins.jetbrains.com/docs/intellij/threading-model.html

## 1. The dev loop
### 1.1 How `runIde` works
- `runIde` extends Gradle `JavaExec` and launches the target IDE with the plugin installed into a
  sandbox. It depends on `prepareSandbox`, which copies the plugin jars and dependencies into the
  sandbox `plugins` dir. On start it prints the log location at lifecycle level, prefixed with
  `IDE logs:`. [TASKS]
- Each `intellijPlatformTesting.runIde.register("x") { }` gets its own sandbox and a
  `prepareSandbox_x` task. Properties: `type`, `version`, `localPath`, `sandboxDirectory`,
  `splitMode`, `plugins { }`, `task { }` (the `RunIdeTask`), `prepareSandboxTask { }`. [TESTEXT]
- This repo: `./gradlew :plugin:runIde` (real server), `./gradlew :plugin:runHarness` (sets
  `t3code.fixtures`, sandbox `plugin/build/harness-sandbox`). [local: plugin/build.gradle.kts]

Sandbox layout, docs vs reality:
- [DEVINST]: `$PROJECT_DIRECTORY$/build/$TARGET_IDE$/idea-sandbox` with `config/`, `plugins/`,
  `system/caches/`, `system/log/idea.log`. [EXT]: `sandboxContainer` defaults to
  `[rootProject]/.intellijPlatform/sandbox`.
- Reality [local]: `.intellijPlatform/sandbox/plugin/IU-2026.3/{config,plugins,system,log}`, log at
  `.../IU-2026.3/log/idea.log` (not under `system/`). `runHarness` subfolders **UNVERIFIED**.
  Trust the `IDE logs:` line `runIde` prints. Delete the dirs to reset the instance. [DEVINST]

Passing system properties and JVM args. `RunIdeTask` is a `JavaExec`, so the usual API works [TASKS]:
```kotlin
// inside intellijPlatformTesting.runIde.register("runHarness") { ... }
task {
  systemProperty("t3code.fixtures", fixturesDir)
  systemProperty("idea.log.debug.categories", "#io.github.xxfast.t3code")
  jvmArgs("-Xmx3g", "-XX:+AllowEnhancedClassRedefinition")
}
// default task: tasks.runIde { systemProperty(...); jvmArgs(...) }
```

- `idea.log.debug.categories` / `idea.log.trace.categories` enable debug/trace logging per category.
  https://plugins.jetbrains.com/docs/intellij/testing-faq.html
- JBR: fetched to match the platform; override with `runIde.jbrVersion`. [DEVINST] With `local(...)`
  probably the IDE's own JBR 25 (2026.3 has `minRequiredJavaVersion: 25` [local]); **UNVERIFIED**.

### 1.2 Reloading the plugin without restarting the sandbox (auto-reload)
What makes a plugin dynamic [DYN]: no components (`<application-components>` etc.); unique `id`
on every action `<group>`; only dynamic EPs (own EPs declared `dynamic="true"`); a `Configurable`
using dynamic EPs implements `Configurable.WithEpDependencies`; no `overrides="true"` services; no
long-lived PSI/`FileType`/`Language` refs or map keys. Load/unload runs on EDT under a write action.
Opt out with `require-restart="true"` on `<idea-plugin>`.

Our plugin today (`toolWindow` EP, light services, no components) should qualify. **UNVERIFIED**
whether Compose itself lets go of our classloader on unload: the Compose runtime and Skiko are
platform modules loaded by the IDE, and anything they cache that points at our composable classes
leaks our `PluginClassLoader`. Test it, don't assume it.

How auto-reload is triggered:
- On by default in Gradle plugin 2.x: `intellijPlatform.autoReload` (default `true`), per task
  `tasks.runIde { autoReload = false }`. [EXT] [GPFAQ] DevKit equivalent: system property
  `idea.auto.reload.plugins`. [DEVINST]
- Loop: sandbox running, change code, `./gradlew :plugin:buildPlugin`, focus the sandbox. [DEVINST]
- It does **not** work when the sandbox is running under the debugger. [DEVINST]
- You must disable `buildSearchableOptions` or you hit "Only one instance of IDEA can be run at a time"
  [DEVINST]: `intellijPlatform { buildSearchableOptions = false }`. Cost: settings pages aren't
  searchable in the Settings dialog. [GPFAQ] We have one small Settings page; acceptable in dev,
  consider re-enabling for release builds.
- **UNVERIFIED** for `runHarness`: `buildPlugin` refreshes the default `prepareSandbox` output. A
  custom registration has its own `prepareSandbox_runHarness`, so the harness probably needs
  `./gradlew :plugin:prepareSandbox_runHarness` instead of `buildPlugin` to see the new jar. Try both.

What breaks reload, and how to debug it [DYN]:
- Any restriction above, or anything holding a reference to the `PluginClassLoader` (static caches,
  listeners not tied to a `Disposable`, coroutines not in the service scope, Swing components kept
  in a global). `CachedValuesManager` values are cleared; nothing else is.
- Log category `com.intellij.ide.plugins.DynamicPlugins` logs the reason for a failed unload.
- Leak hunt: run with `-XX:+UnlockDiagnosticVMOptions`, set registry key
  `ide.plugins.snapshot.on.unload.fail=true`, reload, open the `.hprof` dumped to the user home dir,
  find the plugin id string and its `PluginClassLoader`, walk incoming references.
- Inspection "Plugin.xml dynamic plugin verification" and Plugin Verifier also flag issues.

### 1.3 JBR hotswap from a debug session
- Debug `runIde` / `runHarness` from the Gradle tool window, edit, then **Run | Debugging Actions |
  Reload Changed Classes**. Plain JVM: method bodies only.
  https://www.jetbrains.com/help/idea/2022.2/altering-the-program-s-execution-flow.html
- JBR adds enhanced class redefinition behind `-XX:+AllowEnhancedClassRedefinition` (default
  `false`), plus a `-XX:HotswapAgent=` flag. Both flags exist in the JBR 25.0.4.1 bundled with the
  local 2026.3 EAP [local: `java -XX:+PrintFlagsFinal`]. [DEVINST] lists `dcevm` as a JBR variant and
  says it's bundled by default since JBR 17. Add it via `task { jvmArgs("-XX:+AllowEnhancedClassRedefinition") }`.
- Honest take for Compose: editing a composable body usually changes compiler-generated lambda
  classes and `ComposableSingletons`, i.e. structural changes, so you need enhanced redefinition, and
  even then nothing recomposes until state changes. Expect to close and reopen the tool window, or
  switch fixture scenario, to see it. **UNVERIFIED** in this repo. Auto-reload is off while debugging,
  so pick one per session.

### 1.4 Compose Hot Reload: is it usable inside the IDE?
No, not for code running inside an IntelliJ plugin, as of 2026.

- It's a Gradle plugin (`org.jetbrains.compose.hot-reload`) that launches *your app* via its own
  tasks (`hotRunJvm`, `hotRun`) on JBR. No mention of IntelliJ plugins or Jewel.
  https://github.com/JetBrains/compose-hot-reload
- It can't attach to composables in a `PluginClassLoader` inside a running IDE. The only route is a
  standalone Compose Desktop window with `IntUiTheme`, which CLAUDE.md rules out. Don't bring it in.

### 1.5 Installing a built zip into the running 2026.3 EAP
- Build: `./gradlew :plugin:buildPlugin` -> `plugin/build/distributions/t3-code-<version>.zip`
  (named after the Gradle project, not the plugin name as [TASKS] implies) [local].
- Install: Settings | Plugins | gear | **Install Plugin from Disk...**, pick the zip, restart only if
  prompted. https://www.jetbrains.com/help/idea/managing-plugins.html Dynamic plugins install,
  update and uninstall without restart; failed unload means a restart prompt. [DYN]
- Update: install the newer zip the same way (replace-over-existing isn't spelled out in the help
  page; behaviour per [DYN]). Bump `version` so the Plugins list shows which build is live.
- Caveat: daily IDE runs real mode unless `-Dt3code.fixtures=...` goes in Help | Edit Custom VM
  Options + restart (https://www.jetbrains.com/help/idea/tuning-the-ide.html). Release builds still
  get compiled and verified against 253 (see 6).

## 2. Testing
### 2.1 Tiers at a glance
| Tier | What | Cost | Display | CI |
|---|---|---|---|---|
| 0 | Plain JUnit 5 in `:protocol` (and pure Kotlin in `:plugin`) | ms | no | any runner |
| 1 | Compose UI tests of composables (`createComposeRule` / `runComposeUiTest`) | ~1 s/test | offscreen, **UNVERIFIED** headless | Linux, maybe xvfb |
| 2 | Light platform tests (`BasePlatformTestCase`, or JUnit5 `@TestApplication`) | seconds, app boots once | headless | any runner |
| 3 | Heavy platform tests (`HeavyPlatformTestCase`) | new project per test | headless | any runner |
| 4 | Starter + Driver integration/UI tests (`testIdeUi`) | minutes, full IDE process | yes | Linux + `xvfb-run` |

Platform guidance: headless model-level tests with real components, not mocks, no Swing UI.
https://plugins.jetbrains.com/docs/intellij/testing-plugins.html

### 2.2 Tier 0: plain JVM
`:protocol` has no IDE dependency, so normal `useJUnitPlatform()` + `kotlin-test` /
`junit-jupiter`. Put the `FixtureClient` JSON decoding and any state reducers somewhere that doesn't
touch IDE classes and test them here. This is where most tests should live.

### 2.3 Tier 2/3: platform test framework
What `testFramework(...)` adds [TYPES]:
- `Platform` -> `com.jetbrains.intellij.platform:test-framework`: JUnit 3/4-style
  `BasePlatformTestCase`, `LightPlatformTestCase`, `HeavyPlatformTestCase`, `PlatformTestUtil`.
- `JUnit5` -> `com.jetbrains.intellij.platform:test-framework-junit5`: `com.intellij.testFramework.junit5`.
- `Starter` -> `ide-starter-squashed`, `ide-starter-junit5`, `ide-starter-driver`, `driver-client`,
  `driver-sdk`, `driver-model`. `Bundled` -> `lib/testFramework.jar`, last resort.
- Declare every test-framework dependency explicitly.
  https://plugins.jetbrains.com/docs/intellij/tests-and-fixtures.html

Known dependency gotchas [GPFAQ]:
```kotlin
dependencies {
  testImplementation("org.opentest4j:opentest4j:1.3.0")   // NoClassDefFoundError AssertionFailedError (IJPL-157292)
  testRuntimeOnly("junit:junit:4.13.2")                    // junit5 framework still touches JUnit4 (IJPL-159134)
}
```

Mixing JUnit 3 `BasePlatformTestCase` with JUnit Platform needs `junit-vintage-engine`; regression
reported with Gradle plugin 2.6.0.
https://platform.jetbrains.com/t/introduced-in-2-6-0-running-baseplatformtestcase-alongside-junit-platform-fails/3116

Light vs heavy (https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html):
- Light tests reuse the previous test's project when the `LightProjectDescriptor` matches; use them
  whenever possible. `BasePlatformTestCase` / `LightPlatformTestCase`: `project` gives the `Project`,
  `testRootDisposable` a per-test `Disposable`.
- `HeavyPlatformTestCase` builds a new project per test (multi-module setups). We don't need it.

JUnit 5 API (2025/2026), from the IntelliJ sources' showcase tests
(https://github.com/JetBrains/intellij-community/tree/master/platform/testFramework/junit5/test/showcase):
```kotlin
// com.intellij.testFramework.junit5.{TestApplication, TestDisposable, RunInEdt}
// com.intellij.testFramework.junit5.fixture.projectFixture
@TestApplication                                                  // boots a shared test Application
class T3StateTest {
  companion object { val sharedProject = projectFixture() }       // class-level fixture
  private val project = projectFixture(openAfterCreation = true)  // per-test fixture
  @TestDisposable lateinit var disposable: Disposable             // or as a test parameter
  @Test fun loadsState() { val p: Project = project.get() }
}
```

- Also `tempPathFixture()`, `disposableFixture()`, `moduleFixture(...)`,
  `fileOrDirInProjectFixture(path)`, `@RunInEdt`, `@RegistryKey`, `@SystemProperty` (file list of
  `platform/testFramework/junit5/src` on master). Official docs barely cover it; showcase is the reference.

Testing a `PersistentStateComponent` (light test):
```kotlin
val svc = project.service<T3WorkspaceState>()
svc.loadState(T3WorkspaceState.State().apply { selectedThreadId = "t1" })
assertEquals("t1", svc.state.selectedThreadId)
```

Only the state round trip is ours to test; XML persistence is the platform's.

Testing a `ToolWindowFactory`:
- In tests the `ToolWindowManager` is a headless implementation. Whether `<toolWindow>` EPs are
  auto-registered there is **UNVERIFIED**. Safer: construct the factory and call it directly with a
  mock tool window, e.g. `ToolWindowHeadlessManagerImpl.MockToolWindow(project)` (class name from
  memory, **UNVERIFIED**), then assert on `toolWindow.contentManager.contents`.
- Creating a `ComposePanel` with `java.awt.headless=true` is **UNVERIFIED**; it may work until it's
  shown. If it throws, test the state wiring (services, flows) here and the UI in tier 1.
- Fakes: `testServiceImplementation` in plugin.xml (https://plugins.jetbrains.com/docs/intellij/testing-faq.html)
  or `ServiceContainerUtil.replaceService(...)`. Better seam here: the container picks `FixtureClient`.
- Pump the EDT with `PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()` (also awaits
  background write actions since 2025.3); JUnit 3/4 platform tests run on EDT and can hang otherwise.
  https://platform.jetbrains.com/t/changes-in-threading-model-in-the-intellij-platform-2025-3/2934
- Logs per failing test: `-Didea.split.test.logs=true`. https://plugins.jetbrains.com/docs/intellij/testing-faq.html

### 2.4 Tier 1: composables outside the IDE
- Jewel's own component tests use Compose's desktop test rule with the standalone theme:
  `@get:Rule val rule = createComposeRule()` (import `androidx.compose.ui.test.junit4.v2.createComposeRule`),
  `rule.setContent { IntUiTheme { Link("Whatever", {}, modifier = Modifier.testTag("x")) } }`,
  then `onNodeWithText(...)`, `onNodeWithTag(...)`.
  https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/ui-tests/src/test/kotlin/org/jetbrains/jewel/ui/component/LinkUiTest.kt
- `IntUiTheme` lives in `org.jetbrains.jewel.intui.standalone.theme` and ships as
  `org.jetbrains.jewel:jewel-int-ui-standalone:<jewel>-<ijp-build>`, e.g. `0.34.0-253.31033.149`. [JEWEL]
  Pick the build matching the IDE you compile against (table in [JEWELRN]: 2025.3.x maps to Jewel
  0.32 to 0.36 on Compose MP 1.10.x; 2026.3 EAP is Jewel 0.42 on Compose MP 1.12.1).
- **UNVERIFIED and the real risk**: `:plugin`'s test classpath already contains the IDE's bundled
  Jewel/Compose jars. Adding `jewel-int-ui-standalone` plus `org.jetbrains.compose.ui:ui-test-junit4`
  may produce duplicate or mismatched Compose classes. Options, cheapest first:
  1. Try it in `:plugin` with versions pinned to the target IDE's Compose and see if it links.
  2. Move pure composables (state in, events out, no IDE imports) into their own JVM module that
     depends on the standalone Jewel artifacts for tests and is consumed by `:plugin` as
     `compileOnly`. This is a structural change; ask before doing it.
- Screenshot tests: Compose desktop can `captureToImage()` a node, so golden-image tests under
  `IntUiTheme` are feasible. Not set up anywhere official for plugins. **UNVERIFIED**. Fonts and
  skiko rendering differ by OS; pin CI to one OS for goldens.
- Display: whether these need one is **UNVERIFIED**; Jewel's CI runs display-tagged tests under Xvfb.
  https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/docs/e2e-tests.md

### 2.5 Tier 4: Starter + Driver (integration/UI)
Setup (https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html):
- `testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")`,
  plus `org.junit.jupiter:junit-jupiter`, `org.kodein.di:kodein-di-jvm:7.20.2`,
  `kotlinx-coroutines-core-jvm`. Starter is JUnit 5 only.
- Separate `integrationTest` source set; task via
  `val integrationTest by intellijPlatformTesting.testIdeUi.registering { ... useJUnitPlatform() }`.
  `testIdeUi` runs against `buildPlugin.archiveFile` by default. [TASKS]
- The test reads `System.getProperty("path.to.build.plugin")` and installs the plugin with
  `PluginConfigurator(this).installPluginFromFolder(File(path))` (docs also show `installPluginFromDir`).

Shape of a test:
```kotlin
// IDE identifier: docs say IdeInfo.IdeaUltimate from 262; exact constant UNVERIFIED
Starter.newContext("t3Smoke", TestCase(IdeProductProvider.IU, NoProject).withVersion("2025.3"))
  .apply { PluginConfigurator(this).installPluginFromFolder(File(System.getProperty("path.to.build.plugin"))) }
  .applyVMOptionsPatch { addSystemProperty("t3code.fixtures", fixturesDir) }
  .runIdeWithDriver()
  .useDriverAndCloseIde {
    waitForIndicators(5.minutes)
    invokeAction("ActivateT3CodeToolWindow")   // id derived from tool window id, UNVERIFIED
    // assert through a @Remote stub of our service, see below
  }
```

- It runs the IDE as a separate process and drives it over RMI. First run downloads the IDE; later
  runs reuse the cache. Timings: Jewel reports about a minute per run for its IDE e2e test after the
  first build. [e2e-tests.md above]
- UI API (https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html): `ideFrame { }`,
  `x(xQuery { byAccessibleName("...") })`, `byVisibleText`, `byType`, `.click()`,
  `keyboard { typeText(...); enter() }`, `should { isEnabled() }`, `waitFor(...)`,
  `waitForIndicators(...)`. Inspect the live Swing tree at `http://localhost:63343/api/remote-driver/`.
  On macOS the keyboard robot needs Accessibility permission. Driver is marked experimental.
- Calling plugin code (https://plugins.jetbrains.com/docs/intellij/integration-tests-api.html):
  declare `@Remote("io.github.xxfast.t3code.plugin.T3ProjectService", plugin = "io.github.xxfast.t3code") interface T3ProjectService { fun threadCount(): Int }`
  then `service<T3ProjectService>(project)` / `utility<...>()`. Only public methods, primitives,
  `String`, other `@Remote` refs and lists/arrays of those; no `suspend`. Wrap multi-call sequences
  in `driver.withContext { }`.
- Can it click inside a Jewel panel? Not documented. Driver works on the Swing tree; Compose content
  is one Swing component. JetBrains' own Jewel IDE e2e test uses **Spectre**
  (https://spectre.sebastiano.dev) attached to the IDE process to find and click Compose nodes, and
  the Driver robot for keys, because synthetic events don't reach `IdeEventQueue`/`JBPopup`.
  [e2e-tests.md] Practical plan: assert state through `@Remote` services, not pixels.

### 2.6 What fits CI
- Tiers 0 and 2: any runner, headless (tier 2 needs the platform cache, see 5). Tier 1: Linux, maybe
  `xvfb-run`. Tier 4: Linux + `xvfb-run` (macOS runners can't grant Accessibility to the robot);
  slow, one smoke test at most, maybe nightly.

## 3. Jewel specifics
### 3.1 Wiring
- Jewel ships with the platform from 251.2. Declare bundled modules
  `intellij.platform.jewel.foundation`, `intellij.platform.jewel.ui`, `intellij.platform.jewel.ideLafBridge`,
  `intellij.platform.jewel.markdown.core`, `intellij.platform.jewel.markdown.ideLafBridgeStyling`,
  `intellij.libraries.compose.foundation.desktop`, `intellij.libraries.skiko`; no extra repos. [JEWEL]
  This repo already does that, plus `compose.runtime.desktop`, coroutines and serialization. [local]
- `github.com/JetBrains/jewel` is archived (Apr 2025); code lives in `intellij-community/platform/jewel`.
  Third-party Compose plugins are "not officially supported"; no binary compatibility guarantee.
  https://github.com/JetBrains/jewel
- Since Jewel 0.41/0.42 deprecated APIs are only removed in a new major IJP's EAP; no breaking changes
  in Beta/RC/Stable. [JEWELRN v0.41]

### 3.2 Panels: `JewelComposePanel` vs `compose { }` vs `addComposeTab`
All in package `org.jetbrains.jewel.bridge` [local, 263]:
```kotlin
fun JewelComposePanel(
  focusOnClickInside: Boolean = true,
  config: ComposePanel.() -> Unit = {},
  content: @Composable () -> Unit,
): JComponent
fun compose(focusOnClickInside: Boolean = true, config: ComposePanel.() -> Unit = {}, content: @Composable () -> Unit): JComponent
fun ToolWindow.addComposeTab(
  tabDisplayName: @TabTitle String? = null, isLockable: Boolean = true, isCloseable: Boolean = false,
  focusOnClickInside: Boolean = true, content: @Composable ToolWindowScope.() -> Unit,
)
interface ToolWindowScope { val toolWindow: ToolWindow }
```

- `compose { }` is an alias for `JewelComposePanel`; `composeWithoutTheme { }` /
  `JewelComposeNoThemePanel` skip the theme and are `@ExperimentalJewelApi`. [JEWELRN v0.29 notes, local]
- Parameter names for `addComposeTab` come from Kotlin metadata [local]; defaults are **UNVERIFIED**.
- `JewelComposePanel` applies `SwingBridgeTheme` (`org.jetbrains.jewel.bridge.theme`,
  `@ExperimentalJewelApi` in 263 [local]) for you; don't call it yourself.
- 253 vs 263: the overloads *without* `focusOnClickInside` were `DeprecationLevel.HIDDEN` in 253 and
  removed in 0.42 (263). Source calls like `JewelComposePanel { ... }` resolve to the
  `focusOnClickInside` overload in both, so the bytecode links on 253 and 263.
  https://github.com/JetBrains/intellij-community/blob/253/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/JewelComposePanelWrapper.kt
  and [JEWELRN v0.42 migration guide]
- `addComposeTab` also enables the new Swing compositing pipeline. [JEWEL] Either it or
  `ContentFactory.getInstance().createContent(JewelComposePanel { }, null, false)` works.

### 3.3 Theme, colours, fonts
- `org.jetbrains.jewel.foundation.theme.JewelTheme` exposes `globalColors`, `globalMetrics`,
  `contentColor`, `defaultTextStyle`, `editorTextStyle`, `consoleTextStyle`, `isIslands` (0.42+). [local]
- Typography: `JewelTheme.typography` (in `org.jetbrains.jewel.ui`) has `labelTextStyle`,
  `editorTextStyle`, `consoleTextStyle`, `regular`/`medium`/`small`; the old top-level
  `Typography` object was removed in 0.42. [local] [JEWELRN v0.42]
- Arbitrary IDE LaF keys, in `org.jetbrains.jewel.bridge` (`BridgeUtilsKt`) [local]:
  `retrieveColorOrUnspecified("Panel.background")`, `retrieveColor(key, default)`,
  `retrieveTextStyle(...)`, `retrieveJBFont(key)`, `retrieveEditorColorScheme()`,
  `java.awt.Color.toComposeColor()`, `Color.toAwtColor()`, `isNewUiTheme()`, `isDarculaTheme()`.
  These read the current LaF; call them inside composition so theme changes recompose
  (**UNVERIFIED** whether they're snapshot-observed).

### 3.4 Components (package `org.jetbrains.jewel.ui.component` unless noted) [local, 263]
- `Text`, `DefaultButton`, `OutlinedButton`, `DefaultSlimButton`, `IconButton`, `IconActionButton`,
  `ActionButton`, `Link`, `ExternalLink`, `DropdownLink`, `Divider`, `GroupHeader`, `Tooltip`,
  `CircularProgressIndicator`, `Badge`, `Chip`, `InlineBanner`/`DefaultBanner` family, `TextField`,
  `SearchTextField` (0.42), `LazyTree`, `FilterableLazyColumn`.
- Combo boxes: `ListComboBox(items, selectedIndex, onSelectedItemChange, ...)` and
  `EditableListComboBox`; generic `ComboBox` (experimental overloads). `Dropdown` is deprecated in 0.42
  in favour of the experimental `MenuComboBox` (263 only). For a header picker that must work on 253
  and 263, use `ListComboBox`. (It exists on the 253 branch:
  https://github.com/JetBrains/intellij-community/blob/253/platform/jewel/ui/src/main/kotlin/org/jetbrains/jewel/ui/component/ListComboBox.kt)
- Scrolling: `VerticallyScrollableContainer(scrollState: ScrollableState, modifier, ...) { LazyColumn(state = ...) }`.
  In 0.42 the overloads taking `LazyListState` were removed; pass the `LazyListState` as the
  `ScrollableState` parameter. [JEWELRN v0.42] Selection lists: `SelectableLazyColumn` is deprecated
  in favour of `SingleSelectionLazyColumn` / `MultiSelectionLazyColumn` in
  `org.jetbrains.jewel.foundation.lazy`. [JEWELRN v0.42]
- Icons: `Icon(key: IconKey, contentDescription, ...)`. Platform icons via
  `org.jetbrains.jewel.ui.icons.AllIconsKeys` (e.g. `AllIconsKeys.Actions.Refresh`, `AllIconsKeys.Diff...`),
  generated from `AllIcons`, no setup needed inside a plugin; icons can move between platform
  versions. [local] https://github.com/JetBrains/jewel . A `javax.swing.Icon` converts with
  `IntelliJIconKey.fromPlatformIcon(icon, iconClass)` (`org.jetbrains.jewel.bridge.icon`) [local, 263;
  253 **UNVERIFIED**]. Avoid the `Icon(iconDescriptor: IconDescriptor)` overload: renamed in 0.42,
  doesn't exist on 253. [JEWELRN v0.42]
- Markdown (`intellij.platform.jewel.markdown.core`): `org.jetbrains.jewel.markdown.Markdown(markdown: String, ...)`
  and `LazyMarkdown(blocks, ...)`; parse with `org.jetbrains.jewel.markdown.processing.MarkdownProcessor`
  (`processMarkdownDocument(raw: String): List<MarkdownBlock>` [local]). Provide styling once, above, with
  `org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling(project, ...) { }` (overloads
  with and without `Project`). [local] The 0.42 removals: `Markdown` overloads taking `onTextClick`,
  `ProvideMarkdownStyling(themeName = ...)`. [JEWELRN v0.42] GFM extensions (tables, alerts,
  strikethrough, autolink) are separate jars `intellij.platform.jewel.markdown.extensions.*` [local],
  needing more `bundledModule`s.
- `@ExperimentalJewelApi` (`org.jetbrains.jewel.foundation`) marks: `SwingBridgeTheme`,
  `composeWithoutTheme`, `JewelComposeNoThemePanel`, several `ComboBox` overloads,
  `rememberPopupPositionProviderAtFixedPosition`, `MenuComboBox`. [local] Opt in per file with
  `@OptIn(ExperimentalJewelApi::class)`; Plugin Verifier and the "Unstable API Usage" inspection flag
  `@ApiStatus.Experimental` usage.
  https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html

### 3.5 State in, events out, and calling back into the IDE
- Collect flows with Compose's `collectAsState()`:
  `val threads by client.threads.collectAsState()`. Composition runs on the EDT (Compose Desktop's
  main dispatcher is the AWT queue). Per CLAUDE.md, composables take state, not the client; collect
  in a thin root composable created by the tool window factory.
- Event lambdas (`onClick`) run on the EDT. Opening editors, `OpenFileDescriptor.navigate(true)`,
  `DiffManager.showDiff(...)` are EDT calls, so call them straight from the lambda; anything slow
  (network, `PasswordSafe`, VFS refresh) goes to a service scope:
  `onOpen = { project.service<T3ProjectService>().openFile(path) }`, which does
  `cs.launch { val vf = withContext(Dispatchers.IO) { ... }; withContext(Dispatchers.EDT) { ... } }`.
- `runInEdt(modalityState) { }` (`com.intellij.openapi.application`) exists [local]; from coroutines
  prefer `withContext(Dispatchers.EDT)`. https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html
- Capture `project` in the factory, pass lambdas down, check `project.isDisposed` in late callbacks.

## 4. Platform APIs we need
Signatures below are from `javap` on 263 [local] unless cited otherwise.

**Application service with injected scope.** Light services need no plugin.xml entry, must be
`final`, and get a per-instance `CoroutineScope` (context `Dispatchers.Default` +
`CoroutineName`) that's cancelled on app shutdown or plugin unload. Get them with `service<T>()`
anywhere, any thread, no read action; don't cache.
https://plugins.jetbrains.com/docs/intellij/plugin-services.html
https://plugins.jetbrains.com/docs/intellij/launching-coroutines.html

```kotlin
@Service(Service.Level.APP)
class T3ConnectionService(private val scope: CoroutineScope) {
  val state: StateFlow<ConnectionState> = ...
  fun connect(): Job = scope.launch { ... }
  companion object { fun getInstance(): T3ConnectionService = service() }
}
```

**Project service.** Constructor may take `(Project)` or `(Project, CoroutineScope)`; nothing else
(no other service injection). `project.service<T>()`. Implement `Disposable` if it needs cleanup.
[plugin-services.html above]

```kotlin
@Service(Service.Level.PROJECT)
class T3ProjectService(private val project: Project, private val scope: CoroutineScope)
```

**Per-project workspace state.** `SimplePersistentStateComponent<S : BaseState>` is the Kotlin
recommendation; `loadState` only runs if non-default state was saved; nothing is written when state
equals default; `@Storage(StoragePathMacros.WORKSPACE_FILE)` puts it in `.idea/workspace.xml`
(not shared). Application-level light-service state needs `roamingType = RoamingType.DISABLED`.
https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html

```kotlin
@Service(Service.Level.PROJECT)
@State(name = "T3CodeWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class T3WorkspaceState : SimplePersistentStateComponent<T3WorkspaceState.State>(State()) {
  class State : BaseState() {
    var serverUrl by string("http://127.0.0.1:3773")
    var selectedThreadId by string()
  }
}
```

**Credentials (bearer token).** `com.intellij.credentialStore`: `CredentialAttributes(serviceName, userName)`,
`generateServiceName(subsystem: String, key: String)`, `Credentials(user, password)`.
`PasswordSafe.instance` (`com.intellij.ide.passwordSafe.PasswordSafe`) has `get(attributes): Credentials?`
and `set(attributes, credentials?)` (`null` deletes). Blocking: never on EDT. Backends: macOS Keychain,
libsecret on Linux, KeePass on Windows. [local]
https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html

```kotlin
private fun attrs(server: String) =
  CredentialAttributes(generateServiceName("T3 Code", server))
suspend fun token(server: String): String? =
  withContext(Dispatchers.IO) { PasswordSafe.instance.get(attrs(server))?.getPasswordAsString() }
```

**Settings page under Tools.** Application configurables need a no-arg constructor; project ones a
single `Project` arg. `reset()` runs right after `createComponent()`; `disposeUIResources()` on close.
`id` should be based on the plugin id. https://plugins.jetbrains.com/docs/intellij/settings-guide.html

```xml
<applicationConfigurable parentId="tools" instance="io.github.xxfast.t3code.plugin.settings.T3Configurable"
  id="io.github.xxfast.t3code.settings" displayName="T3 Code"/>
```

Implement `Configurable` (`createComponent`, `isModified`, `apply`, `reset`, `getDisplayName`) or
`BoundConfigurable` + Kotlin UI DSL `panel { }` (safe choice,
https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html). Compose alternative:
`com.intellij.platform.compose.ComposeSearchableConfigurable` (`@ApiStatus.Experimental`, override
`ComposeContent()`), in 263 [local] and the 253 branch; module wiring **UNVERIFIED**.

**Diff.** No SDK page exists (404 at https://plugins.jetbrains.com/docs/intellij/diff.html); use the
`com.intellij.diff` API, not the legacy `com.intellij.openapi.diff`.
https://intellij-support.jetbrains.com/hc/en-us/community/posts/206751345/comments/206084665

```kotlin
val f = DiffContentFactory.getInstance()
val left: DocumentContent = f.create(project, oldText, fileType)         // (Project?, String, FileType?)
val right: DiffContent = f.create(project, virtualFile)                 // (Project?, VirtualFile)
val req = SimpleDiffRequest("Changes in Foo.kt", left, right, "Before", "After")
DiffManager.getInstance().showDiff(project, req)                       // EDT
// multi-file:
val chain = SimpleDiffRequestChain(listOf(req1, req2))                 // or SimpleDiffRequestChain.fromProducers(producers)
DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
```

Also: `create(project, text, virtualFileOrFilePath)` (highlight by path), `createEmpty()` (added or
deleted files), `DiffManager.createRequestPanel(project, parentDisposable, window)` (embeddable),
`DiffUserDataKeys.SCROLL_TO_LINE`. [local] https://platform.jetbrains.com/t/how-to-close-a-simplediffrequest-in-code/994

**Opening files.**

```kotlin
val vf: VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) // or findFileByNioFile
OpenFileDescriptor(project, vf, line, column).navigate(true)            // 0-based line/column, EDT
FileEditorManager.getInstance(project).openFile(vf, /* focusEditor = */ true)
```

`refreshAndFindFileByNioFile` hits disk (keep off EDT); `findFileByNioFile` reads the VFS cache.
Documented twins: `VirtualFileManager.findFileByNioPath()` / `refreshAndFindFileByNioPath()`. [local]
https://plugins.jetbrains.com/docs/intellij/virtual-file.html

**Server-only content.** `com.intellij.testFramework.LightVirtualFile(name: String, fileType: FileType, text: CharSequence)`
(production API despite the package), `isWritable = false`, open with `FileEditorManager`; type via
`FileTypeManager.getInstance().getFileTypeByFileName(name)`. [local]

**Read-only banner.** Register `com.intellij.editorNotificationProvider` with an
`EditorNotificationProvider` whose
`collectNotificationData(project, file): Function<in FileEditor, out JComponent?>?` returns
`{ editor -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).text("From T3 server, read-only") }`
for our `LightVirtualFile`s (tag them with a `Key` in user data). Mark it `DumbAware`. Refresh with
`EditorNotifications.getInstance(project).updateNotifications(file)`. [local]
https://plugins.jetbrains.com/docs/intellij/informing-users.html

**Notifications.** Declare `<notificationGroup id="T3 Code" displayType="BALLOON"/>` (also
`STICKY_BALLOON`, `TOOL_WINDOW` with `toolWindowId`, `NONE`). The docs now show
`Notification("T3 Code", "Paired", NotificationType.INFORMATION).addAction(NotificationAction.createSimpleExpiring("Open") { ... }).notify(project)`
and call `NotificationGroupManager.getNotificationGroup(...)` obsolete; it still exists in 263 [local].
https://plugins.jetbrains.com/docs/intellij/notification-balloons.html

**Tool window.** `<toolWindow id="T3 Code" anchor="right" factoryClass="..." icon="..."/>`;
`ToolWindowFactory` has `createToolWindowContent(project, toolWindow)`, `init(toolWindow)`,
`shouldBeAvailable(project)`, `isApplicable(project)`, `suspend isApplicableAsync(project)`,
`suspend manage(toolWindow, toolWindowManager)` [local]. Make it `DumbAware` or it's disabled while
indexing. Tabs: `toolWindow.contentManager.addContent(content)`, `content.setDisposer(disposable)`,
`canCloseContents="true"` to allow closing. Use `ToolWindowManager.invokeLater()` for tool window EDT
work. https://plugins.jetbrains.com/docs/intellij/tool-windows.html

**Header actions.** `ToolWindow.setTitleActions(List<AnAction>)` (icon buttons in the header),
`setAdditionalGearActions(ActionGroup)` (gear menu), `setTabActions(vararg AnAction)`; `ToolWindowEx`
(`com.intellij.openapi.wm.ex`) adds `getDecoration()` etc. [local] Call these in
`createToolWindowContent`. For a dropdown: a `ComboBoxAction` (`com.intellij.openapi.actionSystem.ex`,
an `AnAction` + `CustomComponentAction`) renders as a combo in toolbars; whether the tool window
title bar renders custom components is **UNVERIFIED**. Recommendation for the fixture scenario
picker: a Jewel `ListComboBox` in a header row inside our own Compose content. It's fixture-only, so
it should live in our UI anyway, and needs no action-system plumbing. Use `setTitleActions` for icon
actions (refresh, settings).

**Actions.** `AnAction.actionPerformed(e)` runs on EDT; launch coroutines with
`currentThreadCoroutineScope().launch { }` so the action system can manage them.
`AnActionEvent.coroutineScope` only exists from 2026.1, so don't use it with `sinceBuild = 253`.
https://plugins.jetbrains.com/docs/intellij/launching-coroutines.html
https://platform.jetbrains.com/t/changes-in-threading-model-in-the-intellij-platform-2026-1/3800

**Listeners.** `<projectListeners><listener class="..." topic="..."/></projectListeners>`; may take a
`Project` ctor arg; stateless, never `Disposable`. In code `messageBus.connect(disposable).subscribe(...)`.
https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html

## 5. CI
Reference: `intellij-platform-plugin-template` `.github/workflows/build.yml` and `release.yml`:
https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/.github/workflows/build.yml
https://github.com/JetBrains/intellij-platform-plugin-template/blob/main/.github/workflows/release.yml
The template frees disk space with `jlumbroso/free-disk-space`, uses `actions/checkout@v7`,
`actions/setup-java@v6` (zulu 21), `gradle/actions/setup-gradle@v6` (`cache-read-only: true` on
non-build jobs), and uploads `build/reports/pluginVerifier`.

Skeleton for this repo (adapted; paths are `plugin/build/...`):
```yaml
name: Build
on:
  push: { branches: [ main ] }
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: jlumbroso/free-disk-space@v2.0.0
        with: { preinstalled-runtimes: false, large-packages: false }
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: zulu, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew build :plugin:verifyPlugin
      - if: ${{ always() }}
        uses: actions/upload-artifact@v7
        with: { name: pluginVerifier-result, path: plugin/build/reports/pluginVerifier }
      - uses: actions/upload-artifact@v7
        with: { name: plugin-distribution, path: plugin/build/distributions/*.zip }
```

Notes:
- No `t3.localIde` on CI, so the build falls back to `intellijIdea("2025.3")`, which is what we ship
  against. Good: CI is the honest compile check for 253. [local: plugin/build.gradle.kts]
- Caching: `setup-gradle` caches the Gradle user home, where the IntelliJ Platform Gradle Plugin
  stores downloaded IDEs (`~/.gradle/caches/...`) by default. Plugin Verifier IDEs go to
  `~/.pluginVerifier/ides` by default (**UNVERIFIED** path for 2.19.0); add an `actions/cache` step
  keyed on the IDE list if verify downloads dominate. The template has no explicit verifier cache.
- `verifyPlugin` checks against `pluginVerification { ides { ... } }`. Default `failureLevel`:
  `COMPATIBILITY_PROBLEMS`, `INTERNAL_API_USAGES`, `OVERRIDE_ONLY_API_USAGES`. [EXT] The repo declares
  `pluginVerifier()` but no `ides` block; without it `verifyPlugin` likely fails with "no IDEs"
  (**UNVERIFIED**). Add:
```kotlin
intellijPlatform {
  pluginVerification {
    ides {
      create(IntelliJPlatformType.IntellijIdea, "2025.3")   // sinceBuild floor
      recommended()                                       // plus whatever the plugin considers current
    }
  }
}
```

  `ides` functions: `recommended()`, `select { }`, `create(...)`, `local(...)`, `current()`,
  `latest { }`. [EXT] For 2025.3+ use type `IntellijIdea` (`IU`); `IntellijIdeaCommunity` only exists
  before 253. [TYPES]
- Signing: `signPlugin` reads `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` (via
  `intellijPlatform.signing.certificateChain/privateKey/password`), outputs a `-signed` zip, and runs
  automatically before `publishPlugin` when a key and chain are set. [EXT] [TASKS]
- Publishing: `publishPlugin` reads `PUBLISH_TOKEN` (`intellijPlatform.publishing.token`), `channels`
  default `["default"]`. The template's `release.yml` triggers on GitHub release `prereleased`/`released`
  and maps all four secrets into the publish step's env. [EXT] [release.yml above]
- Tests on CI: tiers 0 and 2 inside `./gradlew build`. Tier 4 in its own job with `xvfb-run`.

## 6. Gotchas
**Build against 263, ship to 253.** The local build links against 263 bytecode. Anything new in
260+ (e.g. `AnActionEvent.coroutineScope`, Jewel 0.42 APIs like `MenuComboBox`, `SearchTextField`,
`JewelTheme.isIslands`, `Icon(iconDescriptor = ...)`) compiles locally and then breaks on 253 with
`NoSuchMethodError`. CI (which compiles against 2025.3) and `verifyPlugin` against 253 are the guard.
Before using a Jewel API, check it exists on the `253` branch of intellij-community.
`-Xskip-metadata-version-check` (only set with a local IDE) hides that 263's stdlib metadata is newer
than our Kotlin 2.2.20 understands. [local]

**`sinceBuild` vs a newer running IDE.** `sinceBuild = "253"`, `untilBuild = provider { null }`
means "253 and everything later", so 263 accepts it. [EXT] The default `untilBuild` is `MAJOR.*`,
which would block 263; keep the explicit `null`. Newer IDE is still a risk at runtime: internal or
experimental APIs can change underneath you, which is what Plugin Verifier against `recommended()`
IDEs is for. https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html

**Classloaders and bundled libs.** Each plugin has its own `PluginClassLoader`. Never bundle Kotlin
stdlib or kotlinx-coroutines; coroutines "must always use the bundled library from the target IDE".
2025.3 bundles stdlib 2.2.20 and coroutines `1.10.1-intellij-5`; 2026.2 bundles 2.4.0 and
`1.10.2-intellij-1`. https://plugins.jetbrains.com/docs/intellij/using-kotlin.html
Declaring `intellij.libraries.*` modules in plugin.xml and excluding the Maven artifacts from
`runtimeClasspath` (as this repo does) is how you share the IDE's copy. Also: "Do not repackage
libraries into the main plugin JAR file." [TASKS]

**kotlinx.serialization.** The SDK docs say nothing about it. We use the IDE's copy via the
`intellij.libraries.kotlinx.serialization.json` module [local: plugin.xml]. The serialization
*compiler plugin* comes from our Kotlin version (2.2.20); generated serializers call into the IDE's
runtime, so the runtime must not be older than what the compiler plugin targets. **UNVERIFIED**
exact runtime version in 2025.3; check `lib/` of a 2025.3 install. `:protocol` should keep
serialization as `compileOnly` / excluded at runtime, same as stdlib.

**Coroutines and threading.**

- Never `runBlocking` on EDT; `runBlockingCancellable` is "not recommended". Never `GlobalScope`; use
  the injected service scope so unload cancels it. https://plugins.jetbrains.com/docs/intellij/launching-coroutines.html
- `Dispatchers.EDT` (import `com.intellij.openapi.application.EDT`) for UI work that may touch the
  model; it wraps code in an implicit write-intent lock. `Dispatchers.UI` (stable since 2025.3)
  doesn't take the lock, and read/write actions inside it are forbidden; use it for pure UI.
  `Dispatchers.Main` is pure UI too; prefer `EDT`.
  https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html
  https://platform.jetbrains.com/t/changes-in-threading-model-in-the-intellij-platform-2025-3/2934
- Reads: suspending `readAction { }` / `smartReadAction(project) { }` (cancellable, restart on
  write). Blocking: `ReadAction.compute<T, E> { }`, `ReadAction.nonBlocking(...)`. On EDT no
  explicit read action is needed. [THREAD] https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html
- Writes: always in a write action. `writeAction { }` became background-thread and identical to
  `backgroundWriteAction` in 2026.2; on 253 it runs on EDT. `edtWriteAction { }` exists in 263 [local]
  (253 availability **UNVERIFIED**). We barely write to the model; if we do (e.g. a document), test
  on both 253 and 263. `readAndWriteAction` is deprecated in 263 ("renamed because it has unclear
  threading semantics") in favour of `readAndEdtWriteAction` [local].
  https://platform.jetbrains.com/t/changes-in-threading-model-in-the-intellij-platform-2026-2/4438
- 2026.1 dropped the write-intent lock from AWT input event handling; input listeners touching PSI
  must take a lock explicitly. https://platform.jetbrains.com/t/changes-in-threading-model-in-the-intellij-platform-2026-1/3800
- `blockingContext { }`: not mentioned in current docs pages fetched; treat as legacy and don't add
  new uses. **UNVERIFIED** status.
- Kotlin `runReadAction` / `runWriteAction` are obsolete since 2024.1. [THREAD]

**Slow operations.** `SlowOperations.assertSlowOperationsAreAllowed()` fires on EDT for VFS
traversal, index queries etc.; it's enabled in EAP, internal mode and dev instances, i.e. in our
`runIde` and in the user's 2026.3 EAP. Fixing reports is required. Keep `PasswordSafe`, VFS refresh,
network off EDT. [THREAD]

**Disposables.** Tie listeners, message bus connections, and per-content resources to a
`Disposable`: the service (if it implements `Disposable`), `content.setDisposer(...)`, or
`Disposer.newDisposable(parent)`. Don't use `project` itself as a parent disposable for plugin
objects (leaks across unload). Check `project.isDisposed` in callbacks that can fire after close.
[DYN] [https://plugins.jetbrains.com/docs/intellij/disposers.html]

**Extensions as `class`, not `object`.** plugin.xml-registered classes are instantiated by the
platform; don't use Kotlin `object` (except `FileType`). Companion objects in extensions: constants
and a logger only. https://plugins.jetbrains.com/docs/intellij/using-kotlin.html

**Plugin Verifier common failures.** Missing class/method on an older IDE (our 263-vs-253 trap),
`@ApiStatus.Internal` usage (fails by default), `@ApiStatus.OverrideOnly` called directly (fails by
default), `@ApiStatus.Experimental` usage (reported; our Jewel opt-ins will show up),
`@ApiStatus.ScheduledForRemoval`/`@Obsolete`, `@ApiStatus.NonExtendable` implemented. Reports
land in `plugin/build/reports/pluginVerifier`; the verdict comes from the report, not console
output. [TASKS] https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html

**Dynamic reload with Compose.** If reload fails, read the `DynamicPlugins` log category first;
fallback is restarting the sandbox (no re-download with `local(...)`). [DYN]
