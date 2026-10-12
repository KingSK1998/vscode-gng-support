import * as vscode from "vscode";
import type {
  ChangeReviewResponse,
  ChangeReviewStatus,
  ChangeSafetyVerdict,
} from "../../shared/protocol/analysis";

/**
 * Presenter for human and agent change reviews (R5-04).
 * Connects proposed edits, source evidence, change impact, check execution status,
 * and unchecked assumptions under a unified changeId and analysisRevision.
 * Theme-aware and accessible across editor UI, webview, and agent tools.
 */
export class ChangeReviewPresenter {
  /**
   * Formats a ChangeReviewResponse as an accessible Markdown document.
   */
  public static formatMarkdown(review: ChangeReviewResponse): string {
    const lines: string[] = [];

    // Header
    lines.push(`# Change Review: \`${review.changeId}\``);
    lines.push("");
    lines.push(`- **Project**: \`${review.projectUri}\``);
    lines.push(`- **Analysis Revision**: \`${review.analysisRevision}\``);
    lines.push(`- **Freshness**: \`${review.freshness}\``);
    lines.push(`- **Review Status**: ${this.formatStatusBadge(review.status)}`);
    lines.push(`- **Safety Verdict**: ${this.formatVerdictBadge(review.safetyVerdict)}`);
    lines.push("");

    // Recovery Actions / Warnings
    if (review.recoveryActions && review.recoveryActions.length > 0) {
      lines.push("## ⚠️ Required Actions / Recovery");
      lines.push("");
      for (const action of review.recoveryActions) {
        lines.push(`- ${action}`);
      }
      lines.push("");
    }

    // Edits Summary
    lines.push("## 📝 Proposed / Applied Edits");
    lines.push("");
    const editsSummary = review.editsSummary;
    lines.push(`- **Total Files Affected**: ${editsSummary.totalFiles}`);
    lines.push(`- **Total Edits**: ${editsSummary.totalEdits}`);
    if (editsSummary.hasCollisions) {
      lines.push("- **Warning**: Potential edit collision detected!");
    }
    if (editsSummary.files.length > 0) {
      lines.push("");
      lines.push("### Files");
      for (const f of editsSummary.files) {
        lines.push(`- \`${f}\``);
      }
    }
    lines.push("");

    // Impact Summary
    lines.push("## 🔍 Change Impact");
    lines.push("");
    const impact = review.impactSummary;
    lines.push(`- **Impacted Artifacts**: ${impact.impactedCount}`);
    lines.push(`- **Dynamic Unknowns**: ${impact.dynamicUnknownsCount}`);
    if (impact.artifacts.length > 0) {
      lines.push("");
      lines.push("### Impacted Files");
      for (const art of impact.artifacts) {
        lines.push(`- \`${art}\``);
      }
    }
    if (impact.dynamicUnknowns.length > 0) {
      lines.push("");
      lines.push("### Dynamic Constructs (Unprovable by Compiler)");
      for (const du of impact.dynamicUnknowns) {
        lines.push(`- ${du}`);
      }
    }
    lines.push("");

    // Checks Summary
    lines.push("## ✅ Scoped Checks Execution");
    lines.push("");
    if (review.checksSummary) {
      const checks = review.checksSummary;
      lines.push(`- **Execution Status**: \`${checks.checkStatus}\``);
      lines.push(`- **Compilation Success**: ${checks.compilationSuccess ? "Yes" : "No"}`);
      lines.push(
        `- **Tests**: ${checks.testsPassed} passed / ${checks.testsFailed} failed / ${checks.testsSkipped} skipped (${checks.testsTotal} total)`
      );
      lines.push(`- **Errors**: ${checks.errorCount}`);
      lines.push(`- **Warnings**: ${checks.warningCount}`);
    } else {
      lines.push("*(No scoped checks were executed for this change)*");
    }
    lines.push("");

    // Unchecked Assumptions
    if (review.uncheckedAssumptions && review.uncheckedAssumptions.length > 0) {
      lines.push("## ⚠️ Unchecked Assumptions");
      lines.push("");
      for (const assumption of review.uncheckedAssumptions) {
        lines.push(`- ${assumption}`);
      }
      lines.push("");
    }

    // Limitations
    if (review.limitations && review.limitations.length > 0) {
      lines.push("## ℹ️ Invariant Limitations");
      lines.push("");
      for (const lim of review.limitations) {
        lines.push(`- ${lim}`);
      }
      lines.push("");
    }

    return lines.join("\n");
  }

  /**
   * Renders an accessible, theme-aware HTML view of the ChangeReviewResponse.
   */
  public static renderHtml(review: ChangeReviewResponse): string {
    const isSafe = review.safetyVerdict === "SAFE_TO_APPLY";
    const verdictColor = isSafe
      ? "var(--vscode-testing-iconPassed, #4EC9B0)"
      : review.safetyVerdict === "UNSAFE_TO_APPLY" || review.status === "checks_failed"
        ? "var(--vscode-testing-iconFailed, #F14C4C)"
        : "var(--vscode-editorWarning-foreground, #CCA700)";

    const escapeHtml = (text: string) =>
      text
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Change Review: ${escapeHtml(review.changeId)}</title>
  <style>
    body {
      font-family: var(--vscode-font-family, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif);
      color: var(--vscode-editor-foreground, #D4D4D4);
      background-color: var(--vscode-editor-background, #1E1E1E);
      padding: 20px;
      line-height: 1.5;
    }
    h1, h2, h3 {
      color: var(--vscode-foreground, #CCCCCC);
      margin-top: 24px;
      margin-bottom: 12px;
      font-weight: 600;
    }
    .badge {
      display: inline-block;
      padding: 3px 8px;
      border-radius: 4px;
      font-weight: 600;
      font-size: 0.9em;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .badge-verdict {
      background-color: ${verdictColor};
      color: #FFFFFF;
    }
    .badge-status {
      background-color: var(--vscode-badge-background, #4D4D4D);
      color: var(--vscode-badge-foreground, #FFFFFF);
    }
    .banner-warning {
      background-color: var(--vscode-inputValidation-warningBackground, #352A05);
      border-left: 4px solid var(--vscode-inputValidation-warningBorder, #B89500);
      padding: 12px 16px;
      margin: 16px 0;
      border-radius: 2px;
    }
    .banner-error {
      background-color: var(--vscode-inputValidation-errorBackground, #5A1D1D);
      border-left: 4px solid var(--vscode-inputValidation-errorBorder, #BE1100);
      padding: 12px 16px;
      margin: 16px 0;
      border-radius: 2px;
    }
    .section-card {
      background-color: var(--vscode-editorWidget-background, #252526);
      border: 1px solid var(--vscode-editorWidget-border, #454545);
      border-radius: 4px;
      padding: 16px;
      margin-bottom: 16px;
    }
    ul {
      margin: 8px 0;
      padding-left: 20px;
    }
    li {
      margin-bottom: 4px;
    }
    code {
      font-family: var(--vscode-editor-font-family, monospace);
      background-color: var(--vscode-textCodeBlock-background, #2D2D2D);
      padding: 2px 4px;
      border-radius: 3px;
    }
  </style>
</head>
<body>
  <div role="region" aria-label="Change Review Summary">
    <h1>Change Review: <code>${escapeHtml(review.changeId)}</code></h1>
    <div style="margin-bottom: 16px;">
      <span class="badge badge-verdict" role="status" aria-label="Safety Verdict">${escapeHtml(review.safetyVerdict)}</span>
      <span class="badge badge-status" role="status" aria-label="Review Status">${escapeHtml(review.status)}</span>
      <span style="margin-left: 12px; color: var(--vscode-descriptionForeground, #888888);">
        Revision: <code>${escapeHtml(review.analysisRevision)}</code> (${escapeHtml(review.freshness)})
      </span>
    </div>

    ${
      review.recoveryActions && review.recoveryActions.length > 0
        ? `<div class="${review.status === "checks_failed" ? "banner-error" : "banner-warning"}" role="alert">
        <strong>Action Required:</strong>
        <ul>
          ${review.recoveryActions.map((a) => `<li>${escapeHtml(a)}</li>`).join("")}
        </ul>
      </div>`
        : ""
    }

    <div class="section-card">
      <h2>Proposed / Applied Edits</h2>
      <p>Total Files: <strong>${review.editsSummary.totalFiles}</strong> | Total Edits: <strong>${review.editsSummary.totalEdits}</strong></p>
      <ul>
        ${review.editsSummary.files.map((f) => `<li><code>${escapeHtml(f)}</code></li>`).join("")}
      </ul>
    </div>

    <div class="section-card">
      <h2>Change Impact</h2>
      <p>Impacted Artifacts: <strong>${review.impactSummary.impactedCount}</strong> | Dynamic Unknowns: <strong>${review.impactSummary.dynamicUnknownsCount}</strong></p>
      ${
        review.impactSummary.dynamicUnknowns.length > 0
          ? `<h3>Dynamic Unknowns Requiring Manual Verification</h3>
             <ul>${review.impactSummary.dynamicUnknowns.map((du) => `<li>${escapeHtml(du)}</li>`).join("")}</ul>`
          : ""
      }
    </div>

    <div class="section-card">
      <h2>Checks Execution</h2>
      ${
        review.checksSummary
          ? `<p>Status: <strong>${escapeHtml(review.checksSummary.checkStatus)}</strong> | Compilation: <strong>${review.checksSummary.compilationSuccess ? "Passed" : "Failed"}</strong></p>
             <p>Tests: ${review.checksSummary.testsPassed} passed, ${review.checksSummary.testsFailed} failed (${review.checksSummary.testsTotal} total)</p>`
          : `<p><em>No scoped checks were executed for this change.</em></p>`
      }
    </div>

    ${
      review.uncheckedAssumptions && review.uncheckedAssumptions.length > 0
        ? `<div class="section-card">
        <h2>Unchecked Assumptions</h2>
        <ul>
          ${review.uncheckedAssumptions.map((ua) => `<li>${escapeHtml(ua)}</li>`).join("")}
        </ul>
      </div>`
        : ""
    }
  </div>
</body>
</html>`;
  }

  /**
   * Opens an accessible WebviewPanel presenting the review.
   */
  public static presentInWebview(
    review: ChangeReviewResponse,
    viewColumn: vscode.ViewColumn = vscode.ViewColumn.Beside
  ): vscode.WebviewPanel {
    const panel = vscode.window.createWebviewPanel(
      "grailsChangeReview",
      `Review: ${review.changeId}`,
      viewColumn,
      {
        enableScripts: false,
        retainContextWhenHidden: true,
      }
    );

    panel.webview.html = this.renderHtml(review);
    return panel;
  }

  private static formatStatusBadge(status: ChangeReviewStatus): string {
    switch (status) {
      case "ready":
        return "`READY`";
      case "stale_buffer":
        return "`STALE_BUFFER` (Buffer version advanced)";
      case "stale_dependency":
        return "`STALE_DEPENDENCY` (Dependencies dirty)";
      case "checks_failed":
        return "`CHECKS_FAILED` (Errors in scoped checks)";
      case "partial_edits":
        return "`PARTIAL_EDITS`";
      case "unverified":
        return "`UNVERIFIED`";
      default:
        return String(status);
    }
  }

  private static formatVerdictBadge(verdict: ChangeSafetyVerdict): string {
    switch (verdict) {
      case "SAFE_TO_APPLY":
        return "🟢 **SAFE_TO_APPLY**";
      case "REQUIRES_ATTENTION":
        return "🟡 **REQUIRES_ATTENTION**";
      case "UNSAFE_TO_APPLY":
        return "🔴 **UNSAFE_TO_APPLY**";
      case "STALE_REVIEW":
        return "🟠 **STALE_REVIEW**";
      default:
        return String(verdict);
    }
  }
}
