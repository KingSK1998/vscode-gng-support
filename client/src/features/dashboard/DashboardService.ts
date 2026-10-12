import * as path from "path";
import * as vscode from "vscode";
import type { ErrorService } from "../../services/errors/ErrorService";
import type { GradleService } from "../../services/gradle/GradleService";
import type { LanguageServerManager } from "../../services/languageServer/LanguageServerManager";
import { createWebviewStateManager, getNonce } from "../../services/webview/WebviewStateManager";
import type { ProjectService } from "../../services/workspace/ProjectService";
import type { ProjectInfo } from "../models/modelTypes";

interface DashboardState {
  dashboardType?: "user" | "developer";
}

export class DashboardService implements vscode.Disposable {
  private currentPanel: vscode.WebviewPanel | null = null;
  private disposed = false;
  private stateManager: ReturnType<typeof createWebviewStateManager<DashboardState>>;

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly errorService: ErrorService,
    private readonly projectService: ProjectService,
    private readonly gradleService: GradleService,
    private readonly languageServerManager: LanguageServerManager
  ) {
    this.stateManager = createWebviewStateManager<DashboardState>(context, "dashboard");
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    if (this.currentPanel) {
      this.currentPanel.dispose();
      this.currentPanel = null;
    }
    void this.stateManager.clearState();
  }

  public openDashboard(_type: "user" | "developer" = "user"): void {
    const column = vscode.window.activeTextEditor
      ? vscode.window.activeTextEditor.viewColumn
      : undefined;

    if (this.currentPanel) {
      this.currentPanel.reveal(column);
      return;
    }

    this.currentPanel = vscode.window.createWebviewPanel(
      "grailsDashboard",
      "Grails Project Dashboard",
      column ?? vscode.ViewColumn.One,
      this.stateManager.getWebviewOptions([
        vscode.Uri.file(path.join(this.context.extensionPath, "resources")),
      ])
    );

    const projects = this.projectService.getProjects();
    this.currentPanel.webview.html = this.getHtmlContent(projects);

    this.currentPanel.onDidDispose(
      () => {
        this.currentPanel = null;
      },
      null,
      this.context.subscriptions
    );

    this.currentPanel.webview.onDidReceiveMessage((message: { command: string; task?: string }) => {
      if (message.command === "discoverProjects") {
        void vscode.commands.executeCommand("grails.refreshTree");
      } else if (message.command === "runTask" && message.task) {
        const allowedTasks = new Set([
          "grails.refreshTree",
          "grails.runApp",
          "grails.debugApp",
          "grails.testApp",
          "grails.compileProject",
        ]);
        if (allowedTasks.has(message.task)) {
          void vscode.commands.executeCommand(message.task);
        }
      }
    });
  }

  public getHtmlContent(projects?: readonly ProjectInfo[]): string {
    const actualProjects = projects ?? this.projectService?.getProjects() ?? [];
    const nonce = getNonce();
    const lspRunning = this.languageServerManager?.isRunning ?? false;
    const gradleReady = this.gradleService?.isReady ?? false;

    const cardsHtml =
      actualProjects.length === 0
        ? `
        <div class="empty-state" role="status" aria-live="polite">
          <h2>No Grails Projects Detected</h2>
          <p>The workspace does not currently have any recognized Grails projects.</p>
          <button id="discoverBtn" class="action-btn">Discover Projects</button>
        </div>`
        : actualProjects
            .map(
              (p) => `
        <div class="card" tabindex="0" role="article" aria-label="Project ${this.escapeHtml(p.name)}">
          <h3>${this.escapeHtml(p.name)}</h3>
          <div class="path">${this.escapeHtml(p.rootPath)}</div>
          <div class="meta-row">
            <span class="label">Type:</span> <strong>${this.escapeHtml(p.type)}</strong>
          </div>
          <div class="meta-row">
            <span class="label">Dependencies:</span> <strong>${p.dependencies?.length ?? 0} resolved</strong>
          </div>
        </div>`
            )
            .join("\n");

    const lspStatusLabel = lspRunning ? "LSP Running" : "Language Server Disconnected";

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; font-src https:;">
  <title>Grails Dashboard</title>
  <style nonce="${nonce}">
    :root {
      --gap: 16px;
      --card-padding: 16px;
    }
    body {
      font-family: var(--vscode-font-family, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif);
      padding: 20px;
      color: var(--vscode-foreground);
      background-color: var(--vscode-editor-background);
      margin: 0;
    }
    h1 {
      margin-top: 0;
      color: var(--vscode-foreground);
    }
    .origins-bar {
      margin-bottom: var(--gap);
      font-size: 12px;
      color: var(--vscode-descriptionForeground);
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
    }
    .origin-pill {
      background: var(--vscode-badge-background);
      color: var(--vscode-badge-foreground);
      padding: 2px 8px;
      border-radius: 4px;
    }
    .status-bar {
      display: flex;
      gap: 12px;
      margin-bottom: var(--gap);
    }
    .status-badge {
      padding: 4px 10px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 600;
    }
    .status-badge.ok {
      background-color: var(--vscode-testing-iconPassed, #28a745);
      color: #ffffff;
    }
    .status-badge.warn {
      background-color: var(--vscode-testing-iconFailed, #d73a49);
      color: #ffffff;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: var(--gap);
      margin-bottom: var(--gap);
    }
    .card {
      background: var(--vscode-editor-background);
      border: 1px solid var(--vscode-widget-border, #454545);
      padding: var(--card-padding);
      border-radius: 6px;
      transition: outline 0.1s ease;
    }
    .card:focus-visible {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: 2px;
    }
    .card h3 {
      margin-top: 0;
      margin-bottom: 8px;
      color: var(--vscode-button-background);
    }
    .path {
      font-size: 12px;
      color: var(--vscode-descriptionForeground);
      word-break: break-all;
      margin-bottom: 12px;
    }
    .meta-row {
      display: flex;
      justify-content: space-between;
      font-size: 13px;
      margin-bottom: 6px;
    }
    .empty-state {
      padding: 30px;
      border: 1px dashed var(--vscode-widget-border, #454545);
      border-radius: 6px;
      text-align: center;
    }
    .actions {
      display: flex;
      gap: 10px;
      margin-top: var(--gap);
      flex-wrap: wrap;
    }
    .action-btn {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      padding: 8px 14px;
      cursor: pointer;
      border-radius: 4px;
      font-size: 13px;
    }
    .action-btn:hover {
      background: var(--vscode-button-hoverBackground);
    }
    .action-btn:focus-visible {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: 2px;
    }
    @media (forced-colors: active) {
      .card {
        border: 1px solid CanvasText;
      }
      .card:focus-visible {
        outline: 2px solid Highlight;
      }
      .action-btn {
        border: 1px solid ButtonText;
      }
    }
  </style>
</head>
<body>
  <h1>Grails Project Dashboard</h1>
  <div class="origins-bar">
    <span class="origin-pill">Origin: Workspace Project Discovery</span>
    <span class="origin-pill">Origin: Subsystem Lifecycle State</span>
  </div>

  <div class="status-bar">
    <span class="status-badge ${gradleReady ? "ok" : "warn"}">${gradleReady ? "Gradle Ready" : "Gradle Not Ready"}</span>
    <span class="status-badge ${lspRunning ? "ok" : "warn"}">${lspStatusLabel}</span>
  </div>

  <div class="grid">
    ${cardsHtml}
  </div>

  <div class="actions">
    <button id="runAppBtn" class="action-btn">Run App</button>
    <button id="testAppBtn" class="action-btn">Test App</button>
    <button id="refreshProjectsBtn" class="action-btn">Refresh Projects</button>
  </div>

  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();

    const discoverBtn = document.getElementById('discoverBtn');
    if (discoverBtn) {
      discoverBtn.addEventListener('click', () => {
        vscode.postMessage({ command: 'discoverProjects' });
      });
    }

    const runAppBtn = document.getElementById('runAppBtn');
    if (runAppBtn) {
      runAppBtn.addEventListener('click', () => {
        vscode.postMessage({ command: 'runTask', task: 'grails.runApp' });
      });
    }

    const testAppBtn = document.getElementById('testAppBtn');
    if (testAppBtn) {
      testAppBtn.addEventListener('click', () => {
        vscode.postMessage({ command: 'runTask', task: 'grails.testApp' });
      });
    }

    const refreshProjectsBtn = document.getElementById('refreshProjectsBtn');
    if (refreshProjectsBtn) {
      refreshProjectsBtn.addEventListener('click', () => {
        vscode.postMessage({ command: 'runTask', task: 'grails.refreshTree' });
      });
    }
  </script>
</body>
</html>`;
  }

  private escapeHtml(str: string): string {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }
}
