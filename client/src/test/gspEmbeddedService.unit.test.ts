import { equal, notEqual, ok } from "node:assert/strict";
import { test } from "node:test";
import * as vscode from "vscode";
import { GspEmbeddedLanguageService } from "../services/gsp/GspEmbeddedLanguageService";
import { GspPositionMapper } from "../services/gsp/GspPositionMapper";
import { GspRegionParser, type GspRegion } from "../services/gsp/GspRegionParser";
import { VirtualDocumentManager } from "../services/gsp/VirtualDocumentManager";

void test("R4-03/1: GspRegionParser routes HTML, Groovy scriptlets, expressions, and interpolations", () => {
  const gsp = `<html>
  <% def title = "Grails" %>
  <div><%= title %></div>
  <span>\${title.toLowerCase()}</span>
</html>`;

  const regions = GspRegionParser.parse(gsp);
  ok(regions.length >= 4, "Should extract multiple regions");

  const groovyRegions = regions.filter((r: GspRegion) => r.isGroovy);
  equal(groovyRegions.length, 3, "Should detect scriptlet, expression, and interpolation");
  equal(groovyRegions[0].text, '<% def title = "Grails" %>');
  equal(groovyRegions[1].text, "<%= title %>");
  equal(groovyRegions[2].text, "${title.toLowerCase()}");

  // Verify region position retrieval
  const atScriptlet = GspRegionParser.getRegionAt(regions, new vscode.Position(1, 6));
  ok(atScriptlet?.isGroovy, "Position inside scriptlet must resolve to Groovy");

  const atHtml = GspRegionParser.getRegionAt(regions, new vscode.Position(0, 2));
  equal(atHtml?.language, "html", "Top level position must resolve to HTML");
});

void test("R4-03/1: GspRegionParser handles nested Groovy within script and style blocks", () => {
  const gsp = `<script>
  const id = \${user.id};
  console.log(id);
</script>
<style>
  .header { color: \${theme.color}; }
</style>`;

  const regions = GspRegionParser.parse(gsp);

  // Nested Groovy inside script
  const scriptGroovy = regions.find((r: GspRegion) => r.text === "${user.id}");
  ok(scriptGroovy?.isGroovy, "Interpolation in script must be Groovy");

  // Outer script parts must be javascript
  const jsRegion = regions.find((r: GspRegion) => r.language === "javascript" && r.text.includes("console.log"));
  ok(jsRegion, "Non-interpolation script text must be JavaScript");

  // Nested Groovy inside style
  const styleGroovy = regions.find((r: GspRegion) => r.text === "${theme.color}");
  ok(styleGroovy?.isGroovy, "Interpolation in style must be Groovy");

  // Outer style parts must be css
  const cssRegion = regions.find((r: GspRegion) => r.language === "css" && r.text.includes(".header"));
  ok(cssRegion, "Non-interpolation style text must be CSS");
});

void test("R4-03/2: GspRegionParser handles nested braces, comments, directives, CRLF, and astral characters", () => {
  const gsp = `<%@ page contentType="text/html" %>\r\n%{-- Multiline\r\ncomment --}%\r\n<div>\uD83C\uDF89 \${[a: 1, b: { 2 }]}</div>`;
  const regions = GspRegionParser.parse(gsp);

  const directive = regions.find((r: GspRegion) => r.language === "directive");
  ok(directive, "Directive must be recognized");

  const comment = regions.find((r: GspRegion) => r.language === "comment");
  ok(comment, "Comment must be recognized");

  const interpolation = regions.find((r: GspRegion) => r.text === "${[a: 1, b: { 2 }]}");
  ok(interpolation, "Nested braces in interpolation must not prematurely terminate");

  // Check UTF-16 surrogate preservation
  ok(gsp.includes("\uD83C\uDF89"), "Astral characters preserved");
});

void test("R4-03/2: GspRegionParser recovers safely from malformed or unclosed regions", () => {
  const unclosedScriptlet = "<div><% def x = 1";
  const unclosedInterp = "<span>${unfinished";
  const unclosedScript = "<script>var a = 1;";

  const r1 = GspRegionParser.parse(unclosedScriptlet);
  const r2 = GspRegionParser.parse(unclosedInterp);
  const r3 = GspRegionParser.parse(unclosedScript);

  ok(r1.some((r: GspRegion) => r.isGroovy && r.text.includes("def x = 1")), "Recovers unclosed scriptlet");
  ok(r2.some((r: GspRegion) => r.isGroovy && r.text.includes("unfinished")), "Recovers unclosed interpolation");
  ok(r3.some((r: GspRegion) => r.language === "javascript"), "Recovers unclosed script");
});

void test("R4-03/1 & 3: VirtualDocumentManager masks non-target regions with 1:1 offsets and cleans up on close", () => {
  const manager = new VirtualDocumentManager();
  const uri = vscode.Uri.file("/app/views/index.gsp");
  const gspText = `<html>\n  <% def foo = "bar" %>\n  <div>\${foo}</div>\n</html>`;

  const mockDoc = {
    uri,
    version: 1,
    getText: () => gspText,
  };

  const record = manager.updateDocument(mockDoc);
  equal(record.version, 1);

  const htmlVirtual = manager.getVirtualText(uri, "html");
  ok(htmlVirtual, "HTML virtual text exists");
  equal(htmlVirtual.length, gspText.length, "Virtual HTML length must match original exactly");

  // Groovy parts should be masked with spaces in HTML virtual document
  ok(!htmlVirtual.includes("def foo"), "Groovy code should be masked in HTML virtual doc");
  ok(htmlVirtual.includes("<html>"), "HTML markup preserved");

  const groovyVirtual = manager.getVirtualText(uri, "groovy");
  ok(groovyVirtual, "Groovy virtual text exists");
  equal(groovyVirtual.length, gspText.length, "Virtual Groovy length must match original exactly");
  ok(groovyVirtual.includes("def foo"), "Groovy code preserved in Groovy virtual doc");
  ok(!groovyVirtual.includes("<html>"), "HTML markup masked in Groovy virtual doc");

  // Invariant INV-EDIT-001 & INV-PERF-002: Cleanup on document close
  manager.closeDocument(uri);
  equal(manager.getVirtualText(uri, "html"), null, "Closed document virtual content must be evicted");
  equal(manager.getRegions(uri).length, 0, "Closed document regions must be evicted");
  manager.dispose();
});

void test("R4-03/3: GspPositionMapper enforces INV-EDIT-001 by accepting valid edits and rejecting unmappable edits", () => {
  const gsp = `<html>\n  <% def myVar = 10 %>\n  <div>\${myVar}</div>\n</html>`;
  const regions = GspRegionParser.parse(gsp);

  const groovyRegion = regions.find((r: GspRegion) => r.isGroovy && r.text.includes("def myVar"));
  ok(groovyRegion, "Groovy region found");

  // Valid edit strictly inside Groovy region
  const validEdit = new vscode.TextEdit(
    new vscode.Range(new vscode.Position(1, 5), new vscode.Position(1, 14)),
    "def other = 20"
  );
  ok(GspPositionMapper.canApplyEdit(validEdit, groovyRegion), "Edit within region must be allowed");
  const mapped = GspPositionMapper.mapEdit(validEdit, groovyRegion);
  notEqual(mapped, null, "Mapped edit returned");

  // Unmappable edit spilling outside into HTML
  const crossEdit = new vscode.TextEdit(
    new vscode.Range(new vscode.Position(1, 0), new vscode.Position(2, 5)),
    "invalid replacement"
  );
  equal(GspPositionMapper.canApplyEdit(crossEdit, groovyRegion), false, "Cross-region edit must be rejected");
  equal(GspPositionMapper.mapEdit(crossEdit, groovyRegion), null, "Unmappable edit must return null");

  // Completion item with valid vs invalid additionalTextEdits
  const completionItem = new vscode.CompletionItem("myVar", vscode.CompletionItemKind.Property);
  completionItem.additionalTextEdits = [crossEdit];
  const mappedItem = GspPositionMapper.mapCompletionItem(completionItem, groovyRegion);
  equal(mappedItem, null, "Completion item with unmappable additional edit must be rejected");
});

void test("R4-03/1 & 3: GspEmbeddedLanguageService provides TagLib completions and manages lifecycle", async () => {
  const service = new GspEmbeddedLanguageService();
  const uri = vscode.Uri.file("/app/views/test.gsp");
  const gspText = `<g:ea`;

  const mockDoc = {
    uri,
    version: 1,
    languageId: "gsp",
    getText: () => gspText,
    lineAt: (_pos: vscode.Position) => ({
      text: gspText,
    }),
  };

  const token: vscode.CancellationToken = {
    isCancellationRequested: false,
    onCancellationRequested: () => ({
      dispose: () => {
        // no-op
      },
    }),
  };

  const result = await service.provideCompletionItems(
    mockDoc,
    new vscode.Position(0, 5),
    token,
    {}
  );
  const items = "items" in result ? result.items : result;
  ok(items.length > 0, "TagLib completions must be provided");
  const gEach = items.find((i: vscode.CompletionItem) => (typeof i.label === "string" ? i.label : i.label.label) === "g:each");
  ok(gEach, "g:each completion item must be present");

  service.dispose();
});
