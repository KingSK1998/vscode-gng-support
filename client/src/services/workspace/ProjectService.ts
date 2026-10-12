import * as fs from "fs";
import * as fsPromises from "fs/promises";
import * as path from "path";
import type { Disposable, FileSystemWatcher, WorkspaceFolder } from "vscode";
import { RelativePattern, workspace } from "vscode";

import type { EventBus } from "../../core/events/EventBus";
import { EventType } from "../../core/events/eventTypes";
import type { ArtifactCounts, ProjectInfo } from "../../features/models/modelTypes";
import { ProjectType } from "../../features/models/modelTypes";

import type { ErrorService } from "../errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../errors/errorTypes";
import type { ConfigurationService } from "./ConfigurationService";
import type { StatusBarService } from "./StatusBarService";

export class ProjectService implements Disposable {
  private readonly projects = new Map<string, ProjectInfo>();
  private activeProjectId: string | undefined;

  private readonly watchers = new Set<FileSystemWatcher>();
  private readonly watcherDisposables = new Map<FileSystemWatcher, Disposable[]>();
  private readonly folderWatchers = new Map<string, FileSystemWatcher>();
  private disposables: Disposable[] = [];

  private cachedDiscovery: ProjectInfo[] | undefined;
  private lastDiscoveryTime = 0;
  private readonly CACHE_DURATION = 30000;

  private _intialized = false;
  private _disposed = false;

  constructor(
    private readonly statusBarService: StatusBarService,
    private readonly errorService: ErrorService,
    private readonly config: ConfigurationService,
    private readonly eventBus: EventBus
  ) {}

  async discoverProjects(): Promise<ProjectInfo[]> {
    const start = performance.now();

    try {
      this.statusBarService.sync("🔍 Analyzing projects...");

      if (this.isCacheValid() && this.cachedDiscovery) {
        console.log("📊 Using cached project data");
        return this.cachedDiscovery;
      }

      const roots = workspace.workspaceFolders ?? [];
      console.log(`📦 Found ${roots.length} workspace folders`);

      this.projects.clear();

      const results = await Promise.allSettled(
        roots.map(folder => this.loadProjectWithTimeout(folder, 5000))
      );

      const discovered: ProjectInfo[] = [];

      results.forEach((result, i) => {
        if (result.status === "fulfilled" && result.value) {
          const project = result.value;
          discovered.push(project);
          this.projects.set(project.id, project);
        } else {
          console.warn(
            `Project discovery failed for ${roots[i].name}:`,
            result.status === "rejected" ? result.reason : "No project info"
          );
        }
      });

      this.cachedDiscovery = discovered;
      this.lastDiscoveryTime = Date.now();

      if (!this.activeProjectId || !this.projects.has(this.activeProjectId)) {
        if (discovered.length > 0) {
          this.activeProjectId = discovered[0]?.id;
        }
      }

      console.log(`📦 Final discovered projects: ${discovered.length}`);
      discovered.forEach((p, i) => {
        console.log(`📦   ${i + 1}. ${p.name} (${p.type}) at ${p.rootPath}`);
      });

      console.log("📡 Publishing PROJECTS_DISCOVERED event...");
      this.eventBus.publish({
        type: EventType.PROJECTS_DISCOVERED,
        projects: discovered,
        timestamp: Date.now(),
        source: ProjectService.name,
      });
      console.log("✅ Event published successfully");

      this.initWatchers();

      this.statusBarService.success(`✅ ${discovered.length} projects analyzed`);

      console.log(`📊 Optimized discovery: ${(performance.now() - start).toFixed(1)}ms`);

      this._intialized = true;

      return discovered;
    } catch (error) {
      this.errorService.handleError(
        "Project discovery failed",
        error,
        ErrorSource.ProjectService,
        ErrorSeverity.Error
      );
      return this.cachedDiscovery ?? [];
    }
  }

  quickScan(): ProjectInfo[] {
    try {
      this.statusBarService.sync("🔍 Scanning for projects...");
      const start = performance.now();

      const roots = workspace.workspaceFolders ?? [];
      const quickInfo: ProjectInfo[] = [];

      for (const folder of roots) {
        const root = folder.uri.fsPath;
        const build = path.join(root, "build.gradle");

        if (!fs.existsSync(build)) continue;

        quickInfo.push({
          id: root,
          rootPath: root,
          name: path.basename(root),
          type: this.quickDetectType(root),
          dependencies: [],
        });
      }

      console.log(`📊 Quick scan: ${(performance.now() - start).toFixed(1)}ms`);

      return quickInfo;
    } catch (error) {
      this.errorService.handleError(
        "Quick scan failed",
        error,
        ErrorSource.ProjectService,
        ErrorSeverity.Warning
      );
      return [];
    }
  }

  getProjects(): readonly ProjectInfo[] {
    return Array.from(this.projects.values());
  }

  getProjectById(id: string): ProjectInfo | undefined {
    return this.projects.get(id);
  }

  getActiveProject(): ProjectInfo | undefined {
    if (!this.activeProjectId) return undefined;
    return this.projects.get(this.activeProjectId);
  }

  getProjectForUri(
    uri: string | { fsPath?: string; toString?: () => string }
  ): ProjectInfo | undefined {
    let filePath: string;
    if (typeof uri === "string") {
      filePath = uri.replace(/^file:\/\/\/?/, "");
    } else if (uri?.fsPath) {
      filePath = uri.fsPath;
    } else if (typeof uri?.toString === "function") {
      filePath = uri.toString().replace(/^file:\/\/\/?/, "");
    } else {
      return undefined;
    }

    const normalizedTarget = path.normalize(filePath).toLowerCase();
    let bestMatch: ProjectInfo | undefined;
    let bestMatchLen = 0;

    for (const project of this.projects.values()) {
      if (!project.rootPath) continue;
      const normalizedRoot = path.normalize(project.rootPath).toLowerCase();
      if (
        normalizedTarget === normalizedRoot ||
        normalizedTarget.startsWith(normalizedRoot + path.sep)
      ) {
        if (normalizedRoot.length > bestMatchLen) {
          bestMatch = project;
          bestMatchLen = normalizedRoot.length;
        }
      }
    }

    return bestMatch;
  }

  resolveTargetProject(
    targetUri?: string | { fsPath?: string; toString?: () => string }
  ): ProjectInfo | undefined {
    if (targetUri) {
      const match = this.getProjectForUri(targetUri);
      if (match) return match;
    }

    const active = this.getActiveProject();
    if (active) return active;

    const all = this.getProjects();
    return all.length > 0 ? all[0] : undefined;
  }

  setActiveProject(projectId: string): boolean {
    const project = this.projects.get(projectId);

    if (!project) {
      this.errorService.handleError(
        `Invalid project selected: ${projectId}`,
        new Error(`Project ${projectId} not found`),
        ErrorSource.ProjectService,
        ErrorSeverity.Warning
      );
      return false;
    }

    this.activeProjectId = projectId;

    this.eventBus.publish({
      type: EventType.PROJECT_CHANGED,
      timestamp: Date.now(),
      source: ProjectService.name,
      project,
    });

    return true;
  }

  dispose() {
    if (this._disposed) return;
    this._disposed = true;

    this.disposeAllWatchers();

    this.disposables.forEach(d => {
      try {
        d.dispose();
      } catch (error) {
        console.error("[ProjectService] Error disposing disposable:", error);
      }
    });
    this.disposables.length = 0;

    this.projects.clear();
    this.activeProjectId = undefined;
    this.cachedDiscovery = undefined;
    this.lastDiscoveryTime = 0;
  }

  private disposeWatcher(watcher: FileSystemWatcher): void {
    try {
      const eventDisposables = this.watcherDisposables.get(watcher);
      if (eventDisposables) {
        for (const disposable of eventDisposables) {
          disposable.dispose();
        }
        this.watcherDisposables.delete(watcher);
      }

      watcher.dispose();
      this.watchers.delete(watcher);

      for (const [key, w] of this.folderWatchers.entries()) {
        if (w === watcher) {
          this.folderWatchers.delete(key);
          break;
        }
      }
    } catch (error) {
      console.error("[ProjectService] Error disposing watcher:", error);
    }
  }

  private disposeAllWatchers(): void {
    const watchersCopy = Array.from(this.watchers);

    for (const watcher of watchersCopy) {
      this.disposeWatcher(watcher);
    }

    this.watchers.clear();
    this.watcherDisposables.clear();
    this.folderWatchers.clear();
  }

  reinitWatchersOnFolderChange(): void {
    this.disposeAllWatchers();
    this.initWatchers();
  }

  /* ================= INTERNAL =================================== */

  private isCacheValid(): boolean {
    return (
      this.cachedDiscovery !== undefined &&
      Date.now() - this.lastDiscoveryTime < this.CACHE_DURATION
    );
  }

  private async loadProjectWithTimeout(
    folder: WorkspaceFolder,
    timeoutMs: number
  ): Promise<ProjectInfo | undefined> {
    return Promise.race([
      this.loadProject(folder),
      new Promise<undefined>(resolve => setTimeout(() => resolve(undefined), timeoutMs)),
    ]);
  }

  private async loadProject(folder: WorkspaceFolder): Promise<ProjectInfo | undefined> {
    const root = folder.uri.fsPath;
    return this.scanFolder(root);
  }

  private async scanFolder(root: string): Promise<ProjectInfo | undefined> {
    const build = path.join(root, "build.gradle");

    try {
      await fsPromises.access(build);
    } catch {
      return undefined;
    }

    const type = await this.detectProjectType(root);
    if (!type) return undefined;

    const [, artifactCounts] = await Promise.all([
      this.parseDependencies(build),
      this.countArtifacts(root),
    ]);

    const info: ProjectInfo = {
      id: root,
      rootPath: root,
      name: path.basename(root),
      type,
      dependencies: [],
      artifactCounts,
    };

    if (type === ProjectType.Grails || type === ProjectType.GrailsPlugin) {
      const [gv, groovyV] = await Promise.all([
        this.extractVersion(build, /grailsVersion\s*=\s*['"]([^'"]+)['"]/),
        this.extractVersion(build, /groovyVersion\s*=\s*['"]([^'"]+)['"]/),
      ]);
      if (gv) info.grailsVersion = gv;
      if (groovyV) info.groovyVersion = groovyV;
    }

    if (type === ProjectType.GrailsPlugin) {
      const pv = await this.extractVersion(build, /version\s*=\s*['"]([^'"]+)['"]/);
      if (pv) info.pluginVersion = pv;
    }

    return info;
  }

  private async detectProjectType(root: string): Promise<ProjectType | undefined> {
    const grailsDir = path.join(root, "grails-app");
    const build = path.join(root, "build.gradle");

    try {
      await fsPromises.access(build);
    } catch {
      return undefined;
    }

    const hasGrailsApp = await this.directoryExists(grailsDir);
    if (hasGrailsApp) {
      const [hasPluginMarker1, hasPluginMarker2] = await Promise.all([
        this.fileContains(build, "org.grails.grails-plugin"),
        this.fileContains(build, "grails-plugin"),
      ]);

      if (hasPluginMarker1 || hasPluginMarker2) {
        return ProjectType.GrailsPlugin;
      }
      return ProjectType.Grails;
    }

    const [hasGroovyDep, hasGroovySrc] = await Promise.all([
      this.fileContains(build, "groovy"),
      this.directoryExists(path.join(root, "src", "main", "groovy")),
    ]);

    if (hasGroovyDep || hasGroovySrc) {
      return ProjectType.Groovy;
    }

    return undefined;
  }

  private async parseDependencies(buildFile: string): Promise<string[]> {
    try {
      const text = await fsPromises.readFile(buildFile, "utf8");
      const regex = /(implementation|compile|api|runtimeOnly)\s+['"]([^'"]+)['"]/g;
      const deps: string[] = [];
      let match: RegExpExecArray | null = null;

      while ((match = regex.exec(text)) !== null) {
        const dep = match[2];
        if (dep) deps.push(dep);
      }

      return deps;
    } catch {
      return [];
    }
  }

  private async countInDirectory(dir: string, ...extensions: string[]): Promise<number> {
    try {
      const stat = await fsPromises.stat(dir);
      if (!stat.isDirectory()) {
        return 0;
      }
    } catch {
      return 0;
    }

    try {
      const files = await fsPromises.readdir(dir, { withFileTypes: true });
      return files.filter(file => {
        if (!file.isFile()) {
          return false;
        }
        if (extensions.length === 0) {
          return true;
        }
        return extensions.some(ext => file.name.endsWith(ext));
      }).length;
    } catch {
      return 0;
    }
  }

  private async extractVersion(file: string, regex: RegExp): Promise<string | undefined> {
    try {
      const content = await fsPromises.readFile(file, "utf8");
      return content.match(regex)?.[1];
    } catch {
      return undefined;
    }
  }

  private quickDetectType(root: string): ProjectType {
    return fs.existsSync(path.join(root, "grails-app")) ? ProjectType.Grails : ProjectType.Groovy;
  }

  private async fileContains(file: string, needle: string): Promise<boolean> {
    try {
      const content = await fsPromises.readFile(file, "utf8");
      return content.includes(needle);
    } catch {
      return false;
    }
  }

  private async directoryExists(dir: string): Promise<boolean> {
    try {
      const stat = await fsPromises.stat(dir);
      return stat.isDirectory();
    } catch {
      return false;
    }
  }

  private async fileExists(file: string): Promise<boolean> {
    try {
      await fsPromises.access(file);
      return true;
    } catch {
      return false;
    }
  }

  /* ------------- Watchers: refresh project on build.gradle change --- */

  private initWatchers(): void {
    if (this._disposed) return;
    if (this.watchers.size > 0) return;

    const roots = workspace.workspaceFolders ?? [];

    for (const folder of roots) {
      const folderKey = folder.uri.toString();

      if (this.folderWatchers.has(folderKey)) {
        continue;
      }

      try {
        const pattern = new RelativePattern(folder, "build.gradle");
        const watcher = workspace.createFileSystemWatcher(pattern);

        this.watchers.add(watcher);
        this.folderWatchers.set(folderKey, watcher);

        const eventDisposables: Disposable[] = [];

        const refresh = () => {
          if (!this._disposed) {
            void this.reloadProject(folder);
          }
        };

        eventDisposables.push(watcher.onDidChange(refresh));
        eventDisposables.push(watcher.onDidCreate(refresh));
        eventDisposables.push(watcher.onDidDelete(refresh));

        this.watcherDisposables.set(watcher, eventDisposables);
      } catch (error) {
        this.errorService.handleError(
          `Failed to create watcher for ${folder.name}`,
          error,
          ErrorSource.ProjectService,
          ErrorSeverity.Warning
        );
      }
    }
  }

  private async reloadProject(folder: WorkspaceFolder): Promise<void> {
    const updated = await this.scanFolder(folder.uri.fsPath);
    if (!updated) return;

    const previous = this.projects.get(updated.id);
    const prevDeps = previous?.dependencies ?? [];
    const updatedDeps = updated.dependencies ?? [];

    const changed =
      !previous || previous.type !== updated.type || prevDeps.length !== updatedDeps.length;

    if (!changed) return;

    console.log(`📦 ProjectService: Project ${updated.name} changed`);

    this.projects.set(updated.id, updated);

    this.eventBus.publish({
      type: EventType.PROJECT_CHANGED,
      timestamp: Date.now(),
      source: ProjectService.name,
      project: updated,
    });
  }

  private async countArtifacts(root: string): Promise<ArtifactCounts> {
    const grailsApp = path.join(root, "grails-app");
    const counts: ArtifactCounts = {
      controllers: 0,
      services: 0,
      domains: 0,
      views: 0,
      taglibs: 0,
      interceptors: 0,
      filters: 0,
      config: 0,
      urlMappings: 0,
      bootstrap: 0,
      applicationConfig: 0,
      springConfigs: 0,
      hibernateConfigs: 0,
      unitTests: 0,
      integrationTests: 0,
      spockSpecs: 0,
      functionalTests: 0,
      assets: 0,
      i18n: 0,
      resources: 0,
      gspFiles: 0,
      staticFiles: 0,
      commands: 0,
      scripts: 0,
      jobs: 0,
      utils: 0,
      codecs: 0,
      groovySrc: 0,
      javaSrc: 0,
      dependencies: 0,
      tasks: 0,
    };

    const [
      controllers,
      urlMappings,
      services,
      domains,
      taglibs,
      views,
      interceptors,
      filters,
      config,
      springConfigs,
      hibernateConfigs,
      unitTests,
      integrationTests,
      spockSpecs,
      functionalTests,
      assets,
      i18n,
      gspFiles,
      commands,
      scripts,
      jobs,
      utils,
      codecs,
      groovySrc,
      javaSrc,
      bootstrap,
    ] = await Promise.all([
      this.countInDirectory(path.join(grailsApp, "controllers"), "Controller.groovy"),
      this.countInDirectory(path.join(grailsApp, "controllers"), "UrlMappings.groovy"),
      this.countInDirectory(path.join(grailsApp, "services"), "Service.groovy"),
      this.countInDirectory(path.join(grailsApp, "domain"), ".groovy"),
      this.countInDirectory(path.join(grailsApp, "taglib"), "TagLib.groovy"),
      this.countInDirectory(path.join(grailsApp, "views"), ".gsp"),
      this.countInDirectory(path.join(grailsApp, "controllers"), "Interceptor.groovy"),
      this.countInDirectory(path.join(grailsApp, "conf"), "Filters.groovy"),
      this.countInDirectory(path.join(grailsApp, "conf"), ".groovy", ".yml", ".properties"),
      this.countInDirectory(path.join(grailsApp, "conf", "spring"), ".groovy"),
      this.countInDirectory(path.join(grailsApp, "conf", "hibernate"), ".groovy"),
      this.countInDirectory(path.join(root, "src/test/groovy"), "Test.groovy"),
      this.countInDirectory(path.join(root, "src/integration-test/groovy"), "Spec.groovy"),
      this.countInDirectory(path.join(root, "src/test/groovy"), "Spec.groovy"),
      this.countInDirectory(path.join(root, "src/test/functional"), ".groovy"),
      this.countInDirectory(path.join(grailsApp, "assets"), ".js", ".css", ".scss"),
      this.countInDirectory(path.join(grailsApp, "i18n"), ".properties"),
      this.countInDirectory(path.join(grailsApp, "views"), ".gsp"),
      this.countInDirectory(path.join(grailsApp, "commands"), "Command.groovy"),
      this.countInDirectory(path.join(root, "src/main/scripts"), ".groovy"),
      this.countInDirectory(path.join(grailsApp, "jobs"), ".groovy"),
      this.countInDirectory(path.join(grailsApp, "utils"), ".groovy"),
      this.countInDirectory(path.join(grailsApp, "utils"), "Codec.groovy"),
      this.countInDirectory(path.join(root, "src/main/groovy"), ".groovy"),
      this.countInDirectory(path.join(root, "src/main/java"), ".java"),
      this.fileExists(path.join(grailsApp, "conf", "BootStrap.groovy")),
    ]);

    counts.controllers = controllers;
    counts.urlMappings = urlMappings;
    counts.services = services;
    counts.domains = domains;
    counts.taglibs = taglibs;
    counts.views = views;
    counts.interceptors = interceptors;
    counts.filters = filters;
    counts.config = config;
    counts.springConfigs = springConfigs;
    counts.hibernateConfigs = hibernateConfigs;
    counts.unitTests = unitTests;
    counts.integrationTests = integrationTests;
    counts.spockSpecs = spockSpecs;
    counts.functionalTests = functionalTests;
    counts.assets = assets;
    counts.i18n = i18n;
    counts.gspFiles = gspFiles;
    counts.commands = commands;
    counts.scripts = scripts;
    counts.jobs = jobs;
    counts.utils = utils;
    counts.codecs = codecs;
    counts.groovySrc = groovySrc;
    counts.javaSrc = javaSrc;
    counts.bootstrap = bootstrap ? 1 : 0;

    return counts;
  }
}
