/** Tree item types for different content categories */
export enum TreeItemKind {
  // Project structure
  ProjectRoot = "project-root",

  // Grails App structure
  GrailsAppRoot = "grails-app-root",
  GrailsArtifactFolders = "grails-artifact-folders",

  // Main sections
  AssetsRoot = "assets-root",
  ConfigRoot = "config-root",
  InitRoot = "init-root",
  ViewsRoot = "views-root",
  RoutesRoot = "routes-root",
  SrcRoot = "source-root",
  TestsRoot = "test-root",
  DependenciesRoot = "dependency-root",
  I18nRoot = "i18n-root",

  // Sub-categories
  ConfFolder = "config-folder",
  AssetsFolder = "assets-folder",
  ViewsFolder = "view-folder",
  DEPENDENCY_CATEGORY = "dependency-category",

  // Directories
  SourceDir = "source-dir",
  TestDir = "test-dir",

  // Files
  ArtifactFile = "artifact-file",
  ConfFile = "config-file",
  ViewFile = "view-file",
  AssetFile = "asset-file",
  SrcFile = "source-file",
  TestFile = "test-file",

  // Generated (read-only) sources
  GeneratedRoot = "generated-root",
  GeneratedDir = "generated-dir",
  GeneratedFile = "generated-file",

  // Special cases
  Route = "route",
  Dependency = "dependency",
  Error = "error",
  Package = "package",
  Empty = "empty",
  Loading = "loading",
  UTILITIES_ROOT = "utilities-root",
}
