package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomCapacity

/** Supplement official rows only; never create rooms from the third-party response. */
internal fun matchingRoomPeople(roomName: String, people: List<ClassroomCapacity>): ClassroomCapacity? {
    people.firstOrNull { it.name.equals(roomName, ignoreCase = true) }?.let { return it }
    fun roomNumber(value: String): String? = Regex("[0-9]+[A-Za-z]?$").find(value.trim())?.value?.lowercase()
    val number = roomNumber(roomName) ?: return null
    return people.filter { roomNumber(it.name) == number }.singleOrNull()
}
