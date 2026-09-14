import type { DashboardService } from "../../features/dashboard/DashboardService";
import type { DependencyGraphService } from "../../features/dependency-graph/DependencyGraphService";
import type { GormSqlPreviewService } from "../../features/gorm-sql-preview/GormSqlPreviewService";
import type { AnalysisToolService } from "../../services/analysis/AnalysisToolService";
import type { ArtifactService } from "../../services/artifacts/ArtifactService";
import type { DebugService } from "../../services/debugging/DebugService";
import type { ErrorService } from "../../services/errors/ErrorService";
import type { GradleService } from "../../services/gradle/GradleService";
import type { LogStreamingService } from "../../services/gradle/LogStreamingService";
import type { LanguageServerManager } from "../../services/languageServer/LanguageServerManager";
import type { GrailsTestService } from "../../services/testing/GrailsTestService";
import type { ConfigurationService } from "../../services/workspace/ConfigurationService";
import type { ProjectService } from "../../services/workspace/ProjectService";
import type { StatusBarService } from "../../services/workspace/StatusBarService";

export interface ServiceRegistry {
  ErrorService: ErrorService;
  StatusBarService: StatusBarService;
  ConfigurationService: ConfigurationService;
  ProjectService: ProjectService;
  GradleService: GradleService;
  LogStreamingService: LogStreamingService;
  LanguageServerManager: LanguageServerManager;
  ArtifactService: ArtifactService;
  DashboardService: DashboardService;
  DebugService: DebugService;
  DependencyGraphService: DependencyGraphService;
  GormSqlPreviewService: GormSqlPreviewService;
  GrailsTestService: GrailsTestService;
  AnalysisToolService: AnalysisToolService;
}

/** Service names that match the interface keys exactly */
export type ServiceName = keyof ServiceRegistry;
