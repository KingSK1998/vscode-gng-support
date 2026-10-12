/**
 * Typed Analysis Operations Contracts (R3-01)
 * Defined in shared/schemas/analysis-operations.json
 */

export type Freshness = "current" | "stale" | "unavailable";
export type Completeness = "complete" | "partial" | "unavailable";
export type ResolutionKind =
  | "declaration-backed"
  | "framework-derived"
  | "runtime-observed"
  | "heuristic"
  | "unresolved";

export type OriginKind =
  | "DECLARATION"
  | "COMPILATION_UNIT"
  | "CAPABILITY_ADAPTER"
  | "INDEX"
  | "UNKNOWN";

export interface Position {
  line: number;
  character: number;
}

export interface Range {
  start: Position;
  end: Position;
}

export interface AnalysisEvidence {
  uri: string;
  symbol: string;
  adapterRule?: string | null;
  artifactCoordinates?: string | null;
  artifactFingerprint?: string | null;
  sourceSet?: string | null;
}

export interface AnalysisSymbolItem {
  name: string;
  kind: string;
  containerName?: string | null;
  uri: string;
  range: Range;
  origin: OriginKind;
  resolution: ResolutionKind;
  artifactCoordinates?: string | null;
  signature?: string | null;
  evidence?: AnalysisEvidence | null;
}

export interface AnalysisDiagnosticItem {
  documentUri: string;
  range: Range;
  severity: "Error" | "Warning" | "Information" | "Hint";
  message: string;
  source?: string | null;
  code?: string | null;
}

export interface CapabilityDescriptor {
  name: string;
  enabled: boolean;
  detectionEvidence: string;
  applicability: string;
  description: string;
}

export interface ProjectCapabilitiesRequest {
  projectUri: string;
  sourceSet?: string | null;
}

export interface ProjectCapabilitiesResponse {
  schemaVersion: 1;
  projectUri: string;
  projectName: string;
  projectType: string;
  analysisRevision: string;
  grailsVersion?: string | null;
  groovyVersion?: string | null;
  javaVersion?: string | null;
  compatibilityLevel: "TESTED" | "EXPERIMENTAL" | "UNSUPPORTED";
  freshness: Freshness;
  completeness: Completeness;
  capabilities: CapabilityDescriptor[];
  limitations: string[];
  reasons: string[];
}

export interface SymbolSearchRequest {
  projectUri: string;
  query: string;
  uri?: string | null;
  sourceSet?: string | null;
  kinds?: string[] | null;
  limit?: number | null;
  maxPayloadBytes?: number | null;
  cursor?: string | null;
}

export interface SymbolSearchResponse {
  schemaVersion: 1;
  projectUri: string;
  analysisRevision: string;
  freshness: Freshness;
  completeness: Completeness;
  items: AnalysisSymbolItem[];
  nextCursor?: string | null;
  totalEstimated: number;
  limitations: string[];
}

export interface SymbolExplainRequest {
  projectUri: string;
  documentUri: string;
  symbolName?: string | null;
  line?: number | null;
  character?: number | null;
}

export interface SymbolExplainResponse {
  schemaVersion: 1;
  projectUri: string;
  documentUri: string;
  analysisRevision: string;
  freshness: Freshness;
  completeness: Completeness;
  symbol?: AnalysisSymbolItem | null;
  documentation?: string | null;
  declaringType?: string | null;
  inheritedFrom?: string | null;
  relatedSymbols: AnalysisSymbolItem[];
  limitations: string[];
}

export interface ProjectDiagnosticsRequest {
  projectUri: string;
  documentUri?: string | null;
  severity?: "Error" | "Warning" | "Information" | "Hint" | null;
  limit?: number | null;
}

export interface ProjectDiagnosticsResponse {
  schemaVersion: 1;
  projectUri: string;
  analysisRevision: string;
  freshness: Freshness;
  completeness: Completeness;
  diagnostics: AnalysisDiagnosticItem[];
  totalCount: number;
  limitations: string[];
}

export type AnalysisErrorCode =
  | "INVALID_INPUT"
  | "UNAVAILABLE"
  | "CANCELLED"
  | "TIMEOUT"
  | "CURSOR_EXPIRED"
  | "INTERNAL_ERROR";

export interface AnalysisErrorResponse {
  schemaVersion: 1;
  error: string;
  code: AnalysisErrorCode;
  details?: string | null;
}

// R4-02: Evidenced Grails Relationships
export type RelationshipType =
  | "CONTROLLER_ACTION_TO_VIEW"
  | "CONTROLLER_ACTION_TO_SERVICE"
  | "CONTROLLER_TO_DOMAIN"
  | "DOMAIN_HAS_MANY"
  | "DOMAIN_BELONGS_TO"
  | "DOMAIN_HAS_ONE"
  | "SERVICE_TO_DOMAIN"
  | "TAGLIB_TO_VIEW"
  | "URL_MAPPING_TO_CONTROLLER"
  | "CONFIG_TO_ARTIFACT";

export interface RelationshipEndpoint {
  uri: string;
  name: string;
  kind: string;
  range?: Range | null;
}

export interface DynamicUnknownItem {
  uri: string;
  name: string;
  reason: string;
  estimatedType?: string | null;
}

export interface GrailsRelationshipItem {
  id: string;
  type: RelationshipType;
  source: RelationshipEndpoint;
  target: RelationshipEndpoint;
  confidence: "evidenced" | "inferred";
  evidence?: AnalysisEvidence | null;
}

export interface GrailsRelationshipsRequest {
  projectUri: string;
  sourceUri?: string | null;
  targetUri?: string | null;
  uri?: string | null;
  types?: string[] | null;
  depth?: number | null;
  direction?: "outbound" | "inbound" | "both" | null;
  limit?: number | null;
  maxPayloadBytes?: number | null;
  cursor?: string | null;
}

export interface GrailsRelationshipsResponse {
  schemaVersion: 1;
  projectUri: string;
  analysisRevision: string;
  freshness: Freshness;
  completeness: Completeness;
  relationships: GrailsRelationshipItem[];
  dynamicUnknowns: DynamicUnknownItem[];
  nextCursor?: string | null;
  totalEstimated: number;
  limitations: string[];
}

// R5-01: Change Impact Analysis
export interface VerificationSuggestion {
  targetUri: string;
  testClass?: string | null;
  rationale: string;
  priority: "high" | "medium" | "low";
}

export interface ChangeImpactItem {
  artifactUri: string;
  relationshipPath: string[];
  impactKind: "direct" | "transitive" | "dynamic_unknown";
  confidence: "evidenced" | "heuristic";
}

export interface ChangeImpactRequest {
  projectUri: string;
  changedUris: string[];
  changedSymbols?: string[] | null;
  depth?: number | null;
  limit?: number | null;
  maxPayloadBytes?: number | null;
  cursor?: string | null;
}

export interface ChangeImpactResponse {
  schemaVersion: 1;
  projectUri: string;
  analysisRevision: string;
  freshness: Freshness;
  completeness: Completeness;
  changedUris: string[];
  impactedArtifacts: ChangeImpactItem[];
  dynamicUnknowns: DynamicUnknownItem[];
  verificationSuggestions: VerificationSuggestion[];
  nextCursor?: string | null;
  totalEstimated: number;
  limitations: string[];
}

// R5-02: Rename Previews & Application
export interface RenameEdit {
  uri: string;
  documentVersion?: number | null;
  range: Range;
  newText: string;
  evidence?: string | null;
  isEmbeddedGsp?: boolean;
}

export interface DocumentPrecondition {
  uri: string;
  expectedVersion?: number | null;
  expectedHash?: string | null;
}

export interface UncertainMatch {
  uri: string;
  range: Range;
  reason: string;
  risk: "high" | "medium" | "low";
}

export interface RenameCollision {
  uri: string;
  name: string;
  conflictType: string;
  description: string;
}

export interface TargetSymbol {
  name: string;
  kind: string;
  uri: string;
  range: Range;
}

export interface RenamePreviewRequest {
  projectUri: string;
  documentUri: string;
  position: Position;
  newName: string;
  expectedRevision?: string | null;
}

export interface RenamePreviewResponse {
  schemaVersion: 1;
  previewId: string;
  projectUri: string;
  analysisRevision: string;
  freshness: Freshness;
  targetSymbol: TargetSymbol;
  newName: string;
  canApply: boolean;
  edits: RenameEdit[];
  documentPreconditions: DocumentPrecondition[];
  uncertainMatches: UncertainMatch[];
  collisions: RenameCollision[];
  limitations: string[];
}

export interface RenameApplyRequest {
  previewId: string;
  projectUri: string;
  documentPreconditions: DocumentPrecondition[];
  edits: RenameEdit[];
}

export interface RenameApplyResponse {
  schemaVersion: 1;
  applied: boolean;
  appliedEditsCount: number;
  rejectedEditsCount: number;
  rejectedDetails?: { uri: string; reason: string }[];
  recoveryMessage?: string | null;
}

// R5-03: Checks Execution
export type CheckStatus =
  | "success"
  | "failure"
  | "cancelled"
  | "timeout"
  | "no_tests"
  | "skipped"
  | "partial";

export interface CheckScope {
  sourceSet?: string | null;
  classes?: string[] | null;
  methods?: string[] | null;
  tasks?: string[] | null;
}

export interface CheckDiagnosticItem {
  uri?: string | null;
  range?: Range | null;
  severity: "Error" | "Warning" | "Information" | "Hint";
  message: string;
}

export interface CheckSummary {
  testsTotal: number;
  testsPassed: number;
  testsFailed: number;
  testsSkipped: number;
  compilationSuccess: boolean;
  errorCount: number;
  warningCount: number;
}

export interface CheckExecutionRequest {
  projectUri: string;
  checkType: "compile" | "test" | "all";
  scope?: CheckScope | null;
  timeoutMs?: number | null;
  maxOutputBytes?: number | null;
  environment?: Record<string, string> | null;
}

export interface CheckExecutionResponse {
  schemaVersion: 1;
  checkId: string;
  projectUri: string;
  analysisRevision: string;
  checkType: string;
  status: CheckStatus;
  startTime: number;
  endTime: number;
  durationMs: number;
  tasksExecuted: string[];
  summary: CheckSummary;
  diagnostics: CheckDiagnosticItem[];
  output: string;
  artifactLocations: string[];
  limitations: string[];
}

// R5-04: Change Review
export type ChangeReviewStatus =
  | "ready"
  | "stale_buffer"
  | "stale_dependency"
  | "checks_failed"
  | "partial_edits"
  | "unverified";

export type ChangeSafetyVerdict =
  | "SAFE_TO_APPLY"
  | "REQUIRES_ATTENTION"
  | "UNSAFE_TO_APPLY"
  | "STALE_REVIEW";

export interface ChangeEditsSummary {
  totalFiles: number;
  totalEdits: number;
  hasCollisions?: boolean;
  files: string[];
}

export interface ChangeImpactSummary {
  impactedCount: number;
  dynamicUnknownsCount: number;
  artifacts: string[];
  dynamicUnknowns: string[];
}

export interface ChangeChecksSummary {
  checkStatus: string;
  testsTotal: number;
  testsPassed: number;
  testsFailed: number;
  testsSkipped: number;
  compilationSuccess: boolean;
  errorCount: number;
  warningCount: number;
}

export interface ChangeReviewRequest {
  projectUri: string;
  changeId?: string | null;
  edits?: RenameEdit[] | null;
  impact?: ChangeImpactResponse | null;
  checks?: CheckExecutionResponse | null;
  assumptions?: string[] | null;
}

export interface ChangeReviewResponse {
  schemaVersion: 1;
  changeId: string;
  projectUri: string;
  analysisRevision: string;
  freshness: string;
  status: ChangeReviewStatus;
  safetyVerdict: ChangeSafetyVerdict;
  editsSummary: ChangeEditsSummary;
  edits: RenameEdit[];
  impactSummary: ChangeImpactSummary;
  checksSummary: ChangeChecksSummary | null;
  uncheckedAssumptions: string[];
  recoveryActions: string[];
  limitations: string[];
}

