import * as path from "path";
import * as vscode from "vscode";
import type { ErrorService } from "../../services/errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import type { LanguageServerManager } from "../../services/languageServer/LanguageServerManager";
import { createWebviewStateManager, getNonce } from "../../services/webview/WebviewStateManager";
import type { ProjectService } from "../../services/workspace/ProjectService";
import { debounce } from "../../utils/DebounceUtils";
import type { ProjectInfo } from "../models/modelTypes";

interface GraphState {
  currentConfig?: string;
}

export class DependencyGraphService implements vscode.Disposable {
  private currentPanel: vscode.WebviewPanel | null = null;
  private disposed = false;
  private stateManager: ReturnType<typeof createWebviewStateManager<GraphState>>;
  private cancellationTokenSource: vscode.CancellationTokenSource | null = null;

  private readonly debouncedRefresh = debounce((project: ProjectInfo) => {
    if (!this.disposed) {
      void this.doRefreshGraph(project);
    }
  }, 300);

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly errorService: ErrorService,
    private readonly languageServerManager: LanguageServerManager,
    private readonly projectService?: ProjectService
  ) {
    this.stateManager = createWebviewStateManager<GraphState>(context, "dependencyGraph");
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.debouncedRefresh.cancel();
    this.cancelPendingRequest();
    if (this.currentPanel) {
      this.currentPanel.dispose();
      this.currentPanel = null;
    }
    void this.stateManager.clearState();
  }

  private cancelPendingRequest(): void {
    if (this.cancellationTokenSource) {
      this.cancellationTokenSource.cancel();
      this.cancellationTokenSource.dispose();
      this.cancellationTokenSource = null;
    }
  }

  public openGraph(project: ProjectInfo): void {
    const column = vscode.window.activeTextEditor
      ? vscode.window.activeTextEditor.viewColumn
      : undefined;

    if (this.currentPanel) {
      this.currentPanel.reveal(column);
      this.debouncedRefresh(project);
      return;
    }

    this.currentPanel = vscode.window.createWebviewPanel(
      "dependencyGraph",
      `Dependency Graph: ${project.name}`,
      column ?? vscode.ViewColumn.One,
      this.stateManager.getWebviewOptions([
        vscode.Uri.file(path.join(this.context.extensionPath, "resources")),
      ])
    );

    const savedState = this.stateManager.getState();
    this.currentPanel.webview.html = this.getHtmlContent(project, savedState);

    this.currentPanel.onDidDispose(
      () => {
        this.currentPanel = null;
      },
      null,
      this.context.subscriptions
    );

    this.currentPanel.webview.onDidReceiveMessage((message: { command: string; config?: string }) => {
      if (message.command === "changeConfig" && message.config) {
        void this.stateManager.setState({ currentConfig: message.config });
        void this.doRefreshGraph(project);
      } else if (message.command === "refresh") {
        void this.doRefreshGraph(project);
      }
    });

    this.debouncedRefresh(project);
  }

  private async doRefreshGraph(project: ProjectInfo): Promise<void> {
    if (!this.currentPanel || this.disposed) {
      return;
    }

    this.cancelPendingRequest();
    this.cancellationTokenSource = new vscode.CancellationTokenSource();

    try {
      const client = this.languageServerManager.languageClient;
      if (!client) {
        void this.currentPanel.webview.postMessage({
          command: "showStatus",
          message: "Language server not connected. Displaying local project metadata.",
          isError: true,
        });
        return;
      }

      const token = this.cancellationTokenSource.token;
      const graphData = await client.sendRequest(
        "workspace/executeCommand",
        {
          command: "grails.getDependencyGraph",
          arguments: [vscode.Uri.file(project.rootPath).toString()],
        },
        token
      );

      if (token.isCancellationRequested) {
        return;
      }

      void this.currentPanel.webview.postMessage({
        command: "updateGraph",
        data: typeof graphData === "string" ? (JSON.parse(graphData) as unknown) : graphData,
      });
    } catch (error) {
      if (this.cancellationTokenSource?.token.isCancellationRequested) {
        return;
      }
      this.errorService.handleError(
        "Failed to get dependency graph",
        error,
        ErrorSource.LanguageServer,
        ErrorSeverity.Error
      );
    } finally {
      if (this.cancellationTokenSource) {
        this.cancellationTokenSource.dispose();
        this.cancellationTokenSource = null;
      }
    }
  }

  public getHtmlContent(project?: ProjectInfo, savedState?: GraphState): string {
    const currentConfig = savedState?.currentConfig ?? "compileClasspath";
    const nonce = getNonce();
    const projectName = project?.name ?? "Grails Project";

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; font-src https:;">
  <title>Dependency Graph: ${this.escapeHtml(projectName)}</title>
  <style nonce="${nonce}">
    body {
      margin: 0;
      padding: 16px;
      background-color: var(--vscode-editor-background);
      color: var(--vscode-foreground);
      font-family: var(--vscode-font-family, sans-serif);
      display: flex;
      flex-direction: column;
      height: 100vh;
      box-sizing: border-box;
    }
    .header-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
    }
    .origin-badge {
      font-size: 11px;
      background: var(--vscode-badge-background);
      color: var(--vscode-badge-foreground);
      padding: 2px 8px;
      border-radius: 4px;
    }
    .status-banner {
      display: none;
      padding: 8px 12px;
      margin-bottom: 12px;
      border-radius: 4px;
      background: var(--vscode-inputValidation-warningBackground, #5a4b10);
      color: var(--vscode-inputValidation-warningForeground, #fff);
      font-size: 13px;
    }
    .controls {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
      margin-bottom: 12px;
    }
    select, input[type="text"], button {
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border, #3c3c3c);
      padding: 6px 10px;
      border-radius: 4px;
      font-size: 13px;
    }
    select:focus-visible, input[type="text"]:focus-visible, button:focus-visible {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: 1px;
    }
    button {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      cursor: pointer;
      border: none;
    }
    button:hover {
      background: var(--vscode-button-hoverBackground);
    }
    .node-badge {
      font-size: 12px;
      color: var(--vscode-descriptionForeground);
      margin-left: auto;
    }
    #graph-container {
      flex: 1;
      border: 1px solid var(--vscode-widget-border, #454545);
      border-radius: 4px;
      position: relative;
      overflow: hidden;
      min-height: 250px;
    }
    #table-view {
      display: none;
      flex: 1;
      overflow: auto;
      border: 1px solid var(--vscode-widget-border, #454545);
      border-radius: 4px;
      padding: 8px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }
    th, td {
      text-align: left;
      padding: 8px 12px;
      border-bottom: 1px solid var(--vscode-widget-border, #454545);
    }
    th {
      background: var(--vscode-editor-inactiveSelectionBackground);
      color: var(--vscode-foreground);
    }
    @media (prefers-reduced-motion: reduce) {
      * {
        animation-duration: 0.01ms !important;
        animation-iteration-count: 1 !important;
        transition-duration: 0.01ms !important;
      }
    }
  </style>
</head>
<body>
  <div class="header-bar">
    <h2>${this.escapeHtml(projectName)} Dependencies</h2>
    <span class="origin-badge">Origin: Resolved Gradle Classpath (via LSP)</span>
  </div>

  <div id="statusBanner" class="status-banner" role="status" aria-live="polite"></div>

  <div class="controls">
    <label for="configSelect">Configuration:</label>
    <select id="configSelect" aria-label="Gradle Classpath Configuration">
      <option value="compileClasspath" ${currentConfig === "compileClasspath" ? "selected" : ""}>compileClasspath</option>
      <option value="runtimeClasspath" ${currentConfig === "runtimeClasspath" ? "selected" : ""}>runtimeClasspath</option>
      <option value="testCompileClasspath" ${currentConfig === "testCompileClasspath" ? "selected" : ""}>testCompileClasspath</option>
    </select>

    <label for="filterInput">Filter:</label>
    <input type="text" id="filterInput" placeholder="Filter dependencies..." aria-label="Filter dependencies by name" />

    <button id="toggleViewBtn" aria-label="Toggle between graph and table views">Switch to Table View</button>
    <button id="refreshBtn" aria-label="Refresh dependencies">Refresh</button>

    <span id="nodeCount" class="node-badge" aria-live="polite">0 / 250 items</span>
  </div>

  <div id="graph-container" role="region" aria-label="Visual Dependency Graph">
    <svg id="graphSvg" width="100%" height="100%"></svg>
  </div>

  <div id="table-view" role="region" aria-label="Tabular Dependency List">
    <table id="depTable" role="table">
      <thead>
        <tr>
          <th scope="col">Group</th>
          <th scope="col">Artifact Name</th>
          <th scope="col">Version</th>
          <th scope="col">Scope</th>
        </tr>
      </thead>
      <tbody id="depTableBody">
        <tr>
          <td colspan="4">No dependencies loaded.</td>
        </tr>
      </tbody>
    </table>
  </div>

  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();
    const MAX_RENDER_NODES = 250;

    const configSelect = document.getElementById('configSelect');
    if (configSelect) {
      configSelect.addEventListener('change', () => {
        vscode.postMessage({ command: 'changeConfig', config: configSelect.value });
      });
    }

    const refreshBtn = document.getElementById('refreshBtn');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', () => {
        vscode.postMessage({ command: 'refresh' });
      });
    }

    const toggleViewBtn = document.getElementById('toggleViewBtn');
    const graphContainer = document.getElementById('graph-container');
    const tableView = document.getElementById('table-view');
    let showingTable = false;

    if (toggleViewBtn) {
      toggleViewBtn.addEventListener('click', () => {
        showingTable = !showingTable;
        if (showingTable) {
          graphContainer.style.display = 'none';
          tableView.style.display = 'block';
          toggleViewBtn.textContent = 'Switch to Graph View';
        } else {
          graphContainer.style.display = 'block';
          tableView.style.display = 'none';
          toggleViewBtn.textContent = 'Switch to Table View';
        }
      });
    }

    const filterInput = document.getElementById('filterInput');
    if (filterInput) {
      filterInput.addEventListener('input', () => {
        const query = filterInput.value.toLowerCase();
        const rows = document.querySelectorAll('#depTableBody tr');
        let visibleCount = 0;
        rows.forEach(r => {
          const text = r.textContent?.toLowerCase() || '';
          const match = text.includes(query);
          r.style.display = match ? '' : 'none';
          if (match) visibleCount++;
        });
        const badge = document.getElementById('nodeCount');
        if (badge) badge.textContent = visibleCount + ' / ' + MAX_RENDER_NODES + ' items';
      });
    }

    window.addEventListener('message', (event) => {
      const msg = event.data;
      if (msg.command === 'showStatus') {
        const banner = document.getElementById('statusBanner');
        if (banner) {
          banner.textContent = msg.message;
          banner.style.display = 'block';
        }
      }
    });
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
