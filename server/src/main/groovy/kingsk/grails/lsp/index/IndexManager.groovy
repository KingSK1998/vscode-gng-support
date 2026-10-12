package kingsk.grails.lsp.index

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.core.analysis.GrailsRelationshipItem
import kingsk.grails.lsp.model.types.TextFile
import org.codehaus.groovy.ast.ClassNode

/**
 * Service-layer orchestrator that coordinates updates to the ProjectIndex,
 * MethodScopeCache, and GroovydocCache in a thread-safe manner using CAS retry loops.
 */
@Slf4j
@CompileStatic
class IndexManager {
    private final ProjectIndex projectIndex
    private final MethodScopeCache methodScopeCache
    private final GroovydocCache groovydocCache

    IndexManager(ProjectIndex projectIndex, MethodScopeCache methodScopeCache, GroovydocCache groovydocCache) {
        this.projectIndex = projectIndex
        this.methodScopeCache = methodScopeCache
        this.groovydocCache = groovydocCache
    }

    /**
     * Rebuild index symbols and locals for a single file.
     */
    void rebuildFile(String uri, List<ClassNode> classNodes) {
        if (!uri) return

        List<SymbolInfo> newSymbols = IndexBuilder.buildSymbols(uri, classNodes)
        List<LocalSymbolInfo> newLocals = IndexBuilder.buildLocals(uri, classNodes)
        List<ReferenceInfo> newReferences = IndexBuilder.buildReferences(uri, classNodes)
        List<GrailsRelationshipItem> newRels = IndexBuilder.buildRelationships(uri, classNodes)

        while (true) {
            IndexSnapshot expected = projectIndex.getSnapshot()
            Map<String, SymbolInfo> newByDescriptor = new HashMap<>(expected.byDescriptor)
            Map<String, List<SymbolInfo>> newByUri = new HashMap<>(expected.byUri)
            Map<String, List<ReferenceInfo>> newReferencesByName = new HashMap<>(expected.referencesByName)

            // Evict old descriptors for this URI
            Iterator<Map.Entry<String, SymbolInfo>> it = newByDescriptor.entrySet().iterator()
            while (it.hasNext()) {
                if (it.next().value.fileUri == uri) {
                    it.remove()
                }
            }

            // Evict old references for this URI
            newReferencesByName.values().each { list -> list.removeIf { it.fileUri == uri } }
            newReferencesByName.entrySet().removeIf { it.value.isEmpty() }

            // Insert new symbols
            for (SymbolInfo sym : newSymbols) {
                newByDescriptor.put(sym.descriptor, sym)
            }

            // Insert new references
            for (ReferenceInfo ref : newReferences) {
                newReferencesByName.computeIfAbsent(ref.targetName, { k -> [] }).add(ref)
            }

            // Update positional map
            if (newSymbols.isEmpty()) {
                newByUri.remove(uri)
            } else {
                newByUri.put(uri, newSymbols)
            }

            // Update relationships (evict old outbound edges for this URI, then append new edges)
            List<GrailsRelationshipItem> updatedRels = new ArrayList<>(expected.relationships)
            String normUri = TextFile.normalizePath(uri)
            updatedRels.removeIf { GrailsRelationshipItem r ->
                r.source?.uri && (r.source.uri == uri || r.source.uri == normUri || TextFile.normalizePath(r.source.uri) == normUri)
            }
            updatedRels.addAll(newRels)

            IndexSnapshot next = new IndexSnapshot(newByDescriptor, newByUri, newReferencesByName, updatedRels)
            if (projectIndex.compareAndCommit(expected, next)) {
                break
            }
        }

        // Evict and update local cache and docs after successful index commit
        methodScopeCache.evict(uri)
        if (!newLocals.isEmpty()) {
            methodScopeCache.putLocals(uri, newLocals)
        }
        groovydocCache.evictFile(uri)
    }

    /**
     * Rebuild index symbols and locals for a bulk collection of files.
     */
    void rebuildAll(Map<String, List<ClassNode>> allNodes) {
        while (true) {
            IndexSnapshot expected = projectIndex.getSnapshot()
            Map<String, SymbolInfo> newByDescriptor = new HashMap<>(expected.byDescriptor)
            Map<String, List<SymbolInfo>> newByUri = new HashMap<>(expected.byUri)
            Map<String, List<ReferenceInfo>> newReferencesByName = new HashMap<>(expected.referencesByName)
            Map<String, List<LocalSymbolInfo>> allLocals = new HashMap<>()
            List<GrailsRelationshipItem> updatedRels = new ArrayList<>(expected.relationships)

            for (Map.Entry<String, List<ClassNode>> entry : allNodes.entrySet()) {
                String uri = entry.key
                List<ClassNode> classNodes = entry.value

                List<SymbolInfo> newSymbols = IndexBuilder.buildSymbols(uri, classNodes)
                List<LocalSymbolInfo> newLocals = IndexBuilder.buildLocals(uri, classNodes)
                List<ReferenceInfo> newReferences = IndexBuilder.buildReferences(uri, classNodes)
                List<GrailsRelationshipItem> newRels = IndexBuilder.buildRelationships(uri, classNodes)
                allLocals.put(uri, newLocals)

                // Evict old descriptors for this URI
                Iterator<Map.Entry<String, SymbolInfo>> it = newByDescriptor.entrySet().iterator()
                while (it.hasNext()) {
                    if (it.next().value.fileUri == uri) {
                        it.remove()
                    }
                }

                // Evict old references for this URI
                newReferencesByName.values().each { list -> list.removeIf { it.fileUri == uri } }

                // Insert new symbols
                for (SymbolInfo sym : newSymbols) {
                    newByDescriptor.put(sym.descriptor, sym)
                }

                // Insert new references
                for (ReferenceInfo ref : newReferences) {
                    newReferencesByName.computeIfAbsent(ref.targetName, { k -> [] }).add(ref)
                }

                // Update positional map
                if (newSymbols.isEmpty()) {
                    newByUri.remove(uri)
                } else {
                    newByUri.put(uri, newSymbols)
                }

                // Update relationships
                String normUri = TextFile.normalizePath(uri)
                updatedRels.removeIf { GrailsRelationshipItem r ->
                    r.source?.uri && (r.source.uri == uri || r.source.uri == normUri || TextFile.normalizePath(r.source.uri) == normUri)
                }
                updatedRels.addAll(newRels)
            }

            // Remove empty reference lists
            newReferencesByName.entrySet().removeIf { it.value.isEmpty() }

            IndexSnapshot next = new IndexSnapshot(newByDescriptor, newByUri, newReferencesByName, updatedRels)
            if (projectIndex.compareAndCommit(expected, next)) {
                // Apply local cache and doc evictions for all modified files
                for (Map.Entry<String, List<LocalSymbolInfo>> entry : allLocals.entrySet()) {
                    String uri = entry.key
                    List<LocalSymbolInfo> newLocals = entry.value

                    methodScopeCache.evict(uri)
                    if (!newLocals.isEmpty()) {
                        methodScopeCache.putLocals(uri, newLocals)
                    }
                    groovydocCache.evictFile(uri)
                }
                break
            }
        }
    }

    /**
     * Evict a file completely from the index and other caches.
     */
    void evictFile(String uri) {
        if (!uri) return

        while (true) {
            IndexSnapshot expected = projectIndex.getSnapshot()
            Map<String, SymbolInfo> newByDescriptor = new HashMap<>(expected.byDescriptor)
            Map<String, List<SymbolInfo>> newByUri = new HashMap<>(expected.byUri)
            Map<String, List<ReferenceInfo>> newReferencesByName = new HashMap<>(expected.referencesByName)

            // Remove descriptors
            Iterator<Map.Entry<String, SymbolInfo>> it = newByDescriptor.entrySet().iterator()
            while (it.hasNext()) {
                if (it.next().value.fileUri == uri) {
                    it.remove()
                }
            }

            // Remove references
            newReferencesByName.values().each { list -> list.removeIf { it.fileUri == uri } }
            newReferencesByName.entrySet().removeIf { it.value.isEmpty() }

            newByUri.remove(uri)

            // Evict relationships (both outbound and inbound) touching uri
            List<GrailsRelationshipItem> updatedRels = new ArrayList<>(expected.relationships)
            String normUri = TextFile.normalizePath(uri)
            updatedRels.removeIf { GrailsRelationshipItem r ->
                (r.source?.uri && (r.source.uri == uri || r.source.uri == normUri || TextFile.normalizePath(r.source.uri) == normUri)) ||
                (r.target?.uri && (r.target.uri == uri || r.target.uri == normUri || TextFile.normalizePath(r.target.uri) == normUri))
            }

            IndexSnapshot next = new IndexSnapshot(newByDescriptor, newByUri, newReferencesByName, updatedRels)
            if (projectIndex.compareAndCommit(expected, next)) {
                break
            }
        }

        methodScopeCache.evict(uri)
        groovydocCache.evictFile(uri)
    }
}
