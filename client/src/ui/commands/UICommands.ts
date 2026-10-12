import * as vscode from "vscode";
import { EventBus } from "../../core/events/EventBus";
import { EventType } from "../../core/events/eventTypes";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import { BaseCommandProvider } from "./BaseCommandProvider";

export class UICommands extends BaseCommandProvider {
  registerCommands(): void {
    this.register("grails.refreshTree", async () => {
      try {
        const discoveredProjects = await this.container.projectService.discoverProjects();
        EventBus.getInstance().publish({
          type: EventType.PROJECTS_DISCOVERED,
          projects: discoveredProjects,
          timestamp: Date.now(),
          source: "RefreshCommand",
        });

        vscode.window.showInformationMessage("Project tree refreshed");
      } catch (error) {
        this.container.errorService.handleError(
          "Failed to refresh project tree",
          error,
          ErrorSource.Commands,
          ErrorSeverity.Error
        );
      }
    });

    this.register("grails.showDashboard", () => {
      this.container.dashboardService.openDashboard();
    });

    this.register("grails.showExtensionInfo", () => {
      const health = this.container.healthCheck();
      const projects = this.container.projectService.getProjects();

      const info = [
        "## Grails Extension Status",
        `**Health:** ${health.healthy ? "✅ Healthy" : "❌ Issues"}`,
        health.issues.length > 0 ? `**Issues:** ${health.issues.join(", ")}` : "",
        `**Projects:** ${projects.length} detected`,
        `**Gradle:** ${this.container.gradleService.isReady ? "✅ Ready" : "❌ Not Ready"}`,
        `**LSP:** ${this.container.languageServerManager.isRunning ? "✅ Running" : "❌ Stopped"}`,
      ]
        .filter(Boolean)
        .join("\n");

      vscode.window.showInformationMessage(info);
    });

    this.register("grails.statusBarClicked", () => {
      vscode.commands.executeCommand("grails.showExtensionInfo");
    });
  }
}
