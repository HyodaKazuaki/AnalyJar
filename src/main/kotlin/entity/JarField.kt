package entity

import org.jetbrains.exposed.sql.Table

object JarField : Table("properties") {
    val id = integer("id").autoIncrement()
    override val primaryKey = PrimaryKey(id, name = "pk_property_id")
    val typeName = text("type_name")
    val name = text("name")
    val modifiers = text("modifiers")
    val classId = (integer("class_id") references JarClass.id)
}