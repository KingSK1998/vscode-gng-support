package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic

@CompileStatic
class RenamePreviewResponse implements Serializable {
    final int schemaVersion = 1
    String previewId
    String projectUri
    String analysisRevision
    String freshness
    TargetSymbol targetSymbol
    String newName
    boolean canApply = true
    List<RenameEdit> edits = []
    List<DocumentPrecondition> documentPreconditions = []
    List<UncertainMatch> uncertainMatches = []
    List<RenameCollision> collisions = []
    List<String> limitations = []

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            previewId: previewId,
            projectUri: projectUri,
            analysisRevision: analysisRevision,
            freshness: freshness,
            targetSymbol: targetSymbol?.toMap(),
            newName: newName,
            canApply: canApply,
            edits: edits ? edits.collect { it.toMap() } : [],
            documentPreconditions: documentPreconditions ? documentPreconditions.collect { it.toMap() } : [],
            uncertainMatches: uncertainMatches ? uncertainMatches.collect { it.toMap() } : [],
            collisions: collisions ? collisions.collect { it.toMap() } : [],
            limitations: limitations
        ]
    }
}
