import * as vscode from "vscode";
import type { ProjectInfo } from "../../features/models/modelTypes";
import type { DebugService } from "../../services/debugging/DebugService";
import type { ErrorService } from "../../services/errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import type { GradleService } from "../../services/gradle/GradleService";
import type { ProjectService } from "../../services/workspace/ProjectService";
import { BaseCommandProvider } from "./BaseCommandProvider";

/* ================= APPLICATION LAYER: USECASES ================= */

function resolveTargetProject(
  projectService: ProjectService,
  target?: ProjectInfo | vscode.Uri | string
): ProjectInfo | undefined {
  if (target && typeof target === "object" && "rootPath" in target && "type" in target) {
    return target;
  }
  const uri = target ?? vscode.window.activeTextEditor?.document.uri;
  return projectService.resolveTargetProject(uri);
}

export class RunGrailsAppUseCase {
  constructor(
    private readonly gradleService: GradleService,
    private readonly projectService: ProjectService,
    private readonly errorService: ErrorService
  ) {}

  async execute(target?: ProjectInfo | vscode.Uri | string): Promise<boolean> {
    try {
      const project = resolveTargetProject(this.projectService, target);
      if (!project) {
        vscode.window.showWarningMessage("No Grails projects found in workspace");
        return false;
      }
      return await this.gradleService.runGrailsApp(project);
    } catch (error) {
      this.errorService.handleError(
        "Failed to run Grails application",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return false;
    }
  }
}

export class DebugGrailsAppUseCase {
  constructor(
    private readonly debugService: DebugService,
    private readonly projectService: ProjectService,
    private readonly errorService: ErrorService
  ) {}

  async execute(target?: ProjectInfo | vscode.Uri | string): Promise<boolean> {
    try {
      const project = resolveTargetProject(this.projectService, target);
      if (!project) {
        vscode.window.showWarningMessage("No Grails projects found in workspace");
        return false;
      }
      return await this.debugService.debugGrailsApp(project);
    } catch (error) {
      this.errorService.handleError(
        "Failed to debug Grails application",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return false;
    }
  }
}

export class TestGrailsAppUseCase {
  constructor(
    private readonly gradleService: GradleService,
    private readonly projectService: ProjectService,
    private readonly errorService: ErrorService
  ) {}

  async execute(target?: ProjectInfo | vscode.Uri | string): Promise<boolean> {
    try {
      const project = resolveTargetProject(this.projectService, target);
      if (!project) {
        vscode.window.showWarningMessage("No Grails projects found in workspace");
        return false;
      }
      return await this.gradleService.testGrailsApp(project);
    } catch (error) {
      this.errorService.handleError(
        "Failed to test Grails application",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return false;
    }
  }
}

export class BuildGrailsProjectUseCase {
  constructor(
    private readonly gradleService: GradleService,
    private readonly projectService: ProjectService,
    private readonly errorService: ErrorService
  ) {}

  async execute(
    target?: ProjectInfo | vscode.Uri | string,
    taskName = "build"
  ): Promise<boolean> {
    try {
      const project = resolveTargetProject(this.projectService, target);
      if (!project) {
        vscode.window.showWarningMessage("No Grails projects found in workspace");
        return false;
      }
      return await this.gradleService.buildProject(project, taskName);
    } catch (error) {
      this.errorService.handleError(
        `Failed to execute task ${taskName}`,
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return false;
    }
  }
}

export class CleanGrailsProjectUseCase {
  constructor(
    private readonly gradleService: GradleService,
    private readonly projectService: ProjectService,
    private readonly errorService: ErrorService
  ) {}

  async execute(target?: ProjectInfo | vscode.Uri | string): Promise<boolean> {
    try {
      const project = resolveTargetProject(this.projectService, target);
      if (!project) {
        vscode.window.showWarningMessage("No Grails projects found in workspace");
        return false;
      }
      return await this.gradleService.cleanProject(project);
    } catch (error) {
      this.errorService.handleError(
        "Failed to clean Grails project",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return false;
    }
  }
}

/* ================= COMMAND PROVIDER ============================ */

export class GrailsTaskCommands extends BaseCommandProvider {
  registerCommands(): void {
    this.register("grails.runApp", async (target?: ProjectInfo | vscode.Uri | string) => {
      await new RunGrailsAppUseCase(
        this.container.gradleService,
        this.container.projectService,
        this.container.errorService
      ).execute(target);
    });

    this.register("grails.debugApp", async (target?: ProjectInfo | vscode.Uri | string) => {
      await new DebugGrailsAppUseCase(
        this.container.debugService,
        this.container.projectService,
        this.container.errorService
      ).execute(target);
    });

    this.register("grails.testApp", async (target?: ProjectInfo | vscode.Uri | string) => {
      await new TestGrailsAppUseCase(
        this.container.gradleService,
        this.container.projectService,
        this.container.errorService
      ).execute(target);
    });

    this.register("grails.buildProject", async (target?: ProjectInfo | vscode.Uri | string) => {
      await new BuildGrailsProjectUseCase(
        this.container.gradleService,
        this.container.projectService,
        this.container.errorService
      ).execute(target, "build");
    });

    this.register("grails.cleanProject", async (target?: ProjectInfo | vscode.Uri | string) => {
      await new CleanGrailsProjectUseCase(
        this.container.gradleService,
        this.container.projectService,
        this.container.errorService
      ).execute(target);
    });

    this.register("grails.compileProject", async (target?: ProjectInfo | vscode.Uri | string) => {
      await new BuildGrailsProjectUseCase(
        this.container.gradleService,
        this.container.projectService,
        this.container.errorService
      ).execute(target, "compileGroovy");
    });

    this.register("grails.showDependencyGraph", (target?: ProjectInfo | vscode.Uri | string) => {
      const project = resolveTargetProject(this.container.projectService, target);
      if (!project) {
        vscode.window.showWarningMessage("No Grails projects found in workspace");
        return;
      }
      this.container.dependencyGraphService.openGraph(project);
    });

    this.register("grails.showGormSqlPreview", (uri?: vscode.Uri) => {
      const targetUri = uri ?? vscode.window.activeTextEditor?.document.uri;
      if (!targetUri) return;
      this.container.gormSqlPreviewService.openPreview(targetUri);
    });

    this.register("grails.generateAction", async (uri: string, actionName: string) => {
      try {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.uri.toString() !== uri) return;
        const text = editor.document.getText();
        const lastBraceIndex = text.lastIndexOf("}");
        if (lastBraceIndex !== -1) {
          const edit = new vscode.WorkspaceEdit();
          const pos = editor.document.positionAt(lastBraceIndex);
          edit.insert(
            editor.document.uri,
            pos,
            `\n    def ${actionName}() {\n        render "Hello from ${actionName}"\n    }\n`
          );
          await vscode.workspace.applyEdit(edit);
          vscode.window.showInformationMessage(`Action ${actionName} generated`);
        }
      } catch (error) {
        this.container.errorService.handleError(
          "Action generation error",
          error,
          ErrorSource.Commands,
          ErrorSeverity.Error
        );
      }
    });
  }
}
