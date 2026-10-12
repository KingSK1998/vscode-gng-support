import * as vscode from "vscode";
import { BaseCommandProvider } from "./BaseCommandProvider";

export class LegacyCommands extends BaseCommandProvider {
  registerCommands(): void {
    this.register("grails.run", () => this.executeGrailsCommand("bootRun"));
    this.register("grails.test", () => this.executeGrailsCommand("test"));
    this.register("grails.clean", () => this.executeGrailsCommand("clean"));
    this.register("grails.compile", () => this.executeGrailsCommand("compileGroovy"));
  }

  private async executeGrailsCommand(taskName: string): Promise<void> {
    const project = this.container.projectService.resolveTargetProject();
    if (!project) {
      vscode.window.showWarningMessage("No Grails projects found");
      return;
    }
    await this.container.gradleService.runTask(project, taskName);
  }
}
