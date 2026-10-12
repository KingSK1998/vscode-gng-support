import type { ExtensionContext } from "vscode";
import type { ServiceContainer } from "../../core/container/ServiceContainer";
import type { AnalysisToolService } from "../../services/analysis/AnalysisToolService";
import type { ErrorService } from "../../services/errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import type {
  ChangeImpactRequest,
  ChangeImpactResponse,
  ChangeReviewRequest,
  ChangeReviewResponse,
  CheckExecutionRequest,
  CheckExecutionResponse,
  GrailsRelationshipsRequest,
  GrailsRelationshipsResponse,
  ProjectCapabilitiesRequest,
  ProjectCapabilitiesResponse,
  ProjectDiagnosticsRequest,
  ProjectDiagnosticsResponse,
  RenameApplyRequest,
  RenameApplyResponse,
  RenamePreviewRequest,
  RenamePreviewResponse,
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

export class GetRelationshipsUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: GrailsRelationshipsRequest): Promise<GrailsRelationshipsResponse | undefined> {
    try {
      return await this.analysisService.getRelationships(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to get relationships",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class GetChangeImpactUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: ChangeImpactRequest): Promise<ChangeImpactResponse | undefined> {
    try {
      return await this.analysisService.getChangeImpact(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to get change impact",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class GetRenamePreviewUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: RenamePreviewRequest): Promise<RenamePreviewResponse | undefined> {
    try {
      return await this.analysisService.getRenamePreview(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to get rename preview",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class ApplyRenameUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: RenameApplyRequest): Promise<RenameApplyResponse | undefined> {
    try {
      return await this.analysisService.applyRename(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to apply rename refactoring",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class RunChecksUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: CheckExecutionRequest): Promise<CheckExecutionResponse | undefined> {
    try {
      return await this.analysisService.runChecks(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to run checks",
        error,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      return undefined;
    }
  }
}

export class ChangeReviewUseCase {
  constructor(
    private readonly analysisService: AnalysisToolService,
    private readonly errorService: ErrorService
  ) {}

  async execute(req: ChangeReviewRequest): Promise<ChangeReviewResponse | undefined> {
    try {
      return await this.analysisService.getChangeReview(req);
    } catch (error) {
      this.errorService.handleError(
        "Failed to present change review",
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

    this.register("grails.analysis.getRelationships", (req: GrailsRelationshipsRequest) =>
      new GetRelationshipsUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.analysis.getChangeImpact", (req: ChangeImpactRequest) =>
      new GetChangeImpactUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.refactor.preview", (req: RenamePreviewRequest) =>
      new GetRenamePreviewUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.refactor.apply", (req: RenameApplyRequest) =>
      new ApplyRenameUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.checks.run", (req: CheckExecutionRequest) =>
      new RunChecksUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );

    this.register("grails.analysis.changeReview", (req: ChangeReviewRequest) =>
      new ChangeReviewUseCase(
        this.container.analysisToolService,
        this.container.errorService
      ).execute(req)
    );
  }
}
