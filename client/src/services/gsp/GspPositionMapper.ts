import * as vscode from "vscode";
import type { GspRegion } from "./GspRegionParser";

export class GspPositionMapper {
  /**
   * Validates whether a text edit falls completely within the active region.
   * Invariant INV-EDIT-001: Unmappable edits must be rejected.
   */
  static canApplyEdit(edit: vscode.TextEdit, region: GspRegion): boolean {
    if (!edit?.range || !region) return false;
    const start = edit.range.start;
    const end = edit.range.end;

    // Check if start is within region
    if (start.isBefore(region.startPosition) || start.isAfter(region.endPosition)) {
      return false;
    }
    // Check if end is within region
    if (end.isBefore(region.startPosition) || end.isAfter(region.endPosition)) {
      return false;
    }
    return true;
  }

  /**
   * Maps a TextEdit back to the original document, rejecting it if it touches
   * unmappable regions.
   */
  static mapEdit(edit: vscode.TextEdit, region: GspRegion): vscode.TextEdit | null {
    if (!this.canApplyEdit(edit, region)) {
      return null;
    }
    return edit;
  }

  /**
   * Maps a completion item and its additional edits, dropping unmappable edits.
   */
  static mapCompletionItem(item: vscode.CompletionItem, region: GspRegion): vscode.CompletionItem | null {
    if (!item) return null;

    // Validate range if present
    if (item.range) {
      if ("inserting" in item.range && "replacing" in item.range) {
        const insertingValid = this.isRangeWithinRegion(item.range.inserting, region);
        const replacingValid = this.isRangeWithinRegion(item.range.replacing, region);
        if (!insertingValid || !replacingValid) {
          return null;
        }
      } else if (item.range instanceof vscode.Range) {
        if (!this.isRangeWithinRegion(item.range, region)) {
          return null;
        }
      }
    }

    // Validate additionalTextEdits
    if (item.additionalTextEdits && item.additionalTextEdits.length > 0) {
      const validEdits: vscode.TextEdit[] = [];
      for (const edit of item.additionalTextEdits) {
        const mapped = this.mapEdit(edit, region);
        if (mapped) {
          validEdits.push(mapped);
        } else {
          // Reject unmappable edit
          return null;
        }
      }
      item.additionalTextEdits = validEdits;
    }

    return item;
  }

  /**
   * Maps hover ranges back, verifying they don't leak out of the active region.
   */
  static mapHover(hover: vscode.Hover, region: GspRegion): vscode.Hover | null {
    if (!hover) return null;
    if (hover.range && !this.isRangeWithinRegion(hover.range, region)) {
      return null;
    }
    return hover;
  }

  /**
   * Filters and validates formatting edits to only apply within valid boundaries.
   */
  static mapFormattingEdits(edits: vscode.TextEdit[], regions: GspRegion[]): vscode.TextEdit[] {
    if (!edits || edits.length === 0) return [];
    const validEdits: vscode.TextEdit[] = [];
    for (const edit of edits) {
      // Find a region that completely contains this edit
      const container = regions.find(r => this.canApplyEdit(edit, r));
      if (container) {
        validEdits.push(edit);
      }
    }
    return validEdits;
  }

  private static isRangeWithinRegion(range: vscode.Range, region: GspRegion): boolean {
    return (
      !range.start.isBefore(region.startPosition) &&
      !range.start.isAfter(region.endPosition) &&
      !range.end.isBefore(region.startPosition) &&
      !range.end.isAfter(region.endPosition)
    );
  }
}
