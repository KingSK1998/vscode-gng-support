import type { ExtensionContext } from "vscode";
import { DashboardService } from "../../features/dashboard/DashboardService";
import { DependencyGraphService } from "../../features/dependency-graph/DependencyGraphService";
import { GormSqlPreviewService } from "../../features/gorm-sql-preview/GormSqlPreviewService";
import { AnalysisToolService } from "../../services/analysis/AnalysisToolService";
import { ArtifactService } from "../../services/artifacts/ArtifactService";
import { DebugService } from "../../services/debugging/DebugService";
import { ErrorService } from "../../services/errors/ErrorService";
import { GradleService } from "../../services/gradle/GradleService";
import { LogStreamingService } from "../../services/gradle/LogStreamingService";
import { LanguageServerManager } from "../../services/languageServer/LanguageServerManager";
import { RefactorRenameService } from "../../services/refactor/RefactorRenameService";
import { GrailsTestService } from "../../services/testing/GrailsTestService";
import { ConfigurationService } from "../../services/workspace/ConfigurationService";
import { ProjectService } from "../../services/workspace/ProjectService";
import { StatusBarService } from "../../services/workspace/StatusBarService";
import { EventBus } from "../events/EventBus";
import type { ServiceName, ServiceRegistry } from "./ServiceRegistry";

type LazyInitializer<T> = () => T;

export class CircularDependencyError extends Error {
  constructor(
    public readonly cycle: ServiceName[],
    message: string
  ) {
    super(message);
    this.name = "CircularDependencyError";
  }
}

export class ServiceContainer {
  private static _instance: ServiceContainer;
  private static _initializationPromise: Promise<ServiceContainer> | undefined;
  private static _isInitialized = false;
  private readonly _services: Partial<ServiceRegistry> = {};
  private readonly _initializedServices = new Set<ServiceName>();
  private readonly _lazyInitializers = new Map<ServiceName, LazyInitializer<unknown>>();
  private readonly _initializingServices = new Set<ServiceName>();

  private constructor(private readonly context: ExtensionContext) {
    this.initializeServices();
  }

  public static async initialize(context: ExtensionContext): Promise<ServiceContainer> {
    if (ServiceContainer._initializationPromise) {
      return ServiceContainer._initializationPromise;
    }

    ServiceContainer._initializationPromise = (() => {
      if (!ServiceContainer._instance) {
        ServiceContainer._instance = new ServiceContainer(context);
      }
      ServiceContainer._isInitialized = true;
      return Promise.resolve(ServiceContainer._instance);
    })();

    return ServiceContainer._initializationPromise;
  }

  public static intialize(context: ExtensionContext): ServiceContainer {
    if (!ServiceContainer._instance) {
      ServiceContainer._instance = new ServiceContainer(context);
      ServiceContainer._isInitialized = true;
    }
    return ServiceContainer._instance;
  }

  public static getInstance(): ServiceContainer {
    if (!ServiceContainer._instance) {
      throw new Error(
        "ServiceContainer must be initialized first. Call ServiceContainer.initialize() first."
      );
    }
    return ServiceContainer._instance;
  }

  public static async getInstanceAsync(): Promise<ServiceContainer> {
    if (ServiceContainer._isInitialized && ServiceContainer._instance) {
      return ServiceContainer._instance;
    }

    if (!ServiceContainer._initializationPromise) {
      throw new Error("ServiceContainer.initialize() must be called first");
    }

    return ServiceContainer._initializationPromise;
  }

  public static get isInitialized(): boolean {
    return ServiceContainer._isInitialized;
  }

  public static get isInitializing(): boolean {
    return !!ServiceContainer._initializationPromise && !ServiceContainer._isInitialized;
  }

  public static reset(): void {
    if (ServiceContainer._instance) {
      ServiceContainer._instance.dispose();
    }
    ServiceContainer._instance = undefined as unknown as ServiceContainer;
    ServiceContainer._initializationPromise = undefined;
    ServiceContainer._isInitialized = false;
  }

  /* ================================ SERVICE ACCESS =============================== */

  /** Get ErrorService - always available immediately */
  get errorService(): ErrorService {
    return this._services.ErrorService!;
  }

  /** Get StatusBarService - always available immediately */
  get statusBarService(): StatusBarService {
    return this._services.StatusBarService!;
  }

  /** Get ConfigurationService - always available immediately */
  get configurationService(): ConfigurationService {
    return this._services.ConfigurationService!;
  }

  /** Get GradleService - lazy initialized */
  get gradleService(): GradleService {
    return this.getLazyService("GradleService");
  }

  /** Get LogStreamingService - lazy initialized */
  get logStreamingService(): LogStreamingService {
    return this.getLazyService("LogStreamingService");
  }

  /** Get ProjectService - lazy initialized */
  get projectService(): ProjectService {
    return this.getLazyService("ProjectService");
  }

  /** Get LanguageServerManager - lazy initialized */
  get languageServerManager(): LanguageServerManager {
    return this.getLazyService("LanguageServerManager");
  }

  /** Get ArtifactService - lazy initialized */
  get artifactService(): ArtifactService {
    return this.getLazyService("ArtifactService");
  }

  /** Get DebugService - lazy initialized */
  get debugService(): DebugService {
    return this.getLazyService("DebugService");
  }

  /** Get GrailsTestService - lazy initialized */
  get grailsTestService(): GrailsTestService {
    return this.getLazyService("GrailsTestService");
  }

  /** Get AnalysisToolService - lazy initialized */
  get analysisToolService(): AnalysisToolService {
    return this.getLazyService("AnalysisToolService");
  }

  /** Get DashboardService - lazy initialized */
  get dashboardService(): DashboardService {
    return this.getLazyService("DashboardService");
  }

  /** Get DependencyGraphService - lazy initialized */
  get dependencyGraphService(): DependencyGraphService {
    return this.getLazyService("DependencyGraphService");
  }

  /** Get GormSqlPreviewService - lazy initialized */
  get gormSqlPreviewService(): GormSqlPreviewService {
    return this.getLazyService("GormSqlPreviewService");
  }

  /** Get RefactorRenameService - lazy initialized */
  get refactorRenameService(): RefactorRenameService {
    return this.getLazyService("RefactorRenameService");
  }

  /* ================= GENERIC ACCESS (for special cases) ============ */

  get<K extends ServiceName>(name: K): ServiceRegistry[K] {
    if (this._initializedServices.has(name)) {
      return this._services[name] as ServiceRegistry[K];
    }
    const initializer = this._lazyInitializers.get(name);
    if (!initializer) {
      throw new Error(`Service "${name}" not found or has no lazy initializer`);
    }
    return this.getLazyService(name);
  }

  /* ================= LAZY INITIALIZATION HELPER =================== */

  private getLazyService<K extends ServiceName>(name: K): ServiceRegistry[K] {
    if (this._initializedServices.has(name)) {
      return this._services[name] as ServiceRegistry[K];
    }

    const initializer = this._lazyInitializers.get(name);
    if (!initializer) {
      throw new Error(`Service "${name}" has no lazy initializer`);
    }

    if (this._initializingServices.has(name)) {
      const cycle = Array.from(this._initializingServices);
      cycle.push(name);
      throw new CircularDependencyError(
        cycle,
        `Circular dependency detected: ${cycle.join(" -> ")}`
      );
    }

    this._initializingServices.add(name);

    const startTime = performance.now();
    let service: ServiceRegistry[K];
    try {
      service = initializer() as ServiceRegistry[K];
    } finally {
      this._initializingServices.delete(name);
    }

    this._services[name] = service;
    this._initializedServices.add(name);

    const elapsed = performance.now() - startTime;
    if (elapsed > 10) {
      console.log(`[ServiceContainer] Lazy initialized ${name} in ${elapsed.toFixed(1)}ms`);
    }

    return service;
  }

  /* ================= INITIALIZATION ================================= */

  private initializeServices(): void {
    this._services.ErrorService = new ErrorService();
    this._services.StatusBarService = new StatusBarService(this.context);
    this._services.ConfigurationService = new ConfigurationService();
    this._initializedServices.add("ErrorService");
    this._initializedServices.add("StatusBarService");
    this._initializedServices.add("ConfigurationService");

    this._lazyInitializers.set(
      "GradleService",
      () =>
        new GradleService(
          this._services.StatusBarService!,
          this._services.ErrorService!,
          this._services.ConfigurationService
        )
    );

    this._lazyInitializers.set(
      "LogStreamingService",
      () =>
        new LogStreamingService(
          this.gradleService,
          this._services.StatusBarService!,
          this._services.ErrorService!
        )
    );

    this._lazyInitializers.set(
      "ProjectService",
      () =>
        new ProjectService(
          this._services.StatusBarService!,
          this._services.ErrorService!,
          this._services.ConfigurationService!,
          EventBus.getInstance()
        )
    );

    this._lazyInitializers.set(
      "LanguageServerManager",
      () =>
        new LanguageServerManager(
          this.context,
          this._services.StatusBarService!,
          this._services.ErrorService!,
          this._services.ConfigurationService!
        )
    );

    this._lazyInitializers.set(
      "ArtifactService",
      () => new ArtifactService(this._services.ErrorService!)
    );

    this._lazyInitializers.set(
      "DebugService",
      () =>
        new DebugService(
          this.gradleService,
          this._services.ErrorService!,
          this._services.ConfigurationService
        )
    );

    this._lazyInitializers.set(
      "GrailsTestService",
      () =>
        new GrailsTestService(
          this.context,
          this._services.ErrorService!,
          this.languageServerManager,
          this.projectService,
          this.gradleService
        )
    );

    this._lazyInitializers.set(
      "AnalysisToolService",
      () =>
        new AnalysisToolService(
          this.languageServerManager,
          this._services.ErrorService!,
          this._services.ConfigurationService!
        )
    );

    this._lazyInitializers.set(
      "RefactorRenameService",
      () =>
        new RefactorRenameService(
          this.languageServerManager,
          this._services.ErrorService!
        )
    );

    this._lazyInitializers.set(
      "DashboardService",
      () =>
        new DashboardService(
          this.context,
          this._services.ErrorService!,
          this.projectService,
          this.gradleService,
          this.languageServerManager
        )
    );

    this._lazyInitializers.set(
      "DependencyGraphService",
      () =>
        new DependencyGraphService(
          this.context,
          this._services.ErrorService!,
          this.languageServerManager,
          this.projectService
        )
    );

    this._lazyInitializers.set(
      "GormSqlPreviewService",
      () =>
        new GormSqlPreviewService(
          this.context,
          this._services.ErrorService!,
          this.languageServerManager
        )
    );
  }

  /* ================= HEALTH CHECK ==================================== */

  healthCheck(): { healthy: boolean; issues: string[] } {
    const issues: string[] = [];

    if (!this._services.ErrorService) {
      issues.push("ErrorService not initialized");
    }
    if (!this._services.StatusBarService) {
      issues.push("StatusBarService not initialized");
    }
    if (!this._services.ConfigurationService) {
      issues.push("ConfigurationService not initialized");
    }

    if (this._initializedServices.has("GradleService") && !this._services.GradleService) {
      issues.push("GradleService marked as initialized but not found");
    }
    if (this._initializedServices.has("ProjectService") && !this._services.ProjectService) {
      issues.push("ProjectService marked as initialized but not found");
    }
    if (
      this._initializedServices.has("LanguageServerManager") &&
      !this._services.LanguageServerManager
    ) {
      issues.push("LanguageServerManager marked as initialized but not found");
    }

    try {
      if (this._initializedServices.has("GradleService")) {
        const gradleReady = this._services.GradleService?.isReady ?? false;
        if (!gradleReady) {
          issues.push("Gradle API not ready");
        }
      }
      if (this._initializedServices.has("LanguageServerManager")) {
        const lspReady = this._services.LanguageServerManager?.isRunning ?? false;
        if (!lspReady) {
          issues.push("Language Server not running");
        }
      }
    } catch (error) {
      issues.push(`Health check failed: ${String(error)}`);
    }

    return {
      healthy: issues.length === 0,
      issues,
    };
  }

  /* ================= CLEANUP ======================================== */

  dispose(): void {
    for (const name of this._initializedServices) {
      const service = this._services[name];
      if (service && "dispose" in service && typeof service.dispose === "function") {
        try {
          service.dispose();
        } catch (error) {
          console.error(`[ServiceContainer] Error disposing ${String(name)}:`, error);
        }
      }
    }

    (Object.keys(this._services) as ServiceName[]).forEach(key => {
      delete this._services[key];
    });
    this._initializedServices.clear();
    this._lazyInitializers.clear();
  }
}
