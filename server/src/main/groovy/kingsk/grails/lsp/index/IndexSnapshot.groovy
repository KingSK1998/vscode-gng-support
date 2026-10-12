package kingsk.grails.lsp.index

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import kingsk.grails.lsp.core.analysis.GrailsRelationshipItem
import kingsk.grails.lsp.model.types.TextFile
import java.util.Collections

@CompileStatic
class IndexSnapshot {
    static final IndexSnapshot EMPTY = new IndexSnapshot([:], [:], [:], [])
    
    // Primary lookup: descriptor → SymbolInfo (cross-file, O(1))
    final Map<String, SymbolInfo> byDescriptor
    
    // Positional lookup: uri → sorted list for binary search by position
    final Map<String, List<SymbolInfo>> byUri

    // Reference lookup: name → List of references (usages)
    final Map<String, List<ReferenceInfo>> referencesByName

    // Evidenced Grails relationships (R4-02): compact value items
    final List<GrailsRelationshipItem> relationships
    final Map<String, List<GrailsRelationshipItem>> relationshipsBySourceUri
    final Map<String, List<GrailsRelationshipItem>> relationshipsByTargetUri
    
    IndexSnapshot(Map<String, SymbolInfo> byDescriptor, Map<String, List<SymbolInfo>> byUri,
                  Map<String, List<ReferenceInfo>> referencesByName = [:],
                  List<GrailsRelationshipItem> relationships = []) {
        this.byDescriptor = Collections.unmodifiableMap(byDescriptor)
        this.byUri = Collections.unmodifiableMap(byUri)
        this.referencesByName = Collections.unmodifiableMap(referencesByName)
        this.relationships = Collections.unmodifiableList(new ArrayList<GrailsRelationshipItem>(relationships))

        Map<String, List<GrailsRelationshipItem>> bySrc = new HashMap<>()
        Map<String, List<GrailsRelationshipItem>> byTgt = new HashMap<>()
        for (GrailsRelationshipItem rel : relationships) {
            if (rel.source?.uri) {
                bySrc.computeIfAbsent(rel.source.uri, { k -> [] }).add(rel)
                String norm = TextFile.normalizePath(rel.source.uri)
                if (norm && norm != rel.source.uri) {
                    bySrc.computeIfAbsent(norm, { k -> [] }).add(rel)
                }
            }
            if (rel.target?.uri) {
                byTgt.computeIfAbsent(rel.target.uri, { k -> [] }).add(rel)
                String norm = TextFile.normalizePath(rel.target.uri)
                if (norm && norm != rel.target.uri) {
                    byTgt.computeIfAbsent(norm, { k -> [] }).add(rel)
                }
            }
        }
        this.relationshipsBySourceUri = Collections.unmodifiableMap(bySrc)
        this.relationshipsByTargetUri = Collections.unmodifiableMap(byTgt)
    }

    List<GrailsRelationshipItem> getRelationships() {
        return relationships
    }

    List<GrailsRelationshipItem> getRelationshipsBySourceUri(String uri) {
        if (!uri) return []
        List<GrailsRelationshipItem> direct = relationshipsBySourceUri[uri]
        if (direct != null) return direct
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            List<GrailsRelationshipItem> normalized = relationshipsBySourceUri[norm]
            if (normalized != null) return normalized
        }
        return []
    }

    List<GrailsRelationshipItem> getRelationshipsByTargetUri(String uri) {
        if (!uri) return []
        List<GrailsRelationshipItem> direct = relationshipsByTargetUri[uri]
        if (direct != null) return direct
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            List<GrailsRelationshipItem> normalized = relationshipsByTargetUri[norm]
            if (normalized != null) return normalized
        }
        return []
    }
    
    SymbolInfo getSymbolAt(String uri, Position pos) {
        List<SymbolInfo> symbols = getSymbolsForFile(uri)
        if (!symbols) return null
        
        SymbolInfo bestMatch = null
        // Iterate through all symbols for the file to find the smallest containing range.
        // File-level symbol lists are small (typically < 100 entries), making O(N) scan extremely fast.
        for (SymbolInfo symbol : symbols) {
            if (isPositionInRange(pos, symbol.range)) {
                if (bestMatch == null || isSmallerRange(symbol.range, bestMatch.range)) {
                    bestMatch = symbol
                }
            }
        }
        return bestMatch
    }
    
    SymbolInfo getSymbolByDescriptor(String descriptor) {
        return byDescriptor[descriptor]
    }
    
    List<SymbolInfo> getSymbolsForFile(String uri) {
        if (!uri) return []
        List<SymbolInfo> direct = byUri[uri]
        if (direct != null) return direct
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            List<SymbolInfo> normalized = byUri[norm]
            if (normalized != null) return normalized
        }
        return []
    }

    List<ReferenceInfo> getReferencesFor(String name) {
        return referencesByName[name] ?: []
    }

    Set<String> getAllFqcns() {
        return byDescriptor.keySet().findAll { !it.contains('#') } // basic heuristic for class descriptors
    }
    
    private static boolean isPositionInRange(Position pos, Range range) {
        if (pos.line < range.start.line || pos.line > range.end.line) return false
        if (pos.line == range.start.line && pos.character < range.start.character) return false
        if (pos.line == range.end.line && pos.character > range.end.character) return false
        return true
    }
    
    private static boolean isSmallerRange(Range r1, Range r2) {
        int l1 = r1.end.line - r1.start.line
        int l2 = r2.end.line - r2.start.line
        if (l1 != l2) return l1 < l2
        return (r1.end.character - r1.start.character) < (r2.end.character - r2.start.character)
    }
}
