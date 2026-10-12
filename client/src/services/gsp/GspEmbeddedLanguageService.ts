import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { GspPositionMapper } from "./GspPositionMapper";
import { GspRegionParser } from "./GspRegionParser";
import { VirtualDocumentManager } from "./VirtualDocumentManager";

/**
 * Orchestrates embedded language editing in GSP documents (HTML, CSS, JavaScript, Groovy).
 * Routes requests to supported language providers using virtual documents, maps offsets
 * and edits 1:1, rejects unmappable edits, and cleans up on document close (INV-EDIT-001, INV-PERF-002).
 */
export class GspEmbeddedLanguageService
  implements
    vscode.CompletionItemProvider,
    vscode.HoverProvider,
    vscode.DefinitionProvider,
    vscode.DocumentFormattingEditProvider,
    vscode.Disposable
{
  private readonly _disposables: vscode.Disposable[] = [];
  readonly virtualDocumentManager: VirtualDocumentManager;

  constructor() {
    this.virtualDocumentManager = new VirtualDocumentManager();
    this._disposables.push(this.virtualDocumentManager);

    // Document lifecycle tracking
    this._disposables.push(
      vscode.workspace.onDidChangeTextDocument(event => {
        if (event.document.languageId === "gsp") {
          this.virtualDocumentManager.updateDocument(event.document);
        }
      })
    );

    this._disposables.push(
      vscode.workspace.onDidCloseTextDocument(doc => {
        if (doc.languageId === "gsp") {
          this.virtualDocumentManager.closeDocument(doc.uri);
        }
      })
    );

    this._disposables.push(
      vscode.workspace.onDidChangeWorkspaceFolders(() => {
        this.virtualDocumentManager.clear();
      })
    );
  }

  // --- CompletionItemProvider ---
  async provideCompletionItems(
    document: vscode.TextDocument | { uri: vscode.Uri; version: number; languageId: string; getText: () => string; lineAt: (pos: vscode.Position) => { text: string } },
    position: vscode.Position,
    token: vscode.CancellationToken,
    _context: vscode.CompletionContext | Record<string, unknown>
  ): Promise<vscode.CompletionItem[] | vscode.CompletionList> {
    if (token.isCancellationRequested) {
      return new vscode.CompletionList([], false);
    }

    const record = this.virtualDocumentManager.updateDocument(document as vscode.TextDocument);
    const region = GspRegionParser.getRegionAt(record.regions, position);
    const targetLang = region ? (region.isGroovy ? "groovy" : region.language) : "html";

    const items: vscode.CompletionItem[] = [];

    // TagLib, i18n, asset completions when inside HTML/tag context
    if (targetLang === "html" || !region?.isGroovy) {
      const linePrefix = document.lineAt(position).text.substring(0, position.character);
      this.addTagLibCompletions(linePrefix, items, document);
      this.addI18nCompletions(linePrefix, items, document);
      this.addPathCompletions(linePrefix, items, document);
    }

    // Forward to language service for the region
    const virtualUri = this.virtualDocumentManager.getVirtualUri(document.uri, targetLang);
    try {
      const forwarded = await vscode.commands.executeCommand<vscode.CompletionList>(
        "vscode.executeCompletionItemProvider",
        virtualUri,
        position
      );
      if (forwarded?.items) {
        for (const item of forwarded.items) {
          if (region) {
            const mapped = GspPositionMapper.mapCompletionItem(item, region);
            if (mapped) {
              items.push(mapped);
            }
          } else {
            items.push(item);
          }
        }
      }
    } catch {
      // Forwarding to built-in services may be unavailable in some environments; fallback gracefully
    }

    return new vscode.CompletionList(items, false);
  }

  // --- HoverProvider ---
  async provideHover(
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken
  ): Promise<vscode.Hover | null> {
    if (token.isCancellationRequested) return null;

    const record = this.virtualDocumentManager.updateDocument(document);
    const region = GspRegionParser.getRegionAt(record.regions, position);
    const targetLang = region ? (region.isGroovy ? "groovy" : region.language) : "html";
    const virtualUri = this.virtualDocumentManager.getVirtualUri(document.uri, targetLang);

    try {
      const hoverResults = await vscode.commands.executeCommand<vscode.Hover[]>(
        "vscode.executeHoverProvider",
        virtualUri,
        position
      );
      if (hoverResults && hoverResults.length > 0 && region) {
        return GspPositionMapper.mapHover(hoverResults[0], region);
      }
    } catch {
      // Fallback
    }

    return null;
  }

  // --- DefinitionProvider ---
  async provideDefinition(
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken
  ): Promise<vscode.Definition | vscode.LocationLink[] | null> {
    if (token.isCancellationRequested) return null;

    const record = this.virtualDocumentManager.updateDocument(document);
    const region = GspRegionParser.getRegionAt(record.regions, position);
    const targetLang = region ? (region.isGroovy ? "groovy" : region.language) : "html";
    const virtualUri = this.virtualDocumentManager.getVirtualUri(document.uri, targetLang);

    try {
      const defResults = await vscode.commands.executeCommand<vscode.Definition | vscode.LocationLink[]>(
        "vscode.executeDefinitionProvider",
        virtualUri,
        position
      );
      return defResults ?? null;
    } catch {
      return null;
    }
  }

  // --- DocumentFormattingEditProvider ---
  async provideDocumentFormattingEdits(
    document: vscode.TextDocument,
    _options: vscode.FormattingOptions,
    token: vscode.CancellationToken
  ): Promise<vscode.TextEdit[]> {
    if (token.isCancellationRequested) return [];

    const record = this.virtualDocumentManager.updateDocument(document);
    const virtualUri = this.virtualDocumentManager.getVirtualUri(document.uri, "html");

    try {
      const edits = await vscode.commands.executeCommand<vscode.TextEdit[]>(
        "vscode.executeFormatDocumentProvider",
        virtualUri
      );
      if (edits && edits.length > 0) {
        return GspPositionMapper.mapFormattingEdits(edits, record.regions);
      }
    } catch {
      // Formatting fallback
    }

    return [];
  }

  // --- Helpers for TagLib, I18n, and Assets ---
  private addTagLibCompletions(
    prefix: string,
    completions: vscode.CompletionItem[],
    document: { uri: vscode.Uri }
  ): void {
    const gspTags = [
      {
        tag: "g:each",
        detail: "Iterate over a collection",
        insert: 'g:each in="${$1}" var="${2:item}">\n\t$0\n</g:each>',
      },
      { tag: "g:if", detail: "Conditional if", insert: 'g:if test="${$1}">\n\t$0\n</g:if>' },
      { tag: "g:else", detail: "Conditional else", insert: "g:else>\n\t$0\n</g:else>" },
      {
        tag: "g:elseif",
        detail: "Conditional elseif",
        insert: 'g:elseif test="${$1}">\n\t$0\n</g:elseif>',
      },
      {
        tag: "g:set",
        detail: "Sets a variable",
        insert: 'g:set var="${1:name}" value="${$2}" />$0',
      },
      {
        tag: "g:render",
        detail: "Renders a template",
        insert: 'g:render template="${1:templateName}" model="[${2:key}: ${3:value}]" />$0',
      },
      {
        tag: "g:message",
        detail: "I18n message",
        insert: 'g:message code="${1:code.key}" default="${2:default}" />$0',
      },
      {
        tag: "g:form",
        detail: "GSP Form",
        insert: 'g:form controller="${1:controller}" action="${2:action}">\n\t$0\n</g:form>',
      },
      {
        tag: "g:textField",
        detail: "Text Field",
        insert: 'g:textField name="${1:name}" value="${$2}" />$0',
      },
      {
        tag: "g:submitButton",
        detail: "Submit Button",
        insert: 'g:submitButton name="${1:submit}" value="${2:Submit}" />$0',
      },
      {
        tag: "g:link",
        detail: "Create a link",
        insert: 'g:link controller="${1:controller}" action="${2:action}">$3</g:link>$0',
      },
      {
        tag: "asset:stylesheet",
        detail: "Asset pipeline stylesheet",
        insert: 'asset:stylesheet src="${1:application.css}" />$0',
      },
      {
        tag: "asset:javascript",
        detail: "Asset pipeline javascript",
        insert: 'asset:javascript src="${1:application.js}" />$0',
      },
      {
        tag: "asset:image",
        detail: "Asset pipeline image",
        insert: 'asset:image src="${1:logo.png}" alt="${2:Logo}" />$0',
      },
    ];

    if (/<(?:[a-zA-Z0-9_-]+:)?(?:[a-zA-Z0-9_-]*)$/.test(prefix)) {
      gspTags.forEach(t => {
        const item = new vscode.CompletionItem(t.tag, vscode.CompletionItemKind.Property);
        item.detail = t.detail;
        item.insertText = new vscode.SnippetString(t.insert);
        completions.push(item);
      });

      const workspaceFolder = vscode.workspace.getWorkspaceFolder(document.uri);
      if (workspaceFolder) {
        const taglibDir = path.join(workspaceFolder.uri.fsPath, "grails-app", "taglib");
        if (fs.existsSync(taglibDir)) {
          this.recursivelyExtractCustomTags(taglibDir, completions);
        }
      }
    }
  }

  private recursivelyExtractCustomTags(dir: string, completions: vscode.CompletionItem[]): void {
    try {
      const files = fs.readdirSync(dir);
      for (const f of files) {
        const fullPath = path.join(dir, f);
        const stat = fs.statSync(fullPath);
        if (stat.isDirectory()) {
          this.recursivelyExtractCustomTags(fullPath, completions);
        } else if (f.endsWith("TagLib.groovy") || f.endsWith("Taglib.groovy")) {
          const content = fs.readFileSync(fullPath, "utf8");
          let namespace = "g";
          const nsMatch = content.match(/static\s+namespace\s*=\s*['"]([^'"]+)['"]/);
          if (nsMatch) {
            namespace = nsMatch[1];
          }
          const tagRegex = /(?:def|Closure)\s+([a-zA-Z0-9_]+)\s*=\s*\{/g;
          let match: RegExpExecArray | null;
          while ((match = tagRegex.exec(content)) !== null) {
            const tagName = match[1];
            const tag = `${namespace}:${tagName}`;
            const item = new vscode.CompletionItem(tag, vscode.CompletionItemKind.Function);
            item.detail = `Custom TagLib (${f})`;
            item.insertText = new vscode.SnippetString(`${tag} $1>$0</${tag}>`);
            completions.push(item);
          }
        }
      }
    } catch {
      // Safe ignore
    }
  }

  private addI18nCompletions(
    prefix: string,
    completions: vscode.CompletionItem[],
    document: { uri: vscode.Uri }
  ): void {
    const messageCodeRegex = /<g:message[^>]*code=['"]([^'"]*)$/;
    if (messageCodeRegex.test(prefix)) {
      const workspaceFolder = vscode.workspace.getWorkspaceFolder(document.uri);
      if (workspaceFolder) {
        const i18nDir = path.join(workspaceFolder.uri.fsPath, "grails-app", "i18n");
        if (fs.existsSync(i18nDir)) {
          const files = fs.readdirSync(i18nDir).filter(f => f.endsWith(".properties"));
          const keys = new Set<string>();
          files.forEach(f => {
            const content = fs.readFileSync(path.join(i18nDir, f), "utf-8");
            const lines = content.split("\n");
            lines.forEach(line => {
              line = line.trim();
              if (line && !line.startsWith("#")) {
                const parts = line.split("=");
                if (parts.length > 0) {
                  keys.add(parts[0].trim());
                }
              }
            });
          });
          keys.forEach(key => {
            const item = new vscode.CompletionItem(key, vscode.CompletionItemKind.Value);
            item.detail = "I18n Application Message Key";
            completions.push(item);
          });
        }
      }
    }
  }

  private addPathCompletions(
    prefix: string,
    completions: vscode.CompletionItem[],
    document: { uri: vscode.Uri }
  ): void {
    const srcRegex = /<asset:(image|javascript|stylesheet)[^>]*src=['"]([^'"]*)$/;
    const srcMatch = prefix.match(srcRegex);
    if (srcMatch) {
      const type = srcMatch[1];
      const workspaceFolder = vscode.workspace.getWorkspaceFolder(document.uri);
      if (workspaceFolder) {
        let assetDir = path.join(workspaceFolder.uri.fsPath, "grails-app", "assets");
        if (type === "image") assetDir = path.join(assetDir, "images");
        else if (type === "javascript") assetDir = path.join(assetDir, "javascripts");
        else if (type === "stylesheet") assetDir = path.join(assetDir, "stylesheets");

        if (fs.existsSync(assetDir)) {
          this.recursivelyAddFiles(assetDir, "", completions);
        }
      }
    }
  }

  private recursivelyAddFiles(baseDir: string, subPath: string, completions: vscode.CompletionItem[]): void {
    const currentDir = path.join(baseDir, subPath);
    if (!fs.existsSync(currentDir)) return;
    const files = fs.readdirSync(currentDir);
    for (const f of files) {
      const fullPath = path.join(currentDir, f);
      const stat = fs.statSync(fullPath);
      const relativePath = subPath ? `${subPath}/${f}` : f;
      if (stat.isDirectory()) {
        this.recursivelyAddFiles(baseDir, relativePath, completions);
      } else {
        const item = new vscode.CompletionItem(relativePath, vscode.CompletionItemKind.File);
        completions.push(item);
      }
    }
  }

  dispose(): void {
    this._disposables.forEach(d => {
      d.dispose();
    });
    this._disposables.length = 0;
  }
}
