import fs from "fs";
import path from "path";
import type { Command, Disposable, ExtensionContext, TreeDataProvider, TreeItem } from "vscode";
import { EventEmitter, TreeItemCollapsibleState, Uri } from "vscode";
import { ServiceContainer } from "../../core/container/ServiceContainer";
import { EventBus } from "../../core/events/EventBus";
import { EventType } from "../../core/events/eventTypes";
import type { ProjectInfo } from "../../features/models/modelTypes";
import { ArtifactType, ProjectType } from "../../features/models/modelTypes";
import { ProjectMapper } from "../../shared/ProjectMapper";
import { GrailsTreeItem } from "./GrailsTreeItem";
import { TreeItemKind } from "./TreeItemKind";

const NO_ACTION_COMMAND: Command = { command: "grails.noAction", title: "No Action" };

/** Grails-specific tree explorer using utilities and IconProvider */
export class GrailsTreeExplorer implements TreeDataProvider<GrailsTreeItem> {
  protected readonly _onDidChangeTreeData = new EventEmitter<
    GrailsTreeItem | undefined | null | void
  >();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  protected container: ServiceContainer = ServiceContainer.getInstance();
  protected projects: ProjectInfo[] = [];
  protected disposables: Disposable[] = [];

  constructor(protected readonly context: ExtensionContext) {
    console.log("🌳 [GRAILS-TREE] initializing...");
    this.registerEventListeners();
    this.refresh();
  }

  /** Refresh the entire tree */
  refresh(): void {
    this.projects = [...this.container.projectService.getProjects()];
    this._onDidChangeTreeData.fire();
  }

  /** Get tree item for display */
  getTreeItem(element: GrailsTreeItem): TreeItem {
    return element;
  }

  async getChildren(element?: GrailsTreeItem): Promise<GrailsTreeItem[]> {
    // Root level - show Grails projects only
    if (!element) {
      return Promise.resolve(this.getProjectNodes(this.projects));
    }

    if (!element.projectInfo) {
      console.debug("🌳 [GRAILS-TREE] getChildren: no projectInfo", element.label);
      return Promise.resolve([]);
    }

    console.debug(`🌳 [GRAILS-TREE] getChildren: ${element.kind}`);

    switch (element.kind) {
      case TreeItemKind.ProjectRoot:
        return Promise.resolve(this.getRootContainers(element.projectInfo));

      case TreeItemKind.GrailsAppRoot:
        return Promise.resolve(this.getGrailsArtefactCategories(element.projectInfo));

      case TreeItemKind.GrailsArtifactFolders:
        return Promise.resolve(this.getArtifactFolderChildren(element));

      case TreeItemKind.ConfigRoot:
      case TreeItemKind.ConfFolder:
        return Promise.resolve(this.getConfFolderChildren(element));

      case TreeItemKind.I18nRoot:
        return Promise.resolve(this.getI18nFolderChildren(element));

      case TreeItemKind.ViewsRoot:
      case TreeItemKind.ViewsFolder:
        return Promise.resolve(this.getViewFolderChildren(element));

      case TreeItemKind.AssetsRoot:
      case TreeItemKind.AssetsFolder:
        return Promise.resolve(this.getAssetFolderChildren(element));

      case TreeItemKind.RoutesRoot:
        return Promise.resolve(this.getRouteList(element.projectInfo));

      case TreeItemKind.SrcRoot:
      case TreeItemKind.SourceDir:
        return Promise.resolve(this.getSourceDirChildren(element));

      case TreeItemKind.TestsRoot:
      case TreeItemKind.TestDir:
        return Promise.resolve(this.getTestDirChildren(element));

      case TreeItemKind.GeneratedRoot:
      case TreeItemKind.GeneratedDir:
        return Promise.resolve(this.getGeneratedDirChildren(element));

      case TreeItemKind.InitRoot:
        return Promise.resolve(this.getInitFolderChildren(element));

      case TreeItemKind.DependenciesRoot:
        return Promise.resolve(this.getDependencyCategories(element.projectInfo));

      case TreeItemKind.DEPENDENCY_CATEGORY:
        return Promise.resolve(this.getDependencyList(element.projectInfo, element.label));

      default:
        console.log(`⚠️ [GRAILS-TREE] Unknown TreeItemKind: ${element.kind}`);
        return Promise.resolve([]);
    }
  }

  /** Get project nodes */
  protected getProjectNodes(projects: ProjectInfo[]): GrailsTreeItem[] {
    if (!projects?.length) {
      console.debug("[GRAILS-TREE] getProjectNodes: no projects found");
      return [];
    }

    const items = projects
      .filter(project => {
        if (!project?.rootPath || !project?.name) {
          console.debug(
            "[GRAILS-TREE] getProjectNodes: skipping invalid project",
            project?.name || "unnamed"
          );
          return false;
        }
        return true;
      })
      .map(
        project =>
          new GrailsTreeItem(
            this.getProjectLabel(project),
            TreeItemCollapsibleState.Expanded,
            TreeItemKind.ProjectRoot,
            {
              command: "grails.selectProject",
              title: "Select Project",
              arguments: [project],
            },
            project,
            project.rootPath
          )
      );

    console.log(`[GRAILS-TREE] getProjectNodes: created ${items.length} project nodes`);
    return items;
  }

  /** Generate project label with version info */
  private getProjectLabel(project: ProjectInfo): string {
    const typeLabel =
      project.type === ProjectType.GrailsPlugin
        ? "Plugin"
        : project.type === ProjectType.Grails
          ? "Grails"
          : "Groovy";

    const version = project.grailsVersion ?? project.pluginVersion;
    return version ? `${project.name} (${typeLabel} ${version})` : `${project.name} (${typeLabel})`;
  }

  /** Get grails-app sections */
  private getRootContainers(project: ProjectInfo): GrailsTreeItem[] {
    const items: GrailsTreeItem[] = [];

    // 1. Grails Artefacts (only if grails-app exists)
    if (this.hasGrailsApp(project)) {
      items.push(
        new GrailsTreeItem(
          "Grails Artefacts",
          TreeItemCollapsibleState.Expanded,
          TreeItemKind.GrailsAppRoot,
          NO_ACTION_COMMAND,
          project,
          path.join(project.rootPath, "grails-app")
        )
      );
    }

    // 2. Configuration (only if conf folder exists)
    if (this.hasConfigFolder(project)) {
      items.push(
        new GrailsTreeItem(
          "Configuration",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.ConfigRoot,
          NO_ACTION_COMMAND,
          project,
          path.join(project.rootPath, "grails-app", "conf")
        )
      );
    }

    const i18nFolder = path.join(project.rootPath, "grails-app", "i18n");
    if (this.folderExists(i18nFolder)) {
      items.push(
        new GrailsTreeItem(
          "Internationalization",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.I18nRoot,
          NO_ACTION_COMMAND,
          project,
          i18nFolder
        )
      );
    }

    const assetsFolder = path.join(project.rootPath, "grails-app", "assets");
    if (this.folderExists(assetsFolder)) {
      items.push(
        new GrailsTreeItem(
          "Assets",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.AssetsRoot,
          NO_ACTION_COMMAND,
          project,
          assetsFolder
        )
      );
    }

    // 3. Views (only if views folder exists)
    if (this.hasViewsFolder(project)) {
      items.push(
        new GrailsTreeItem(
          "Views",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.ViewsRoot,
          NO_ACTION_COMMAND,
          project,
          path.join(project.rootPath, "grails-app", "views")
        )
      );
    }

    // 4. Routes (Virtual - for Grails projects)
    if (this.isGrailsProject(project)) {
      items.push(
        new GrailsTreeItem(
          "Routes",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.RoutesRoot,
          NO_ACTION_COMMAND,
          project
        )
      );
    }

    // 5. Sources (main or src)
    const srcMainFolder = path.join(project.rootPath, "src", "main");
    const srcFolder = path.join(project.rootPath, "src");
    if (this.folderExists(srcMainFolder)) {
      items.push(
        new GrailsTreeItem(
          "Sources",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.SrcRoot,
          NO_ACTION_COMMAND,
          project,
          srcMainFolder
        )
      );
    } else if (this.folderExists(srcFolder)) {
      items.push(
        new GrailsTreeItem(
          "Sources",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.SrcRoot,
          NO_ACTION_COMMAND,
          project,
          srcFolder
        )
      );
    }

    // 6. Tests (src/test or test)
    const testFolder = path.join(project.rootPath, "src", "test");
    const testRootFolder = path.join(project.rootPath, "test");
    if (this.folderExists(testFolder)) {
      items.push(
        new GrailsTreeItem(
          "Tests",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.TestsRoot,
          NO_ACTION_COMMAND,
          project,
          testFolder
        )
      );
    } else if (this.folderExists(testRootFolder)) {
      items.push(
        new GrailsTreeItem(
          "Tests",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.TestsRoot,
          NO_ACTION_COMMAND,
          project,
          testRootFolder
        )
      );
    }

    // 7. Init (Startup)
    const intiFolder = path.join(project.rootPath, "grails-app", "init");
    if (this.folderExists(intiFolder)) {
      items.push(
        new GrailsTreeItem(
          "Init (Startup)",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.InitRoot,
          NO_ACTION_COMMAND,
          project,
          intiFolder
        )
      );
    }

    // 8. Generated Sources (build/generated/sources or build/generated)
    const genSourcesFolder = path.join(project.rootPath, "build", "generated", "sources");
    const genBuildFolder = path.join(project.rootPath, "build", "generated");
    if (this.folderExists(genSourcesFolder)) {
      items.push(
        new GrailsTreeItem(
          "Generated Sources",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.GeneratedRoot,
          NO_ACTION_COMMAND,
          project,
          genSourcesFolder
        )
      );
    } else if (this.folderExists(genBuildFolder)) {
      items.push(
        new GrailsTreeItem(
          "Generated Sources",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.GeneratedRoot,
          NO_ACTION_COMMAND,
          project,
          genBuildFolder
        )
      );
    }

    // 9. Dependencies (parsed from build.gradle or model)
    const buildGradle = path.join(project.rootPath, "build.gradle");
    if (fs.existsSync(buildGradle) || (project.dependencies && project.dependencies.length > 0)) {
      items.push(
        new GrailsTreeItem(
          "Dependencies",
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.DependenciesRoot,
          NO_ACTION_COMMAND,
          project,
          buildGradle
        )
      );
    }

    return items;
  }

  /** Get grails-app specific structure */
  private getGrailsArtefactCategories(project: ProjectInfo): GrailsTreeItem[] {
    const items: GrailsTreeItem[] = [];

    // Assets (inside grails-app)
    // if (this.hasAssetsFolder(project)) {
    //   const assetCount = this.getAssetCount(project);
    //   items.push(
    //     new GrailsTreeItem(
    //       `Assets (${assetCount})`,
    //       TreeItemCollapsibleState.Collapsed,
    //       TreeItemKind.AssetsRoot,
    //       NO_ACTION_COMMAND,
    //       project,
    //       path.join(project.rootPath, "grails-app", "assets")
    //     )
    //   );
    // }

    const artefactCategories = [
      {
        label: "Controllers",
        type: ArtifactType.Controller,
        folder: "controllers",
      },
      {
        label: "Services",
        type: ArtifactType.Service,
        folder: "services",
      },
      {
        label: "Domains",
        type: ArtifactType.Domain,
        folder: "domain",
      },
      {
        label: "Tag Libraries",
        type: ArtifactType.TagLib,
        folder: "taglib",
      },
      {
        label: "Interceptors",
        type: ArtifactType.Interceptor,
        folder: "controllers",
      },
      {
        label: "Jobs",
        type: ArtifactType.Job,
        folder: "jobs",
      },
      {
        label: "Commands",
        type: ArtifactType.Command,
        folder: "controllers",
      },
      {
        label: "Utils",
        type: ArtifactType.Utils,
        folder: "utils",
      },
    ];

    // Only show categories that exist
    for (const category of artefactCategories) {
      const categoryPath = path.join(project.rootPath, "grails-app", category.folder);

      if (this.folderExists(categoryPath)) {
        const count = this.getArtefactCount(categoryPath, category.type);

        const displayLabel = count > 0 ? `${category.label} (${count})` : category.label;

        items.push(
          new GrailsTreeItem(
            displayLabel,
            TreeItemCollapsibleState.Collapsed,
            TreeItemKind.GrailsArtifactFolders,
            NO_ACTION_COMMAND,
            project,
            categoryPath,
            category.type
          )
        );
      }
    }

    return items;
  }


  private getRouteList(project: ProjectInfo): GrailsTreeItem[] {
    // Parse UrlMappings.groovy if it exists
    const urlMappingsPath = path.join(project.rootPath, "grails-app", "conf", "UrlMappings.groovy");

    if (!fs.existsSync(urlMappingsPath)) {
      return [
        new GrailsTreeItem(
          "❌ No URL mappings found",
          TreeItemCollapsibleState.None,
          TreeItemKind.Error,
          NO_ACTION_COMMAND,
          project
        ),
      ];
    }

    try {
      // Simple route parsing (could be enhanced with AST)
      const content = fs.readFileSync(urlMappingsPath, "utf8");
      const routes = this.parseSimpleRoutes(content);

      if (routes.length === 0) {
        return [
          new GrailsTreeItem(
            "Default routing active",
            TreeItemCollapsibleState.None,
            TreeItemKind.Route,
            NO_ACTION_COMMAND,
            project
          ),
        ];
      }

      return routes.map(
        route =>
          new GrailsTreeItem(
            `${route.method} ${route.pattern} → ${route.controller}#${route.action}`,
            TreeItemCollapsibleState.None,
            TreeItemKind.Route,
            {
              command: "grails.goToController",
              title: "Go to Controller",
              arguments: [route.controller, route.action],
            },
            project
          )
      );
    } catch (error) {
      console.warn(`Error parsing UrlMappings.groovy:`, error);
      return [
        new GrailsTreeItem(
          "❌ Error parsing URL mappings",
          TreeItemCollapsibleState.None,
          TreeItemKind.Error,
          NO_ACTION_COMMAND,
          project
        ),
      ];
    }
  }

  private parseSimpleRoutes(
    _content: string
  ): { method: string; pattern: string; controller: string; action: string }[] {
    // Simple regex-based parsing - could be enhanced with proper AST parsing
    const routes: { method: string; pattern: string; controller: string; action: string }[] = [];

    // Look for common patterns

    // Default Grails pattern
    routes.push({
      method: "ALL",
      pattern: "/$controller/$action?/$id?",
      controller: "default",
      action: "index",
    });

    return routes;
  }


  private getDependencyCategories(project: ProjectInfo): GrailsTreeItem[] {
    const items: GrailsTreeItem[] = [];

    // Parse build.gradle for dependencies
    const buildGradlePath = path.join(project.rootPath, "build.gradle");

    if (!fs.existsSync(buildGradlePath)) {
      return [
        new GrailsTreeItem(
          "❌ No build.gradle found",
          TreeItemCollapsibleState.None,
          TreeItemKind.Error,
          NO_ACTION_COMMAND,
          project
        ),
      ];
    }

    try {
      const dependencies = this.parseBuildGradleDependencies(buildGradlePath);

      // Group by type
      const plugins = dependencies.filter(dep => dep.type === "plugin");
      const libraries = dependencies.filter(
        dep => dep.type === "implementation" || dep.type === "compile" || dep.type === "runtime"
      );

      if (plugins.length > 0) {
        items.push(
          new GrailsTreeItem(
            `Grails Plugins (${plugins.length})`,
            TreeItemCollapsibleState.Collapsed,
            TreeItemKind.DEPENDENCY_CATEGORY,
            NO_ACTION_COMMAND,
            project
          )
        );
      }

      if (libraries.length > 0) {
        items.push(
          new GrailsTreeItem(
            `External Libraries (${libraries.length})`,
            TreeItemCollapsibleState.Collapsed,
            TreeItemKind.DEPENDENCY_CATEGORY,
            NO_ACTION_COMMAND,
            project
          )
        );
      }

      if (items.length === 0) {
        items.push(
          new GrailsTreeItem(
            "No dependencies found",
            TreeItemCollapsibleState.None,
            TreeItemKind.Error,
            NO_ACTION_COMMAND,
            project
          )
        );
      }
    } catch (error) {
      console.warn(`Error parsing build.gradle:`, error);
      items.push(
        new GrailsTreeItem(
          "❌ Error parsing dependencies",
          TreeItemCollapsibleState.None,
          TreeItemKind.Error,
          NO_ACTION_COMMAND,
          project
        )
      );
    }

    return items;
  }

  private parseBuildGradleDependencies(
    buildGradlePath: string
  ): { name: string; version: string; type: string }[] {
    const content = fs.readFileSync(buildGradlePath, "utf8");
    const dependencies: { name: string; version: string; type: string }[] = [];

    // Basic regex patterns for dependencies
    const patterns = [
      /implementation\s+['"](.*?):(.*?):(.*?)['"]/g,
      /compile\s+['"](.*?):(.*?):(.*?)['"]/g,
      /runtime\s+['"](.*?):(.*?):(.*?)['"]/g,
    ];

    patterns.forEach(pattern => {
      let match;
      while ((match = pattern.exec(content)) !== null) {
        dependencies.push({
          name: `${match[1]}:${match[2]}`,
          version: match[3],
          type: "implementation",
        });
      }
    });

    // Look for plugins
    const pluginPattern = /id\s+['"](.*?)['"]\s+version\s+['"](.*?)['"]/g;
    let pluginMatch;
    while ((pluginMatch = pluginPattern.exec(content)) !== null) {
      dependencies.push({
        name: pluginMatch[1],
        version: pluginMatch[2],
        type: "plugin",
      });
    }

    return dependencies;
  }

  private static readonly MAX_DIRECTORY_ITEMS = 300;

  private readDirectorySafely(
    dirPath: string,
    project: ProjectInfo,
    createDirItem: (fullPath: string, name: string) => GrailsTreeItem,
    createFileItem: (fullPath: string, name: string) => GrailsTreeItem | null
  ): GrailsTreeItem[] {
    if (!this.folderExists(dirPath)) {
      return [
        new GrailsTreeItem(
          "(Folder not found)",
          TreeItemCollapsibleState.None,
          TreeItemKind.Empty,
          NO_ACTION_COMMAND,
          project,
          dirPath
        ),
      ];
    }

    try {
      const entries = fs.readdirSync(dirPath, { withFileTypes: true });
      if (entries.length === 0) {
        return [
          new GrailsTreeItem(
            "(Empty)",
            TreeItemCollapsibleState.None,
            TreeItemKind.Empty,
            NO_ACTION_COMMAND,
            project,
            dirPath
          ),
        ];
      }

      const dirs: GrailsTreeItem[] = [];
      const files: GrailsTreeItem[] = [];

      entries.sort((a, b) => a.name.localeCompare(b.name));

      for (const entry of entries) {
        const fullPath = path.join(dirPath, entry.name);
        if (entry.isDirectory()) {
          dirs.push(createDirItem(fullPath, entry.name));
        } else if (entry.isFile()) {
          const item = createFileItem(fullPath, entry.name);
          if (item) {
            files.push(item);
          }
        }
      }

      const all = [...dirs, ...files];
      if (all.length === 0) {
        return [
          new GrailsTreeItem(
            "(Empty)",
            TreeItemCollapsibleState.None,
            TreeItemKind.Empty,
            NO_ACTION_COMMAND,
            project,
            dirPath
          ),
        ];
      }

      if (all.length > GrailsTreeExplorer.MAX_DIRECTORY_ITEMS) {
        const truncated = all.slice(0, GrailsTreeExplorer.MAX_DIRECTORY_ITEMS);
        truncated.push(
          new GrailsTreeItem(
            `... and ${all.length - GrailsTreeExplorer.MAX_DIRECTORY_ITEMS} more items`,
            TreeItemCollapsibleState.None,
            TreeItemKind.Empty,
            NO_ACTION_COMMAND,
            project,
            dirPath
          )
        );
        return truncated;
      }

      return all;
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      return [
        new GrailsTreeItem(
          `Error: ${message}`,
          TreeItemCollapsibleState.None,
          TreeItemKind.Error,
          NO_ACTION_COMMAND,
          project,
          dirPath
        ),
      ];
    }
  }

  private compactPackagePath(startPath: string): { finalPath: string; displayLabel: string } {
    let currentPath = startPath;
    const parts: string[] = [path.basename(startPath)];

    while (true) {
      try {
        const entries = fs.readdirSync(currentPath, { withFileTypes: true });
        const dirs = entries.filter(e => e.isDirectory());
        const files = entries.filter(e => e.isFile());

        if (dirs.length === 1 && files.length === 0) {
          currentPath = path.join(currentPath, dirs[0].name);
          parts.push(dirs[0].name);
        } else {
          break;
        }
      } catch {
        break;
      }
    }

    return {
      finalPath: currentPath,
      displayLabel: parts.join("."),
    };
  }

  private matchesArtifactType(fileName: string, artifactType?: ArtifactType): boolean {
    if (!artifactType) {
      return fileName.endsWith(".groovy") || fileName.endsWith(".java");
    }
    switch (artifactType) {
      case ArtifactType.Controller:
        return fileName.endsWith("Controller.groovy") || fileName.endsWith("Controller.java");
      case ArtifactType.Service:
        return fileName.endsWith("Service.groovy") || fileName.endsWith("Service.java");
      case ArtifactType.Domain:
        return (
          fileName.endsWith(".groovy") &&
          !fileName.includes("Test") &&
          !fileName.includes("Spec")
        );
      case ArtifactType.TagLib:
        return fileName.endsWith("TagLib.groovy") || fileName.endsWith("TagLib.java");
      case ArtifactType.Interceptor:
        return fileName.endsWith("Interceptor.groovy") || fileName.endsWith("Interceptor.java");
      case ArtifactType.Job:
        return fileName.endsWith("Job.groovy") || fileName.endsWith("Job.java");
      case ArtifactType.Command:
        return fileName.endsWith("Command.groovy") || fileName.endsWith("Command.java");
      case ArtifactType.Utils:
        return fileName.endsWith(".groovy") || fileName.endsWith(".java");
      default:
        return fileName.endsWith(".groovy") || fileName.endsWith(".java");
    }
  }

  private isRelevantFile(fileName: string): boolean {
    const relevantExtensions = [
      ".groovy",
      ".java",
      ".gsp",
      ".yml",
      ".yaml",
      ".properties",
      ".xml",
      ".json",
      ".sql",
      ".txt",
    ];
    return relevantExtensions.some(ext => fileName.endsWith(ext));
  }

  private getArtifactFolderChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      fullPath => {
        const compacted = this.compactPackagePath(fullPath);
        return new GrailsTreeItem(
          compacted.displayLabel,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.GrailsArtifactFolders,
          NO_ACTION_COMMAND,
          element.projectInfo,
          compacted.finalPath,
          element.artifactType
        );
      },
      (fullPath, name) => {
        if (!this.matchesArtifactType(name, element.artifactType)) {
          return null;
        }
        return new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.ArtifactFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath,
          element.artifactType
        );
      }
    );
  }

  private getConfFolderChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.ConfFolder,
          NO_ACTION_COMMAND,
          element.projectInfo,
          fullPath
        ),
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.ConfFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        )
    );
  }

  private getI18nFolderChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.ConfFolder,
          NO_ACTION_COMMAND,
          element.projectInfo,
          fullPath
        ),
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.ConfFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        )
    );
  }

  private getViewFolderChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.ViewsFolder,
          NO_ACTION_COMMAND,
          element.projectInfo,
          fullPath
        ),
      (fullPath, name) => {
        if (!name.endsWith(".gsp") && !name.endsWith(".html") && !name.endsWith(".gsp.tpl")) {
          return null;
        }
        return new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.ViewFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        );
      }
    );
  }

  private getAssetFolderChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.AssetsFolder,
          NO_ACTION_COMMAND,
          element.projectInfo,
          fullPath
        ),
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.AssetFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        )
    );
  }

  private getSourceDirChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      fullPath => {
        const compacted = this.compactPackagePath(fullPath);
        return new GrailsTreeItem(
          compacted.displayLabel,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.SourceDir,
          NO_ACTION_COMMAND,
          element.projectInfo,
          compacted.finalPath
        );
      },
      (fullPath, name) => {
        if (!this.isRelevantFile(name)) {
          return null;
        }
        return new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.SrcFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        );
      }
    );
  }

  private getTestDirChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      fullPath => {
        const compacted = this.compactPackagePath(fullPath);
        return new GrailsTreeItem(
          compacted.displayLabel,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.TestDir,
          NO_ACTION_COMMAND,
          element.projectInfo,
          compacted.finalPath
        );
      },
      (fullPath, name) => {
        if (!this.isRelevantFile(name)) {
          return null;
        }
        return new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.TestFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        );
      }
    );
  }

  private getGeneratedDirChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      fullPath => {
        const compacted = this.compactPackagePath(fullPath);
        return new GrailsTreeItem(
          compacted.displayLabel,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.GeneratedDir,
          NO_ACTION_COMMAND,
          element.projectInfo,
          compacted.finalPath
        );
      },
      (fullPath, name) =>
        new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.GeneratedFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        )
    );
  }

  private getInitFolderChildren(element: GrailsTreeItem): GrailsTreeItem[] {
    const dirPath = element.resourcePath;
    if (!dirPath || !element.projectInfo) {
      return [];
    }

    return this.readDirectorySafely(
      dirPath,
      element.projectInfo,
      fullPath => {
        const compacted = this.compactPackagePath(fullPath);
        return new GrailsTreeItem(
          compacted.displayLabel,
          TreeItemCollapsibleState.Collapsed,
          TreeItemKind.SourceDir,
          NO_ACTION_COMMAND,
          element.projectInfo,
          compacted.finalPath
        );
      },
      (fullPath, name) => {
        if (!this.isRelevantFile(name)) {
          return null;
        }
        return new GrailsTreeItem(
          name,
          TreeItemCollapsibleState.None,
          TreeItemKind.SrcFile,
          { command: "vscode.open", title: "Open File", arguments: [Uri.file(fullPath)] },
          element.projectInfo,
          fullPath
        );
      }
    );
  }

  private getDependencyList(project: ProjectInfo, category: string): GrailsTreeItem[] {
    const items: GrailsTreeItem[] = [];

    try {
      const buildGradlePath = path.join(project.rootPath, "build.gradle");
      if (!fs.existsSync(buildGradlePath)) {
        return items;
      }

      const dependencies = this.parseBuildGradleDependencies(buildGradlePath);

      let filteredDeps: { name: string; version: string; type: string }[] = [];

      if (category.includes("Plugins")) {
        filteredDeps = dependencies.filter(dep => dep.type === "plugin");
      } else if (category.includes("Libraries")) {
        filteredDeps = dependencies.filter(
          dep => dep.type === "implementation" || dep.type === "compile" || dep.type === "runtime"
        );
      }

      const dependencyItems = filteredDeps.map(
        dep =>
          new GrailsTreeItem(
            `${dep.name} (${dep.version})`,
            TreeItemCollapsibleState.None,
            TreeItemKind.Dependency,
            NO_ACTION_COMMAND,
            project
          )
      );

      items.push(...dependencyItems);
    } catch (error) {
      console.warn(`Error getting dependency list for ${category}:`, error);
    }

    return items;
  }

  private getFileCount(dirPath: string, extensions: string[]): number {
    if (!this.folderExists(dirPath)) {
      return 0;
    }

    try {
      return fs.readdirSync(dirPath).filter(file => extensions.some(ext => file.endsWith(ext)))
        .length;
    } catch (error) {
      console.warn(`Error counting files in ${dirPath}:`, error);
      return 0;
    }
  }

  // Core folder checks
  private hasGrailsApp(project: ProjectInfo): boolean {
    return this.folderExists(path.join(project.rootPath, "grails-app"));
  }

  private hasConfigFolder(project: ProjectInfo): boolean {
    return this.folderExists(path.join(project.rootPath, "grails-app", "conf"));
  }

  private hasViewsFolder(project: ProjectInfo): boolean {
    return this.folderExists(path.join(project.rootPath, "grails-app", "views"));
  }

  private hasAssetsFolder(project: ProjectInfo): boolean {
    return this.folderExists(path.join(project.rootPath, "grails-app", "assets"));
  }

  private hasSourceFolder(project: ProjectInfo): boolean {
    return (
      this.folderExists(path.join(project.rootPath, "src", "main")) ||
      this.folderExists(path.join(project.rootPath, "src"))
    );
  }

  private hasTestFolder(project: ProjectInfo): boolean {
    return this.folderExists(path.join(project.rootPath, "src", "test"));
  }

  // Project type detection
  private isGrailsProject(project: ProjectInfo): boolean {
    return (
      project.type === ProjectType.Grails ||
      project.type === ProjectType.GrailsPlugin ||
      this.hasGrailsApp(project)
    );
  }

  // Generic folder existence check
  private folderExists(folderPath: string): boolean {
    try {
      return fs.existsSync(folderPath) && fs.lstatSync(folderPath).isDirectory();
    } catch (error) {
      console.warn(`Error checking folder ${folderPath}:`, error);
      return false;
    }
  }

  // Count artefacts by type
  private getArtefactCount(categoryPath: string, artifactType: ArtifactType): number {
    if (!this.folderExists(categoryPath)) {
      return 0;
    }

    try {
      const suffix = this.getArtefactSuffix(artifactType);
      const files = fs.readdirSync(categoryPath);

      return files.filter(file => {
        if (artifactType === ArtifactType.Domain) {
          return file.endsWith(".groovy") && !file.includes("Test");
        }
        return file.endsWith(`${suffix}.groovy`);
      }).length;
    } catch (error) {
      console.warn(`Error counting artefacts in ${categoryPath}:`, error);
      return 0;
    }
  }

  private getArtefactSuffix(artifactType: ArtifactType): string {
    switch (artifactType) {
      case ArtifactType.Controller:
        return "Controller";
      case ArtifactType.Service:
        return "Service";
      case ArtifactType.Domain:
        return "";
      case ArtifactType.TagLib:
        return "TagLib";
      case ArtifactType.Interceptor:
        return "Interceptor";
      case ArtifactType.Job:
        return "Job";
      case ArtifactType.Command:
        return "Command";
      default:
        return "";
    }
  }

  // Count assets
  private getAssetCount(project: ProjectInfo): number {
    const assetsPath = path.join(project.rootPath, "grails-app", "assets");
    if (!this.folderExists(assetsPath)) {
      return 0;
    }

    let count = 0;
    const assetDirs = ["stylesheets", "javascripts", "images"];

    for (const dir of assetDirs) {
      const dirPath = path.join(assetsPath, dir);
      if (this.folderExists(dirPath)) {
        count += this.getRecursiveFileCount(dirPath, [
          ".css",
          ".scss",
          ".js",
          ".coffee",
          ".png",
          ".jpg",
          ".gif",
        ]);
      }
    }

    return count;
  }

  // Recursive file counting
  private getRecursiveFileCount(dirPath: string, extensions: string[]): number {
    if (!this.folderExists(dirPath)) {
      return 0;
    }

    let count = 0;
    try {
      const entries = fs.readdirSync(dirPath, { withFileTypes: true });

      for (const entry of entries) {
        const fullPath = path.join(dirPath, entry.name);
        if (entry.isDirectory()) {
          count += this.getRecursiveFileCount(fullPath, extensions);
        } else if (extensions.some(ext => entry.name.endsWith(ext))) {
          count++;
        }
      }
    } catch (error) {
      console.warn(`Error reading directory ${dirPath}:`, error);
    }

    return count;
  }

  /** Register event listeners for auto-refresh */
  private registerEventListeners(): void {
    console.log("🔧 TreeExplorer: Registering event listeners");
    const eventBus = EventBus.getInstance();

    // Refresh when projects are discovered or changed
    const projectsDiscoveredSub = eventBus.subscribe(EventType.PROJECTS_DISCOVERED, event => {
      console.log(
        `🌳 TreeExplorer: PROJECTS_DISCOVERED received with ${event.projects.length} projects`
      );
      console.log(
        "🌳 Projects:",
        event.projects.map(p => ({ name: p.name, type: p.type, path: p.rootPath }))
      );
      this.projects = event.projects.map(p => ProjectMapper.toProjectInfo(p));
      this.refresh();
    });

    const projectChangedSub = eventBus.subscribe(EventType.PROJECT_CHANGED, event => {
      console.log(`🌳 TreeExplorer: PROJECT_CHANGED received for ${event.project.name}`);

      const updatedProject = ProjectMapper.toProjectInfo(event.project);
      const index = this.projects.findIndex(p => p.id === updatedProject.id);
      if (index >= 0) {
        this.projects[index] = updatedProject;
      } else {
        this.projects.push(updatedProject);
      }

      this.refresh();
    });

    // Listen for tree refresh events (e.g., icon theme changes)
    const treeRefreshSub = eventBus.subscribe(EventType.TREE_REFRESH, () => {
      console.log("🌳 TreeExplorer: TREE_REFRESH received");
      this.refresh();
    });

    this.disposables.push(projectsDiscoveredSub, projectChangedSub, treeRefreshSub);
  }


  public dispose(): void {
    void this._onDidChangeTreeData?.dispose();
    this.disposables?.forEach(d => void d.dispose());
    this.disposables = [];
  }
}
