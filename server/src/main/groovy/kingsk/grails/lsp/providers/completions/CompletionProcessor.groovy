package kingsk.grails.lsp.providers.completions

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.utils.completion.CompletionUtil
import kingsk.grails.lsp.utils.grails.GrailsUtils
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.CompletionItem

/**
 * Unified completion processor that combines filtering, ranking, and scoring
 * for smart context-aware completions. Optimized using CompletionUtil and GrailsUtils.
 */
@Slf4j
@CompileStatic
class CompletionProcessor {

	// Scoring weights for different match types
	private static final int EXACT_MATCH_SCORE = 100
	private static final int PREFIX_MATCH_SCORE = 80
	private static final int CAMEL_CASE_SCORE = 60
	private static final int SUBSEQUENCE_SCORE = 40

	// Context-based scoring bonuses
	private static final int CURRENT_CLASS_BONUS = 100
	private static final int ARTIFACT_BASE_BONUS = 80
	private static final int GRAILS_INJECTED_BONUS = 70
	private static final int GORM_DYNAMIC_BONUS = 60
	private static final int GROOVY_META_BONUS = 30
	private static final int PREFIX_START_BONUS = 20
	private static final int TYPE_MATCH_BONUS = 20
	private static final int DETAIL_CONTAINS_BONUS = 10

	// Penalties
	private static final int DEPRECATED_PENALTY = -30
	private static final int SYNTHETIC_PENALTY = -10

	/**
	 * Extracts the word prefix before the cursor position for completion filtering.
	 */
	static String extractPrefix(String text, org.eclipse.lsp4j.Position position) {
		if (!text) return ""
		def lines = text.readLines()
		if (position.line >= lines.size()) return ""
		String line = lines[position.line]
		int charPos = Math.min(position.character, line.length())
		String beforeCursor = line.substring(0, charPos)

		def matcher = beforeCursor =~ /([a-zA-Z0-9_$]+)$/
		if (matcher.find()) {
			return matcher.group(1)
		}
		return ""
	}

	/**
	 * Process completions with unified filtering, scoring, and ranking
	 * Returns the final filtered, scored, and limited completion items
	 */
	static List<CompletionItem> processCompletions(List<CompletionItem> items, String prefix, ClassNode currentClass = null) {
		if (!items) return []

		log.debug("[COMPLETION] Processing {} items with prefix '{}'", items.size(), prefix)

		// Step 1: Score and filter in one pass using optimized approach
		Map<CompletionItem, Integer> scoredItems = scoreAndFilterItems(items, prefix, currentClass)

		// Step 2: Sort by score (highest first) and extract items
		List<CompletionItem> sortedItems = scoredItems.entrySet()
				.sort { a, b -> b.value <=> a.value }
				.collect { it.key }

		// Step 3: Apply smart limiting with quality threshold
		List<CompletionItem> finalItems = limitCompletionResults(sortedItems, getMaxResults(prefix))

		log.debug("[COMPLETION] Processed to {} final items from {} original", finalItems.size(), items.size())

		return finalItems
	}

	/**
	 * Score and filter items in a single pass for efficiency using CompletionUtil
	 */
	private static Map<CompletionItem, Integer> scoreAndFilterItems(List<CompletionItem> items, String prefix, ClassNode currentClass) {
		Map<CompletionItem, Integer> scoredItems = [:]
		Set<String> seenItems = new LinkedHashSet<>()

		for (CompletionItem item : items) {
			// Use CompletionUtil for consistent filtering
			if (!CompletionUtil.isSeenItem(item, prefix, seenItems)) continue

			// Quick basic filter using optimized checks
			if (!passesContextualFilter(item, prefix)) continue

			// Calculate comprehensive score
			int score = calculateItemScore(item, prefix, currentClass)

			// Apply minimum score threshold
			if (score >= getMinimumScoreThreshold(prefix)) {
				scoredItems[item] = score
			}
		}

		return scoredItems
	}

	/**
	 * Contextual filtering using GrailsUtils and optimized checks
	 */
	private static boolean passesContextualFilter(CompletionItem item, String prefix) {
		if (!item?.label) return false

		String label = item.label
		String detail = item.detail ?: ''

		// Filter out internal/synthetic items
		if (label.startsWith('$') || label.startsWith('__')) return false

		// Filter out constructor calls in inappropriate contexts
		if (label == '<init>' || label == '<clinit>') return false

		// Filter out getClass() unless specifically requested
		if (label == 'getClass' && !prefix?.toLowerCase()?.startsWith('get')) return false

		// Use GrailsUtils for dummy prefix detection and CompletionUtil for matching
		if (prefix && !prefix.isEmpty() && !GrailsUtils.isDummyPrefix(prefix)) {
			return CompletionUtil.computeMatchScore(label, prefix) > 0
		}

		return true
	}

	/**
	 * Calculate comprehensive score using CompletionUtil and optimized logic
	 */
	private static int calculateItemScore(CompletionItem item, String prefix, ClassNode currentClass) {
		int score = 0
		String label = item.label
		String detail = item.detail ?: ''

		// 1. Use CompletionUtil for base prefix matching score
		score += CompletionUtil.computeMatchScore(label, prefix ?: '')

		// 2. Context-aware scoring (if currentClass available)
		if (currentClass) {
			score += calculateContextualScore(item, currentClass, prefix)
			score += calculateSourceBasedScore(item, detail)
		} else {
			// Fallback scoring without full context
			score += calculateFallbackScore(item, detail)
		}

		// 3. Apply penalties using optimized checks
		score += calculateScorePenalties(item, detail)

		return Math.max(0, score) // Ensure non-negative
	}

	/**
	 * Get maximum results based on prefix specificity
	 */
	static int getMaxResults(String prefix) {
		if (GrailsUtils.isDummyPrefix(prefix)) return GrailsUtils.MAX_COMPLETION_ITEM_LIMIT
		if (!prefix || prefix.isEmpty()) return 500
		if (prefix.length() == 1) return 600
		if (prefix.length() == 2) return 800
		return 1000 // More specific prefixes can show more results
	}

	/**
	 * Context-aware scoring using ClassNode and prefix
	 */
	private static int calculateContextualScore(CompletionItem item, ClassNode currentClass, String prefix) {
		int score = 0
		String label = item.label

		// Check if declared in current class
		if (isItemDeclaredInCurrentClass(item, currentClass)) {
			score += CURRENT_CLASS_BONUS
		}
		// Check if declared in artifact base class
		else if (isItemDeclaredInArtifactBaseClass(item, currentClass)) {
			score += ARTIFACT_BASE_BONUS
		}

		// Prefix start bonus
		if (prefix && label.toLowerCase().startsWith(prefix.toLowerCase())) {
			score += PREFIX_START_BONUS
		}

		return score
	}

	/**
	 * Source-based scoring (Grails, GORM, Groovy, etc.)
	 */
	private static int calculateSourceBasedScore(CompletionItem item, String detail) {
		int score = 0

		if (detail.contains('Grails') && detail.contains('Injected')) {
			score += GRAILS_INJECTED_BONUS
		} else if (detail.contains('GORM') || detail.contains('Dynamic')) {
			score += GORM_DYNAMIC_BONUS
		} else if (detail.contains('Groovy') && detail.contains('Meta')) {
			score += GROOVY_META_BONUS
		} else if (detail.contains('Groovy')) {
			score += GROOVY_META_BONUS
		}

		return score
	}


	/**
	 * Fallback context scoring without full request
	 */
	private static int calculateFallbackScore(CompletionItem item, String detail) {
		int score = 0

		// Boost common patterns based on completion item kind
		String kindStr = item.kind?.toString() ?: ''
		if (kindStr.contains('Method')) score += 5
		if (kindStr.contains('Property')) score += 8
		if (kindStr.contains('Field')) score += 3

		return score
	}

	/**
	 * Calculate penalties for undesirable items using GrailsUtils
	 */
	private static int calculateScorePenalties(CompletionItem item, String detail) {
		int penalty = 0

		// Use GrailsUtils for deprecated detection if available, fallback to manual check
		if (item.deprecated == Boolean.TRUE || detail.toLowerCase().contains('deprecated')) {
			penalty += DEPRECATED_PENALTY
		}

		// Synthetic items (less priority)
		if (detail.contains('synthetic')) {
			penalty += SYNTHETIC_PENALTY
		}

		return penalty
	}

	/**
	 * Smart limiting that preserves high-quality results
	 */
	private static List<CompletionItem> limitCompletionResults(List<CompletionItem> sortedItems, int maxResults) {
		if (sortedItems.size() <= maxResults) {
			return sortedItems
		}

		// Simply take the top results (already sorted by score)
		return sortedItems.take(maxResults)
	}

	/**
	 * Get minimum score threshold based on prefix length using GrailsUtils constants
	 */
	private static int getMinimumScoreThreshold(String prefix) {
		if (!prefix || prefix.isEmpty()) return 0
		if (GrailsUtils.isDummyPrefix(prefix)) return 0 // Accept all for dummy prefix
		if (prefix.length() == 1) return 10
		if (prefix.length() == 2) return 15
		return 20
	}

	// === Helper Methods ===

	private static boolean isItemDeclaredInCurrentClass(CompletionItem item, ClassNode currentClass) {
		if (!currentClass) return false

		String label = item.label
		return currentClass.fields?.any { it.name == label } ||
				currentClass.methods?.any { it.name == label }
	}

	private static boolean isItemDeclaredInArtifactBaseClass(CompletionItem item, ClassNode currentClass) {
		if (!currentClass?.superClass) return false

		String label = item.label

		// Use GrailsUtils for artifact detection
		boolean isGrailsBase = GrailsUtils.isControllerClass(currentClass.superClass) ||
				GrailsUtils.isServiceClass(currentClass.superClass) ||
				GrailsUtils.isDomainClass(currentClass.superClass) ||
				GrailsUtils.isTagLibClass(currentClass.superClass)

		if (!isGrailsBase) return false

		return currentClass.superClass.fields?.any { it.name == label } ||
				currentClass.superClass.methods?.any { it.name == label }
	}

}