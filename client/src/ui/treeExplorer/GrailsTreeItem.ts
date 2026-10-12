import type { Command, ThemeIcon, TreeItemCollapsibleState } from "vscode";
import { TreeItem, Uri } from "vscode";
import { ProjectType, type ArtifactType, type ProjectInfo } from "../../features/models/modelTypes";
import { IconProvider } from "../icons/IconProvider";
import { TreeItemKind } from "./TreeItemKind";

/** Grails-specific tree item */
export class GrailsTreeItem extends TreeItem {
  constructor(
    public override readonly label: string,
    public override readonly collapsibleState: TreeItemCollapsibleState,
    public readonly kind: TreeItemKind,
    public override readonly command: Command,
    public readonly projectInfo?: ProjectInfo,
    public readonly resourcePath?: string,
    public readonly artifactType?: ArtifactType,
    public readonly children?: GrailsTreeItem[]
  ) {
    super(label, collapsibleState);

    this.contextValue = this.buildContextValue();
    this.setupIcon();
    this.tooltip = this.buildTooltip();
    this.children = children;

    // Stable scoped identity for tree item state preservation (INV-DISC-001, INV-OWN-004)
    const scopePrefix = this.projectInfo?.rootPath ?? "global";
    this.id = `${scopePrefix}::${this.kind}::${this.resourcePath ?? this.label}`;

    // Accessibility label for keyboard and screen-reader navigation
    this.accessibilityInformation = {
      label: this.buildAccessibilityLabel(),
    };

    // Label generated and read-only locations distinctly
    if (
      this.kind === TreeItemKind.GeneratedRoot ||
      this.kind === TreeItemKind.GeneratedDir ||
      this.kind === TreeItemKind.GeneratedFile
    ) {
      this.description = "(generated, read-only)";
    }
  }

  private buildAccessibilityLabel(): string {
    const isGenerated =
      this.kind === TreeItemKind.GeneratedRoot ||
      this.kind === TreeItemKind.GeneratedDir ||
      this.kind === TreeItemKind.GeneratedFile;
    const parts = [this.label, this.kind];
    if (isGenerated) {
      parts.push("generated read-only");
    }
    if (this.projectInfo?.name) {
      parts.push(`project ${this.projectInfo.name}`);
    }
    return parts.join(", ");
  }

  private buildContextValue(): string {
    const contexts: string[] = [this.kind];

    if (this.projectInfo?.type) {
      contexts.push(`project:${this.projectInfo.type}`);
    }

    if (this.artifactType) {
      contexts.push(`artifact:${this.artifactType}`);
    }

    return contexts.join(".");
  }

  /** Setup icons with clean theme-aware logic */
  private setupIcon(): void {
    const icon = this.resolveIcon();

    if (icon !== undefined) {
      this.iconPath = icon;
    }
  }

  /** Centralized icon resolution - MUCH simpler! */
  private resolveIcon(): ThemeIcon | undefined {
    // Single method call with all context
    const icon = IconProvider.getIcon(
      this.kind,
      this.projectInfo?.type,
      this.artifactType,
      this.resourcePath
    );

    // If no custom icon, let VSCode handle it
    if (!icon && this.resourcePath) {
      this.resourceUri = Uri.file(this.resourcePath);
    }

    return icon;
  }

  /** Build enhanced tooltip with rich hover content */
  private buildTooltip(): string {
    // Project root tooltip with detailed info
    if (this.projectInfo && this.kind === TreeItemKind.ProjectRoot) {
      return this.buildProjectTooltip();
    }

    // File tooltips with path info
    if (this.resourcePath && IconProvider.isLeafKind(this.kind)) {
      return this.buildFileTooltip();
    }

    // Category tooltips with context
    if (this.artifactType) {
      return this.buildCategoryTooltip();
    }

    // Fallback to label
    return this.label;
  }

  /** Build detailed project tooltip */
  private buildProjectTooltip(): string {
    const project = this.projectInfo!;
    const parts = [`**${project.name}**`, `📁 ${project.rootPath}`];

    if (project.type !== ProjectType.Unknown) {
      parts.push(`🚀 ${project.type}`);
    }
    if (project.grailsVersion) {
      parts.push(`⚙️ Grails ${project.grailsVersion}`);
    }
    if (project.groovyVersion) {
      parts.push(`🟢 Groovy ${project.groovyVersion}`);
    }
    if (project.dependencies?.length) {
      parts.push(`📦 ${project.dependencies.length} dependencies`);
    }
    return parts.join("\n");
  }

  /** Build file-specific tooltip */
  private buildFileTooltip(): string {
    const fileName = this.resourcePath!.split(/[/\\]/).pop() ?? this.label;
    const relativePath = this.getRelativePath();

    const parts = [`**${fileName}**`];
    if (this.artifactType) {
      const title = this.artifactType.charAt(0).toUpperCase() + this.artifactType.slice(1);
      parts.push(`📄 ${title} File`);
    }
    if (relativePath !== fileName) {
      parts.push(`📁 ${relativePath}`);
    }
    return parts.join("\n");
  }

  /** Build category-specific tooltip */
  private buildCategoryTooltip(): string {
    const categoryName = this.artifactType!.charAt(0).toUpperCase() + this.artifactType!.slice(1);
    const descriptions: Record<string, string> = {
      controller: "🌐 Web controllers handling HTTP requests",
      service: "⚙️ Business logic and transactional services",
      domain: "🗄️ Domain classes and database entities",
      taglib: "🏷️ Custom GSP tags and view helpers",
      view: "👁️ GSP templates and views",
      assets: "🎨 CSS, JavaScript, and static assets",
      config: "🔧 Application configuration files",
      tests: "🧪 Test files and specifications",
    };
    const description = descriptions[this.artifactType!] || `📂 ${categoryName} files`;
    return `**${categoryName}**\n${description}`;
  }

  /** Get relative path for display */
  private getRelativePath(): string {
    if (!this.resourcePath) {
      return this.label;
    }

    const parts = this.resourcePath.split(/[/\\]/);
    const grailsAppIndex = parts.findIndex(part => part === "grails-app");
    if (grailsAppIndex !== -1) {
      return parts.slice(grailsAppIndex).join("/");
    }
    const srcIndex = parts.findIndex(part => part === "src");
    if (srcIndex !== -1) {
      return parts.slice(srcIndex).join("/");
    }
    return parts.slice(-2).join("/");
  }
}
