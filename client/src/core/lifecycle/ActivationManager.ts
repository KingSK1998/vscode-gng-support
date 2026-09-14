import type { Disposable, ExtensionContext } from "vscode";
import { commands, env, extensions, Uri, window, workspace } from "vscode";
import type { ProjectInfo } from "../../features/models/modelTypes";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import { registerLspHandlers } from "../../services/lsp/LspHandlerRegistry";
import { Commands } from "../../ui/commands";
import { DiagnosticDecorationProvider } from "../../ui/decorations/DiagnosticDecorationProvider";
import { GrailsGutterProvider } from "../../ui/decorations/GrailsGutterProvider";
import { IconThemeDetector } from "../../ui/icons/IconThemeDetector";
import { createProjectTreeProvider } from "../../ui/treeExplorer/TreeDataProvider";
import { ServiceContainer } from "../container/ServiceContainer";
import { EventBus } from "../events/EventBus";
import { EventType } from "../events/eventTypes";

// Performance timing constants
const IMMEDIATE_PHASE_TIMER = "⚡ Immediate Phase";
const BACKGROUND_PHASE_TIMER = "🔄 Background Phase";
const ACTIVATION_TIMEOUT_MS = 30000;

function withTimeout<T>(promise: Promise<T>, timeoutMs: number, operationName: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => {
        console.error(`[ActivationManager] ${operationName} timed out after ${timeoutMs}ms`);
        reject(new Error(`${operationName} timed out after ${timeoutMs}ms`));
      }, timeoutMs)
    ),
  ]);
}

/**
 * Manages the complete extension activation lifecycle.
 */
export class ActivationManager implements Disposable {
  private disposables: Disposable[] = [];
  private readonly listenerCounts = new Map<string, number>();
  private container: ServiceContainer;
  private commands: Commands;

  constructor(private readonly context: ExtensionContext) {
    this.container = ServiceContainer.intialize(context);
    this.commands = new Commands(context, this.container);
  }

  /**
   * Main activation sequence - called from extension.ts
   */
  activate(): void {
    console.time(IMMEDIATE_PHASE_TIMER);
    const startTime = performance.now();

    try {
      // =================================
      // PHASE 1: IMMEDIATE ACTIVATION
      // =================================
      console.log("📦 Initializing services...");
      console.log("⚡ Starting immediate activation phase...");

      // Initialize icon theme detection early
      IconThemeDetector.initialize();

      this.container.statusBarService.sync("⚡ Starting Grails extension...");

      // 1. Register commands first
      this.commands.registerAllCommands();

      // Register language model tools (if supported by environment)
      const lmToolsDisposable = this.container.analysisToolService.registerLanguageModelTools();
      this.disposables.push(lmToolsDisposable);

      // 2. Setup event listeners
      this.setupEventListeners();

      // Setup icon theme change listener
      this.setupIconThemeChangeListener();

      // 3. Quick project scan (just check build.gradle presence)
      const quickProjects = this.container.projectService.quickScan();

      // 4. Setup basic UI immediately
      this.setupUIComponents();

      // 5. Show ready status
      this.container.statusBarService.ready(
        `${quickProjects.length} project${quickProjects.length !== 1 ? "s" : ""} found`
      );

      const immediateTime = performance.now() - startTime;
      console.timeEnd(IMMEDIATE_PHASE_TIMER);
      console.log(`📊 Immediate phase: ${immediateTime.toFixed(1)}ms`);

      // ================================
      // PHASE 2: BACKGROUND (Non-blocking)
      // ================================

      const backgroundInitPromise = withTimeout(
        this.startBackgroundInitialization(),
        ACTIVATION_TIMEOUT_MS,
        "Background initialization"
      );

      backgroundInitPromise.catch(error => {
        console.error(`[ActivationManager] Background initialization failed: ${error}`);
        const message =
          error instanceof Error && error.message.includes("timed out")
            ? "Grails extension: Background initialization timed out. Some features may be limited."
            : "Grails extension: Startup delayed. Some features may be limited.";
        this.container.statusBarService.error(message);
        void window.showErrorMessage(message);
      });

      void backgroundInitPromise;

      console.log("✅ Extension UI ready - background services starting...");
    } catch (error) {
      this.container.errorService.handle(
        `Extension activation failed: ${String(error)}`,
        ErrorSource.Extension,
        ErrorSeverity.Critical
      );
      throw error;
    }
  }

  /**
   * Background initialization - runs after UI is already responsive.
   */
  private startBackgroundInitialization(): Promise<void> {
    // Don't await this - let it run in background
    void this.runBackgroundTasks().catch(error => {
      this.container.errorService.handleError(
        "Background initialization failed",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Error
      );
    });

    return Promise.resolve();
  }

  /**
   * Heavy operations that don't block initial UI
   */
  private async runBackgroundTasks(): Promise<void> {
    console.time(BACKGROUND_PHASE_TIMER);
    const backgroundStart = performance.now();

    try {
      this.container.statusBarService.sync("🔄 Starting language server...");

      // Run operations in parallel with individual timeouts
      const backgroundPromises = [
        this.initializeGradleWithTimeout(15000), // 15s timeout
        this.startLanguageServerWithTimeout(10000), // 10s timeout
        this.runFullDiscoveryWithTimeout(8000), // 8s timeout
      ];

      // Use allSettled so one failure doesn't block others
      const results = await Promise.allSettled(backgroundPromises);

      // Process results
      const [gradleResult, lspResult, discoveryResult] = results;

      this.handleGradleResult(gradleResult as PromiseSettledResult<boolean>);
      this.handleLSPResult(lspResult as PromiseSettledResult<boolean>);
      this.handleDiscoveryResult(discoveryResult as PromiseSettledResult<ProjectInfo[]>);

      // Final Health check and optional features
      await Promise.allSettled([this.performHealthCheck(), this.suggestIconThemesIfNeeded()]);

      const backgroundTime = performance.now() - backgroundStart;
      console.timeEnd(BACKGROUND_PHASE_TIMER);
      console.log(`📊 Parallel background phase: ${backgroundTime.toFixed(1)}ms`);

      // Final status
      const activeProject = this.container.projectService.getActiveProject();
      if (activeProject) {
        this.container.statusBarService.projectReady(activeProject.name);
      } else {
        this.container.statusBarService.ready("No projects");
      }
      // this.container.statusBarService.ready("🚀 Grails extension ready");
    } catch (error) {
      this.container.statusBarService.error("Extension ready with limited features");
      this.container.errorService.handleError(
        "Background task error",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      // Don't throw - extension should still work with basic features
    }
  }

  private async initializeGradleWithTimeout(timeoutMs: number): Promise<boolean> {
    this.container.statusBarService.sync("⚙️ Initializing Gradle...");

    return Promise.race([
      this.container.gradleService.fullSync(),
      new Promise<boolean>(resolve => setTimeout(() => resolve(false), timeoutMs)),
    ]);
  }

  private async startLanguageServerWithTimeout(timeoutMs: number): Promise<boolean> {
    this.container.statusBarService.sync("🚀 Starting Language Server...");

    return Promise.race([
      this.container.languageServerManager.start().then(client => !!client),
      new Promise<boolean>(resolve => setTimeout(() => resolve(false), timeoutMs)),
    ]);
  }

  private async runFullDiscoveryWithTimeout(timeoutMs: number): Promise<ProjectInfo[]> {
    this.container.statusBarService.sync("🔍 Deep project analysis...");

    return Promise.race([
      this.container.projectService.discoverProjects(),
      new Promise<ProjectInfo[]>(resolve => setTimeout(() => resolve([]), timeoutMs)),
    ]);
  }

  private handleGradleResult(result: PromiseSettledResult<boolean>): void {
    if (result.status === "fulfilled" && result.value) {
      console.log("✅ Gradle initialized successfully");
    } else {
      console.log("⚠️ Gradle initialization failed or timed out - some features limited");
      this.container.statusBarService.warning("Gradle unavailable");
    }
  }

  private handleLSPResult(result: PromiseSettledResult<boolean>): void {
    if (result.status === "fulfilled" && result.value) {
      console.log("✅ Language Server started successfully");
    } else {
      console.log("⚠️ Language Server failed to start - language features unavailable");
      this.container.statusBarService.warning("Language Server unavailable");
    }
  }

  private handleDiscoveryResult(result: PromiseSettledResult<ProjectInfo[]>): void {
    if (result.status === "fulfilled") {
      const projects = result.value;
      console.log(`✅ Deep project analysis complete: ${projects.length} projects`);
    } else {
      console.log("⚠️ Project analysis failed or timed out - using quick scan data");
    }
  }

  /**
   * Suggest popular icon themes installation
   */
  private async suggestIconThemesIfNeeded(): Promise<void> {
    try {
      const shouldSuggest = workspace
        .getConfiguration("grails")
        .get<boolean>("icons.suggestThemes", true);

      if (!shouldSuggest) {
        console.log("🎨 Icon theme suggestions disabled by user configuration");
        return;
      }

      // Use IconThemeDetector's built-in suggestion logic
      await IconThemeDetector.suggestIconTheme();

      console.log("✅ Icon theme suggestion completed");
    } catch (error) {
      console.warn("⚠️ Icon theme suggestion failed:", error);
    }
  }

  /**
   * Setup workspace and configuration event listeners.
   */
  private setupEventListeners(): void {
    const lspHandlers = registerLspHandlers();
    this.disposables.push(...lspHandlers);
    this.listenerCounts.set("lsp", lspHandlers.length);

    // Configuration changes
    this.disposables.push(
      workspace.onDidChangeConfiguration(async e => {
        if (e.affectsConfiguration("grails")) {
          this.container.statusBarService.info("Configuration changed");

          // Restart LSP if needed
          if (
            e.affectsConfiguration("grails.completion") ||
            e.affectsConfiguration("grails.server")
          ) {
            await this.container.languageServerManager.restart();
          }

          // Re-sync Gradle if JVM args changed
          if (e.affectsConfiguration("grails.server.jvmArgs")) {
            await this.container.gradleService.waitForSync();
          }
        }

        // Handle icon theme changes
        if (e.affectsConfiguration("workbench.iconTheme")) {
          console.log("🎨 Icon theme configuration changed - refreshing tree view");

          // Reset icon theme cache to pick up new theme
          IconThemeDetector.resetCache();

          // Refersh tree with new icons
          this.refreshTreeView();
        }
      })
    );

    // Listen to extension install/uninstall (for icon themes)
    this.disposables.push(
      extensions.onDidChange(() => {
        console.log("🔌 Extensions changed - resetting icon theme cache");

        // Reset cache in case icon theme extensions were installed/removed
        IconThemeDetector.resetCache();

        // Optionally refresh tree view if theme support changed
        // this.refreshTreeView();
      })
    );

    // Workspace changes
    this.disposables.push(
      workspace.onDidChangeWorkspaceFolders(async () => {
        this.container.statusBarService.info("Workspace changed, discovering projects...");
        await this.container.projectService.discoverProjects();
      })
    );

    // Build file changes
    this.disposables.push(
      workspace
        .createFileSystemWatcher(
          "**/{build.gradle,build.gradle.kts,settings.gradle,settings.gradle.kts,gradle.properties,gradle/libs.versions.toml,*.versions.toml}"
        )
        .onDidChange(async () => {
          this.container.statusBarService.info("Build file changed, refreshing projects...");
          await this.container.projectService.discoverProjects();
        })
    );

    // Internal event subscriptions
    const eventBus = EventBus.getInstance();
    this.disposables.push(
      eventBus.subscribe(EventType.PROJECTS_DISCOVERED, event => {
        console.log(`📂 Projects discovered: ${event.projects.length} projects`);

        this.container.statusBarService.info(`Discovered ${event.projects.length} projects`);

        // Update UI if this is the full discovery
        if (event.source === "ProjectService") {
          this.recreateUIIfNeeded();
        }
      })
    );

    this.disposables.push(
      eventBus.subscribe(EventType.PROJECT_CHANGED, event => {
        console.log(`🔄 Project changed: ${event.project.name}`);
        this.container.statusBarService.info(`Project ${event.project.name} changed`);

        // Potentially recreate UI if project types changed
        this.recreateUIIfNeeded();
      })
    );
  }

  /**
   * Setup icon theme change detection and handling
   */
  private setupIconThemeChangeListener(): void {
    try {
      // Listen for dynamic icon theme changes
      const themeChangeListener = IconThemeDetector.onThemeChanged(() => {
        const currentTheme = IconThemeDetector.getCurrentThemeType();
        const themeName = IconThemeDetector.getActiveThemeDisplayName();

        console.log(`🎨 Icon theme changed to: ${themeName} (${currentTheme})`);

        // Immediately refresh tree views with new icons
        this.refreshTreeView();

        // Show subtle notification to user
        this.container.statusBarService.info(`Icons updated for ${themeName}`);
      });

      // Store for cleanup
      this.disposables.push(themeChangeListener);
      console.log("🎨 Icon theme change listener registered");
    } catch (error) {
      console.warn("⚠️ Icon theme change listener failed:", error);
    }
  }

  /**
   * Refresh tree view for icon theme changes
   */
  private refreshTreeView(): void {
    console.log("🔄 Refreshing tree view for icon theme change");

    try {
      // Method 1: Trigger via EventBus (preferred for decoupling)
      const eventBus = EventBus.getInstance();
      eventBus?.publish({
        type: EventType.TREE_REFRESH,
        timestamp: Date.now(),
        source: "IconThemeChange",
      });

      // Method 2: Trigger via command (backup/additional)
      commands.executeCommand("grails.refreshTree").then(
        () => console.log("✅ Tree refresh command executed successfully"),
        error => console.warn("⚠️ Tree refresh command failed, using EventBus fallback:", error)
      );
    } catch (error) {
      this.container.errorService.handleError(
        "Failed to refresh tree view",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
    }
  }

  /**
   * Setup UI components
   */
  private setupUIComponents(): void {
    try {
      // Create and register tree view
      const treeProvider = createProjectTreeProvider(this.context);
      if (!treeProvider) {
        console.log("❌ No supported projects found in the workspace, skipping UI setup.");

        window
          .showInformationMessage(
            "No Grails or Groovy projects found in the workspace.",
            "Learn More"
          )
          .then(selection => {
            if (selection === "Learn More") {
              env.openExternal(Uri.parse("https://grails.org"));
            }
          });

        return;
      }

      const treeView = window.createTreeView("grailsExplorer", {
        treeDataProvider: treeProvider,
        showCollapseAll: true,
        canSelectMany: false,
      });

      this.context.subscriptions.push(treeView, treeProvider);

      // Store reference for manual refresh commands
      // (this.container as any).treeProvider = treeProvider;

      // Register diagnostic decorations
      const decorationProvider = new DiagnosticDecorationProvider();
      window.registerFileDecorationProvider(decorationProvider);
      this.context.subscriptions.push(decorationProvider);

      // Register Gutter Icons
      const gutterProvider = new GrailsGutterProvider(this.context);
      this.context.subscriptions.push(gutterProvider);

      console.log("✅ Tree view registered successfully");

      // Update status bar to show tree view is ready
      this.container.statusBarService.ready("Project Explorer ready");
    } catch (error) {
      this.container.errorService.handleError(
        "UI setup failed",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Error
      );
    }
  }

  /**
   * Recreate UI if project composition changed (optional)
   */
  private recreateUIIfNeeded(): void {
    // Advanced: If project types changed, recreate tree with different provider
    // For now, existing tree will auto-refresh via its own event listeners
    console.log("🔄 UI recreation check - tree will auto-refresh via events");
  }

  /**
   * Perform final health check and report status.
   */
  private performHealthCheck(): void {
    const health = this.container.healthCheck();

    if (health.healthy) {
      this.container.statusBarService.ready("Grails extension ready");

      // Show success message with project count
      const projectCount = this.container.projectService.getProjects().length;
      const message =
        projectCount > 0
          ? `🎉 Grails Framework Support is ready! ${projectCount} project${projectCount === 1 ? "" : "s"} detected.`
          : "🎉 Grails Framework Support is ready!";

      window.showInformationMessage(message);
    } else {
      this.container.statusBarService.warning("Extension ready with issues");

      window
        .showWarningMessage(
          `Grails extension activated with issues: ${health.issues.join(", ")}`,
          "Show Details"
        )
        .then(selection => {
          if (selection === "Show Details") {
            // Could open output channel or show detailed info
            console.log("Health issues:", health.issues);
          }
        });
    }
  }

  /**
   * Cleanup on extension deactivation.
   */
  dispose(): void {
    console.log(`[ActivationManager] Disposing ${this.disposables.length} listeners...`);

    this.commands.dispose();

    let disposedCount = 0;
    let errorCount = 0;

    for (const disposable of this.disposables) {
      try {
        disposable.dispose();
        disposedCount++;
      } catch (error) {
        errorCount++;
        console.warn("[ActivationManager] Error disposing listener:", error);
      }
    }

    this.disposables = [];
    this.listenerCounts.clear();

    console.log(
      `[ActivationManager] Disposed ${disposedCount} listeners${errorCount > 0 ? `, ${errorCount} errors` : ""}`
    );

    this.container.dispose();
  }
}
