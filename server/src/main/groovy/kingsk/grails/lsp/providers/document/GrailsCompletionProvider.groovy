package kingsk.grails.lsp.providers.document

import kingsk.grails.lsp.context.ProviderContext
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.services.WorkspaceManager

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.providers.completions.CompletionBuilder
import kingsk.grails.lsp.providers.completions.CompletionProcessor
import kingsk.grails.lsp.providers.completions.CompletionRequest
import kingsk.grails.lsp.utils.grails.GrailsUtils
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either

import java.util.concurrent.CompletableFuture

/**
 * Modern Completion Provider for Grails and Groovy files.
 * Uses a strategy-based approach for generating completions.
 */
@Slf4j
@CompileStatic
class GrailsCompletionProvider extends BaseProvider {

    GrailsCompletionProvider(ProviderContext providerContext, WorkspaceManager workspaceManager) {
        super(providerContext, workspaceManager)
    }

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> provideCompletion(
        TextDocumentIdentifier textDocument, Position position, CompletionContext context) {

        def token = createCancellationToken(textDocument.uri)
        long startTime = System.currentTimeMillis()

        return CompletableFuture.supplyAsync({ ->
            RequestContext ctx = null
            try {
                checkCancellation(token)
                ctx = createRequestContext(textDocument.uri)

                String uri = textDocument.uri
                def textFile = providerContext.fileTracker.getTextFile(uri)
                if (!textFile) return (Either<List<CompletionItem>, CompletionList>) Either.forLeft([] as List<CompletionItem>)

                // 1. Resolve prefix and AST context
                String prefix = CompletionProcessor.extractPrefix(textFile.text, position)

                def offsetNode = getNodeAtPosition(ctx, position)
                def parentNode = offsetNode ? ctx.ast()?.getParent(offsetNode) : null
                log.info("[COMPLETION_DEBUG] uri=${uri} pos=${position.line}:${position.character} prefix='${prefix}' offsetNode=${offsetNode?.class?.simpleName} text=${offsetNode?.text} parentNode=${parentNode?.class?.simpleName}")

                // 2. Build Request
                def request = new CompletionRequest(
                    uri, offsetNode, parentNode, prefix,
                    ctx.grailsProject()?.isGrailsProject ?: false,
                    providerContext
                )

                // 3. Execute Builder
                List<CompletionItem> rawItems = CompletionBuilder.buildCompletions(request, ctx)

                // 4. Post-process (filter, score, sort)
                List<CompletionItem> processedItems = CompletionProcessor.processCompletions(rawItems, prefix)

                // 5. Build Result
                boolean isIncomplete = rawItems.size() > processedItems.size() ||
                                     processedItems.size() >= CompletionProcessor.getMaxResults(prefix)

                log.info("[COMPLETION] uri=${uri} prefix='${prefix}' items=${processedItems.size()} time=${System.currentTimeMillis() - startTime}ms")

                return isIncomplete ?
                    (Either<List<CompletionItem>, CompletionList>) Either.forRight(new CompletionList(isIncomplete, processedItems)) :
                    (Either<List<CompletionItem>, CompletionList>) Either.forLeft(processedItems)
            } finally {
                ctx?.close()
                recordHealth("completion", System.currentTimeMillis() - startTime, true)
            }
        } as java.util.function.Supplier<Either<List<CompletionItem>, CompletionList>>)
    }

    void clearCaches() {
        // Implementation for clearing global completion caches
    }

    void clearCaches(String uri) {
        // Implementation for clearing file-specific completion caches
    }
}
