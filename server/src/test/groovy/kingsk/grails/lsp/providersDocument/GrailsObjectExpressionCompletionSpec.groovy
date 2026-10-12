package kingsk.grails.lsp.providers.document

import kingsk.grails.lsp.test.CompletionTestSpec
import kingsk.grails.lsp.test.ProjectType

/**
 * Spock tests for Grails-specific object expression completions
 * Tests the fromObjectExpression method for Grails artifacts and dynamic methods
 */
class GrailsObjectExpressionCompletionSpec extends CompletionTestSpec {

	def setup() {
		initializeProject(ProjectType.GRAILS)
	}

	def "should provide domain class completions with GORM methods"() {
		given: "a Grails domain class object access"
		String content = """\
            class Person {
                String name
                Integer age

                static constraints = {
                    name nullable: false
                }
            }

Person.
        """
		String uri = openTextDocument("grails-app/domain/Person.groovy", content)

		when: "requesting completions for domain class"
		def items = getCompletionItems(uri, 9, 7)
		// after "Person."

		then: "should include GORM static methods"
		items.size() > 0
		assertContainsItem(items, "findBy")
		assertContainsItem(items, "findAllBy")
		assertContainsItem(items, "count")
		assertContainsItem(items, "list")
		assertContainsItem(items, "get")

		and: "should have Grails-specific details"
		def findByItem = assertContainsItem(items, "findBy")
		findByItem.detail.contains("GORM dynamic finder")
	}

	def "should provide controller completions with controller methods"() {
		given: "a Grails controller object access"
		String content = """\
            class PersonController {
                def index() {
                    render""" + " \n" + """\
                }
            }
        """
		String uri = openTextDocument("grails-app/controllers/PersonController.groovy", content)

		when: "requesting completions after render"
		def items = getCompletionItems(uri, 2, 27)
		// after "render "

		then: "should include controller-specific methods"
		items.size() > 0
		assertContainsItem(items, "view")
		assertContainsItem(items, "model")
		assertContainsItem(items, "text")
		assertContainsItem(items, "status")

		and: "should have controller-specific details"
		def viewItem = assertContainsItem(items, "view")
		viewItem.detail.contains("Grails controller method")
	}

	def "should provide service completions with service methods"() {
		given: "a Grails service object access"
		String content = """\
            import grails.gorm.transactions.Transactional

            class PersonService {
                @Transactional
                Person savePerson(String name) {
                    return new Person(name: name).save()
                }
            }

            class TestController {
                PersonService personService

                def index() {
                    personService.
                }
            }
        """
		String uri = openTextDocument("TestController.groovy", content)

		when: "requesting completions for service"
		def items = getCompletionItems(uri, 13, 34)
		// after "personService."

		then: "should include service methods"
		items.size() > 0
		assertContainsItem(items, "savePerson")

		and: "should indicate transactional nature"
		def saveItem = assertContainsItem(items, "savePerson")
		saveItem.detail.contains("@Transactional")
	}

	def "should provide domain instance completions with GORM instance methods"() {
		given: "a domain instance object access"
		String content = """\
            class Person {
                String name
            }

            def person = new Person(name: 'John')
            person.
        """
		String uri = openTextDocument("grails-app/domain/TestScript.groovy", content)

		when: "requesting completions for domain instance"
		def items = getCompletionItems(uri, 5, 19)
		// after "person."

		then: "should include GORM instance methods"
		items.size() > 0
		assertContainsItem(items, "save")
		assertContainsItem(items, "delete")
		assertContainsItem(items, "refresh")
		assertContainsItem(items, "validate")
		assertContainsItem(items, "hasErrors")

		and: "should include standard properties"
		assertContainsItem(items, "name")
	}

	def "should provide GSP tag library completions"() {
		given: "tag library usage in controller"
		String content = """\
            class TestController {
                def index() {
                    def html = g.
                }
            }
        """
		String uri = openTextDocument("grails-app/controllers/TestController.groovy", content)

		when: "requesting completions for tag library"
		def items = getCompletionItems(uri, 2, 33)
		// after "g."

		then: "should include tag library methods"
		items.size() > 0
		assertContainsItem(items, "link")
		assertContainsItem(items, "form")
		assertContainsItem(items, "textField")
		assertContainsItem(items, "select")

		and: "should have GSP-specific details"
		def linkItem = assertContainsItem(items, "link")
		linkItem.detail.contains("GSP tag")
	}

	def "should handle domain class relationships"() {
		given: "domain class with relationships"
		String content = """\
            class Person {
                String name
                static hasMany = [books: Book]
            }

            class Book {
                String title
                static belongsTo = [person: Person]
            }

            def person = new Person()
            person.books.
        """
		String uri = openTextDocument("grails-app/domain/RelationshipTest.groovy", content)

		when: "requesting completions for relationship property"
		def items = getCompletionItems(uri, 11, 25)
		// after "person.books."

		then: "should provide collection and GORM methods"
		items.size() > 0
		assertContainsItem(items, "add")
		assertContainsItem(items, "remove")
		assertContainsItem(items, "size")

		and: "should provide Grails relationship methods"
		assertContainsItem(items, "findAll")
		assertContainsItem(items, "find")
	}

	def "should provide criteria builder completions"() {
		given: "criteria builder context"
		String content = """\
            class Person {
                String name
                Integer age
            }

            def results = Person.createCriteria().list {

            }
        """
		String uri = openTextDocument("grails-app/domain/CriteriaTest.groovy", content)

		when: "requesting completions in criteria closure"
		def items = getCompletionItems(uri, 6, 0)
		// inside criteria closure

		then: "should include criteria methods"
		items.size() > 0
		assertContainsItem(items, "eq")
		assertContainsItem(items, "like")
		assertContainsItem(items, "between")
		assertContainsItem(items, "order")
		assertContainsItem(items, "projections")

		and: "should have criteria-specific details"
		def eqItem = assertContainsItem(items, "eq")
		eqItem.detail.contains("Criteria restriction")
	}

	def "should handle command objects"() {
		given: "command object in controller"
		String content = """\
            import grails.validation.Validateable

            class PersonCommand implements Validateable {
                String name
                Integer age

                static constraints = {
                    name nullable: false
                }
            }

            class TestController {
                def save(PersonCommand cmd) {
                    cmd.
                }
            }
        """
		String uri = openTextDocument("grails-app/controllers/TestController.groovy", content)

		when: "requesting completions for command object"
		def items = getCompletionItems(uri, 13, 24)
		// after "cmd."

		then: "should include command object methods"
		items.size() > 0
		assertContainsItem(items, "validate")
		assertContainsItem(items, "hasErrors")
		assertContainsItem(items, "errors")

		and: "should include properties"
		assertContainsItem(items, "name")
		assertContainsItem(items, "age")
	}

	def "should provide configuration completions"() {
		given: "Grails configuration access"
		String content = """\
            class TestController {
                def grailsApplication

                def index() {
                    def config = grailsApplication.config.
                }
            }
        """
		String uri = openTextDocument("grails-app/controllers/TestController.groovy", content)

		when: "requesting completions for config"
		def items = getCompletionItems(uri, 4, 58)
		// after "grailsApplication.config."

		then: "should include configuration properties"
		items.size() > 0
		assertContainsItem(items, "dataSource")
		assertContainsItem(items, "grails")

		and: "should have config-specific details"
		def dataSourceItem = assertContainsItem(items, "dataSource")
		dataSourceItem.detail.contains("Configuration property")
	}

	def "should handle params object in controller"() {
		given: "params object access in controller"
		String content = """\
            class TestController {
                def index() {
                    params.
                }
            }
        """
		String uri = openTextDocument("grails-app/controllers/TestController.groovy", content)

		when: "requesting completions for params"
		def items = getCompletionItems(uri, 2, 27)
		// after "params."

		then: "should include params methods"
		items.size() > 0
		assertContainsItem(items, "get")
		assertContainsItem(items, "containsKey")
		assertContainsItem(items, "keySet")

		and: "should have controller context details"
		def getItem = assertContainsItem(items, "get")
		getItem.detail.contains("Controller params")
	}

	def "should not add Grails completions in non-Grails project"() {
		given: "switching to regular Groovy project"
		initializeProject(ProjectType.GROOVY)

		and: "a regular class that looks like domain"
		String content = """\
            class Person {
                String name
            }

Person.
        """
		String uri = openTextDocument("Person.groovy", content)

		when: "requesting completions"
		def items = getCompletionItems(uri, 4, 7)
		// after "Person."

		then: "should only include standard Groovy completions"
		items.size() > 0

		and: "should NOT include GORM methods"
		!items.any { it.label == "findBy" }
		!items.any { it.label == "count" }
		!items.any { it.label == "list" }
	}

	def "should handle complex Grails expressions"() {
		given: "complex chained Grails expression"
		String content = """\
            class Person {
                String name
                static hasMany = [books: Book]
            }

            class Book {
                String title
                boolean published
            }

            def result = Person.findByName('John').books.findAll { it.published }.
        """
		String uri = openTextDocument("grails-app/domain/ComplexTest.groovy", content)

		when: "requesting completions for complex expression"
		def items = getCompletionItems(uri, 10, 82)
		// after the complex chain

		then: "should provide completions for final result type (Collection)"
		items.size() > 0
		assertContainsItem(items, "size")
		assertContainsItem(items, "isEmpty")
		assertContainsItem(items, "each")
		assertContainsItem(items, "collect")
	}
}
