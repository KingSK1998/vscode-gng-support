import { equal, ok } from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { after, before, test } from "node:test";
import type { ExtensionContext, Uri } from "vscode";
import { TreeItemCollapsibleState } from "vscode";
import { ServiceContainer } from "../core/container/ServiceContainer";
import { ArtifactType, ProjectType, type ProjectInfo } from "../features/models/modelTypes";
import { GrailsTreeExplorer } from "../ui/treeExplorer/GrailsTreeExplorer";
import { GrailsTreeItem } from "../ui/treeExplorer/GrailsTreeItem";
import { TreeItemKind } from "../ui/treeExplorer/TreeItemKind";

class TestGrailsTreeExplorer extends GrailsTreeExplorer {
  public setTestProjects(projects: ProjectInfo[]): void {
    this.projects = [...projects];
    this._onDidChangeTreeData.fire();
  }
}

function createTestProject(
  id: string,
  name: string,
  rootPath: string,
  type: ProjectType = ProjectType.Grails,
  extra: Partial<ProjectInfo> = {}
): ProjectInfo {
  return {
    id,
    name,
    rootPath,
    type,
    dependencies: [],
    ...extra,
  };
}

let tempDir: string;
let projADir: string;
let projBDir: string;
let explorer: TestGrailsTreeExplorer;

before(() => {
  const mockContext = {
    subscriptions: [],
    extensionUri: { fsPath: "" },
  } as unknown as ExtensionContext;

  ServiceContainer.intialize(mockContext);

  tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "grails-tree-test-"));
  projADir = path.join(tempDir, "projectA");
  projBDir = path.join(tempDir, "projectB");

  fs.mkdirSync(projADir, { recursive: true });
  fs.mkdirSync(projBDir, { recursive: true });

  // Project A Structure:
  // - grails-app/controllers/com/example/app/BookController.groovy
  // - grails-app/controllers/com/example/app/BookInterceptor.groovy
  // - grails-app/services/ (empty)
  // - grails-app/conf/application.yml
  // - build/generated/sources/annotation/GeneratedMeta.java
  // - src/main/groovy/com/example/app/Util.groovy
  // - src/test/groovy/com/example/app/BookSpec.groovy

  const pAControllers = path.join(projADir, "grails-app", "controllers", "com", "example", "app");
  fs.mkdirSync(pAControllers, { recursive: true });
  fs.writeFileSync(path.join(pAControllers, "BookController.groovy"), "class BookController {}");
  fs.writeFileSync(path.join(pAControllers, "BookInterceptor.groovy"), "class BookInterceptor {}");

  const pAServices = path.join(projADir, "grails-app", "services");
  fs.mkdirSync(pAServices, { recursive: true }); // empty directory

  const pAConf = path.join(projADir, "grails-app", "conf");
  fs.mkdirSync(pAConf, { recursive: true });
  fs.writeFileSync(path.join(pAConf, "application.yml"), "grails: { app: { name: 'appA' } }");

  const pAGen = path.join(projADir, "build", "generated", "sources", "annotation");
  fs.mkdirSync(pAGen, { recursive: true });
  fs.writeFileSync(path.join(pAGen, "GeneratedMeta.java"), "public class GeneratedMeta {}");

  const pASrc = path.join(projADir, "src", "main", "groovy", "com", "example", "app");
  fs.mkdirSync(pASrc, { recursive: true });
  fs.writeFileSync(path.join(pASrc, "Util.groovy"), "class Util {}");

  const pATest = path.join(projADir, "src", "test", "groovy", "com", "example", "app");
  fs.mkdirSync(pATest, { recursive: true });
  fs.writeFileSync(path.join(pATest, "BookSpec.groovy"), "class BookSpec {}");

  // Project B Structure:
  // - grails-app/controllers/com/example/plugin/PluginController.groovy
  const pBControllers = path.join(projBDir, "grails-app", "controllers", "com", "example", "plugin");
  fs.mkdirSync(pBControllers, { recursive: true });
  fs.writeFileSync(path.join(pBControllers, "PluginController.groovy"), "class PluginController {}");

  explorer = new TestGrailsTreeExplorer(mockContext);
});

after(() => {
  explorer.dispose();
  try {
    fs.rmSync(tempDir, { recursive: true, force: true });
  } catch {
    // Ignore cleanup error
  }
});

void test("R4-05: Multi-module scoped identities and project root separation", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir, ProjectType.Grails, {
    grailsVersion: "7.0.0",
  });
  const projectB = createTestProject("proj-b", "projectB", projBDir, ProjectType.GrailsPlugin, {
    pluginVersion: "1.0.0",
  });

  explorer.setTestProjects([projectA, projectB]);

  const rootItems = await explorer.getChildren();
  equal(rootItems.length, 2, "Should return 2 project nodes for multi-module workspace");

  const [itemA, itemB] = rootItems;
  equal(itemA.kind, TreeItemKind.ProjectRoot);
  equal(itemB.kind, TreeItemKind.ProjectRoot);

  ok(itemA.id?.startsWith(projADir), "Project A root item id must be scoped with projectA path");
  ok(itemB.id?.startsWith(projBDir), "Project B root item id must be scoped with projectB path");
  ok(itemA.id !== itemB.id, "Scoped ids must not collide across projects");

  // Verify accessibility label
  ok(itemA.accessibilityInformation?.label.includes("projectA"));
  ok(itemB.accessibilityInformation?.label.includes("projectB"));
});

void test("R4-05: Lazy child resolution at project root", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir, ProjectType.Grails, {
    grailsVersion: "7.0.0",
  });

  const projectNode = new GrailsTreeItem(
    "projectA",
    TreeItemCollapsibleState.Expanded,
    TreeItemKind.ProjectRoot,
    { command: "grails.selectProject", title: "Select", arguments: [projectA] },
    projectA,
    projADir
  );

  const containers = await explorer.getChildren(projectNode);
  ok(containers.length > 0, "Should return container nodes");

  const kinds = containers.map(c => c.kind);
  ok(kinds.includes(TreeItemKind.GrailsAppRoot), "Must include Grails Artefacts container");
  ok(kinds.includes(TreeItemKind.ConfigRoot), "Must include Configuration container");
  ok(kinds.includes(TreeItemKind.GeneratedRoot), "Must include Generated Sources container");
  ok(kinds.includes(TreeItemKind.SrcRoot), "Must include Sources container");
  ok(kinds.includes(TreeItemKind.TestsRoot), "Must include Tests container");

  // Verify scoped ID on containers
  for (const c of containers) {
    ok(c.id?.startsWith(projADir), `Container ${c.label} id must be scoped to project path`);
  }
});

void test("R4-05: Generated sources are marked (generated, read-only) with accessible label", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir);

  const genBuild = path.join(projADir, "build", "generated");
  const genRoot = new GrailsTreeItem(
    "Generated Sources",
    TreeItemCollapsibleState.Collapsed,
    TreeItemKind.GeneratedRoot,
    { command: "grails.noAction", title: "No Action" },
    projectA,
    genBuild
  );

  equal(genRoot.description, "(generated, read-only)");
  ok(genRoot.accessibilityInformation?.label.includes("generated read-only"));

  const genChildren = await explorer.getChildren(genRoot);
  ok(genChildren.length > 0, "Should lazily load generated children");

  const firstGenDir = genChildren[0];
  equal(firstGenDir.description, "(generated, read-only)");
  ok(firstGenDir.accessibilityInformation?.label.includes("generated read-only"));
});

void test("R4-05: Lazy artifact package compacting and convention filtering", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir);

  // Expand Controllers category
  const controllersFolder = path.join(projADir, "grails-app", "controllers");
  const controllersNode = new GrailsTreeItem(
    "Controllers",
    TreeItemCollapsibleState.Collapsed,
    TreeItemKind.GrailsArtifactFolders,
    { command: "grails.noAction", title: "No Action" },
    projectA,
    controllersFolder,
    ArtifactType.Controller
  );

  const controllerPackages = await explorer.getChildren(controllersNode);
  equal(controllerPackages.length, 1, "Should compact com.example.app into a single package node");

  const pkgNode = controllerPackages[0];
  equal(pkgNode.label, "com.example.app");
  equal(pkgNode.kind, TreeItemKind.GrailsArtifactFolders);

  // Expand the package node
  const pkgChildren = await explorer.getChildren(pkgNode);
  equal(pkgChildren.length, 1, "Should filter only BookController.groovy under Controllers (excluding Interceptor)");

  const fileItem = pkgChildren[0];
  equal(fileItem.label, "BookController.groovy");
  equal(fileItem.kind, TreeItemKind.ArtifactFile);
  equal(fileItem.collapsibleState, TreeItemCollapsibleState.None);
  ok(fileItem.command, "File item must have a navigation command");
  equal(fileItem.command.command, "vscode.open");

  const fileUri = (fileItem.command.arguments?.[0] as Uri).fsPath;
  ok(fileUri.endsWith("BookController.groovy"));
});

void test("R4-05: Empty directory resolves to (Empty) state item", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir);

  const servicesFolder = path.join(projADir, "grails-app", "services");
  const servicesNode = new GrailsTreeItem(
    "Services",
    TreeItemCollapsibleState.Collapsed,
    TreeItemKind.GrailsArtifactFolders,
    { command: "grails.noAction", title: "No Action" },
    projectA,
    servicesFolder,
    ArtifactType.Service
  );

  const children = await explorer.getChildren(servicesNode);
  equal(children.length, 1);
  equal(children[0].kind, TreeItemKind.Empty);
  equal(children[0].label, "(Empty)");
});

void test("R4-05: Missing directory resolves to recoverable state item", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir);

  const missingFolder = path.join(projADir, "grails-app", "nonexistent");
  const node = new GrailsTreeItem(
    "Missing",
    TreeItemCollapsibleState.Collapsed,
    TreeItemKind.GrailsArtifactFolders,
    { command: "grails.noAction", title: "No Action" },
    projectA,
    missingFolder,
    ArtifactType.Domain
  );

  const children = await explorer.getChildren(node);
  equal(children.length, 1);
  equal(children[0].kind, TreeItemKind.Empty);
  equal(children[0].label, "(Folder not found)");
});

void test("R4-05: Bounded work caps large directory at 300 with truncation node", async () => {
  const projectA = createTestProject("proj-a", "projectA", projADir);

  const largeDir = path.join(tempDir, "large_dir");
  fs.mkdirSync(largeDir, { recursive: true });
  for (let i = 0; i < 305; i++) {
    fs.writeFileSync(path.join(largeDir, `file_${String(i).padStart(3, "0")}.groovy`), "class Dummy {}");
  }

  const largeNode = new GrailsTreeItem(
    "Large Folder",
    TreeItemCollapsibleState.Collapsed,
    TreeItemKind.SourceDir,
    { command: "grails.noAction", title: "No Action" },
    projectA,
    largeDir
  );

  const children = await explorer.getChildren(largeNode);
  equal(children.length, 301, "Must be capped at 300 items plus 1 truncation indicator");

  const lastItem = children[children.length - 1];
  equal(lastItem.kind, TreeItemKind.Empty);
  ok(lastItem.label.includes("... and 5 more items"), "Truncation item should state remaining count");
});
