import * as path from "path";
import * as vscode from "vscode";
import type { ErrorService } from "../../services/errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../../services/errors/errorTypes";
import type { LanguageServerManager } from "../../services/languageServer/LanguageServerManager";
import { createWebviewStateManager, getNonce } from "../../services/webview/WebviewStateManager";
import { debounce } from "../../utils/DebounceUtils";

interface SqlPreviewState {
  lastUri?: string;
}

export class GormSqlPreviewService implements vscode.Disposable {
  private currentPanel: vscode.WebviewPanel | null = null;
  private disposed = false;
  private stateManager: ReturnType<typeof createWebviewStateManager<SqlPreviewState>>;
  private cancellationTokenSource: vscode.CancellationTokenSource | null = null;

  private readonly debouncedRefresh = debounce((uri: vscode.Uri) => {
    if (!this.disposed) {
      void this.doRefreshPreview(uri);
    }
  }, 300);

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly errorService: ErrorService,
    private readonly languageServerManager: LanguageServerManager
  ) {
    this.stateManager = createWebviewStateManager<SqlPreviewState>(context, "gormSqlPreview");
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

  public openPreview(uri: vscode.Uri): void {
    if (this.currentPanel) {
      this.currentPanel.reveal(vscode.ViewColumn.Beside);
      this.debouncedRefresh(uri);
      return;
    }

    this.currentPanel = vscode.window.createWebviewPanel(
      "gormSqlPreview",
      `GORM SQL: ${path.basename(uri.fsPath)}`,
      vscode.ViewColumn.Beside,
      this.stateManager.getWebviewOptions()
    );

    this.currentPanel.webview.html = this.getHtmlContent(uri);

    this.currentPanel.onDidDispose(
      () => {
        this.currentPanel = null;
      },
      null,
      this.context.subscriptions
    );

    this.currentPanel.webview.onDidReceiveMessage(async (message: { command: string; text?: string }) => {
      if (message.command === "copySql" && message.text) {
        await vscode.env.clipboard.writeText(message.text);
        void vscode.window.showInformationMessage("SQL copied to clipboard");
      }
    });

    this.debouncedRefresh(uri);
  }

  private async doRefreshPreview(uri: vscode.Uri): Promise<void> {
    if (!this.currentPanel || this.disposed) {
      return;
    }

    this.cancelPendingRequest();
    this.cancellationTokenSource = new vscode.CancellationTokenSource();

    try {
      const client = this.languageServerManager.languageClient;
      if (!client) {
        void this.currentPanel.webview.postMessage({
          command: "updateSql",
          sql: "-- Language server is disconnected. Cannot analyze domain class AST.",
        });
        return;
      }

      const token = this.cancellationTokenSource.token;

      const sql = await client.sendRequest(
        "workspace/executeCommand",
        {
          command: "grails.getGormSql",
          arguments: [uri.toString()],
        },
        token
      );

      if (token.isCancellationRequested) {
        return;
      }

      void this.currentPanel.webview.postMessage({
        command: "updateSql",
        sql: (typeof sql === "string" && sql.trim().length > 0) ? sql : "-- No DDL generated for domain entity.",
      });

      await this.stateManager.setState({ lastUri: uri.toString() });
    } catch (error) {
      if (this.cancellationTokenSource?.token.isCancellationRequested) {
        return;
      }
      this.errorService.handleError(
        "Failed to generate SQL preview",
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

  public getHtmlContent(uri?: vscode.Uri): string {
    const nonce = getNonce();
    const fileName = uri ? path.basename(uri.fsPath) : "DomainClass.groovy";

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; font-src https:;">
  <title>GORM SQL Preview</title>
  <style nonce="${nonce}">
    body {
      margin: 0;
      padding: 16px;
      background-color: var(--vscode-editor-background);
      color: var(--vscode-editor-foreground);
      font-family: var(--vscode-font-family, monospace);
      font-size: var(--vscode-editor-font-size, 13px);
      display: flex;
      flex-direction: column;
      height: 100vh;
      box-sizing: border-box;
    }
    .header {
      margin-bottom: 12px;
    }
    h2 {
      margin-top: 0;
      margin-bottom: 6px;
    }
    .disclaimer-box {
      background: var(--vscode-badge-background);
      color: var(--vscode-badge-foreground);
      padding: 6px 10px;
      border-radius: 4px;
      font-size: 12px;
      margin-bottom: 10px;
      line-height: 1.4;
    }
    .origin-badge {
      display: inline-block;
      margin-top: 4px;
      font-size: 11px;
      opacity: 0.9;
    }
    .toolbar {
      display: flex;
      justify-content: flex-end;
      margin-bottom: 8px;
    }
    button {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      padding: 6px 12px;
      cursor: pointer;
      border-radius: 4px;
      font-size: 12px;
    }
    button:hover {
      background: var(--vscode-button-hoverBackground);
    }
    button:focus-visible {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: 2px;
    }
    pre {
      flex: 1;
      margin: 0;
      background: var(--vscode-textCodeBlock-background, #1e1e1e);
      border: 1px solid var(--vscode-widget-border, #454545);
      border-radius: 4px;
      padding: 12px;
      overflow: auto;
      font-family: var(--vscode-editor-font-family, monospace);
      line-height: 1.5;
    }
    pre:focus-visible {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: 2px;
    }
    @media (forced-colors: active) {
      pre {
        border: 1px solid CanvasText;
      }
      pre:focus-visible {
        outline: 2px solid Highlight;
      }
    }
  </style>
</head>
<body>
  <div class="header">
    <h2>Static DDL Approximation: ${this.escapeHtml(fileName)}</h2>
    <div class="disclaimer-box">
      <strong>Notice:</strong> This is simulated DDL and is not executed against a live database.
      <div class="origin-badge">Origin: Static Domain AST Analysis</div>
    </div>
  </div>

  <div class="toolbar">
    <button id="copyBtn" aria-label="Copy SQL DDL to clipboard">Copy SQL</button>
  </div>

  <pre id="sqlOutput" tabindex="0" role="region" aria-label="SQL DDL Output">-- Loading preview...</pre>

  <script nonce="${nonce}">
    const vscode = acquireVsCodeApi();

    const copyBtn = document.getElementById('copyBtn');
    if (copyBtn) {
      copyBtn.addEventListener('click', () => {
        const sql = document.getElementById('sqlOutput')?.textContent || '';
        vscode.postMessage({ command: 'copySql', text: sql });
      });
    }

    window.addEventListener('message', (event) => {
      const msg = event.data;
      if (msg.command === 'updateSql') {
        const output = document.getElementById('sqlOutput');
        if (output) {
          output.textContent = msg.sql;
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
