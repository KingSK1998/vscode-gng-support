import type { Disposable, ExtensionContext } from "vscode";
import type { ServiceContainer } from "../../core/container/ServiceContainer";
import { AnalysisCommands } from "./AnalysisCommands";
import { ArtifactCommands } from "./ArtifactCommands";
import type { BaseCommandProvider } from "./BaseCommandProvider";
import { ExtensionCommands } from "./ExtensionCommands";
import { GrailsTaskCommands } from "./GrailsTaskCommands";
import { LegacyCommands } from "./LegacyCommands";
import { LogCommands } from "./LogCommands";
import { NavigationCommands } from "./NavigationCommands";

export class Commands implements Disposable {
  private providers: BaseCommandProvider[] = [];

  constructor(
    private readonly context: ExtensionContext,
    private readonly container: ServiceContainer
  ) {
    this.providers = [
      new UICommands(context, container),
      new ProjectCommands(context, container),
      new GrailsTaskCommands(context, container),
      new ArtifactCommands(context, container),
      new NavigationCommands(context, container),
      new ExtensionCommands(context, container),
      new LogCommands(context, container),
      new LegacyCommands(context, container),
      new AnalysisCommands(context, container),
    ];
  }

  registerAllCommands(): void {
    this.providers.forEach(p => p.registerCommands());
  }

  dispose(): void {
    this.providers.forEach(p => p.dispose());
    this.providers = [];
  }
}

import { ProjectCommands } from "./ProjectCommands";
import { UICommands } from "./UICommands";
