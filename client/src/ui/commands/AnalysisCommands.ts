import type { ExtensionContext } from "vscode";
import type { ServiceContainer } from "../../core/container/ServiceContainer";
import type { AnalysisToolService } from "../../services/analysis/AnalysisToolService";
import type { ErrorService } from "../../services/errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import type {
  ProjectCapabilitiesRequest,
  ProjectCapabilitiesResponse,
  ProjectDiagnosticsRequest,
  ProjectDiagnosticsResponse,
  SymbolExplainRequest,
  SymbolExplainResponse,
  SymbolSearchRequest,
  SymbolSearchResponse,
} from "../../shared/protocol/analysis";
import { BaseCommandProvider } from "./BaseCommandProvider";

export class GetCapabilitiesUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: ProjectCapabilitiesRequest): Promise<ProjectCapabilitiesResponse | undefined> {
    try {
      return await this.analysisService.getProjectCapabilities(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to get project capabilities",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class SearchSymbolsUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: SymbolSearchRequest): Promise<SymbolSearchResponse | undefined> {
    try {
      return await this.analysisService.searchSymbols(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to search symbols",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class ExplainSymbolUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: SymbolExplainRequest): Promise<SymbolExplainResponse | undefined> {
    try {
      return await this.analysisService.explainSymbol(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to explain symbol",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class GetDiagnosticsUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: ProjectDiagnosticsRequest): Promise<ProjectDiagnosticsResponse | undefined> {
    try {
      return await this.analysisService.getProjectDiagnostics(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to get project diagnostics",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class AnalysisCommands extends BaseCommandProvider {
  constructor(context: ExtensionContext, container: ServiceContainer) {
    super(context, container);
  }

  registerCommands(): void {
    this.register("grails.analysis.getCapabilities", (req: ProjectCapabilitiesRequest) =>
      new GetCapabilitiesUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.analysis.searchSymbols", (req: SymbolSearchRequest) =>
      new SearchSymbolsUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.analysis.explainSymbol", (req: SymbolExplainRequest) =>
      new ExplainSymbolUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.analysis.getDiagnostics", (req: ProjectDiagnosticsRequest) =>
      new GetDiagnosticsUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );
  }
}

