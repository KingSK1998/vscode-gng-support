package kingsk.grails.lsp.providers.document

import kingsk.grails.lsp.test.CompletionTestSpec
import kingsk.grails.lsp.test.ProjectType
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Tests for the Groovy completion provider
 */
class GroovyCompletionProviderSpec extends CompletionTestSpec {
	
	def setup() {
		initializeProject(ProjectType.GROOVY)
	}
	
	def "should provide member access completions after dot"() {
		given: "Various scenarios with dot notation"
		
		// --- local variable ---
		
		when: "Testing local variable access"
		StringBuilder content = new StringBuilder()
		content.append("class Completion {\n")
		content.append("  public Completion() {\n")
		content.append("    String localVar\n")
		content.append("    localVar.\n")
		content.append("  }\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		def items = getCompletionItems(uri, 3, 14)
		
		then: "Should provide String methods for local variable"
		items.size() > 0
		def completion = assertContainsItem(items, "charAt")
		completion.kind == CompletionItemKind.Method
		closeTextDocument(uri)
		
		// --- member variable ---
		
		when: "Testing member variable access"
		content = new StringBuilder()
		content.append("class Completion2 {\n")
		content.append("  String memberVar\n")
		content.append("  public Completion2() {\n")
		content.append("    memberVar.\n")
		content.append("  }\n")
		content.append("}")
		uri = openTextDocument("Completion2.groovy", content.toString())
		items = getCompletionItems(uri, 3, 14)
		
		then: "Should provide String methods for member variable"
		items.size() > 0
		assertContainsItem(items, "charAt").kind == CompletionItemKind.Method
		closeTextDocument(uri)
		
		// --- array element ---
		
		when: "Testing array element access"
		content = new StringBuilder()
		content.append("class Completion3 {\n")
		content.append("  public Completion3() {\n")
		content.append("    String[] localVar\n")
		content.append("    localVar[0].\n")
		content.append("  }\n")
		content.append("}")
		uri = openTextDocument("Completion3.groovy", content.toString())
		items = getCompletionItems(uri, 3, 16)
		
		then: "Should provide String methods for array element"
		items.size() > 0
		assertContainsItem(items, "charAt").kind == CompletionItemKind.Method
		closeTextDocument(uri)
	}
	
	def "should provide this and class member access completions"() {
		
		// --- this access ---
		
		given: "A class with member variables and methods"
		StringBuilder content = new StringBuilder()
		content.append("class Completion {\n")
		content.append("  String memberVar\n")
		content.append("  public Completion() {\n")
		content.append("    this.\n")
		content.append("  }\n")
		content.append("  static void staticMethod() {}\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		
		when: "Testing this access"
		def items = getCompletionItems(uri, 3, 9)
		
		then: "Should provide class members"
		items.size() > 0
		def completion = assertContainsItem(items, "memberVar")
		completion.kind in [CompletionItemKind.Field, CompletionItemKind.Property]
		
		// --- static access ---
		
		when: "Testing class static access"
		content = new StringBuilder()
		content.append("class Completion2 {\n")
		content.append("  String memberVar\n")
		content.append("  public Completion2() {\n")
		content.append("    Completion2.\n")
		content.append("  }\n")
		content.append("  static void staticMethod() {}\n")
		content.append("}")
		uri = openTextDocument("Completion2.groovy", content.toString())
		items = getCompletionItems(uri, 3, 16)
		
		then: "Should provide static members"
		items.size() > 0
		assertContainsItem(items, "staticMethod").kind == CompletionItemKind.Function
	}
	
	def "should provide partial completion with filtering"() {
		given: "A class with partial property access"
		StringBuilder content = new StringBuilder()
		content.append("class Completion {\n")
		content.append("  public Completion() {\n")
		content.append("    String localVar\n")
		content.append("    localVar.charA\n")
		content.append("  }\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		
		when: "Requesting completions after partial property"
		def items = getCompletionItems(uri, 3, 18)
		
		then: "Should provide matching String methods"
		items.size() > 0
		def completion = assertContainsItem(items, "charAt")
		completion.kind == CompletionItemKind.Method
	}
	
	def "should provide multiple completion results with proper filtering"() {
		given: "A class with multiple methods with similar names"
		StringBuilder content = new StringBuilder()
		content.append("class Completion {\n")
		content.append("  public Completion() {\n")
		content.append("    this.abcde\n")
		content.append("  }\n")
		content.append("  public abc() {}\n")
		content.append("  public abcdef() {}\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		
		when: "Requesting completions with partial name"
		def items = getCompletionItems(uri, 2, 11)
		
		then: "Should include both methods"
		items.size() == 2
		assertContainsItem(items, "abc")
		assertContainsItem(items, "abcdef")
		
		when: "Requesting completions with more specific partial name"
		items = getCompletionItems(uri, 2, 13)
		
		then: "Should only include the matching method"
		items.size() == 1
		assertContainsItem(items, "abcdef")
	}
	
	def "should provide variable and member completions for partial expressions"() {
		given: "Various scenarios with partial variable expressions"
		
		// --- member variable ---
		
		when: "Testing member variable completion"
		StringBuilder content = new StringBuilder()
		content.append("class Completion {\n")
		content.append("  String memberVar\n")
		content.append("  public Completion() {\n")
		content.append("    mem\n")
		content.append("  }\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		def items = getCompletionItems(uri, 3, 7)
		
		then: "Should provide member variable"
		items.size() > 0
		def completion = assertContainsItem(items, "memberVar")
		completion.kind in [CompletionItemKind.Field, CompletionItemKind.Property]
		
		// --- member method ---
		
		when: "Testing member method completion"
		content = new StringBuilder()
		content.append("class Completion2 {\n")
		content.append("  String memberMethod() {}\n")
		content.append("  public Completion2() {\n")
		content.append("    mem\n")
		content.append("  }\n")
		content.append("}")
		uri = openTextDocument("Completion2.groovy", content.toString())
		items = getCompletionItems(uri, 3, 7)
		
		then: "Should provide member method"
		items.size() > 0
		def completion2 = assertContainsItem(items, "memberMethod")
		completion2.kind == CompletionItemKind.Method
		
		// --- parameter ---
		
		when: "Testing parameter completion"
		content = new StringBuilder()
		content.append("class Completion3 {\n")
		content.append("  public void testMethod(String paramName) {\n")
		content.append("    par\n")
		content.append("  }\n")
		content.append("}")
		uri = openTextDocument("Completion3.groovy", content.toString())
		items = getCompletionItems(uri, 2, 7)
		
		then: "Should provide parameter"
		items.size() > 0
		assertContainsItem(items, "paramName").kind == CompletionItemKind.Variable
	}
	
	def "should provide local variable completion in nested scopes"() {
		given: "A method with a local variable and partial reference inside a block"
		StringBuilder content = new StringBuilder()
		content.append("class Completion {\n")
		content.append("  public void testMethod(String paramName) {\n")
		content.append("    String localVar\n")
		content.append("    if(true) {\n")
		content.append("      loc\n")
		content.append("    }\n")
		content.append("  }\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		
		when: "Requesting completions with partial name inside block"
		def items = getCompletionItems(uri, 4, 9)
		
		then: "Should provide local variable"
		items.size() > 0
		def completion = assertContainsItem(items, "localVar")
		completion.kind == CompletionItemKind.Variable
	}
	
	def "should provide class completions for own and system classes"() {
		given: "Various class reference scenarios"
		
		// --- own classes ---
		
		when: "Testing own class completion"
		StringBuilder content = new StringBuilder()
		content.append("package com.example\n")
		content.append("class Completion {\n")
		content.append("  public Completion() {\n")
		content.append("    Completio\n")
		content.append("  }\n")
		content.append("}")
		String uri = openTextDocument("Completion.groovy", content.toString())
		def items = getCompletionItems(uri, 3, 13)
		
		then: "Should provide own class"
		items.size() > 0
		def completion = assertContainsItem(items, "Completion")
		completion.kind == CompletionItemKind.Class
		completion.detail.contains("com.example")
		
		// --- system classes ---
		
		when: "Testing system class completion"
		content = new StringBuilder()
		content.append("class Completion2 {\n")
		content.append("  public Completion2() {\n")
		content.append("    ArrayLis\n")
		content.append("  }\n")
		content.append("}")
		uri = openTextDocument("Completion2.groovy", content.toString())
		items = getCompletionItems(uri, 2, 12)
		
		then: "Should provide system class"
		items.size() > 0
		def completion2 = assertContainsItem(items, "ArrayList")
		completion2.kind == CompletionItemKind.Class
		completion2.detail.contains("java.util")
	}
}