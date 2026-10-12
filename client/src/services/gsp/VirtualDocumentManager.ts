import * as vscode from "vscode";
import { GspRegionParser, type GspRegion } from "./GspRegionParser";

export interface VirtualDocumentRecord {
  version: number;
  originalText: string;
  regions: GspRegion[];
  virtualContents: Map<string, string>;
}

export class VirtualDocumentManager implements vscode.Disposable {
  static readonly SCHEME = "gsp-virtual";
  private readonly _records = new Map<string, VirtualDocumentRecord>();

  /**
   * Registers or updates a virtual document representation for a GSP text document.
   */
  updateDocument(document: vscode.TextDocument | { uri: vscode.Uri; version: number; getText: () => string }): VirtualDocumentRecord {
    const key = document.uri.toString();
    const text = document.getText();
    const version = document.version;

    const existing = this._records.get(key);
    if (existing && existing.version === version && existing.originalText === text) {
      return existing;
    }

    const regions = GspRegionParser.parse(text);
    const virtualContents = new Map<string, string>();

    // Precompute virtual texts for each embedded language
    const languages = ["html", "css", "javascript", "groovy"];
    for (const lang of languages) {
      virtualContents.set(lang, this.buildVirtualContent(text, regions, lang));
    }

    const record: VirtualDocumentRecord = {
      version,
      originalText: text,
      regions,
      virtualContents,
    };

    this._records.set(key, record);
    return record;
  }

  /**
   * Builds virtual document content where non-target regions are masked with spaces,
   * strictly preserving all newlines, UTF-16 surrogate pairs, and column positions.
   */
  buildVirtualContent(originalText: string, regions: GspRegion[], targetLang: string): string {
    const chars: string[] = [];
    const n = originalText.length;
    let regionIdx = 0;

    for (let i = 0; i < n; i++) {
      const c = originalText[i];
      if (c === "\n" || c === "\r") {
        chars.push(c);
        continue;
      }

      // Advance regionIdx to current offset
      while (regionIdx < regions.length && regions[regionIdx].endOffset <= i) {
        regionIdx++;
      }

      const activeRegion =
        regionIdx < regions.length &&
        regions[regionIdx].startOffset <= i &&
        regions[regionIdx].endOffset > i
          ? regions[regionIdx]
          : null;

      const isTarget = activeRegion
        ? targetLang === "groovy"
          ? activeRegion.isGroovy
          : activeRegion.language === targetLang
        : targetLang === "html"; // Default unclassified markup is HTML

      if (isTarget) {
        chars.push(c);
      } else {
        chars.push(" ");
      }
    }

    return chars.join("");
  }

  getVirtualText(uri: vscode.Uri, language: string): string | null {
    const record = this._records.get(uri.toString());
    return record?.virtualContents.get(language) ?? null;
  }

  getRegions(uri: vscode.Uri): GspRegion[] {
    const record = this._records.get(uri.toString());
    return record ? record.regions : [];
  }

  getVirtualUri(uri: vscode.Uri, language: string): vscode.Uri {
    const ext = language === "groovy" ? "groovy" : language === "javascript" ? "js" : language;
    const rawPath = uri.path || uri.fsPath || uri.toString();
    const cleanPath = rawPath.replace(/\.gsp$/i, "");
    return vscode.Uri.parse(
      `${VirtualDocumentManager.SCHEME}://${language}/${cleanPath.replace(/^\/+/, "")}.${ext}`
    );
  }

  /**
   * Disposes virtual document state on document close or workspace removal.
   */
  closeDocument(uri: vscode.Uri): void {
    this._records.delete(uri.toString());
  }

  clear(): void {
    this._records.clear();
  }

  dispose(): void {
    this.clear();
  }
}
