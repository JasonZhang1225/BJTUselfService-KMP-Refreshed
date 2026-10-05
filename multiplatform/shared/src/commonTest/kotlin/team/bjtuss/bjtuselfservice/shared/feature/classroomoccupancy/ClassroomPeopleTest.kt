package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomCapacity

class ClassroomPeopleTest {
    @Test fun roomNumberFallbackMatchesOfficialRoomAndRejectsAmbiguousRows() {
        val people = ClassroomCapacity("思源楼101", 20.0, 18, 90)
        assertEquals(people, matchingRoomPeople("SY101", listOf(people)))
        assertNull(matchingRoomPeople("SY102", listOf(people)))
        assertNull(matchingRoomPeople("SY101", listOf(people, people.copy(name = "另一间101"))))
    }
}
