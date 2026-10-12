import * as assert from "node:assert/strict";
import * as test from "node:test";
import * as vscode from "vscode";
import { EventBus } from "../core/events/EventBus";
import { DashboardService } from "../features/dashboard/DashboardService";
import { DependencyGraphService } from "../features/dependency-graph/DependencyGraphService";
import { GormSqlPreviewService } from "../features/gorm-sql-preview/GormSqlPreviewService";
import { ProjectType, type ProjectInfo } from "../features/models/modelTypes";
import { ErrorService } from "../services/errors/ErrorService";
import { GradleService } from "../services/gradle/GradleService";
import { LanguageServerManager } from "../services/languageServer/LanguageServerManager";
import { ConfigurationService } from "../services/workspace/ConfigurationService";
import { ProjectService } from "../services/workspace/ProjectService";
import { StatusBarService } from "../services/workspace/StatusBarService";

const mockContext = {
  subscriptions: [] as vscode.Disposable[],
  extensionPath: "/mock/extension/path",
  extensionUri: vscode.Uri.file("/mock/extension/path"),
  globalState: {
    get: (_k: string) => undefined,
    update: () => Promise.resolve(),
  },
} as unknown as vscode.ExtensionContext;

const fixtureProject: ProjectInfo = {
  id: "proj-grails-main",
  name: "MyGrailsApp",
  rootPath: "/workspace/MyGrailsApp",
  type: ProjectType.Grails,
  dependencies: [
    { name: "grails-core", group: "org.grails", version: "6.2.0" },
    { name: "grails-web", group: "org.grails", version: "6.2.0" },
  ],
};

void test.describe("Views and Accessibility (R4-07)", () => {
  const errorService = new ErrorService();
  const statusBarService = new StatusBarService(mockContext);
  const configService = new ConfigurationService();
  const eventBus = EventBus.getInstance();

  void test.it("R4-07/1: Dashboard shows actual model state, origins, empty and degraded states without fabricated metrics", () => {
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    const gradleService = new GradleService(statusBarService, errorService, configService);
    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);

    const dashboardService = new DashboardService(
      mockContext,
      errorService,
      projectService,
      gradleService,
      lspManager
    );

    // 1. With actual project model
    const htmlWithProjects = dashboardService.getHtmlContent([fixtureProject]);
    assert.ok(htmlWithProjects.includes("MyGrailsApp"), "Dashboard displays project name");
    assert.ok(htmlWithProjects.includes("/workspace/MyGrailsApp"), "Dashboard displays project root path");
    assert.ok(htmlWithProjects.includes("Origin: Workspace Project Discovery"), "Discloses project discovery origin");
    assert.ok(htmlWithProjects.includes("Origin: Subsystem Lifecycle State"), "Discloses subsystem state origin");

    // Must NOT contain fictional/fabricated metrics
    assert.ok(!htmlWithProjects.includes("94%"), "No fabricated code coverage metric");
    assert.ok(!htmlWithProjects.includes("1.2s"), "No fabricated LSP indexing timing");
    assert.ok(!htmlWithProjects.includes(">24<"), "No fabricated 24 artifacts count");
    assert.ok(!htmlWithProjects.includes("24 Artifacts"), "No fabricated 24 artifacts label");

    // 2. Empty state when no projects detected
    const htmlNoProjects = dashboardService.getHtmlContent([]);
    assert.ok(htmlNoProjects.includes("No Grails Projects Detected"), "Displays empty state notification");
    assert.ok(htmlNoProjects.includes("Discover Projects"), "Provides action button for discovery");

    dashboardService.dispose();
  });

  void test.it("R4-07/1: Dependency graph discloses origin, configuration options, and handles disconnected/error states", () => {
    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);
    const depService = new DependencyGraphService(mockContext, errorService, lspManager);

    const html = depService.getHtmlContent(fixtureProject);
    assert.ok(html.includes("Origin: Resolved Gradle Classpath (via LSP)"), "Discloses origin as resolved Gradle classpath");
    assert.ok(html.includes("compileClasspath"), "Includes compileClasspath configuration");
    assert.ok(html.includes("runtimeClasspath"), "Includes runtimeClasspath configuration");
    assert.ok(html.includes("testCompileClasspath"), "Includes testCompileClasspath configuration");
    assert.ok(html.includes("id=\"statusBanner\""), "Includes status banner container for disconnected/error states");

    depService.dispose();
  });

  void test.it("R4-07/1: GORM SQL preview accurately labels static DDL approximation and discloses AST origin", () => {
    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);
    const sqlService = new GormSqlPreviewService(mockContext, errorService, lspManager);

    const targetUri = vscode.Uri.file("/workspace/MyGrailsApp/grails-app/domain/Book.groovy");
    const html = sqlService.getHtmlContent(targetUri);

    // Accurate static approximation labeling
    assert.ok(html.includes("Static DDL Approximation"), "Accurately labeled as Static DDL Approximation");
    assert.ok(
      html.includes("simulated DDL and is not executed against a live database"),
      "Explicitly notes simulated DDL AST origin without live DB execution"
    );
    assert.ok(html.includes("Origin: Static Domain AST Analysis"), "Discloses AST origin");
    assert.ok(html.includes("Book.groovy"), "Displays target entity file name");

    sqlService.dispose();
  });

  void test.it("R4-07/2: Restrictive Content-Security-Policy (CSP) and nonce protection enforced across all views", () => {
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    const gradleService = new GradleService(statusBarService, errorService, configService);
    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);

    const dashboardService = new DashboardService(mockContext, errorService, projectService, gradleService, lspManager);
    const depService = new DependencyGraphService(mockContext, errorService, lspManager);
    const sqlService = new GormSqlPreviewService(mockContext, errorService, lspManager);

    const dashHtml = dashboardService.getHtmlContent([fixtureProject]);
    const depHtml = depService.getHtmlContent(fixtureProject);
    const sqlHtml = sqlService.getHtmlContent(vscode.Uri.file("/workspace/Book.groovy"));

    for (const [name, html] of [
      ["Dashboard", dashHtml],
      ["Dependency Graph", depHtml],
      ["GORM SQL Preview", sqlHtml],
    ]) {
      assert.ok(html.includes("Content-Security-Policy"), `${name} includes Content-Security-Policy meta header`);
      assert.ok(html.includes("default-src 'none'"), `${name} enforces default-src 'none'`);
      assert.ok(html.includes("script-src 'nonce-"), `${name} enforces nonce-protected script execution`);
      assert.ok(html.includes("style-src"), `${name} restricts stylesheet execution`);
      assert.ok(!html.includes("onclick="), `${name} has zero inline onclick event handlers`);
      assert.ok(!html.includes("onchange="), `${name} has zero inline onchange event handlers`);
    }

    dashboardService.dispose();
    depService.dispose();
    sqlService.dispose();
  });

  void test.it("R4-07/2: Large dependency results support live filtering, capping at MAX_RENDER_NODES, and clean disposal", () => {
    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);
    const depService = new DependencyGraphService(mockContext, errorService, lspManager);

    const html = depService.getHtmlContent(fixtureProject);
    assert.ok(html.includes("id=\"filterInput\""), "Includes live text filter input");
    assert.ok(html.includes("MAX_RENDER_NODES = 250"), "Capped at bounded node maximum");
    assert.ok(html.includes("id=\"nodeCount\""), "Provides accessible node count badge");

    // Clean disposal
    depService.dispose();
    // Subsequent dispose is a safe no-op
    depService.dispose();
  });

  void test.it("R4-07/3: Accessibility features include accessible table view, screen-reader semantics, and reduced-motion", () => {
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    const gradleService = new GradleService(statusBarService, errorService, configService);
    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);

    const dashboardService = new DashboardService(mockContext, errorService, projectService, gradleService, lspManager);
    const depService = new DependencyGraphService(mockContext, errorService, lspManager);
    const sqlService = new GormSqlPreviewService(mockContext, errorService, lspManager);

    // Dependency Graph has switchable Accessible Table View
    const depHtml = depService.getHtmlContent(fixtureProject);
    assert.ok(depHtml.includes("id=\"table-view\""), "Includes accessible tabular view container");
    assert.ok(depHtml.includes("table id=\"depTable\""), "Includes semantic HTML table");
    assert.ok(depHtml.includes("th scope=\"col\""), "Includes scoped table header cells");
    assert.ok(depHtml.includes("id=\"toggleViewBtn\""), "Includes keyboard-accessible view toggle button");
    assert.ok(depHtml.includes("prefers-reduced-motion"), "Includes prefers-reduced-motion media query");

    // GORM SQL Preview has focusable pre block and copy button
    const sqlHtml = sqlService.getHtmlContent(vscode.Uri.file("/workspace/Book.groovy"));
    assert.ok(sqlHtml.includes("tabindex=\"0\""), "SQL pre output is focusable via keyboard");
    assert.ok(sqlHtml.includes("role=\"region\""), "SQL pre output has semantic region role");
    assert.ok(sqlHtml.includes("aria-label=\"SQL DDL Output\""), "SQL pre output provides accessible label");
    assert.ok(sqlHtml.includes("id=\"copyBtn\""), "Includes accessible Copy SQL button");

    // Dashboard cards have keyboard focus and ARIA attributes
    const dashHtml = dashboardService.getHtmlContent([fixtureProject]);
    assert.ok(dashHtml.includes("tabindex=\"0\""), "Dashboard cards are focusable via keyboard");
    assert.ok(dashHtml.includes("role=\"article\""), "Dashboard cards have article role");
    assert.ok(dashHtml.includes("aria-label=\"Project MyGrailsApp\""), "Dashboard cards have descriptive ARIA label");
    assert.ok(dashHtml.includes(":focus-visible"), "Styles provide visible focus rings");
    assert.ok(dashHtml.includes("forced-colors: active"), "Supports Windows High Contrast mode");

    dashboardService.dispose();
    depService.dispose();
    sqlService.dispose();
  });

  void test.it("R4-07/3: Views do not start heavy work on initialization or activation", () => {
    let tasksExecuted = 0;
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    const gradleService = new GradleService(statusBarService, errorService, configService);
    gradleService.runTask = () => {
      tasksExecuted++;
      return Promise.resolve(true);
    };

    const lspManager = new LanguageServerManager(mockContext, statusBarService, errorService, configService);

    // Instantiation must NOT trigger any Gradle builds or LSP requests
    const dashboardService = new DashboardService(mockContext, errorService, projectService, gradleService, lspManager);
    const depService = new DependencyGraphService(mockContext, errorService, lspManager);
    const sqlService = new GormSqlPreviewService(mockContext, errorService, lspManager);

    assert.strictEqual(tasksExecuted, 0, "No background tasks or builds executed during view instantiation");

    dashboardService.dispose();
    depService.dispose();
    sqlService.dispose();
  });
});
