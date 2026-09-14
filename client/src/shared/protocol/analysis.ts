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

