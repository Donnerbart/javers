package org.javers.core.examples


import org.javers.core.JaversBuilder
import org.javers.core.examples.model.Address
import org.javers.core.examples.model.Employee
import spock.lang.Specification

import java.time.ZonedDateTime

import static org.javers.core.examples.model.Position.Specialist
import static org.javers.repository.jql.QueryBuilder.byInstanceId

class QueryBuilderLimitExamples extends Specification {

    def "snapshot limit with query for Changes"() {
        given:
        def javers = JaversBuilder.javers().build()

        def bob = new Employee("Bob", 9_000, "ScrumMaster")
        javers.commit("me", bob)

        bob.salary += 1_000
        bob.position = Specialist
        bob.age = 21
        bob.lastPromotionDate = ZonedDateTime.now()
        javers.commit("me", bob)

        when:
        def changes = javers.findChanges(byInstanceId("Bob", Employee)
                .limit(2).build())

        print(changes.prettyPrint())

        then:
        changes.size() == 4
    }

    def "snapshot limit with query for Shadows"() {
        given:
        def javers = JaversBuilder.javers().build()

        def bob = new Employee("Bob", 9_000, "ScrumMaster")
        bob.primaryAddress = new Address("London")
        javers.commit("me", bob) // 2 snapshots are persisted

        bob.salary += 1_000
        bob.primaryAddress.city = "New York"
        javers.commit("me", bob) // 3 snapshots are persisted

        when : "snapshots limit == 2"
        def shadows = javers.findShadows(byInstanceId("Bob", Employee)
                .limit(2).build())

        shadows.each {println(it)}

        then:
        shadows.size() == 1

        when : "snapshots limit == 4"
        shadows = javers.findShadows(byInstanceId("Bob", Employee)
                .limit(4).build())

        shadows.each {println(it)}

        then:
        shadows.size() == 2
    }
}
